import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class ActivationTiledTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

  class ActivationTestHarness(val n: Int) extends Module {
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

    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

    when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
    ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

    ctrl.io.output_data_r := outMem.read(ctrl.io.output_rd_addr)
    io.out_rd_data := outMem.read(io.out_rd_addr)

    actFunc.io.in := ctrl.io.output_data_wr
    actFunc.io.func := io.func
    actFunc.io.shift := io.shift
    actFunc.io.relu6_threshold := io.relu6_threshold

    // Apply activation only on the last inner tile
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

    ctrl.io.weight_base_addr := 0.U
    ctrl.io.activation_base_addr := 0.U
    ctrl.io.output_base_addr := 0.U
    ctrl.io.M := io.M
    ctrl.io.K := io.K
    ctrl.io.N := io.N
    ctrl.io.start := io.start
    ctrl.io.rst_hard := false.B
    io.busy := ctrl.io.busy
  }

  def waitForIdle(dut: ActivationTestHarness, maxCycles: Int = 500000): Int = {
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

  def loadWeights(dut: ActivationTestHarness, W: Array[Array[Int]], m: Int, k: Int): Unit = {
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

  def loadActivations(dut: ActivationTestHarness, A: Array[Array[Int]], k: Int, nn: Int): Unit = {
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

  def clearOutputMem(dut: ActivationTestHarness, count: Int = 512): Unit = {
    for (addr <- 0 until count) {
      dut.io.out_wr_en.poke(true.B)
      dut.io.out_wr_addr.poke(addr.U)
      for (c <- 0 until n) dut.io.out_wr_data(c).poke(0.S)
      dut.clock.step()
    }
    dut.io.out_wr_en.poke(false.B)
  }

  def readResults(dut: ActivationTestHarness, m: Int, nn: Int): Array[Array[Long]] = {
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

  def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]], m: Int, k: Int, nn: Int): Array[Array[Long]] = {
    val result = Array.ofDim[Long](m, nn)
    for (r <- 0 until m; c <- 0 until nn)
      result(r)(c) = (0 until k).map(i => W(r)(i).toLong * A(i)(c).toLong).sum
    result
  }

  def applyActivation(x: Long, func: Int, shift: Int, relu6Thresh: Int): Long = {
    val shifted = x >> shift
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

  def expectedWithActivation(W: Array[Array[Int]], A: Array[Array[Int]],
                              m: Int, k: Int, nn: Int,
                              func: Int, shift: Int, relu6Thresh: Int): Array[Array[Long]] = {
    val raw = expectedMatMul(W, A, m, k, nn)
    Array.tabulate(m, nn)((r, c) => applyActivation(raw(r)(c), func, shift, relu6Thresh))
  }

  def pokeFuncEnum(dut: ActivationTestHarness, func: Int): Unit = {
    func match {
      case 0 => dut.io.func.poke(ActivationFunc.IDENTITY)
      case 1 => dut.io.func.poke(ActivationFunc.RELU)
      case 2 => dut.io.func.poke(ActivationFunc.LEAKY_RELU)
      case 3 => dut.io.func.poke(ActivationFunc.RELU6)
    }
  }

  def runTest(dut: ActivationTestHarness, W: Array[Array[Int]], A: Array[Array[Int]],
              m: Int, k: Int, nn: Int,
              func: Int = 0, shift: Int = 0, relu6Thresh: Int = 6): (Int, Array[Array[Long]]) = {
    dut.io.start.poke(false.B)
    dut.io.wt_wr_en.poke(false.B)
    dut.io.act_wr_en.poke(false.B)
    dut.io.out_wr_en.poke(false.B)
    dut.io.out_rd_addr.poke(0.U)
    pokeFuncEnum(dut, func)
    dut.io.shift.poke(shift.U)
    dut.io.relu6_threshold.poke(relu6Thresh.S)

    waitForIdle(dut)
    loadWeights(dut, W, m, k)
    loadActivations(dut, A, k, nn)
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

  "Activation + TiledMatMul" should "pass through with identity (single tile)" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array(Array(1,2,3,4), Array(5,6,7,8), Array(1,0,1,0), Array(0,1,0,1))
      val A = Array(Array(1,0,0,1), Array(0,1,0,1), Array(0,0,1,1), Array(1,0,0,1))
      val expected = expectedWithActivation(W, A, m, k, nn, 0, 0, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 0, shift = 0)
      println(s"Identity single tile ($cycles cycles):")
      for (r <- 0 until m) println(s"  Row $r: ${result(r).mkString(", ")}")
      assertMatMul(result, expected, m, nn, "identity single tile")
    }
  }

  it should "apply ReLU on single tile with negatives" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array(Array(1,0,0,0), Array(0,1,0,0), Array(0,0,-1,0), Array(0,0,0,-1))
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val expected = expectedWithActivation(W, A, m, k, nn, 1, 0, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 1, shift = 0)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"ReLU single tile ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, relu=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "relu single tile")
    }
  }

  it should "apply Leaky ReLU on single tile" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array(Array(1,0,0,0), Array(0,1,0,0), Array(0,0,-1,0), Array(0,0,0,-1))
      val A = Array.tabulate(k, nn)((r, c) => (r * nn + c + 1) * 4)
      val expected = expectedWithActivation(W, A, m, k, nn, 2, 0, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 2, shift = 0)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"Leaky ReLU single tile ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, leaky=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "leaky relu single tile")
    }
  }

  it should "apply ReLU6 on single tile" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c - 5)
      val thresh = 6
      val expected = expectedWithActivation(W, A, m, k, nn, 3, 0, thresh)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 3, shift = 0, relu6Thresh = thresh)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"ReLU6 single tile ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, relu6=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "relu6 single tile")
    }
  }

  it should "apply identity with shift on 8x8 tiled" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val rng = new Random(42)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 8
      val expected = expectedWithActivation(W, A, m, k, nn, 0, shift, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 0, shift = shift)
      println(s"Identity shift=$shift 8x8 tiled ($cycles cycles)")
      assertMatMul(result, expected, m, nn, "identity shift=8 tiled")
    }
  }

  it should "apply ReLU with shift on 8x8 tiled" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val rng = new Random(100)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 4
      val expected = expectedWithActivation(W, A, m, k, nn, 1, shift, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 1, shift = shift)
      println(s"ReLU shift=$shift 8x8 tiled ($cycles cycles)")
      assertMatMul(result, expected, m, nn, "relu shift=4 tiled")
    }
  }

  it should "apply Leaky ReLU with shift on 8x8 tiled" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val rng = new Random(200)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 6
      val expected = expectedWithActivation(W, A, m, k, nn, 2, shift, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 2, shift = shift)
      println(s"Leaky ReLU shift=$shift 8x8 tiled ($cycles cycles)")
      assertMatMul(result, expected, m, nn, "leaky relu shift=6 tiled")
    }
  }

  it should "apply ReLU6 with shift on 8x8 tiled" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val rng = new Random(300)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 8
      val thresh = 100
      val expected = expectedWithActivation(W, A, m, k, nn, 3, shift, thresh)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 3, shift = shift, relu6Thresh = thresh)
      println(s"ReLU6 shift=$shift thresh=$thresh 8x8 tiled ($cycles cycles)")
      assertMatMul(result, expected, m, nn, "relu6 shift=8 tiled")
    }
  }

  it should "apply all functions on same 12x12 data" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 12; val k = 12; val nn = 12
      val rng = new Random(400)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 6
      val thresh = 50

      for (func <- 0 until 4) {
        val funcName = Seq("identity", "relu", "leaky_relu", "relu6")(func)
        val expected = expectedWithActivation(W, A, m, k, nn, func, shift, thresh)
        val (cycles, result) = runTest(dut, W, A, m, k, nn, func = func, shift = shift, relu6Thresh = thresh)
        println(s"  $funcName 12x12 shift=$shift ($cycles cycles)")
        assertMatMul(result, expected, m, nn, s"$funcName 12x12")
      }
    }
  }

  it should "handle various shift amounts with ReLU" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.fill(m, k)(127)
      val A = Array.fill(k, nn)(127)

      for (shift <- Seq(0, 1, 4, 8, 12, 16)) {
        val expected = expectedWithActivation(W, A, m, k, nn, 1, shift, 6)
        val (_, result) = runTest(dut, W, A, m, k, nn, func = 1, shift = shift)
        println(s"  ReLU shift=$shift: ${result(0).mkString(",")}")
        assertMatMul(result, expected, m, nn, s"relu shift=$shift")
      }
    }
  }

  it should "correctly bypass activation during accumulation (multi-tile K)" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      // 4x8 × 8x4: K=8 means tilesK=2, so we accumulate once
      val m = 4; val k = 8; val nn = 4
      val rng = new Random(500)
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val shift = 4

      // With ReLU: activation should only apply after full accumulation
      val expected = expectedWithActivation(W, A, m, k, nn, 1, shift, 6)
      val (cycles, result) = runTest(dut, W, A, m, k, nn, func = 1, shift = shift)
      val raw = expectedMatMul(W, A, m, k, nn)
      println(s"ReLU with K=8 accumulation ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: raw=${raw(r).mkString(",")}, shifted+relu=${result(r).mkString(",")}")
      assertMatMul(result, expected, m, nn, "relu accumulation K=8")
    }
  }

  it should "stress test with random params" in {
    simulate(new ActivationTestHarness(n)) { dut =>
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
        val funcName = Seq("id", "relu", "lrelu", "relu6")(func)

        loadWeights(dut, W, m, k)
        loadActivations(dut, A, k, nn)
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

        val expected = expectedWithActivation(W, A, m, k, nn, func, shift, thresh)
        val result = readResults(dut, m, nn)

        if (trial % 10 == 0)
          println(s"  Trial $trial: ${m}x${k}*${k}x${nn} $funcName sh=$shift ($cycles cyc)")

        assertMatMul(result, expected, m, nn,
          s"trial $trial (${m}x${k}*${k}x${nn} $funcName sh=$shift)")
      }
      println(s"  All 30 stress trials PASSED")
    }
  }

  it should "handle non-square with activation and deep K" in {
    simulate(new ActivationTestHarness(n)) { dut =>
      val rng = new Random(999)
      val testCases = Seq((4, 16, 4), (8, 12, 4), (4, 8, 12), (12, 8, 8))
      for (tc <- testCases) { tc match { case (m, k, nn) =>
        val W = Array.fill(m, k)(rng.nextInt(64) - 32)
        val A = Array.fill(k, nn)(rng.nextInt(64) - 32)
        val shift = 4
        val thresh = 30
        for (func <- 0 until 4) {
          val funcName = Seq("id", "relu", "lrelu", "relu6")(func)
          val expected = expectedWithActivation(W, A, m, k, nn, func, shift, thresh)
          val (cycles, result) = runTest(dut, W, A, m, k, nn, func = func, shift = shift, relu6Thresh = thresh)
          assertMatMul(result, expected, m, nn, s"${m}x${k}*${k}x${nn} $funcName")
        }
        println(s"  ${m}x${k} × ${k}x${nn} all funcs PASSED")
      }}
    }
  }
}