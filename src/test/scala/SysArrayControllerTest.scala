// import chisel3._
// import chisel3.simulator._
// import chisel3.simulator.scalatest.{HasCliOptions, Cli}
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// class SysArrayControllerTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
//   val n = 4

//   // Combinational memory model
//   // Weight memory: address t returns Vec(n) = W[0][t], W[1][t], ..., W[n-1][t]
//   //   i.e. column t across all rows
//   // Activation memory: address t returns Vec(n) = A[t][0], A[t][1], ..., A[t][n-1]
//   //   i.e. row t across all columns
//   // Output memory: address r stores Vec(n) = C[r][0], C[r][1], ..., C[r][n-1]
//   //   i.e. row r across all columns

//   class ControllerTestHarness(val n: Int) extends Module {
//     val io = IO(new Bundle {
//       val start = Input(Bool())
//       val busy = Output(Bool())
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

//     val ctrl = Module(new SysArrayController(n, 16, 16, 16))

//     // Combinational memories (Mem, not SyncReadMem)
//     val weightMem = Mem(256, Vec(n, SInt(8.W)))
//     val actMem = Mem(256, Vec(n, SInt(8.W)))
//     val outMem = Mem(256, Vec(n, SInt(32.W)))

//     // Weight memory: testbench writes, controller reads combinationally
//     when(io.wt_wr_en) {
//       weightMem.write(io.wt_wr_addr, io.wt_wr_data)
//     }
//     ctrl.io.weight_data := weightMem.read(ctrl.io.weight_addr)

//     // Activation memory: testbench writes, controller reads combinationally
//     when(io.act_wr_en) {
//       actMem.write(io.act_wr_addr, io.act_wr_data)
//     }
//     ctrl.io.activation_data := actMem.read(ctrl.io.activation_addr)

//     // Output memory: controller writes, testbench reads combinationally
//     when(ctrl.io.output_wen) {
//       outMem.write(ctrl.io.output_addr, ctrl.io.output_data_wr)
//     }
//     io.out_rd_data := outMem.read(io.out_rd_addr)
//     ctrl.io.output_data_r := outMem.read(ctrl.io.output_addr)

//     // Controller connections
//     ctrl.io.weight_base_addr := 0.U
//     ctrl.io.activation_base_addr := 0.U
//     ctrl.io.output_base_addr := 0.U
//     // ctrl.io.weight_dims(0) := n.U
//     // ctrl.io.weight_dims(1) := n.U
//     // ctrl.io.activation_dims(0) := n.U
//     // ctrl.io.activation_dims(1) := n.U
//     ctrl.io.start := io.start
//     ctrl.io.rst_hard := false.B
//     io.busy := ctrl.io.busy
//   }

//   def loadWeights(dut: ControllerTestHarness, W: Array[Array[Int]]): Unit = {
//     // Address t stores column t: W[0][t], W[1][t], ..., W[n-1][t]
//     for (t <- 0 until n) {
//       dut.io.wt_wr_en.poke(true.B)
//       dut.io.wt_wr_addr.poke(t.U)
//       for (r <- 0 until n) {
//         dut.io.wt_wr_data(r).poke(W(r)(t).S)
//       }
//       dut.clock.step()
//     }
//     dut.io.wt_wr_en.poke(false.B)
//   }

//   def loadActivations(dut: ControllerTestHarness, A: Array[Array[Int]]): Unit = {
//     // Address t stores row t: A[t][0], A[t][1], ..., A[t][n-1]
//     for (t <- 0 until n) {
//       dut.io.act_wr_en.poke(true.B)
//       dut.io.act_wr_addr.poke(t.U)
//       for (c <- 0 until n) {
//         dut.io.act_wr_data(c).poke(A(t)(c).S)
//       }
//       dut.clock.step()
//     }
//     dut.io.act_wr_en.poke(false.B)
//   }

//   def readResults(dut: ControllerTestHarness): Array[Array[Long]] = {
//     val result = Array.ofDim[Long](n, n)
//     for (r <- 0 until n) {
//       dut.io.out_rd_addr.poke(r.U)
//       // Combinational read — no step needed, but step to let it settle
//       dut.clock.step()
//       for (c <- 0 until n) {
//         val v = dut.io.out_rd_data(c).peek().litValue
//         result(r)(c) = if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong
//       }
//     }
//     result
//   }

//   def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]]): Array[Array[Long]] = {
//     val result = Array.ofDim[Long](n, n)
//     for (r <- 0 until n; c <- 0 until n)
//       result(r)(c) = (0 until n).map(k => W(r)(k).toLong * A(k)(c).toLong).sum
//     result
//   }

//   def runTest(dut: ControllerTestHarness, W: Array[Array[Int]], A: Array[Array[Int]], maxCycles: Int = 10000): Array[Array[Long]] = {
//     dut.io.start.poke(false.B)
//     dut.io.wt_wr_en.poke(false.B)
//     dut.io.act_wr_en.poke(false.B)
//     dut.io.out_rd_addr.poke(0.U)

//     // Wait for INIT to finish
//     while (dut.io.busy.peek().litToBoolean) {
//       dut.clock.step()
//     }

//     // Load memories
//     loadWeights(dut, W)
//     loadActivations(dut, A)
//     dut.clock.step(2)

//     // Start
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
//     dut.clock.step(2)

//     readResults(dut)
//   }

//   "SysArrayController" should "multiply identity × matrix" in {
//     simulate(new ControllerTestHarness(n)) { dut =>
//       val W = Array.tabulate(n, n)((r, c) => if (r == c) 1 else 0)
//       val A = Array.tabulate(n, n)((r, c) => r * n + c + 1)
//       val expected = expectedMatMul(W, A)
//       val result = runTest(dut, W, A)

//       println("Identity × A:")
//       for (r <- 0 until n)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until n; c <- 0 until n)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "multiply matrix × identity" in {
//     simulate(new ControllerTestHarness(n)) { dut =>
//       val W = Array(
//         Array(1, 2, 3, 4),
//         Array(5, 6, 7, 8),
//         Array(1, 0, 1, 0),
//         Array(0, 1, 0, 1)
//       )
//       val A = Array.tabulate(n, n)((r, c) => if (r == c) 1 else 0)
//       val expected = expectedMatMul(W, A)
//       val result = runTest(dut, W, A)

//       println("W × Identity:")
//       for (r <- 0 until n)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until n; c <- 0 until n)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "compute a general matrix multiply" in {
//     simulate(new ControllerTestHarness(n)) { dut =>
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
//       val expected = expectedMatMul(W, A)
//       val result = runTest(dut, W, A)

//       println("W × A:")
//       for (r <- 0 until n)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until n; c <- 0 until n)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "handle negative values" in {
//     simulate(new ControllerTestHarness(n)) { dut =>
//       val W = Array(
//         Array(-1, 2, -3, 4),
//         Array(5, -6, 7, -8),
//         Array(-1, 0, 1, 0),
//         Array(0, -1, 0, 1)
//       )
//       val A = Array(
//         Array(1, -2, 3, -4),
//         Array(-5, 6, -7, 8),
//         Array(9, -10, 11, -12),
//         Array(-13, 14, -15, 16)
//       )
//       val expected = expectedMatMul(W, A)
//       val result = runTest(dut, W, A)

//       println("Negative values:")
//       for (r <- 0 until n)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until n; c <- 0 until n)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "handle max 8-bit boundary values" in {
//     simulate(new ControllerTestHarness(n)) { dut =>
//       val W = Array(
//         Array(127, -128, 127, -128),
//         Array(-128, 127, -128, 127),
//         Array(127, 127, -128, -128),
//         Array(-128, -128, 127, 127)
//       )
//       val A = Array(
//         Array(127, -128, 1, -1),
//         Array(-128, 127, -1, 1),
//         Array(1, -1, 127, -128),
//         Array(-1, 1, -128, 127)
//       )
//       val expected = expectedMatMul(W, A)
//       val result = runTest(dut, W, A)

//       println("Boundary values:")
//       for (r <- 0 until n)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       for (r <- 0 until n; c <- 0 until n)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "pass random matrix multiplies" in {
//     val rng = new Random(42)
//     simulate(new ControllerTestHarness(n)) { dut =>
//       // Wait for init
//       while (dut.io.busy.peek().litToBoolean) {
//         dut.clock.step()
//       }

//       for (trial <- 0 until 10) {
//         val W = Array.fill(n, n)(rng.nextInt(256) - 128)
//         val A = Array.fill(n, n)(rng.nextInt(256) - 128)
//         val expected = expectedMatMul(W, A)

//         loadWeights(dut, W)
//         loadActivations(dut, A)
//         dut.clock.step(2)

//         dut.io.start.poke(true.B)
//         dut.clock.step()
//         dut.io.start.poke(false.B)

//         var cycles = 0
//         while (dut.io.busy.peek().litToBoolean && cycles < 10000) {
//           dut.clock.step()
//           cycles += 1
//         }

//         val result = readResults(dut)
//         val resultSigned = result.map(_.map(v => v))

//         println(s"Random trial $trial ($cycles cycles):")
//         var pass = true
//         for (r <- 0 until n; c <- 0 until n) {
//           if (resultSigned(r)(c) != expected(r)(c)) {
//             println(s"  FAIL at ($r,$c): got ${resultSigned(r)(c)}, expected ${expected(r)(c)}")
//             pass = false
//           }
//         }
//         if (pass) println(s"  PASSED")
//         for (r <- 0 until n; c <- 0 until n)
//           assert(resultSigned(r)(c) == expected(r)(c),
//             s"Trial $trial mismatch at ($r,$c): got ${resultSigned(r)(c)}, expected ${expected(r)(c)}")
//       }
//     }
//   }
// }