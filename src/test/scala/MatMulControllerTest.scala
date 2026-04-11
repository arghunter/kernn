// import chisel3._
// import chisel3.simulator._
// import chisel3.simulator.scalatest.{HasCliOptions, Cli}
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// class TiledMatMulControllerTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
//   val n = 4

//   class TiledTestHarness(val n: Int) extends Module {
//     val io = IO(new Bundle {
//       val start = Input(Bool())
//       val busy = Output(Bool())
//       val M = Input(UInt(24.W))
//       val K = Input(UInt(24.W))
//       val N = Input(UInt(24.W))
//       val wt_wr_en = Input(Bool())
//       val wt_wr_addr = Input(UInt(16.W))
//       val wt_wr_data = Input(Vec(n, SInt(8.W)))
//       val act_wr_en = Input(Bool())
//       val act_wr_addr = Input(UInt(16.W))
//       val act_wr_data = Input(Vec(n, SInt(8.W)))
//       val out_rd_addr = Input(UInt(16.W))
//       val out_rd_data = Output(Vec(n, SInt(32.W)))
//       val out_wr_en = Input(Bool())
//       val out_wr_addr = Input(UInt(16.W))
//       val out_wr_data = Input(Vec(n, SInt(32.W)))
//     })

//     val ctrl = Module(new TiledMatMulController(n, 16))

//     val weightMem = Mem(4096, Vec(n, SInt(8.W)))
//     val actMem = Mem(4096, Vec(n, SInt(8.W)))
//     val outMem = Mem(4096, Vec(n, SInt(32.W)))

//     when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
//     ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

//     when(io.act_wr_en) { actMem.write(io.act_wr_addr, io.act_wr_data) }
//     ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

//     // Output memory: controller writes take priority, then testbench writes
//     when(ctrl.io.output_wen) {
//       outMem.write(ctrl.io.output_addr, ctrl.io.output_data_wr)
//     }.elsewhen(io.out_wr_en) {
//       outMem.write(io.out_wr_addr, io.out_wr_data)
//     }
//     ctrl.io.output_data_r := outMem.read(ctrl.io.output_addr)
//     io.out_rd_data := outMem.read(io.out_rd_addr)

//     ctrl.io.weight_base_addr := 0.U
//     ctrl.io.activation_base_addr := 0.U
//     ctrl.io.output_base_addr := 0.U
//     ctrl.io.M := io.M
//     ctrl.io.K := io.K
//     ctrl.io.N := io.N
//     ctrl.io.start := io.start
//     ctrl.io.rst_hard := false.B
//     io.busy := ctrl.io.busy
//   }

//   def waitForIdle(dut: TiledTestHarness, maxCycles: Int = 500000): Int = {
//     var cycles = 0
//     while (dut.io.busy.peek().litToBoolean && cycles < maxCycles) {
//       dut.clock.step()
//       cycles += 1
//     }
//     assert(cycles < maxCycles, s"Did not reach IDLE in $maxCycles cycles")
//     cycles
//   }

//   def signExtend(v: BigInt): Long = {
//     if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong
//   }

//   def loadWeights(dut: TiledTestHarness, W: Array[Array[Int]], M: Int, K: Int): Unit = {
//     val tilesK = K / n
//     for (tR <- 0 until M / n) {
//       for (tK <- 0 until tilesK) {
//         val base = (tR * tilesK + tK) * n
//         for (t <- 0 until n) {
//           dut.io.wt_wr_en.poke(true.B)
//           dut.io.wt_wr_addr.poke((base + t).U)
//           for (r <- 0 until n) {
//             dut.io.wt_wr_data(r).poke(W(tR * n + r)(tK * n + t).S)
//           }
//           dut.clock.step()
//         }
//       }
//     }
//     dut.io.wt_wr_en.poke(false.B)
//   }

//   def loadActivations(dut: TiledTestHarness, A: Array[Array[Int]], K: Int, N: Int): Unit = {
//     val tilesN = N / n
//     for (tK <- 0 until K / n) {
//       for (tC <- 0 until tilesN) {
//         val base = (tK * tilesN + tC) * n
//         for (t <- 0 until n) {
//           dut.io.act_wr_en.poke(true.B)
//           dut.io.act_wr_addr.poke((base + t).U)
//           for (c <- 0 until n) {
//             dut.io.act_wr_data(c).poke(A(tK * n + t)(tC * n + c).S)
//           }
//           dut.clock.step()
//         }
//       }
//     }
//     dut.io.act_wr_en.poke(false.B)
//   }

//   def clearOutputMem(dut: TiledTestHarness, count: Int = 512): Unit = {
//     for (addr <- 0 until count) {
//       dut.io.out_wr_en.poke(true.B)
//       dut.io.out_wr_addr.poke(addr.U)
//       for (c <- 0 until n) dut.io.out_wr_data(c).poke(0.S)
//       dut.clock.step()
//     }
//     dut.io.out_wr_en.poke(false.B)
//   }

//   def readResults(dut: TiledTestHarness, M: Int, N: Int): Array[Array[Long]] = {
//     val tilesN = N / n
//     val result = Array.ofDim[Long](M, N)
//     for (tR <- 0 until M / n) {
//       for (tC <- 0 until tilesN) {
//         val base = (tR * tilesN + tC) * n
//         for (r <- 0 until n) {
//           dut.io.out_rd_addr.poke((base + r).U)
//           dut.clock.step()
//           for (c <- 0 until n) {
//             result(tR * n + r)(tC * n + c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
//           }
//         }
//       }
//     }
//     result
//   }

//   def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]], M: Int, K: Int, N: Int): Array[Array[Long]] = {
//     val result = Array.ofDim[Long](M, N)
//     for (r <- 0 until M; c <- 0 until N)
//       result(r)(c) = (0 until K).map(k => W(r)(k).toLong * A(k)(c).toLong).sum
//     result
//   }

//   def runTest(dut: TiledTestHarness, W: Array[Array[Int]], A: Array[Array[Int]],
//               M: Int, K: Int, N: Int, maxCycles: Int = 500000): Array[Array[Long]] = {
//     dut.io.start.poke(false.B)
//     dut.io.wt_wr_en.poke(false.B)
//     dut.io.act_wr_en.poke(false.B)
//     dut.io.out_wr_en.poke(false.B)
//     dut.io.out_rd_addr.poke(0.U)

//     waitForIdle(dut)

//     loadWeights(dut, W, M, K)
//     loadActivations(dut, A, K, N)
//     clearOutputMem(dut)
//     dut.clock.step(5)

//     dut.io.M.poke(M.U)
//     dut.io.K.poke(K.U)
//     dut.io.N.poke(N.U)
//     dut.io.start.poke(true.B)
//     dut.clock.step()
//     dut.io.start.poke(false.B)

//     val cycles = waitForIdle(dut, maxCycles)
//     println(s"  Completed in $cycles cycles")
//     dut.clock.step(5)

//     readResults(dut, M, N)
//   }

//   "TiledMatMulController" should "multiply 4x4 identity × matrix (single tile)" in {
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 4; val K = 4; val N = 4
//       val W = Array.tabulate(M, K)((r, c) => if (r == c) 1 else 0)
//       val A = Array.tabulate(K, N)((r, c) => r * N + c + 1)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("4×4 Identity × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "multiply 4x4 general matrices (single tile)" in {
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 4; val K = 4; val N = 4
//       val W = Array(
//         Array(1, 2, 3, 4),
//         Array(5, 6, 7, 8),
//         Array(1, 0, 1, 0),
//         Array(0, 1, 0, 1)
//       )
//       val A = Array(
//         Array(1, 0, 0, 1),
//         Array(0, 1, 0, 1),
//         Array(0, 0, 1, 1),
//         Array(1, 0, 0, 1)
//       )
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("4×4 General W × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile an 8x8 identity multiply" in {
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 8; val K = 8; val N = 8
//       val W = Array.tabulate(M, K)((r, c) => if (r == c) 1 else 0)
//       val A = Array.tabulate(K, N)((r, c) => r * N + c + 1)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("8×8 Identity × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile an 8x8 general multiply" in {
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 8; val K = 8; val N = 8
//       val W = Array.tabulate(M, K)((r, c) => (r - c) % 5)
//       val A = Array.tabulate(K, N)((r, c) => (r + c) % 7 - 3)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("8×8 General W × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile a 12x12 random multiply" in {
//     val rng = new Random(123)
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 12; val K = 12; val N = 12
//       val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//       val A = Array.fill(K, N)(rng.nextInt(256) - 128)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("12×12 Random W × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "handle non-square 8x4 × 4x8" in {
//     val rng = new Random(456)
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 8; val K = 4; val N = 8
//       val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//       val A = Array.fill(K, N)(rng.nextInt(256) - 128)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("8×4 × 4×8:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile a 16x16 random multiply" in {
//     val rng = new Random(789)
//     simulate(new TiledTestHarness(n)) { dut =>
//       val M = 16; val K = 16; val N = 16
//       val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//       val A = Array.fill(K, N)(rng.nextInt(256) - 128)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("16×16 Random (first 4 rows):")
//       for (r <- 0 until 4)
//         println(s"  Row $r: ${result(r).take(8).mkString(", ")}... (expected: ${expected(r).take(8).mkString(", ")}...)")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "run back-to-back tiled multiplies" in {
//     val rng = new Random(999)
//     simulate(new TiledTestHarness(n)) { dut =>
//       waitForIdle(dut)

//       for (trial <- 0 until 3) {
//         val M = 8; val K = 8; val N = 8
//         val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//         val A = Array.fill(K, N)(rng.nextInt(256) - 128)

//         loadWeights(dut, W, M, K)
//         loadActivations(dut, A, K, N)
//         clearOutputMem(dut)
//         dut.clock.step(5)

//         dut.io.M.poke(M.U)
//         dut.io.K.poke(K.U)
//         dut.io.N.poke(N.U)
//         dut.io.start.poke(true.B)
//         dut.clock.step()
//         dut.io.start.poke(false.B)

//         val cycles = waitForIdle(dut)
//         dut.clock.step(5)

//         val expected = expectedMatMul(W, A, M, K, N)
//         val result = readResults(dut, M, N)

//         println(s"Back-to-back trial $trial ($cycles cycles):")
//         for (r <- 0 until M; c <- 0 until N)
//           assert(result(r)(c) == expected(r)(c),
//             s"Trial $trial mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//         println(s"  PASSED")
//       }
//     }
//   }

//   it should "pass random stress test" in {
//     val rng = new Random(42)
//     simulate(new TiledTestHarness(n)) { dut =>
//       waitForIdle(dut)

//       for (trial <- 0 until 5) {
//         val M = 4 * (rng.nextInt(3) + 1)
//         val K = 4 * (rng.nextInt(3) + 1)
//         val N = 4 * (rng.nextInt(3) + 1)
//         val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//         val A = Array.fill(K, N)(rng.nextInt(256) - 128)

//         loadWeights(dut, W, M, K)
//         loadActivations(dut, A, K, N)
//         clearOutputMem(dut)
//         dut.clock.step(5)

//         dut.io.M.poke(M.U)
//         dut.io.K.poke(K.U)
//         dut.io.N.poke(N.U)
//         dut.io.start.poke(true.B)
//         dut.clock.step()
//         dut.io.start.poke(false.B)

//         val cycles = waitForIdle(dut)
//         dut.clock.step(5)

//         val expected = expectedMatMul(W, A, M, K, N)
//         val result = readResults(dut, M, N)

//         println(s"Stress trial $trial (${M}x${K} × ${K}x${N}, $cycles cycles):")
//         for (r <- 0 until M; c <- 0 until N)
//           assert(result(r)(c) == expected(r)(c),
//             s"Trial $trial mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//         println(s"  PASSED")
//       }
//     }
//   }
// }