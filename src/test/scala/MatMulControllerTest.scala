// import chisel3._
// import chisel3.simulator._
// import chisel3.simulator.scalatest.{HasCliOptions, Cli}
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// class MatMulControllerTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
//   val n = 4

//   class MatMulTestHarness(val n: Int) extends Module {
//     val io = IO(new Bundle {
//       val start = Input(Bool())
//       val busy = Output(Bool())
//       val M = Input(UInt(24.W))
//       val K = Input(UInt(24.W))
//       val N = Input(UInt(24.W))
//       // Weight memory load
//       val wt_wr_en = Input(Bool())
//       val wt_wr_addr = Input(UInt(16.W))
//       val wt_wr_data = Input(Vec(n, SInt(8.W)))
//       // Activation memory load
//       val act_wr_en = Input(Bool())
//       val act_wr_addr = Input(UInt(16.W))
//       val act_wr_data = Input(Vec(n, SInt(8.W)))
//       // Output memory read
//       val out_rd_addr = Input(UInt(16.W))
//       val out_rd_data = Output(Vec(n, SInt(32.W)))
//     })

//     val ctrl = Module(new MatMulController(n, 16))

//     // Combinational memories
//     val weightMem = Mem(4096, Vec(n, SInt(8.W)))
//     val actMem = Mem(4096, Vec(n, SInt(8.W)))
//     val outMem = Mem(4096, Vec(n, SInt(32.W)))

//     // Weight memory: testbench writes, controller reads
//     when(io.wt_wr_en) {
//       weightMem.write(io.wt_wr_addr, io.wt_wr_data)
//     }
//     ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

//     // Activation memory: testbench writes, controller reads
//     when(io.act_wr_en) {
//       actMem.write(io.act_wr_addr, io.act_wr_data)
//     }
//     ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

//     // Output memory: controller reads and writes, testbench reads
//     when(ctrl.io.output_wen) {
//       outMem.write(ctrl.io.output_addr, ctrl.io.output_data_wr)
//     }
//     ctrl.io.output_data_r := outMem.read(ctrl.io.output_addr)
//     io.out_rd_data := outMem.read(io.out_rd_addr)

//     // Controller connections
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

//   // Weight memory layout:
//   // Address = tileR*n*K + tileK*n + t
//   // Returns Vec(n) = W[tileR*n + 0..n-1][tileK*n + t] (column slice)
//   def loadWeights(dut: MatMulTestHarness, W: Array[Array[Int]], M: Int, K: Int): Unit = {
//     for (row <- 0 until M / n) {
//       for (kTile <- 0 until K / n) {
//         for (t <- 0 until n) {
//           val addr = row * n * K + kTile * n + t
//           dut.io.wt_wr_en.poke(true.B)
//           dut.io.wt_wr_addr.poke(addr.U)
//           for (r <- 0 until n) {
//             dut.io.wt_wr_data(r).poke(W(row * n + r)(kTile * n + t).S)
//           }
//           dut.clock.step()
//         }
//       }
//     }
//     dut.io.wt_wr_en.poke(false.B)
//   }

//   // Activation memory layout:
//   // Address = (tileK*n + t) * N + tileC*n
//   // Returns Vec(n) = A[tileK*n + t][tileC*n + 0..n-1] (row slice)
//   def loadActivations(dut: MatMulTestHarness, A: Array[Array[Int]], K: Int, N: Int): Unit = {
//     for (kTile <- 0 until K / n) {
//       for (t <- 0 until n) {
//         for (cTile <- 0 until N / n) {
//           val addr = (kTile * n + t) * N + cTile * n
//           dut.io.act_wr_en.poke(true.B)
//           dut.io.act_wr_addr.poke(addr.U)
//           for (c <- 0 until n) {
//             dut.io.act_wr_data(c).poke(A(kTile * n + t)(cTile * n + c).S)
//           }
//           dut.clock.step()
//         }
//       }
//     }
//     dut.io.act_wr_en.poke(false.B)
//   }

//   def readResults(dut: MatMulTestHarness, M: Int, N: Int): Array[Array[Long]] = {
//     val result = Array.ofDim[Long](M, N)
//     for (rTile <- 0 until M / n) {
//       for (row <- 0 until n) {
//         for (cTile <- 0 until N / n) {
//           val addr = (rTile * n + row) * N + cTile * n
//           dut.io.out_rd_addr.poke(addr.U)
//           dut.clock.step()
//           for (c <- 0 until n) {
//             val v = dut.io.out_rd_data(c).peek().litValue
//             result(rTile * n + row)(cTile * n + c) =
//               if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong
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

//   def runTest(dut: MatMulTestHarness, W: Array[Array[Int]], A: Array[Array[Int]],
//               M: Int, K: Int, N: Int, maxCycles: Int = 500000): Array[Array[Long]] = {
//     dut.io.start.poke(false.B)
//     dut.io.wt_wr_en.poke(false.B)
//     dut.io.act_wr_en.poke(false.B)
//     dut.io.out_rd_addr.poke(0.U)

//     // Wait for any init to finish
//     var initCycles = 0
//     while (dut.io.busy.peek().litToBoolean && initCycles < 1000) {
//       dut.clock.step()
//       initCycles += 1
//     }

//     // Load memories
//     loadWeights(dut, W, M, K)
//     loadActivations(dut, A, K, N)
//     dut.clock.step(5)

//     // Set dimensions and start
//     dut.io.M.poke(M.U)
//     dut.io.K.poke(K.U)
//     dut.io.N.poke(N.U)
//     dut.io.start.poke(true.B)
//     dut.clock.step()
//     dut.io.start.poke(false.B)

//     // Wait for completion
//     var cycles = 0
//     while (dut.io.busy.peek().litToBoolean && cycles < maxCycles) {
//       dut.clock.step()
//       cycles += 1
//     }
//     assert(cycles < maxCycles, s"Controller did not finish in $maxCycles cycles")
//     println(s"  Completed in $cycles cycles")
//     dut.clock.step(5)

//     readResults(dut, M, N)
//   }

//   "MatMulController" should "multiply 4x4 identity × matrix" in {
//     simulate(new MatMulTestHarness(n)) { dut =>
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

//   it should "multiply 4x4 general matrices" in {
//     simulate(new MatMulTestHarness(n)) { dut =>
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
//     simulate(new MatMulTestHarness(n)) { dut =>
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
//     simulate(new MatMulTestHarness(n)) { dut =>
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
//     simulate(new MatMulTestHarness(n)) { dut =>
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
//     simulate(new MatMulTestHarness(n)) { dut =>
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
//     simulate(new MatMulTestHarness(n)) { dut =>
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

//   it should "handle negative values in 8x8 multiply" in {
//     simulate(new MatMulTestHarness(n)) { dut =>
//       val M = 8; val K = 8; val N = 8
//       val W = Array.tabulate(M, K)((r, c) => -(r * K + c + 1) % 128)
//       val A = Array.tabulate(K, N)((r, c) => (r * N + c + 1) % 128)
//       val expected = expectedMatMul(W, A, M, K, N)
//       val result = runTest(dut, W, A, M, K, N)

//       println("8×8 Negative weights:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until M; c <- 0 until N)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "run back-to-back tiled multiplies" in {
//     val rng = new Random(999)
//     simulate(new MatMulTestHarness(n)) { dut =>
//       for (trial <- 0 until 3) {
//         val M = 8; val K = 8; val N = 8
//         val W = Array.fill(M, K)(rng.nextInt(256) - 128)
//         val A = Array.fill(K, N)(rng.nextInt(256) - 128)
//         val expected = expectedMatMul(W, A, M, K, N)
//         val result = runTest(dut, W, A, M, K, N)

//         println(s"Back-to-back trial $trial:")
//         for (r <- 0 until M; c <- 0 until N)
//           assert(result(r)(c) == expected(r)(c),
//             s"Trial $trial mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//         println(s"  PASSED")
//       }
//     }
//   }
// }