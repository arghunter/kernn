import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class TiledSysArrayControllerTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

  class ControllerTestHarness(val n: Int) extends Module {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val busy = Output(Bool())
      val weight_base_addr = Input(UInt(16.W))
      val activation_base_addr = Input(UInt(16.W))
      val output_base_addr = Input(UInt(16.W))
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

    val ctrl = Module(new SysArrayController(n, 16, 16, 16))
    val weightMem = Mem(4096, Vec(n, SInt(8.W)))
    val actMem = Mem(4096, Vec(n, SInt(8.W)))
    val outMem = Mem(4096, Vec(n, SInt(32.W)))

    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

    when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
    ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

    when(ctrl.io.output_wen) {
      outMem.write(ctrl.io.output_addr, ctrl.io.output_data_wr)
    }.elsewhen(io.out_wr_en) {
      outMem.write(io.out_wr_addr, io.out_wr_data)
    }
    io.out_rd_data := outMem.read(io.out_rd_addr)
    ctrl.io.output_data_r := outMem.read(ctrl.io.output_addr)

    ctrl.io.weight_base_addr := io.weight_base_addr
    ctrl.io.activation_base_addr := io.activation_base_addr
    ctrl.io.output_base_addr := io.output_base_addr
    ctrl.io.start := io.start
    ctrl.io.rst_hard := false.B
    io.busy := ctrl.io.busy
  }

  def waitForIdle(dut: ControllerTestHarness, maxCycles: Int = 10000): Int = {
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

  // All tile data stored contiguously:
  // Weight tile (tR, tK) at base = (tR * tilesK + tK) * n, addresses base+0..base+n-1
  // Each address stores Vec(n) = column t across n rows: W[tR*n+0..n-1][tK*n+t]
  def loadWeights(dut: ControllerTestHarness, W: Array[Array[Int]], M: Int, K: Int): Unit = {
    val tilesK = K / n
    for (tR <- 0 until M / n) {
      for (tK <- 0 until tilesK) {
        val base = (tR * tilesK + tK) * n
        for (t <- 0 until n) {
          dut.io.wt_wr_en.poke(true.B)
          dut.io.wt_wr_addr.poke((base + t).U)
          for (r <- 0 until n) {
            dut.io.wt_wr_data(r).poke(W(tR * n + r)(tK * n + t).S)
          }
          dut.clock.step()
        }
      }
    }
    dut.io.wt_wr_en.poke(false.B)
  }

  // Activation tile (tK, tC) at base = (tK * tilesN + tC) * n, addresses base+0..base+n-1
  // Each address stores Vec(n) = row t across n cols: A[tK*n+t][tC*n+0..n-1]
  def loadActivations(dut: ControllerTestHarness, A: Array[Array[Int]], K: Int, N: Int): Unit = {
    val tilesN = N / n
    for (tK <- 0 until K / n) {
      for (tC <- 0 until tilesN) {
        val base = (tK * tilesN + tC) * n
        for (t <- 0 until n) {
          dut.io.act_wr_en.poke(true.B)
          dut.io.act_wr_addr.poke((base + t).U)
          for (c <- 0 until n) {
            dut.io.act_wr_data(c).poke(A(tK * n + t)(tC * n + c).S)
          }
          dut.clock.step()
        }
      }
    }
    dut.io.act_wr_en.poke(false.B)
  }

  // Output tile (tR, tC) at base = (tR * tilesN + tC) * n, addresses base+0..base+n-1
  // Each address stores Vec(n) = row r across n cols: C[tR*n+r][tC*n+0..n-1]
  def clearOutput(dut: ControllerTestHarness, M: Int, N: Int): Unit = {
    val tilesN = N / n
    for (tR <- 0 until M / n) {
      for (tC <- 0 until tilesN) {
        val base = (tR * tilesN + tC) * n
        for (r <- 0 until n) {
          dut.io.out_wr_en.poke(true.B)
          dut.io.out_wr_addr.poke((base + r).U)
          for (c <- 0 until n) {
            dut.io.out_wr_data(c).poke(0.S)
          }
          dut.clock.step()
        }
      }
    }
    dut.io.out_wr_en.poke(false.B)
  }

  def readResults(dut: ControllerTestHarness, M: Int, N: Int): Array[Array[Long]] = {
    val tilesN = N / n
    val result = Array.ofDim[Long](M, N)
    for (tR <- 0 until M / n) {
      for (tC <- 0 until tilesN) {
        val base = (tR * tilesN + tC) * n
        for (r <- 0 until n) {
          dut.io.out_rd_addr.poke((base + r).U)
          dut.clock.step()
          for (c <- 0 until n) {
            result(tR * n + r)(tC * n + c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
          }
        }
      }
    }
    result
  }

  def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]], M: Int, K: Int, N: Int): Array[Array[Long]] = {
    val result = Array.ofDim[Long](M, N)
    for (r <- 0 until M; c <- 0 until N)
      result(r)(c) = (0 until K).map(k => W(r)(k).toLong * A(k)(c).toLong).sum
    result
  }

  def runOneTile(dut: ControllerTestHarness, weightBase: Int, actBase: Int, outBase: Int): Int = {
    dut.io.weight_base_addr.poke(weightBase.U)
    dut.io.activation_base_addr.poke(actBase.U)
    dut.io.output_base_addr.poke(outBase.U)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    waitForIdle(dut)
  }

  def tiledMatMul(dut: ControllerTestHarness, M: Int, K: Int, N: Int): Array[Array[Long]] = {
    val tilesM = M / n
    val tilesK = K / n
    val tilesN = N / n

    // Temp output area after real output
    val tempOutBase = tilesM * tilesN * n + 100 // safely after output area

    clearOutput(dut, M, N)

    var totalCycles = 0

    for (tR <- 0 until tilesM) {
      for (tC <- 0 until tilesN) {
        for (tK <- 0 until tilesK) {
          val weightBase = (tR * tilesK + tK) * n
          val actBase = (tK * tilesN + tC) * n
          val tileOutBase = tempOutBase

          val cycles = runOneTile(dut, weightBase, actBase, tileOutBase)
          totalCycles += cycles

          // Accumulate: read sysarr result from temp, add to real output, write back
          val realOutTileBase = (tR * tilesN + tC) * n
          for (row <- 0 until n) {
            // Read sysarr result
            dut.io.out_rd_addr.poke((tileOutBase + row).U)
            dut.clock.step()
            val tileRow = Array.ofDim[Long](n)
            for (c <- 0 until n) {
              tileRow(c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
            }

            // Read existing accumulated value
            dut.io.out_rd_addr.poke((realOutTileBase + row).U)
            dut.clock.step()
            val accRow = Array.ofDim[Long](n)
            for (c <- 0 until n) {
              accRow(c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
            }

            // Write back sum
            dut.io.out_wr_en.poke(true.B)
            dut.io.out_wr_addr.poke((realOutTileBase + row).U)
            for (c <- 0 until n) {
              dut.io.out_wr_data(c).poke((tileRow(c) + accRow(c)).S)
            }
            dut.clock.step()
            dut.io.out_wr_en.poke(false.B)
          }
        }
      }
    }

    println(s"  Total compute cycles: $totalCycles")
    readResults(dut, M, N)
  }

  "Tiled SysArrayController" should "tile an 8x8 identity multiply" in {
    simulate(new ControllerTestHarness(n)) { dut =>
      val M = 8; val K = 8; val N = 8
      val W = Array.tabulate(M, K)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(K, N)((r, c) => r * N + c + 1)

      waitForIdle(dut)
      loadWeights(dut, W, M, K)
      loadActivations(dut, A, K, N)
      dut.clock.step(5)

      val expected = expectedMatMul(W, A, M, K, N)
      val result = tiledMatMul(dut, M, K, N)

      println("8×8 Identity × A:")
      for (r <- 0 until M)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until M; c <- 0 until N)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "tile an 8x8 general multiply" in {
    simulate(new ControllerTestHarness(n)) { dut =>
      val M = 8; val K = 8; val N = 8
      val W = Array.tabulate(M, K)((r, c) => (r - c) % 5)
      val A = Array.tabulate(K, N)((r, c) => (r + c) % 7 - 3)

      waitForIdle(dut)
      loadWeights(dut, W, M, K)
      loadActivations(dut, A, K, N)
      dut.clock.step(5)

      val expected = expectedMatMul(W, A, M, K, N)
      val result = tiledMatMul(dut, M, K, N)

      println("8×8 General W × A:")
      for (r <- 0 until M)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until M; c <- 0 until N)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "tile a 12x12 random multiply" in {
    val rng = new Random(123)
    simulate(new ControllerTestHarness(n)) { dut =>
      val M = 12; val K = 12; val N = 12
      val W = Array.fill(M, K)(rng.nextInt(256) - 128)
      val A = Array.fill(K, N)(rng.nextInt(256) - 128)

      waitForIdle(dut)
      loadWeights(dut, W, M, K)
      loadActivations(dut, A, K, N)
      dut.clock.step(5)

      val expected = expectedMatMul(W, A, M, K, N)
      val result = tiledMatMul(dut, M, K, N)

      println("12×12 Random W × A:")
      for (r <- 0 until M)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until M; c <- 0 until N)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "handle non-square 8x4 × 4x8" in {
    val rng = new Random(456)
    simulate(new ControllerTestHarness(n)) { dut =>
      val M = 8; val K = 4; val N = 8
      val W = Array.fill(M, K)(rng.nextInt(256) - 128)
      val A = Array.fill(K, N)(rng.nextInt(256) - 128)

      waitForIdle(dut)
      loadWeights(dut, W, M, K)
      loadActivations(dut, A, K, N)
      dut.clock.step(5)

      val expected = expectedMatMul(W, A, M, K, N)
      val result = tiledMatMul(dut, M, K, N)

      println("8×4 × 4×8:")
      for (r <- 0 until M)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until M; c <- 0 until N)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "tile a 16x16 random multiply" in {
    val rng = new Random(789)
    simulate(new ControllerTestHarness(n)) { dut =>
      val M = 16; val K = 16; val N = 16
      val W = Array.fill(M, K)(rng.nextInt(256) - 128)
      val A = Array.fill(K, N)(rng.nextInt(256) - 128)

      waitForIdle(dut)
      loadWeights(dut, W, M, K)
      loadActivations(dut, A, K, N)
      dut.clock.step(5)

      val expected = expectedMatMul(W, A, M, K, N)
      val result = tiledMatMul(dut, M, K, N)

      println("16×16 Random (first 4 rows):")
      for (r <- 0 until 16)
        println(s"  Row $r: ${result(r).take(8).mkString(", ")}... (expected: ${expected(r).take(8).mkString(", ")}...)")
      for (r <- 0 until M; c <- 0 until N)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "run back-to-back tiled multiplies" in {
    val rng = new Random(999)
    simulate(new ControllerTestHarness(n)) { dut =>
      waitForIdle(dut)

      for (trial <- 0 until 3) {
        val M = 8; val K = 8; val N = 8
        val W = Array.fill(M, K)(rng.nextInt(256) - 128)
        val A = Array.fill(K, N)(rng.nextInt(256) - 128)

        loadWeights(dut, W, M, K)
        loadActivations(dut, A, K, N)
        dut.clock.step(5)

        val expected = expectedMatMul(W, A, M, K, N)
        val result = tiledMatMul(dut, M, K, N)

        println(s"Back-to-back trial $trial:")
        for (r <- 0 until M; c <- 0 until N)
          assert(result(r)(c) == expected(r)(c),
            s"Trial $trial mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
        println(s"  PASSED")
      }
    }
  }
}