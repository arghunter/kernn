import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class SyncMemStressTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

  class SyncMemTestHarness(val n: Int) extends Module {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val busy = Output(Bool())
      val M = Input(UInt(24.W))
      val K = Input(UInt(24.W))
      val N = Input(UInt(24.W))
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
    val weightMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val actMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val outMem = SyncReadMem(4096, Vec(n, SInt(32.W)))

    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

    when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
    ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

    ctrl.io.output_data_r := outMem.read(ctrl.io.output_rd_addr)
    io.out_rd_data := outMem.read(io.out_rd_addr)

    when(ctrl.io.output_wen) {
      outMem.write(ctrl.io.output_wr_addr, ctrl.io.output_data_wr)
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

  def waitForIdle(dut: SyncMemTestHarness, maxCycles: Int = 500000): Int = {
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

  def loadWeights(dut: SyncMemTestHarness, W: Array[Array[Int]], m: Int, k: Int): Unit = {
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

  def loadActivations(dut: SyncMemTestHarness, A: Array[Array[Int]], k: Int, nn: Int): Unit = {
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

  def clearOutputMem(dut: SyncMemTestHarness, count: Int = 512): Unit = {
    for (addr <- 0 until count) {
      dut.io.out_wr_en.poke(true.B)
      dut.io.out_wr_addr.poke(addr.U)
      for (c <- 0 until n) dut.io.out_wr_data(c).poke(0.S)
      dut.clock.step()
    }
    dut.io.out_wr_en.poke(false.B)
  }

  def readResults(dut: SyncMemTestHarness, m: Int, nn: Int): Array[Array[Long]] = {
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

  def runTest(dut: SyncMemTestHarness, W: Array[Array[Int]], A: Array[Array[Int]],
              m: Int, k: Int, nn: Int): (Int, Array[Array[Long]]) = {
    dut.io.start.poke(false.B)
    dut.io.wt_wr_en.poke(false.B)
    dut.io.act_wr_en.poke(false.B)
    dut.io.out_wr_en.poke(false.B)
    dut.io.out_rd_addr.poke(0.U)
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

  "SyncMem Stress" should "handle all square sizes 4 to 20" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(100)
      for (size <- 4 to 20 by 4) {
        val W = Array.fill(size, size)(rng.nextInt(256) - 128)
        val A = Array.fill(size, size)(rng.nextInt(256) - 128)
        val expected = expectedMatMul(W, A, size, size, size)
        val (cycles, result) = runTest(dut, W, A, size, size, size)
        println(s"  ${size}x${size}: $cycles cycles")
        assertMatMul(result, expected, size, size, s"${size}x${size}")
      }
    }
  }

  it should "handle all non-square combinations of 4,8,12" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(200)
      val dims = Seq(4, 8, 12)
      for (m <- dims; k <- dims; nn <- dims) {
        val W = Array.fill(m, k)(rng.nextInt(256) - 128)
        val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
        val expected = expectedMatMul(W, A, m, k, nn)
        val (cycles, result) = runTest(dut, W, A, m, k, nn)
        println(s"  ${m}x${k} × ${k}x${nn}: $cycles cycles")
        assertMatMul(result, expected, m, nn, s"${m}x${k}*${k}x${nn}")
      }
    }
  }

  it should "handle tall × wide shapes" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(300)
      val testCases = Seq((16, 4, 16), (20, 4, 20), (12, 4, 8), (16, 8, 4), (4, 16, 4))
      for (tc <- testCases) { tc match { case (m, k, nn) =>
        val W = Array.fill(m, k)(rng.nextInt(256) - 128)
        val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
        val expected = expectedMatMul(W, A, m, k, nn)
        val (cycles, result) = runTest(dut, W, A, m, k, nn)
        println(s"  ${m}x${k} × ${k}x${nn}: $cycles cycles")
        assertMatMul(result, expected, m, nn, s"${m}x${k}*${k}x${nn}")
      }}
    }
  }

  it should "handle extreme values" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8

      val W1 = Array.fill(m, k)(127)
      val A1 = Array.fill(k, nn)(127)
      val (_, r1) = runTest(dut, W1, A1, m, k, nn)
      assertMatMul(r1, expectedMatMul(W1, A1, m, k, nn), m, nn, "all +127")

      val W2 = Array.fill(m, k)(-128)
      val A2 = Array.fill(k, nn)(-128)
      val (_, r2) = runTest(dut, W2, A2, m, k, nn)
      assertMatMul(r2, expectedMatMul(W2, A2, m, k, nn), m, nn, "all -128")

      val W3 = Array.tabulate(m, k)((r, c) => if ((r + c) % 2 == 0) 127 else -128)
      val A3 = Array.tabulate(k, nn)((r, c) => if ((r + c) % 2 == 0) -128 else 127)
      val (_, r3) = runTest(dut, W3, A3, m, k, nn)
      assertMatMul(r3, expectedMatMul(W3, A3, m, k, nn), m, nn, "checkerboard")

      val W4 = Array.fill(m, k)(0)
      val A4 = Array.fill(k, nn)(0)
      val (_, r4) = runTest(dut, W4, A4, m, k, nn)
      assertMatMul(r4, expectedMatMul(W4, A4, m, k, nn), m, nn, "all zeros")

      val W5 = Array.fill(m, k)(1)
      val A5 = Array.tabulate(k, nn)((r, c) => if (r == c) 1 else 0)
      val (_, r5) = runTest(dut, W5, A5, m, k, nn)
      assertMatMul(r5, expectedMatMul(W5, A5, m, k, nn), m, nn, "ones × identity")
    }
  }

  it should "survive 50 random back-to-back multiplies" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(400)
      waitForIdle(dut)

      for (trial <- 0 until 50) {
        val m = 4 * (rng.nextInt(4) + 1)
        val k = 4 * (rng.nextInt(4) + 1)
        val nn = 4 * (rng.nextInt(4) + 1)
        val W = Array.fill(m, k)(rng.nextInt(256) - 128)
        val A = Array.fill(k, nn)(rng.nextInt(256) - 128)

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

        val expected = expectedMatMul(W, A, m, k, nn)
        val result = readResults(dut, m, nn)

        if (trial % 10 == 0)
          println(s"  Trial $trial: ${m}x${k} × ${k}x${nn}, $cycles cycles")

        assertMatMul(result, expected, m, nn, s"trial $trial (${m}x${k}*${k}x${nn})")
      }
      println(s"  All 50 trials PASSED")
    }
  }

  it should "handle deep inner dimension" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(500)
      val testCases = Seq((4, 16, 4), (4, 20, 4), (8, 16, 8), (4, 24, 4))
      for (tc <- testCases) { tc match { case (m, k, nn) =>
        val W = Array.fill(m, k)(rng.nextInt(64) - 32)
        val A = Array.fill(k, nn)(rng.nextInt(64) - 32)
        val expected = expectedMatMul(W, A, m, k, nn)
        val (cycles, result) = runTest(dut, W, A, m, k, nn)
        println(s"  ${m}x${k} × ${k}x${nn}: $cycles cycles")
        assertMatMul(result, expected, m, nn, s"${m}x${k}*${k}x${nn}")
      }}
    }
  }

  it should "produce consistent results across 5 identical runs" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val W = Array.tabulate(m, k)((r, c) => (r * 7 + c * 13) % 256 - 128)
      val A = Array.tabulate(k, nn)((r, c) => (r * 11 + c * 3) % 256 - 128)
      val expected = expectedMatMul(W, A, m, k, nn)
      for (run <- 0 until 5) {
        val (_, result) = runTest(dut, W, A, m, k, nn)
        assertMatMul(result, expected, m, nn, s"repeat run $run")
      }
      println(s"  All 5 identical runs PASSED")
    }
  }

  it should "handle single-tile random stress" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(600)
      for (trial <- 0 until 30) {
        val W = Array.fill(n, n)(rng.nextInt(256) - 128)
        val A = Array.fill(n, n)(rng.nextInt(256) - 128)
        val expected = expectedMatMul(W, A, n, n, n)
        val (cycles, result) = runTest(dut, W, A, n, n, n)
        assertMatMul(result, expected, n, n, s"single-tile trial $trial")
      }
      println(s"  All 30 single-tile trials PASSED")
    }
  }
}