import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class AlchitryTopTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4
  val clockFreq = 100000000
  val baudRate = 1000000
  val cyclesPerBit = clockFreq / baudRate // ~868 cycles per bit

  // === UART bit-level helpers ===

  def sendUartByte(dut: AlchitryTop, b: Int): Unit = {
    // Start bit (low)
    dut.io.usb_rx.poke(false.B)
    dut.clock.step(cyclesPerBit)
    // 8 data bits, LSB first
    for (bit <- 0 until 8) {
      dut.io.usb_rx.poke((((b >> bit) & 1) != 0).B)
      dut.clock.step(cyclesPerBit)
    }
    // Stop bit (high)
    dut.io.usb_rx.poke(true.B)
    dut.clock.step(cyclesPerBit)
    // Inter-byte gap
    dut.clock.step(cyclesPerBit / 2)
  }

  def sendUartBytes(dut: AlchitryTop, bytes: Seq[Int]): Unit = {
    bytes.foreach(b => sendUartByte(dut, b & 0xFF))
  }

  def recvUartByte(dut: AlchitryTop, timeout: Int = cyclesPerBit * 20): Int = {
    // Wait for start bit (tx goes low)
    var waited = 0
    while (dut.io.usb_tx.peek().litToBoolean && waited < timeout) {
      dut.clock.step()
      waited += 1
    }
    if (waited >= timeout) {
      throw new RuntimeException(s"UART TX timeout after $waited cycles")
    }
    // Skip to middle of start bit
    dut.clock.step(cyclesPerBit / 2)
    // Read 8 data bits
    var value = 0
    for (bit <- 0 until 8) {
      dut.clock.step(cyclesPerBit)
      if (dut.io.usb_tx.peek().litToBoolean) {
        value |= (1 << bit)
      }
    }
    // Wait for stop bit
    dut.clock.step(cyclesPerBit)
    value
  }

  // === Protocol helpers ===

  def u16Bytes(v: Int): Seq[Int] = Seq((v >> 8) & 0xFF, v & 0xFF)
  def s8Byte(v: Int): Int = v & 0xFF
  def s32BytesLE(v: Int): Seq[Int] =
    Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)

  def sendConfig(dut: AlchitryTop, layers: Seq[Map[String, Int]]): Unit = {
    sendUartByte(dut, 0x01)
    sendUartByte(dut, layers.length)
    for (layer <- layers) {
      sendUartBytes(dut,
        u16Bytes(layer("weight_base")) ++
        u16Bytes(layer("bias_base")) ++
        u16Bytes(layer("M")) ++
        u16Bytes(layer("K")) ++
        u16Bytes(layer("N")) ++
        Seq(layer("activation") & 0x03) ++
        Seq(layer("shift") & 0x1F) ++
        u16Bytes(layer("relu6_thresh")) ++
        Seq(if (layer("clamp_en") != 0) 1 else 0))
    }
  }

  def sendWeights(dut: AlchitryTop, W: Array[Array[Int]], m: Int, k: Int, baseAddr: Int): Unit = {
    val tilesK = k / n
    val count = (m / n) * tilesK * n
    sendUartByte(dut, 0x02)
    sendUartBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tR <- 0 until m / n; tK <- 0 until tilesK; t <- 0 until n; r <- 0 until n)
      sendUartByte(dut, s8Byte(W(tR * n + r)(tK * n + t)))
  }

  def sendBias(dut: AlchitryTop, bias: Array[Int], nn: Int, baseAddr: Int): Unit = {
    val count = nn / n
    sendUartByte(dut, 0x03)
    sendUartBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tC <- 0 until count; c <- 0 until n)
      sendUartBytes(dut, s32BytesLE(bias(tC * n + c)))
  }

  def sendActivations(dut: AlchitryTop, A: Array[Array[Int]], k: Int, nn: Int, baseAddr: Int): Unit = {
    val tilesN = nn / n
    val count = (k / n) * tilesN * n
    sendUartByte(dut, 0x04)
    sendUartBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tK <- 0 until k / n; tC <- 0 until tilesN; t <- 0 until n; c <- 0 until n)
      sendUartByte(dut, s8Byte(A(tK * n + t)(tC * n + c)))
  }

  def sendRun(dut: AlchitryTop, inputBase: Int, bufBBase: Int): Unit = {
    sendUartByte(dut, 0x05)
    sendUartBytes(dut, u16Bytes(inputBase) ++ u16Bytes(bufBBase))
  }

  def waitForDoneAck(dut: AlchitryTop): Unit = {
    val ack = recvUartByte(dut, cyclesPerBit * 100000) // long timeout for compute
assert(ack == 0xAA, f"Expected 0xAA done ack, got 0x$ack%02X")  }

  def readOutput(dut: AlchitryTop, m: Int, nn: Int, baseAddr: Int = 0): Array[Array[Long]] = {
    val tilesN = nn / n
    val count = (m / n) * tilesN * n
    sendUartByte(dut, 0x06)
    sendUartBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))

    val result = Array.ofDim[Long](m, nn)
    for (tR <- 0 until m / n; tC <- 0 until tilesN; r <- 0 until n) {
      val rowBytes = Array.ofDim[Int](n * 4)
      for (b <- 0 until n * 4) {
        rowBytes(b) = recvUartByte(dut)
      }
      for (c <- 0 until n) {
        val b0 = rowBytes(c * 4) & 0xFF
        val b1 = rowBytes(c * 4 + 1) & 0xFF
        val b2 = rowBytes(c * 4 + 2) & 0xFF
        val b3 = rowBytes(c * 4 + 3) & 0xFF
        val raw = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
        result(tR * n + r)(tC * n + c) = raw.toLong
      }
    }
    // Read done marker
    val marker = recvUartByte(dut)
    assert(marker == 0xFF, f"Expected 0xFF marker, got 0x$marker%02X")
    result
  }

  // === Software reference ===

  def matMul(W: Array[Array[Int]], A: Array[Array[Int]], m: Int, k: Int, nn: Int): Array[Array[Long]] = {
    val result = Array.ofDim[Long](m, nn)
    for (r <- 0 until m; c <- 0 until nn)
      result(r)(c) = (0 until k).map(i => W(r)(i).toLong * A(i)(c).toLong).sum
    result
  }

  def applyBiasActClamp(raw: Array[Array[Long]], bias: Array[Int],
                         m: Int, nn: Int, func: Int, shift: Int,
                         relu6Thresh: Int, clampEn: Boolean): Array[Array[Long]] = {
    Array.tabulate(m, nn) { (r, c) =>
      val biased = raw(r)(c) + bias(c).toLong
      val shifted = biased >> shift
      val activated = func match {
        case 0 => shifted
        case 1 => if (shifted < 0) 0L else shifted
        case 2 => if (shifted < 0) shifted >> 3 else shifted
        case 3 =>
          if (shifted < 0) 0L
          else if (shifted > relu6Thresh) relu6Thresh.toLong
          else shifted
      }
      if (clampEn) {
        if (activated > 127) 127L else if (activated < -128) -128L else activated
      } else activated
    }
  }

  def assertResult(result: Array[Array[Long]], expected: Array[Array[Long]],
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

  "AlchitryTop" should "complete a 4x4 identity matmul over UART" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      // Hold UART idle (high)
      dut.io.usb_rx.poke(true.B)
      // Wait for system init
      dut.clock.step(cyclesPerBit * 20)

      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val bias = Array.fill(nn)(0)

      println("Sending config...")
      sendConfig(dut, Seq(Map(
        "weight_base" -> 0, "bias_base" -> 0,
        "M" -> m, "K" -> k, "N" -> nn,
        "activation" -> 0, "shift" -> 0,
        "relu6_thresh" -> 6, "clamp_en" -> 0)))

      println("Sending weights...")
      sendWeights(dut, W, m, k, 0)

      println("Sending bias...")
      sendBias(dut, bias, nn, 0)

      println("Sending activations...")
      sendActivations(dut, A, k, nn, 0)

      println("Sending run...")
      sendRun(dut, 0, 2048)

      println("Waiting for completion...")
      waitForDoneAck(dut)

      println("Reading output...")
      val result = readOutput(dut, m, nn)

      val expected = matMul(W, A, m, k, nn)
      println("Result:")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertResult(result, expected, m, nn, "identity 4x4 UART")
    }
  }

  it should "complete a 4x4 matmul with bias and relu over UART" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      dut.io.usb_rx.poke(true.B)
      dut.clock.step(cyclesPerBit * 20)

      val rng = new Random(42)
      val m = 4; val k = 4; val nn = 4
      val W = Array.fill(m, k)(rng.nextInt(20) - 10)
      val A = Array.fill(k, nn)(rng.nextInt(20) - 10)
      val bias = Array.fill(nn)(rng.nextInt(100) - 50)

      sendConfig(dut, Seq(Map(
        "weight_base" -> 0, "bias_base" -> 0,
        "M" -> m, "K" -> k, "N" -> nn,
        "activation" -> 1, "shift" -> 4,
        "relu6_thresh" -> 6, "clamp_en" -> 0)))
      sendWeights(dut, W, m, k, 0)
      sendBias(dut, bias, nn, 0)
      sendActivations(dut, A, k, nn, 0)
      sendRun(dut, 0, 2048)
      waitForDoneAck(dut)

      val result = readOutput(dut, m, nn)
      val raw = matMul(W, A, m, k, nn)
      val expected = applyBiasActClamp(raw, bias, m, nn, 1, 4, 6, false)

      println("Bias+ReLU result:")
      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertResult(result, expected, m, nn, "bias+relu 4x4 UART")
    }
  }

  it should "complete a two-layer inference over UART" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      dut.io.usb_rx.poke(true.B)
      dut.clock.step(cyclesPerBit * 20)

      val rng = new Random(100)
      val m0 = 4; val k0 = 4; val n0 = 4
      val m1 = 4; val k1 = 4; val n1 = 4
      val W0 = Array.fill(m0, k0)(rng.nextInt(10) - 5)
      val W1 = Array.fill(m1, k1)(rng.nextInt(10) - 5)
      val A0 = Array.fill(k0, n0)(rng.nextInt(10) - 5)
      val bias0 = Array.fill(n0)(rng.nextInt(50) - 25)
      val bias1 = Array.fill(n1)(0)

      val w0Base = 0
      val w1Base = (m0 / n) * (k0 / n) * n
      val b0Base = 0
      val b1Base = n0 / n

      val configs = Seq(
        Map("weight_base" -> w0Base, "bias_base" -> b0Base,
          "M" -> m0, "K" -> k0, "N" -> n0,
          "activation" -> 1, "shift" -> 3, "relu6_thresh" -> 6, "clamp_en" -> 1),
        Map("weight_base" -> w1Base, "bias_base" -> b1Base,
          "M" -> m1, "K" -> k1, "N" -> n1,
          "activation" -> 0, "shift" -> 0, "relu6_thresh" -> 6, "clamp_en" -> 0))

      println("Sending two-layer config...")
      sendConfig(dut, configs)
      sendWeights(dut, W0, m0, k0, w0Base)
      sendWeights(dut, W1, m1, k1, w1Base)
      sendBias(dut, bias0, n0, b0Base)
      sendBias(dut, bias1, n1, b1Base)
      sendActivations(dut, A0, k0, n0, 0)

      println("Running two-layer inference...")
      sendRun(dut, 0, 2048)
      waitForDoneAck(dut)

      val result = readOutput(dut, m1, n1)

      // Software reference
      val raw0 = matMul(W0, A0, m0, k0, n0)
      val act0 = applyBiasActClamp(raw0, bias0, m0, n0, 1, 3, 6, true)
      val act0_int = act0.map(_.map(_.toInt))
      val raw1 = matMul(W1, act0_int, m1, k1, n1)
      val expected = applyBiasActClamp(raw1, bias1, m1, n1, 0, 0, 6, false)

      println("Two-layer result:")
      for (r <- 0 until m1)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      assertResult(result, expected, m1, n1, "two-layer UART")
    }
  }
}