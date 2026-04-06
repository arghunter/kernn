import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

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

  def expectedMatMul(W: Array[Array[Int]], A: Array[Array[Int]]): Array[Array[Int]] = {
    val result = Array.ofDim[Int](n, n)
    for (r <- 0 until n; c <- 0 until n)
      result(r)(c) = (0 until n).map(k => W(r)(k) * A(k)(c)).sum
    result
  }

  def checkResult(name: String, result: Array[Array[BigInt]], expected: Array[Array[Int]]): Unit = {
    println(s"$name:")
    for (r <- 0 until n)
      println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
    for (r <- 0 until n; c <- 0 until n)
      assert(result(r)(c) == expected(r)(c),
        s"$name mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
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
      checkResult("Identity × A", result, A)
    }
  }

  it should "multiply matrix × identity" in {
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
      checkResult("W × Identity", result, W)
    }
  }

  it should "compute a general matrix multiply" in {
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
      val result = runMatMul(dut, W, A)
      checkResult("W × A", result, expectedMatMul(W, A))
    }
  }

  it should "handle negative weights" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(-1, 2, -3, 4),
        Array(5, -6, 7, -8),
        Array(-1, 0, 1, 0),
        Array(0, -1, 0, 1)
      )
      val A = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16)
      )
      val result = runMatMul(dut, W, A)
      checkResult("Negative weights", result, expectedMatMul(W, A))
    }
  }

  it should "handle negative activations" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(1, 0, 1, 0),
        Array(0, 1, 0, 1)
      )
      val A = Array(
        Array(-1, 2, -3, 4),
        Array(5, -6, 7, -8),
        Array(-9, 10, -11, 12),
        Array(13, -14, 15, -16)
      )
      val result = runMatMul(dut, W, A)
      checkResult("Negative activations", result, expectedMatMul(W, A))
    }
  }

  it should "handle all negative values" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(-1, -2, -3, -4),
        Array(-5, -6, -7, -8),
        Array(-1, -1, -1, -1),
        Array(-2, -2, -2, -2)
      )
      val A = Array(
        Array(-1, -2, -3, -4),
        Array(-5, -6, -7, -8),
        Array(-9, -10, -11, -12),
        Array(-13, -14, -15, -16)
      )
      val result = runMatMul(dut, W, A)
      checkResult("All negative", result, expectedMatMul(W, A))
    }
  }

  it should "handle zeros matrix" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array.fill(n, n)(0)
      val A = Array(
        Array(1, 2, 3, 4),
        Array(5, 6, 7, 8),
        Array(9, 10, 11, 12),
        Array(13, 14, 15, 16)
      )
      val result = runMatMul(dut, W, A)
      checkResult("Zero × A", result, expectedMatMul(W, A))
    }
  }

  it should "handle max 8-bit weight values" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(127, -128, 127, -128),
        Array(-128, 127, -128, 127),
        Array(127, 127, 127, 127),
        Array(-128, -128, -128, -128)
      )
      val A = Array(
        Array(1, 0, 0, 0),
        Array(0, 1, 0, 0),
        Array(0, 0, 1, 0),
        Array(0, 0, 0, 1)
      )
      val result = runMatMul(dut, W, A)
      checkResult("Max weight × Identity", result, W.map(_.map(_.toInt)))
    }
  }

  it should "handle max 8-bit activation values" in {
    simulate(new SysArray(n)) { dut =>
      val W = Array(
        Array(1, 0, 0, 0),
        Array(0, 1, 0, 0),
        Array(0, 0, 1, 0),
        Array(0, 0, 0, 1)
      )
      val A = Array(
        Array(127, -128, 1, -1),
        Array(-128, 127, -1, 1),
        Array(127, 127, 127, 127),
        Array(-128, -128, -128, -128)
      )
      val result = runMatMul(dut, W, A)
      checkResult("Identity × max activations", result, A.map(_.map(_.toInt)))
    }
  }

  it should "pass random matrix multiplies" in {
    val rng = new Random(42)
    simulate(new SysArray(n)) { dut =>
      for (trial <- 0 until 20) {
        // Random 8-bit signed values: -128 to 127
        val W = Array.fill(n, n)(rng.nextInt(256) - 128)
        val A = Array.fill(n, n)(rng.nextInt(256) - 128)
        val result = runMatMul(dut, W, A)
        val expected = expectedMatMul(W, A)
        // Sign-extend 32-bit results for comparison
        val resultSigned = result.map(_.map(v =>
          if (v > Int.MaxValue) (v - 4294967296L).toInt else v.toInt
        ))
        println(s"Random trial $trial:")
        for (r <- 0 until n)
          println(s"  Row $r: ${resultSigned(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
        for (r <- 0 until n; c <- 0 until n)
          assert(resultSigned(r)(c) == expected(r)(c),
            s"Trial $trial mismatch at ($r,$c): got ${resultSigned(r)(c)}, expected ${expected(r)(c)}")
      }
    }
  }
}