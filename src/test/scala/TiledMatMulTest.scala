// import chisel3._
// import chisel3.simulator._
// import chisel3.simulator.scalatest.{HasCliOptions, Cli}
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// class TiledMatMulTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
//   val n = 4

//   def runMatMul(dut: SysArray, W: Array[Array[Int]], A: Array[Array[Int]]): Array[Array[BigInt]] = {
//     // Initial reset
//     dut.io.resetIn.poke(true.B)
//     dut.io.actIn.foreach(_.poke(0.S))
//     dut.io.weightIn.foreach(_.poke(0.S))
//     dut.clock.step(n + 1)
//     dut.io.resetIn.poke(false.B)
//     dut.clock.step(2 * n)

//     // Feed matrix data for n cycles
//     for (t <- 0 until n) {
//       for (r <- 0 until n)
//         dut.io.weightIn(r).poke(W(r)(t).S)
//       for (c <- 0 until n)
//         dut.io.actIn(c).poke(A(t)(c).S)
//       dut.clock.step()
//     }

//     // Flush
//     dut.io.weightIn.foreach(_.poke(0.S))
//     dut.io.actIn.foreach(_.poke(0.S))
//     dut.clock.step(2 * n)

//     // Drain
//     dut.io.resetIn.poke(true.B)
//     val results = Array.ofDim[BigInt](n, n)
//     for (i <- 0 until n) {
//       dut.clock.step()
//       for (c <- 0 until n) {
//         results(n - 1 - i)(c) = dut.io.actOut(c).peek().litValue
//       }
//     }
//     dut.io.resetIn.poke(false.B)
//     results
//   }

//   def signExtend(v: BigInt): Long = {
//     if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong
//   }

//   def tiledMatMul(dut: SysArray, W: Array[Array[Int]], A: Array[Array[Int]], M: Int): Array[Array[Long]] = {
//     val tiles = M / n
//     val C = Array.ofDim[Long](M, M)

//     for (tileR <- 0 until tiles) {
//       for (tileC <- 0 until tiles) {
//         // Accumulate over inner dimension tiles
//         for (tileK <- 0 until tiles) {
//           // Extract n×n sub-matrices
//           val wTile = Array.tabulate(n, n)((r, c) =>
//             W(tileR * n + r)(tileK * n + c))
//           val aTile = Array.tabulate(n, n)((r, c) =>
//             A(tileK * n + r)(tileC * n + c))

//           val result = runMatMul(dut, wTile, aTile)

//           for (r <- 0 until n; c <- 0 until n)
//             C(tileR * n + r)(tileC * n + c) += signExtend(result(r)(c))
//         }
//       }
//     }
//     C
//   }

//   def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]], M: Int): Array[Array[Long]] = {
//     val result = Array.ofDim[Long](M, M)
//     for (r <- 0 until M; c <- 0 until M)
//       result(r)(c) = (0 until M).map(k => W(r)(k).toLong * A(k)(c).toLong).sum
//     result
//   }

//   "SysArray" should "tile an 8x8 identity multiply" in {
//     val M = 8
//     simulate(new SysArray(n)) { dut =>
//       val W = Array.tabulate(M, M)((r, c) => if (r == c) 1 else 0)
//       val A = Array.tabulate(M, M)((r, c) => r * M + c + 1)

//       val result = tiledMatMul(dut, W, A, M)
//       val expected = expectedMatMul(W, A, M)

//       println("8×8 Identity × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")}")

//       for (r <- 0 until M; c <- 0 until M)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile an 8x8 general multiply" in {
//     val M = 8
//     simulate(new SysArray(n)) { dut =>
//       val W = Array.tabulate(M, M)((r, c) => (r - c) % 5)
//       val A = Array.tabulate(M, M)((r, c) => (r + c) % 7 - 3)

//       val result = tiledMatMul(dut, W, A, M)
//       val expected = expectedMatMul(W, A, M)

//       println("8×8 General W × A:")
//       for (r <- 0 until M) {
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
//       }

//       for (r <- 0 until M; c <- 0 until M)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile a 12x12 random multiply" in {
//     val M = 12
//     val rng = new Random(123)
//     simulate(new SysArray(n)) { dut =>
//       val W = Array.fill(M, M)(rng.nextInt(256) - 128)
//       val A = Array.fill(M, M)(rng.nextInt(256) - 128)

//       val result = tiledMatMul(dut, W, A, M)
//       val expected = expectedMatMul(W, A, M)

//       println("12×12 Random W × A:")
//       for (r <- 0 until M)
//         println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")

//       for (r <- 0 until M; c <- 0 until M)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "tile a 16x16 random multiply" in {
//     val M = 16
//     val rng = new Random(456)
//     simulate(new SysArray(n)) { dut =>
//       val W = Array.fill(M, M)(rng.nextInt(256) - 128)
//       val A = Array.fill(M, M)(rng.nextInt(256) - 128)

//       val result = tiledMatMul(dut, W, A, M)
//       val expected = expectedMatMul(W, A, M)

//       println(s"${M}×${M} Random W × A (first 4 rows):")
//       for (r <- 0 until 4)
//         println(s"  Row $r: ${result(r).take(8).mkString(", ")}... (expected: ${expected(r).take(8).mkString(", ")}...)")

//       for (r <- 0 until M; c <- 0 until M)
//         assert(result(r)(c) == expected(r)(c),
//           s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//     }
//   }

//   it should "run multiple tiled multiplies back to back" in {
//     val M = 8
//     val rng = new Random(789)
//     simulate(new SysArray(n)) { dut =>
//       for (trial <- 0 until 5) {
//         val W = Array.fill(M, M)(rng.nextInt(256) - 128)
//         val A = Array.fill(M, M)(rng.nextInt(256) - 128)

//         val result = tiledMatMul(dut, W, A, M)
//         val expected = expectedMatMul(W, A, M)

//         println(s"Back-to-back trial $trial:")
//         for (r <- 0 until M; c <- 0 until M)
//           assert(result(r)(c) == expected(r)(c),
//             s"Trial $trial mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
//         println(s"  PASSED")
//       }
//     }
//   }
// }