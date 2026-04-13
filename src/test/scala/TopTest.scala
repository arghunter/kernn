import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class AlchitryTopTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  
  // Accelerated parameters for faster simulation
  val n = 4
  val clockFreq = 100000000
  val baudRate = 1000000 
  val cyclesPerBit = clockFreq / baudRate // 100 cycles per bit

  // ==========================================
  // UART BIT-BANGING DRIVERS
  // ==========================================

  def sendUartByte(dut: AlchitryTop, b: Int): Unit = {
    // Start bit (low)
    dut.io.usb_rx.poke(false.B)
    dut.clock.step(cyclesPerBit)
    
    // 8 data bits (LSB first)
    for (bit <- 0 until 8) {
      dut.io.usb_rx.poke((((b >> bit) & 1) != 0).B)
      dut.clock.step(cyclesPerBit)
    }
    
    // Stop bit (high)
    dut.io.usb_rx.poke(true.B)
    dut.clock.step(cyclesPerBit)
    
    // Tiny inter-byte gap to let state machines breathe
    dut.clock.step(10)
  }

  def sendUartBytes(dut: AlchitryTop, bytes: Seq[Int]): Unit = {
    bytes.foreach(b => sendUartByte(dut, b & 0xFF))
  }

  def recvUartByte(dut: AlchitryTop, timeoutCycles: Int = cyclesPerBit * 50000): Int = {
    var waited = 0
    // Wait for RX line to drop (Start Bit)
    while (dut.io.usb_tx.peek().litToBoolean && waited < timeoutCycles) {
      dut.clock.step(1)
      waited += 1
    }
    
    if (waited >= timeoutCycles) {
      throw new RuntimeException(s"UART TX timeout! The FPGA stopped responding after $waited cycles. Sequencer likely deadlocked.")
    }

    // Fast-forward to the center of the first data bit (1.5 bit periods)
    dut.clock.step(cyclesPerBit + (cyclesPerBit / 2))
    
    var value = 0
    for (bit <- 0 until 8) {
      if (dut.io.usb_tx.peek().litToBoolean) {
        value |= (1 << bit)
      }
      if (bit < 7) dut.clock.step(cyclesPerBit)
    }
    
    // Wait through the stop bit
    dut.clock.step(cyclesPerBit)
    value
  }

  // ==========================================
  // PROTOCOL PACKERS (Matches fixed Python script)
  // ==========================================

  // FIXED: Little-endian 16-bit packing
  def u16(v: Int): Seq[Int] = Seq((v >> 8) & 0xFF, v & 0xFF)
  def s8(v: Int): Int = v & 0xFF
  def s32LE(v: Int): Seq[Int] = Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)

  def sendConfig(dut: AlchitryTop, layers: Seq[Map[String, Int]]): Unit = {
    sendUartByte(dut, 0x01)
    sendUartByte(dut, layers.length)
    for (layer <- layers) {
      sendUartBytes(dut,
        u16(layer("weight_base")) ++
        u16(layer("bias_base")) ++
        u16(layer("M")) ++
        u16(layer("K")) ++
        u16(layer("N")) ++
        Seq(layer("activation") & 0x03) ++
        Seq(layer("shift") & 0x1F) ++
        u16(layer("relu6_thresh")) ++
        Seq(if (layer("clamp_en") != 0) 1 else 0))
    }
  }

  def sendWeights(dut: AlchitryTop, W: Array[Array[Int]], m: Int, k: Int, baseAddr: Int): Unit = {
    val tilesK = k / n
    val count = (m / n) * tilesK * n
    sendUartBytes(dut, Seq(0x02) ++ u16(baseAddr) ++ u16(count))
    for (tR <- 0 until m / n; tK <- 0 until tilesK; t <- 0 until n; r <- 0 until n)
      sendUartByte(dut, s8(W(tR * n + r)(tK * n + t)))
  }

  def sendBias(dut: AlchitryTop, bias: Array[Int], nn: Int, baseAddr: Int): Unit = {
    val count = nn / n
    sendUartBytes(dut, Seq(0x03) ++ u16(baseAddr) ++ u16(count))
    for (tC <- 0 until count; c <- 0 until n)
      sendUartBytes(dut, s32LE(bias(tC * n + c)))
  }

  def sendActivations(dut: AlchitryTop, A: Array[Array[Int]], k: Int, nn: Int, baseAddr: Int): Unit = {
    val tilesN = nn / n
    val count = (k / n) * tilesN * n
    sendUartBytes(dut, Seq(0x04) ++ u16(baseAddr) ++ u16(count))
    for (tK <- 0 until k / n; tC <- 0 until tilesN; t <- 0 until n; c <- 0 until n)
      sendUartByte(dut, s8(A(tK * n + t)(tC * n + c)))
  }

  def sendRun(dut: AlchitryTop, inputBase: Int, bufBBase: Int): Unit = {
    sendUartBytes(dut, Seq(0x05) ++ u16(inputBase) ++ u16(bufBBase))
  }

  def readOutput(dut: AlchitryTop, m: Int, nn: Int, baseAddr: Int = 0): Array[Array[Long]] = {
    val tilesN = nn / n
    val count = (m / n) * tilesN * n
    sendUartBytes(dut, Seq(0x06) ++ u16(baseAddr) ++ u16(count))

    val result = Array.ofDim[Long](m, nn)
    for (tR <- 0 until m / n; tC <- 0 until tilesN; r <- 0 until n) {
      val rowBytes = Array.ofDim[Int](n * 4)
      for (b <- 0 until n * 4) rowBytes(b) = recvUartByte(dut)
      
      for (c <- 0 until n) {
        val raw = (rowBytes(c * 4)) |
                  (rowBytes(c * 4 + 1) << 8) |
                  (rowBytes(c * 4 + 2) << 16) |
                  (rowBytes(c * 4 + 3) << 24)
        result(tR * n + r)(tC * n + c) = raw.toLong
      }
    }
    val marker = recvUartByte(dut)
    assert(marker == 0xFF, f"Expected 0xFF Read Marker, got 0x$marker%02X")
    result
  }

  // ==========================================
  // SOFTWARE REFERENCE MODEL
  // ==========================================

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
      val shifted = (raw(r)(c) + bias(c).toLong) >> shift
      val activated = func match {
        case 0 => shifted
        case 1 => math.max(0L, shifted)
        case 2 => if (shifted < 0) shifted >> 3 else shifted
        case 3 => math.min(math.max(0L, shifted), relu6Thresh.toLong)
      }
      if (clampEn) math.max(-128L, math.min(127L, activated)) else activated
    }
  }

  // ==========================================
  // TESTS
  // ==========================================

  "AlchitryTop" should "pass a basic UART Health Check (Read 1 item)" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      dut.io.usb_rx.poke(true.B)
      dut.clock.step(cyclesPerBit * 10) 

      println("[TB] Sending Health Check (Read 1 item)...")
      // Address 0, Count 1 (0x0001)
      sendUartBytes(dut, Seq(0x06, 0x00, 0x00, 0x00, 0x01))
      
      // Since count=1, the FPGA will send 4 bytes of memory data first
      val b0 = recvUartByte(dut, cyclesPerBit * 5000)
      val b1 = recvUartByte(dut, cyclesPerBit * 5000)
      val b2 = recvUartByte(dut, cyclesPerBit * 5000)
      val b3 = recvUartByte(dut, cyclesPerBit * 5000)
            val b4 = recvUartByte(dut, cyclesPerBit * 5000)
                  val b5 = recvUartByte(dut, cyclesPerBit * 5000)
      
      // THEN it sends the 0xFF marker
      val marker = recvUartByte(dut, cyclesPerBit * 5000)
      assert(marker == 0xFF, f"Parser dead! Expected 0xFF, got 0x$marker%02X")
      println(f"[TB] Health Check PASSED. Memory data: $b0%02X $b1%02X $b2%02X $b3%02X")
    }
  }
  "AlchitryTop" should "pass a basic UART Health Check (Read 0 bytes)" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      dut.io.usb_rx.poke(true.B)
      dut.clock.step(cyclesPerBit * 10) // Wait for init

      println("[TB] Sending Health Check (Read 0 items)...")
      // Send 0x06 (Read), Addr 0, Count 0
      sendUartBytes(dut, Seq(0x06, 0x00, 0x00, 0x00, 0x00))
      
      val marker7 = recvUartByte(dut, cyclesPerBit * 5000)
      val marker2 = recvUartByte(dut, cyclesPerBit * 5000)
      val marker3 = recvUartByte(dut, cyclesPerBit * 5000)
      val marker4 = recvUartByte(dut, cyclesPerBit * 5000)
            val marker5 = recvUartByte(dut, cyclesPerBit * 5000)

      val marker6 = recvUartByte(dut, cyclesPerBit * 5000)

      val marker = recvUartByte(dut, cyclesPerBit * 5000)

      assert(marker == 0xFF, f"Parser dead! Expected 0xFF, got 0x$marker%02X")
      println("[TB] Health Check PASSED. Parser and UART are alive.")
    }
  }

  it should "complete a 4x4 identity matmul" in {
    simulate(new AlchitryTop(n, clockFreq, baudRate)) { dut =>
      dut.io.usb_rx.poke(true.B)
      dut.clock.step(cyclesPerBit * 10)

      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val bias = Array.fill(nn)(0)

      println("[TB] Loading Config...")
      sendConfig(dut, Seq(Map(
        "weight_base" -> 0, "bias_base" -> 0, "M" -> m, "K" -> k, "N" -> nn,
        "activation" -> 0, "shift" -> 0, "relu6_thresh" -> 6, "clamp_en" -> 0)))

      println("[TB] Loading Memory...")
      sendWeights(dut, W, m, k, 0)
      sendBias(dut, bias, nn, 0)
      sendActivations(dut, A, k, nn, 0)

      println("[TB] Sending RUN command...")
      sendRun(dut, 0, 2048)

      println("[TB] Waiting for Sequencer 0xAA ACK...")
      val ack = recvUartByte(dut, cyclesPerBit * 200000) // generous timeout for compute
      assert(ack == 0xAA, f"Expected 0xAA done ack, got 0x$ack%02X")
      
      println("[TB] Reading Output...")
      val result = readOutput(dut, m, nn)
      println("result:")
      println(result.map(_.mkString(" ")).mkString("\n"))
      println("expected:")

      val expected = matMul(W, A, m, k, nn)
            println(expected.map(_.mkString(" ")).mkString("\n"))

      var failed = false
      for (r <- 0 until m; c <- 0 until nn) {
        if (result(r)(c) != expected(r)(c)) failed = true
      }
      assert(!failed, "Hardware matmul did not match software reference!")
      println("[TB] Identity 4x4 PASSED.")
    }
  }
}