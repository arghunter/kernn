import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class TiledMatMulSyncMemTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
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

    // Weight memory
    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

    // Activation memory
    when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
    ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

    // Output memory — dual ported
    // Port A: controller read + testbench read
    ctrl.io.output_data_r := outMem.read(ctrl.io.output_rd_addr)
    io.out_rd_data := outMem.read(io.out_rd_addr)

    // Port B: controller write or testbench write
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
    for (tR <- 0 until m / n) {
      for (tK <- 0 until tilesK) {
        val base = (tR * tilesK + tK) * n
        for (t <- 0 until n) {
          dut.io.wt_wr_en.poke(true.B)
          dut.io.wt_wr_addr.poke((base + t).U)
          for (r <- 0 until n)
            dut.io.wt_wr_data(r).poke(W(tR * n + r)(tK * n + t).S)
          dut.clock.step()
        }
      }
    }
    dut.io.wt_wr_en.poke(false.B)
  }

  def loadActivations(dut: SyncMemTestHarness, A: Array[Array[Int]], k: Int, nn: Int): Unit = {
    val tilesN = nn / n
    for (tK <- 0 until k / n) {
      for (tC <- 0 until tilesN) {
        val base = (tK * tilesN + tC) * n
        for (t <- 0 until n) {
          dut.io.act_wr_en.poke(true.B)
          dut.io.act_wr_addr.poke((base + t).U)
          for (c <- 0 until n)
            dut.io.act_wr_data(c).poke(A(tK * n + t)(tC * n + c).S)
          dut.clock.step()
        }
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
    for (tR <- 0 until m / n) {
      for (tC <- 0 until tilesN) {
        val base = (tR * tilesN + tC) * n
        for (r <- 0 until n) {
          dut.io.out_rd_addr.poke((base + r).U)
          dut.clock.step() // issue address
          dut.clock.step() // data available
          for (c <- 0 until n)
            result(tR * n + r)(tC * n + c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
        }
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

  "TiledMatMulController with SyncReadMem" should "multiply 4x4 identity (single tile)" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"4×4 Identity × A ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "4x4 identity")
    }
  }

  it should "multiply 4x4 general matrices (single tile)" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 4; val k = 4; val nn = 4
      val W = Array(Array(1,2,3,4), Array(5,6,7,8), Array(1,0,1,0), Array(0,1,0,1))
      val A = Array(Array(1,0,0,1), Array(0,1,0,1), Array(0,0,1,1), Array(1,0,0,1))
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"4×4 General ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "4x4 general")
    }
  }

  it should "tile an 8x8 identity multiply" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"8×8 Identity × A ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "8x8 identity")
    }
  }

  it should "tile an 8x8 general multiply" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 8; val k = 8; val nn = 8
      val W = Array.tabulate(m, k)((r, c) => (r - c) % 5)
      val A = Array.tabulate(k, nn)((r, c) => (r + c) % 7 - 3)
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"8×8 General ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "8x8 general")
    }
  }

  it should "tile a 12x12 random multiply" in {
    val rng = new Random(123)
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 12; val k = 12; val nn = 12
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"12×12 Random ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "12x12 random")
    }
  }

  it should "handle non-square 8x4 × 4x8" in {
    val rng = new Random(456)
    simulate(new SyncMemTestHarness(n)) { dut =>
      val m = 8; val k = 4; val nn = 8
      val W = Array.fill(m, k)(rng.nextInt(256) - 128)
      val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
      val expected = expectedMatMul(W, A, m, k, nn)
      val (cycles, result) = runTest(dut, W, A, m, k, nn)

      println(s"8×4 × 4×8 ($cycles cycles):")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertMatMul(result, expected, m, nn, "8x4*4x8")
    }
  }

  it should "survive 20 random back-to-back multiplies" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(400)
      waitForIdle(dut)

      for (trial <- 0 until 20) {
        val m = 4 * (rng.nextInt(3) + 1)
        val k = 4 * (rng.nextInt(3) + 1)
        val nn = 4 * (rng.nextInt(3) + 1)
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

        if (trial % 5 == 0)
          println(s"  Trial $trial: ${m}x${k} × ${k}x${nn}, $cycles cycles")

        assertMatMul(result, expected, m, nn, s"trial $trial (${m}x${k}*${k}x${nn})")
      }
      println(s"  All 20 trials PASSED")
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

      val W3 = Array.fill(m, k)(0)
      val A3 = Array.fill(k, nn)(0)
      val (_, r3) = runTest(dut, W3, A3, m, k, nn)
      assertMatMul(r3, expectedMatMul(W3, A3, m, k, nn), m, nn, "all zeros")
    }
  }

  it should "handle deep inner dimension" in {
    simulate(new SyncMemTestHarness(n)) { dut =>
      val rng = new Random(500)
      val testCases = Seq((4, 16, 4), (4, 20, 4), (8, 16, 8))
      for (tc <- testCases) { tc match { case (m, k, nn) =>
        val W = Array.fill(m, k)(rng.nextInt(64) - 32)
        val A = Array.fill(k, nn)(rng.nextInt(64) - 32)
        val expected = expectedMatMul(W, A, m, k, nn)
        val (cycles, result) = runTest(dut, W, A, m, k, nn)
        println(s"  ${m}x${k} × ${k}x${nn} (deep K): $cycles cycles")
        assertMatMul(result, expected, m, nn, s"${m}x${k}*${k}x${nn}")
      }}
    }
  }

  it should "produce consistent results across repeated runs" in {
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
}