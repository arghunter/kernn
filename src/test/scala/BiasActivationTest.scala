import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class BiasActivationTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

  class BiasTestHarness(val n: Int) extends Module {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val busy = Output(Bool())
      val M = Input(UInt(24.W))
      val K = Input(UInt(24.W))
      val N = Input(UInt(24.W))
      val func = Input(ActivationFunc())
      val shift = Input(UInt(5.W))
      val relu6_threshold = Input(SInt(32.W))
      val wt_wr_en = Input(Bool())
      val wt_wr_addr = Input(UInt(16.W))
      val wt_wr_data = Input(Vec(n, SInt(8.W)))
      val act_wr_en = Input(Bool())
      val act_wr_addr = Input(UInt(16.W))
      val act_wr_data = Input(Vec(n, SInt(8.W)))
      val bias_wr_en = Input(Bool())
      val bias_wr_addr = Input(UInt(16.W))
      val bias_wr_data = Input(Vec(n, SInt(32.W)))
      val out_rd_addr = Input(UInt(16.W))
      val out_rd_data = Output(Vec(n, SInt(32.W)))
      val out_wr_en = Input(Bool())
      val out_wr_addr = Input(UInt(16.W))
      val out_wr_data = Input(Vec(n, SInt(32.W)))
    })

    val ctrl = Module(new TiledMatMulController(n, 16))
    val actFunc = Module(new ActivationFunctionBank(n, 32))

    val weightMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val actMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val outMem = SyncReadMem(4096, Vec(n, SInt(32.W)))
    val biasMem = SyncReadMem(256, Vec(n, SInt(32.W)))

    // Weight memory
    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

    // Activation input memory
    when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
    ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

    // Output memory
    ctrl.io.output_data_r := outMem.read(ctrl.io.output_rd_addr)
    io.out_rd_data := outMem.read(io.out_rd_addr)

    // Bias memory
    when(io.bias_wr_en) { biasMem.write(io.bias_wr_addr, io.bias_wr_data) }
    ctrl.io.bias_data := biasMem.read(ctrl.io.bias_addr)

    // Activation function bank
    actFunc.io.in := ctrl.io.output_data_wr
    actFunc.io.bias := ctrl.io.bias_values
    actFunc.io.bias_en := ctrl.io.bias_en
    actFunc.io.func := io.func
    actFunc.io.shift := io.shift
    actFunc.io.relu6_threshold := io.relu6_threshold

    // Write: activation on last K, raw otherwise
    val writeData = Wire(Vec(n, SInt(32.W)))
    when(ctrl.io.isLastK) {
      writeData := actFunc.io.out
    }.otherwise {
      writeData := ctrl.io.output_data_wr
    }

    when(ctrl.io.output_wen) {
      outMem.write(ctrl.io.output_wr_addr, writeData)
    }.elsewhen(io.out_wr_en) {
      outMem.write(io.out_wr_addr, io.out_wr_data)
    }

    // Controller connections
    ctrl.io.weight_base_addr := 0.U
    ctrl.io.activation_base_addr := 0.U
    ctrl.io.output_base_addr := 0.U
    ctrl.io.bias_base_addr := 0.U
    ctrl.io.M := io.M
    ctrl.io.K := io.K
    ctrl.io.N := io.N
    ctrl.io.start := io.start
    ctrl.io.rst_hard := false.B
    io.busy := ctrl.io.busy
  }

  // === Helpers ===

  def waitForIdle(dut: BiasTestHarness, maxCycles: Int = 500000): Int = {
    var cycles = 0
    while (dut.io.busy.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < maxCycles, s"Did not reach IDLE in $maxCycles cycles")
    cycles
  }

  def signExtend(v: BigInt): Long = {
    if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong
  }

  def loadWeights(dut: BiasTestHarness, W: Array[Array[Int]], m: Int, k: Int): Unit = {
    val tilesK = k / n
    for (tR <- 0 until m / n; tK <- 0 until tilesK) {
      val base = (tR * tilesK + tK) * n
      for (t <- 0 until n) {
        dut.io.wt_wr_en.poke(true.B)
        dut.io.wt_wr_addr.poke((base + t).U)
        for (r <- 0 until n)
          dut.io.wt_wr_data(r).poke(W(tR * n + r)(tK * n + t).S)
        dut.clock.step()
      }
    }
    dut.io.wt_wr_en.poke(false.B)
  }

  def loadActivations(dut: BiasTestHarness, A: Array[Array[Int]], k: Int, nn: Int): Unit = {
    val tilesN = nn / n
    for (tK <- 0 until k / n; tC <- 0 until tilesN) {
      val base = (tK * tilesN + tC) * n
      for (t <- 0 until n) {
        dut.io.act_wr_en.poke(true.B)
        dut.io.act_wr_addr.poke((base + t).U)
        for (c <- 0 until n)
          dut.io.act_wr_data(c).poke(A(tK * n + t)(tC * n + c).S)
        dut.clock.step()
      }
    }
    dut.io.act_wr_en.poke(false.B)
  }

  // Bias layout: address tC stores bias[tC*n + 0..n-1]
  def loadBias(dut: BiasTestHarness, bias: Array[Int], nn: Int): Unit = {
    val tilesN = nn / n
    for (tC <- 0 until tilesN) {
      dut.io.bias_wr_en.poke(true.B)
      dut.io.bias_wr_addr.poke(tC.U)
      for (c <- 0 until n)
        dut.io.bias_wr_data(c).poke(bias(tC * n + c).S)
      dut.clock.step()
    }
    dut.io.bias_wr_en.poke(false.B)
  }

  def clearOutputMem(dut: BiasTestHarness, count: Int = 512): Unit = {
    for (addr <- 0 until count) {
      dut.io.out_wr_en.poke(true.B)
      dut.io.out_wr_addr.poke(addr.U)
      for (c <- 0 until n) dut.io.out_wr_data(c).poke(0.S)
      dut.clock.step()
    }
    dut.io.out_wr_en.poke(false.B)
  }

  def readResults(dut: BiasTestHarness, m: Int, nn: Int): Array[Array[Long]] = {
    val tilesN = nn / n
    val result = Array.ofDim[Long](m, nn)
    for (tR <- 0 until m / n; tC <- 0 until tilesN) {
      val base = (tR * tilesN + tC) * n
      for (r <- 0 until n) {
        dut.io.out_rd_addr.poke((base + r).U)
        dut.clock.step()
        dut.clock.step()
        for (c <- 0 until n)
          result(tR * n + r)(tC * n + c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
      }
    }
    result
  }

  def pokeFuncEnum(dut: BiasTestHarness, func: Int): Unit = {
    func match {
      case 0 => dut.io.func.poke(ActivationFunc.IDENTITY)
      case 1 => dut.io.func.poke(ActivationFunc.RELU)
      case 2 => dut.io.func.poke(ActivationFunc.LEAKY_RELU)
      case 3 => dut.io.func.poke(ActivationFunc.RELU6)
    }
  }

  def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]], m: Int, k: Int, nn: Int): Array[Array[Long]] = {
    val result = Array.ofDim[Long](m, nn)
    for (r <- 0 until m; c <- 0 until nn)
      result(r)(c) = (0 until k).map(i => W(r)(i).toLong * A(i)(c).toLong).sum
    result
  }

  def applyBiasAndActivation(x: Long, bias: Long, func: Int, shift: Int, relu6Thresh: Int): Long = {
    val biased = x + bias
    val shifted = biased >> shift
    func match {
      case 0 => shifted
      case 1 => if (shifted < 0) 0 else shifted
      case 2 => if (shifted < 0) shifted >> 3 else shifted
      case 3 =>
        if (shifted < 0) 0
        else if (shifted > relu6Thresh) relu6Thresh
        else shifted
    }
  }

  def expectedWithBiasActivation(W: Array[Array[Int]], A: Array[Array[Int]], bias: Array[Int],
                                  m: Int, k: Int, nn: Int,
                                  func: Int, shift: Int, relu6Thresh: Int): Array[Array[Long]] = {
    val raw = expectedMatMul(W, A, m, k, nn)
    Array.tabulate(m, nn)((r, c) => applyBiasAndActivation(raw(r)(c), bias(c).toLong, func, shift, relu6Thresh))
  }

  def runTest(dut: BiasTestHarness, W: Array[Array[Int]], A: Array[Array[Int]], bias: Array[Int],
              m: Int, k: Int, nn: Int,
              func: Int = 0, shift: Int = 0, relu6Thresh: Int = 6): (Int, Array[Array[Long]]) = {
    dut.io.start.poke(false.B)
    dut.io.wt_wr_en.poke(false.B)
    dut.io.act_wr_en.poke(false.B)
    dut.io.bias_wr_en.poke(false.B)
    dut.io.out_wr_en.poke(false.B)
    dut.io.out_rd_addr.poke(0.U)
    pokeFuncEnum(dut, func)
    dut.io.shift.poke(shift.U)
    dut.io.relu6_threshold.poke(relu6Thresh.S)

    waitForIdle(dut)
    loadWeights(dut, W, m, k)
    loadActivations(dut, A, k, nn)
    loadBias(dut, bias, nn)
    clearOutputMem(dut)
    dut.clock.step(5)

    dut.io.M.poke(m.U)
    dut.io.K.poke(k.U)
    dut.io.N.poke(nn.U)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    val cycles = waitForIdle(dut)
    dut.clock.step(5)
    (cycles, readResults(dut, m, nn))
  }

  def assertMatMul(result: Array[Array[Long]], expected: Array[Array[Long]],
                   m: Int, nn: Int, label: String): Unit = {
    var failed = false
    for (r <- 0 until m; c <- 0 until nn) {
      if (result(r)(c) != expected(r)(c)) {
        if (!failed) println(s"  FAILURES in $label:")
        println(s"    ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
        failed = true
      }
    }
    if (!failed) println(s"  $label PASSED")
    for (r <- 0 until m; c <- 0 until nn)
      assert(result(r)(c) == expected(r)(c),
        s"$label mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
  }

  // === Tests ===

  "Bias + Activation" should "add zero bias with identity (no change)" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array(Array(1,2,3,4), Array(5,6,7,8), Array(1,0,1,0), Array(0,1,0,1))
      val A = Array(Array(1,0,0,1), Array(0,1,0,1), Array(0,0,1,1), Array(1,0,0,1))
      val bias = Array.fill(nn)(0)
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 0, 0, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 0, shift = 0)
      println(s"Zero bias identity ($cycles cycles):")
      for (r <- 0 until m) println(s"  Row $r: ${result(r).mkString(", ")}")
      assertMatMul(result, expected, m, nn, "zero bias identity")
    }
  }

  it should "add constant bias with identity" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val bias = Array(10, 20, 30, 40)
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 0, 0, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 0, shift = 0)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"Constant bias identity ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, +bias=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "constant bias identity")
    }
  }

  it should "add bias then apply ReLU" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c - 8) // some negative
      val bias = Array(5, 5, 5, 5) // shifts values up
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 1, 0, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 1, shift = 0)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"Bias + ReLU ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, +bias+relu=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "bias + relu")
    }
  }

  it should "add negative bias" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => 10)
      val bias = Array(-5, -10, -15, -20)
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 0, 0, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 0, shift = 0)
      println(s"Negative bias ($cycles cycles):")
      for (r <- 0 until m) println(s"  Row $r: ${result(r).mkString(", ")}")
      assertMatMul(result, expected, m, nn, "negative bias")
    }
  }

  it should "work with bias + shift + ReLU on 8x8 tiled" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val rng = new Random(42)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val bias = Array.fill(nn)(rng.nextInt(1000) - 500)
      val shift = 4
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 1, shift, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 1, shift = shift)
      println(s"Bias + shift + ReLU 8x8 ($cycles cycles)")
      assertMatMul(result, expected, m, nn, "bias shift relu 8x8")
    }
  }

  it should "work with bias on 12x12 tiled with all activation functions" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 12; val k = 12; val nn = 12
      val rng = new Random(100)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val bias = Array.fill(nn)(rng.nextInt(2000) - 1000)
      val shift = 6
      val thresh = 50

      for (func <- 0 until 4) {
        val funcName = Seq("identity", "relu", "leaky_relu", "relu6")(func)
        val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, func, shift, thresh)
        val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = func, shift = shift, relu6Thresh = thresh)
        println(s"  $funcName 12x12 bias+shift ($cycles cycles)")
        assertMatMul(result, expected, m, nn, s"$funcName 12x12 bias")
      }
    }
  }

  it should "handle bias with deep K accumulation" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val m = 4; val k = 12; val nn = 4
      val rng = new Random(200)
      val W = Array.fill(m, k)(rng.nextInt(64) - 32)
      val A = Array.fill(k, nn)(rng.nextInt(64) - 32)
      val bias = Array(100, -100, 50, -50)
      val shift = 4
      val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, 1, shift, 6)
      val (cycles, result) = runTest(dut, W, A, bias, m, k, nn, func = 1, shift = shift)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"Bias + ReLU deep K=12 ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, final=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "bias deep K")
    }
  }

  it should "stress test with random bias" in {
    simulate(new BiasTestHarness(n)) { dut =>
      val rng = new Random(789)
      waitForIdle(dut)

      for (trial <- 0 until 30) {
        val m = 4 * (rng.nextInt(3) + 1)
        val k = 4 * (rng.nextInt(3) + 1)
        val nn = 4 * (rng.nextInt(3) + 1)
        val func = rng.nextInt(4)
        val shift = rng.nextInt(16)
        val thresh = rng.nextInt(200) + 1
        val W = Array.fill(m, k)(rng.nextInt(256) - 128)
        val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
        val bias = Array.fill(nn)(rng.nextInt(10000) - 5000)
        val funcName = Seq("id", "relu", "lrelu", "relu6")(func)

        loadWeights(dut, W, m, k)
        loadActivations(dut, A, k, nn)
        loadBias(dut, bias, nn)
        clearOutputMem(dut)
        dut.clock.step(5)

        dut.io.M.poke(m.U)
        dut.io.K.poke(k.U)
        dut.io.N.poke(nn.U)
        pokeFuncEnum(dut, func)
        dut.io.shift.poke(shift.U)
        dut.io.relu6_threshold.poke(thresh.S)
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        val cycles = waitForIdle(dut)
        dut.clock.step(5)

        val expected = expectedWithBiasActivation(W, A, bias, m, k, nn, func, shift, thresh)
        val result = readResults(dut, m, nn)

        if (trial % 10 == 0)
          println(s"  Trial $trial: ${m}x${k}*${k}x${nn} $funcName sh=$shift ($cycles cyc)")

        assertMatMul(result, expected, m, nn,
          s"trial $trial (${m}x${k}*${k}x${nn} $funcName sh=$shift)")
      }
      println(s"  All 30 stress trials PASSED")
    }
  }
}