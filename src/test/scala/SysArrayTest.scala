import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec

class SysArrayTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

 def runMatMul(dut: SysArray, W: Array[Array[Int]], A: Array[Array[Int]]): Array[Array[BigInt]] = {
    // Initial reset
    dut.io.resetIn.poke(true.B)
    dut.io.actIn.foreach(_.poke(0.S))
    dut.io.weightIn.foreach(_.poke(0.S))
    dut.clock.step(n + 1)
    dut.io.resetIn.poke(false.B)
    dut.clock.step(2 * n)

    // Feed matrix data for n cycles
    for (t <- 0 until n) {
      for (r <- 0 until n)
        dut.io.weightIn(r).poke(W(r)(t).S)
      for (c <- 0 until n)
        dut.io.actIn(c).poke(A(t)(c).S)
      dut.clock.step()
    }

    // Flush
    dut.io.weightIn.foreach(_.poke(0.S))
    dut.io.actIn.foreach(_.poke(0.S))
    dut.clock.step(4 * n)

    // Drain
    dut.io.resetIn.poke(true.B)
    val results = Array.ofDim[BigInt](n, n)
    for (i <- 0 until n) {
      dut.clock.step()
      for (c <- 0 until n) {
        results(n - 1 - i)(c) = dut.io.actOut(c).peek().litValue
      }
    }
    dut.io.resetIn.poke(false.B)
    dut.clock.step(2 * n)
    results
  }

  "SysArray" should "multiply identity × matrix" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(1, 0, 0, 0),
        Array(0, 1, 0, 0),
        Array(0, 0, 1, 0),
        Array(0, 0, 0, 1)
      )
      val A = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16)
      )
      val result = runMatMul(dut, W, A)
      println("Identity × A:")
      for (r <- 0 until n)
        println(s"  Row $r: ${result(r).mkString(", ")}")

      for (r <- 0 until n; c <- 0 until n)
        assert(result(r)(c) == A(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${A(r)(c)}")
    }
  }

  "SysArray" should "multiply two non-trivial matrices" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(1, 2, 0, 0),
        Array(0, 1, 2, 0),
        Array(0, 0, 1, 2),
        Array(2, 0, 0, 1)
      )
      val A = Array(
        Array(1, 0, 0, 0),
        Array(0, 1, 0, 0),
        Array(0, 0, 1, 0),
        Array(0, 0, 0, 1)
      )
      val result = runMatMul(dut, W, A)
      println("W × Identity:")
      for (r <- 0 until n)
        println(s"  Row $r: ${result(r).mkString(", ")}")

      for (r <- 0 until n; c <- 0 until n)
        assert(result(r)(c) == W(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${W(r)(c)}")
    }
  }

  "SysArray" should "compute a general matrix multiply" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(1, 0, 1, 0),
        Array(0, 1, 0, 1)
      )
      val A = Array(
        Array(1, 0, 0, 1),
        Array(0, 1, 0, 1),
        Array(0, 0, 1, 1),
        Array(1, 0, 0, 1)
      )
      val expected = Array.ofDim[Int](n, n)
      for (r <- 0 until n; c <- 0 until n)
        expected(r)(c) = (0 until n).map(k => W(r)(k) * A(k)(c)).sum

      val result = runMatMul(dut, W, A)
      println("W × A:")
      for (r <- 0 until n)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")

      for (r <- 0 until n; c <- 0 until n)
        assert(result(r)(c) == expected(r)(c),
          s"Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }
}