import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class CommandParserStressTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {
  val n = 4

  class FullSystemHarness(val n: Int) extends Module {
    val io = IO(new Bundle {
      val rx_data = Input(UInt(8.W))
      val rx_valid = Input(Bool())
      val tx_data = Output(UInt(8.W))
      val tx_valid = Output(Bool())
      val tx_ready = Input(Bool())
      val seq_done = Output(Bool())
      val busy = Output(Bool())
      val seqState = Output(SeqState())
      val parserState = Output(CmdState())
      val tiledState = Output(TiledState())
    })

    val parser = Module(new CommandParser(n))
    val seq = Module(new LayerSequencer(n, 16, 16))

    val weightMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val actMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
    val outMem = SyncReadMem(4096, Vec(n, SInt(32.W)))
    val biasMem = SyncReadMem(256, Vec(n, SInt(32.W)))

    io.seqState := seq.io.stateOut
    io.parserState := parser.io.stateOut
    io.tiledState := seq.io.tiledState

    parser.io.rx_data := io.rx_data
    parser.io.rx_valid := io.rx_valid
    io.tx_data := parser.io.tx_data
    io.tx_valid := parser.io.tx_valid
    parser.io.tx_ready := io.tx_ready

    seq.io.start := parser.io.seq_start
    parser.io.seq_busy := seq.io.busy
    parser.io.seq_done := seq.io.done
    seq.io.num_layers := parser.io.seq_num_layers
    seq.io.input_base := parser.io.seq_input_base
    seq.io.buffer_b_base := parser.io.seq_buffer_b_base
    parser.io.seq_result_base := seq.io.result_base
    parser.io.seq_result_M := seq.io.result_M
    parser.io.seq_result_N := seq.io.result_N

    seq.io.config_wr_en := parser.io.config_wr_en
    seq.io.config_wr_idx := parser.io.config_wr_idx
    seq.io.config_wr_data := parser.io.config_wr_data

    when(parser.io.wt_wr_en) { weightMem.write(parser.io.wt_wr_addr, parser.io.wt_wr_data) }
    seq.io.weight_data := weightMem.read(seq.io.weight_addr)

    when(seq.io.act_wr_en) {
      actMem.write(seq.io.act_wr_addr, seq.io.act_wr_data)
    }.elsewhen(parser.io.act_wr_en) {
      actMem.write(parser.io.act_wr_addr, parser.io.act_wr_data)
    }
    seq.io.act_rd_data := actMem.read(seq.io.act_rd_addr)

    when(parser.io.bias_wr_en) { biasMem.write(parser.io.bias_wr_addr, parser.io.bias_wr_data) }
    seq.io.bias_data := biasMem.read(seq.io.bias_addr)

    when(seq.io.output_wen) {
      outMem.write(seq.io.output_wr_addr, seq.io.output_data_wr)
    }.elsewhen(parser.io.out_wr_en) {
      outMem.write(parser.io.out_wr_addr, parser.io.out_wr_data)
    }
    seq.io.output_data_r := outMem.read(seq.io.output_rd_addr)
    parser.io.out_rd_data := outMem.read(parser.io.out_rd_addr)

    io.seq_done := seq.io.done
    io.busy := parser.io.busy || seq.io.busy
  }

  def sendByte(dut: FullSystemHarness, b: Int): Unit = {
    dut.io.rx_data.poke((b & 0xFF).U)
    dut.io.rx_valid.poke(true.B)
    dut.clock.step()
    dut.io.rx_valid.poke(false.B)
    dut.clock.step()
  }

  def sendBytes(dut: FullSystemHarness, bytes: Seq[Int]): Unit =
    bytes.foreach(b => sendByte(dut, b))

  def u16Bytes(v: Int): Seq[Int] = Seq((v >> 8) & 0xFF, v & 0xFF)
  def s8Byte(v: Int): Int = v & 0xFF
  def s32BytesLE(v: Int): Seq[Int] =
    Seq(v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)

  def sendConfig(dut: FullSystemHarness, layers: Seq[Map[String, Int]]): Unit = {
    sendByte(dut, 0x01)
    dut.clock.step()
    sendByte(dut, layers.length)
    for (layer <- layers) {
      sendBytes(dut,
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
    dut.clock.step(5)
  }

  def sendWeights(dut: FullSystemHarness, W: Array[Array[Int]], m: Int, k: Int, baseAddr: Int): Unit = {
    val tilesK = k / n
    val count = (m / n) * tilesK * n
    sendByte(dut, 0x02)
    dut.clock.step()
    sendBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tR <- 0 until m / n; tK <- 0 until tilesK; t <- 0 until n; r <- 0 until n)
      sendByte(dut, s8Byte(W(tR * n + r)(tK * n + t)))
    dut.clock.step(5)
  }

  def sendBias(dut: FullSystemHarness, bias: Array[Int], nn: Int, baseAddr: Int): Unit = {
    val count = nn / n
    sendByte(dut, 0x03)
    dut.clock.step()
    sendBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tC <- 0 until count; c <- 0 until n)
      sendBytes(dut, s32BytesLE(bias(tC * n + c)))
    dut.clock.step(5)
  }

  def sendActivations(dut: FullSystemHarness, A: Array[Array[Int]], k: Int, nn: Int, baseAddr: Int): Unit = {
    val tilesN = nn / n
    val count = (k / n) * tilesN * n
    sendByte(dut, 0x04)
    dut.clock.step()
    sendBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))
    for (tK <- 0 until k / n; tC <- 0 until tilesN; t <- 0 until n; c <- 0 until n)
      sendByte(dut, s8Byte(A(tK * n + t)(tC * n + c)))
    dut.clock.step(5)
  }

  def sendRun(dut: FullSystemHarness, inputBase: Int, bufBBase: Int): Unit = {
    sendByte(dut, 0x05)
    dut.clock.step()
    sendBytes(dut, u16Bytes(inputBase) ++ u16Bytes(bufBBase))
    dut.clock.step(5)
  }

  def waitDone(dut: FullSystemHarness, maxCycles: Int = 5000000): Int = {
    var cycles = 0
    while (!dut.io.seq_done.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < maxCycles, s"Did not finish in $maxCycles cycles")
    dut.clock.step(5)
    cycles
  }

  def readOutput(dut: FullSystemHarness, m: Int, nn: Int, baseAddr: Int = 0): Array[Array[Long]] = {
    val tilesN = nn / n
    val count = (m / n) * tilesN * n
    sendByte(dut, 0x06)
    dut.clock.step()
    sendBytes(dut, u16Bytes(baseAddr) ++ u16Bytes(count))

    val result = Array.ofDim[Long](m, nn)
    dut.io.tx_ready.poke(true.B)

    for (tR <- 0 until m / n; tC <- 0 until tilesN; r <- 0 until n) {
      val rowBytes = Array.ofDim[Int](n * 4)
      for (b <- 0 until n * 4) {
        var waited = 0
        while (!dut.io.tx_valid.peek().litToBoolean && waited < 10000) {
          dut.clock.step()
          waited += 1
        }
        assert(waited < 10000, s"TX timeout byte $b")
        rowBytes(b) = dut.io.tx_data.peek().litValue.toInt
        dut.clock.step()
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
    var waited = 0
    while (!dut.io.tx_valid.peek().litToBoolean && waited < 10000) {
      dut.clock.step()
      waited += 1
    }
    dut.clock.step()
    result
  }

  def waitForInit(dut: FullSystemHarness): Unit = { dut.clock.step(200) }

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

  def expectedSingleLayer(W: Array[Array[Int]], A: Array[Array[Int]], bias: Array[Int],
                           m: Int, k: Int, nn: Int, func: Int, shift: Int,
                           relu6Thresh: Int, clampEn: Boolean): Array[Array[Long]] = {
    applyBiasActClamp(matMul(W, A, m, k, nn), bias, m, nn, func, shift, relu6Thresh, clampEn)
  }

  def expectedMultiLayer(weights: Seq[Array[Array[Int]]], biases: Seq[Array[Int]],
                          input: Array[Array[Int]], configs: Seq[Map[String, Int]]): Array[Array[Long]] = {
    var curAct: Array[Array[Int]] = input
    for (i <- 0 until configs.length - 1) {
      val cfg = configs(i)
      val raw = matMul(weights(i), curAct, cfg("M"), cfg("K"), cfg("N"))
      val out = applyBiasActClamp(raw, biases(i), cfg("M"), cfg("N"),
        cfg("activation"), cfg("shift"), cfg("relu6_thresh"), cfg("clamp_en") != 0)
      curAct = out.map(_.map(_.toInt))
    }
    val last = configs.last
    val raw = matMul(weights.last, curAct, last("M"), last("K"), last("N"))
    applyBiasActClamp(raw, biases.last, last("M"), last("N"),
      last("activation"), last("shift"), last("relu6_thresh"), last("clamp_en") != 0)
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

  def runSingleLayer(dut: FullSystemHarness, W: Array[Array[Int]], A: Array[Array[Int]],
                     bias: Array[Int], m: Int, k: Int, nn: Int,
                     func: Int, shift: Int, relu6Thresh: Int, clampEn: Boolean): Array[Array[Long]] = {
    sendConfig(dut, Seq(Map(
      "weight_base" -> 0, "bias_base" -> 0,
      "M" -> m, "K" -> k, "N" -> nn,
      "activation" -> func, "shift" -> shift,
      "relu6_thresh" -> relu6Thresh, "clamp_en" -> (if (clampEn) 1 else 0))))
    sendWeights(dut, W, m, k, 0)
    sendBias(dut, bias, nn, 0)
    sendActivations(dut, A, k, nn, 0)
    sendRun(dut, 0, 2048)
    waitDone(dut)
    readOutput(dut, m, nn)
  }

  // === Tests — each test gets its own simulate block ===

  "CommandParser Stress" should "handle identity 4x4" in {
    simulate(new FullSystemHarness(n)) { dut =>
      dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
      val bias = Array.fill(nn)(0)
      val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
      val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
      assertResult(result, expected, m, nn, "identity 4x4")
    }
  }

  // it should "handle identity 8x8 tiled" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 8; val k = 8; val nn = 8
  //     val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
  //     val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1)
  //     val bias = Array.fill(nn)(0)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
  //     assertResult(result, expected, m, nn, "identity 8x8")
  //   }
  // }

  // it should "handle relu single tile" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(10)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(20) - 10)
  //     val A = Array.fill(k, nn)(rng.nextInt(20) - 10)
  //     val bias = Array.fill(nn)(rng.nextInt(100) - 50)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 1, 4, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 1, 4, 6, false)
  //     assertResult(result, expected, m, nn, "relu single tile")
  //   }
  // }

  // it should "handle leaky relu single tile" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(20)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(20) - 10)
  //     val A = Array.fill(k, nn)(rng.nextInt(20) - 10)
  //     val bias = Array.fill(nn)(rng.nextInt(100) - 50)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 2, 4, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 2, 4, 6, false)
  //     assertResult(result, expected, m, nn, "leaky relu single tile")
  //   }
  // }

  // it should "handle relu6 single tile" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(30)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(20) - 10)
  //     val A = Array.fill(k, nn)(rng.nextInt(20) - 10)
  //     val bias = Array.fill(nn)(rng.nextInt(100) - 50)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 3, 4, 50, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 3, 4, 50, false)
  //     assertResult(result, expected, m, nn, "relu6 single tile")
  //   }
  // }

  // it should "handle bias + relu + clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(100)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(20) - 10)
  //     val A = Array.fill(k, nn)(rng.nextInt(20) - 10)
  //     val bias = Array.fill(nn)(rng.nextInt(200) - 100)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 1, 4, 6, true)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 1, 4, 6, true)
  //     assertResult(result, expected, m, nn, "bias+relu+clamp")
  //   }
  // }

  // it should "handle random 8x8 tiled no clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(200)
  //     val m = 8; val k = 8; val nn = 8
  //     val W = Array.fill(m, k)(rng.nextInt(256) - 128)
  //     val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
  //     val bias = Array.fill(nn)(0)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 1, 8, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 1, 8, 6, false)
  //     assertResult(result, expected, m, nn, "random 8x8 relu no clamp")
  //   }
  // }

  // it should "handle non-square 8x4 × 4x8 no clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(300)
  //     val m = 8; val k = 4; val nn = 8
  //     val W = Array.fill(m, k)(rng.nextInt(256) - 128)
  //     val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
  //     val bias = Array.fill(nn)(rng.nextInt(500) - 250)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 4, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 4, 6, false)
  //     assertResult(result, expected, m, nn, "nonsquare no clamp")
  //   }
  // }

  // it should "handle random 4x4 with clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(350)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(256) - 128)
  //     val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
  //     val bias = Array.fill(nn)(rng.nextInt(1000) - 500)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 1, 8, 6, true)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 1, 8, 6, true)
  //     assertResult(result, expected, m, nn, "random 4x4 clamp")
  //   }
  // }

  // it should "handle random 12x12 tiled" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(380)
  //     val m = 12; val k = 12; val nn = 12
  //     val W = Array.fill(m, k)(rng.nextInt(256) - 128)
  //     val A = Array.fill(k, nn)(rng.nextInt(256) - 128)
  //     val bias = Array.fill(nn)(rng.nextInt(500) - 250)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 2, 6, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 2, 6, 6, false)
  //     assertResult(result, expected, m, nn, "random 12x12 leaky relu")
  //   }
  // }

  // it should "handle two-layer inference" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(500)
  //     val m0 = 4; val k0 = 4; val n0 = 4
  //     val m1 = 4; val k1 = 4; val n1 = 4
  //     val W0 = Array.fill(m0, k0)(rng.nextInt(10) - 5)
  //     val W1 = Array.fill(m1, k1)(rng.nextInt(10) - 5)
  //     val A0 = Array.fill(k0, n0)(rng.nextInt(10) - 5)
  //     val bias0 = Array.fill(n0)(rng.nextInt(50) - 25)
  //     val bias1 = Array.fill(n1)(0)

  //     val w0Base = 0
  //     val w1Base = (m0 / n) * (k0 / n) * n
  //     val b0Base = 0
  //     val b1Base = n0 / n

  //     val configs = Seq(
  //       Map("weight_base" -> w0Base, "bias_base" -> b0Base,
  //         "M" -> m0, "K" -> k0, "N" -> n0,
  //         "activation" -> 1, "shift" -> 4, "relu6_thresh" -> 6, "clamp_en" -> 1),
  //       Map("weight_base" -> w1Base, "bias_base" -> b1Base,
  //         "M" -> m1, "K" -> k1, "N" -> n1,
  //         "activation" -> 0, "shift" -> 0, "relu6_thresh" -> 6, "clamp_en" -> 0))

  //     sendConfig(dut, configs)
  //     sendWeights(dut, W0, m0, k0, w0Base)
  //     sendWeights(dut, W1, m1, k1, w1Base)
  //     sendBias(dut, bias0, n0, b0Base)
  //     sendBias(dut, bias1, n1, b1Base)
  //     sendActivations(dut, A0, k0, n0, 0)
  //     sendRun(dut, 0, 2048)

  //     val cycles = waitDone(dut)
  //     println(s"Two-layer done in $cycles cycles")

  //     val expected = expectedMultiLayer(Seq(W0, W1), Seq(bias0, bias1), A0, configs)
  //     val result = readOutput(dut, m1, n1)
  //     assertResult(result, expected, m1, n1, "two-layer")
  //   }
  // }

  // it should "handle three-layer inference" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(600)
  //     val sizes = Seq((4, 4, 4), (4, 4, 4), (4, 4, 4))
  //     val funcs = Seq(1, 2, 0)
  //     val shifts = Seq(3, 3, 0)
  //     val clamps = Seq(true, true, false)

  //     val weights = sizes.map { case (m, k, _) => Array.fill(m, k)(rng.nextInt(6) - 3) }
  //     val biases = sizes.map { case (_, _, nn) => Array.fill(nn)(rng.nextInt(20) - 10) }
  //     val input = Array.fill(sizes(0)._2, sizes(0)._3)(rng.nextInt(10) - 5)

  //     var wtOffset = 0
  //     var biasOffset = 0
  //     val configs = for (i <- 0 until 3) yield {
  //       val (m, k, nn) = sizes(i)
  //       val cfg = Map(
  //         "weight_base" -> wtOffset, "bias_base" -> biasOffset,
  //         "M" -> m, "K" -> k, "N" -> nn,
  //         "activation" -> funcs(i), "shift" -> shifts(i),
  //         "relu6_thresh" -> 6, "clamp_en" -> (if (clamps(i)) 1 else 0))
  //       wtOffset += (m / n) * (k / n) * n
  //       biasOffset += nn / n
  //       cfg
  //     }

  //     sendConfig(dut, configs)
  //     for (i <- 0 until 3) {
  //       val (m, k, _) = sizes(i)
  //       sendWeights(dut, weights(i), m, k, configs(i)("weight_base"))
  //     }
  //     for (i <- 0 until 3) {
  //       val (_, _, nn) = sizes(i)
  //       sendBias(dut, biases(i), nn, configs(i)("bias_base"))
  //     }
  //     sendActivations(dut, input, sizes(0)._2, sizes(0)._3, 0)
  //     sendRun(dut, 0, 2048)

  //     val cycles = waitDone(dut)
  //     println(s"Three-layer done in $cycles cycles")

  //     val expected = expectedMultiLayer(weights, biases, input, configs)
  //     val result = readOutput(dut, sizes(2)._1, sizes(2)._3)
  //     assertResult(result, expected, sizes(2)._1, sizes(2)._3, "three-layer")
  //   }
  // }

  // it should "handle extreme weight values no clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 4; val k = 4; val nn = 4
  //     val bias = Array.fill(nn)(0)
  //     val W = Array.fill(m, k)(127)
  //     val A = Array.fill(k, nn)(127)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
  //     assertResult(result, expected, m, nn, "all +127 no clamp")
  //   }
  // }

  // it should "handle extreme negative values no clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 4; val k = 4; val nn = 4
  //     val bias = Array.fill(nn)(0)
  //     val W = Array.fill(m, k)(-128)
  //     val A = Array.fill(k, nn)(-128)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
  //     assertResult(result, expected, m, nn, "all -128 no clamp")
  //   }
  // }

  // it should "handle extreme values with clamp" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 4; val k = 4; val nn = 4
  //     val bias = Array.fill(nn)(0)
  //     val W = Array.fill(m, k)(127)
  //     val A = Array.fill(k, nn)(127)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 8, 6, true)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 8, 6, true)
  //     assertResult(result, expected, m, nn, "all +127 with clamp")
  //   }
  // }

  // it should "handle zero matrices" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.fill(m, k)(0)
  //     val A = Array.fill(k, nn)(0)
  //     val bias = Array.fill(nn)(0)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
  //     assertResult(result, expected, m, nn, "all zeros")
  //   }
  // }

  // it should "handle deep K accumulation 4x12 × 12x4" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val rng = new Random(700)
  //     val m = 4; val k = 12; val nn = 4
  //     val W = Array.fill(m, k)(rng.nextInt(64) - 32)
  //     val A = Array.fill(k, nn)(rng.nextInt(64) - 32)
  //     val bias = Array.fill(nn)(rng.nextInt(200) - 100)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 1, 6, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 1, 6, 6, false)
  //     assertResult(result, expected, m, nn, "deep K 4x12*12x4")
  //   }
  // }

  // it should "handle large bias values" in {
  //   simulate(new FullSystemHarness(n)) { dut =>
  //     dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)
  //     val m = 4; val k = 4; val nn = 4
  //     val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
  //     val A = Array.fill(k, nn)(0)
  //     val bias = Array(10000, -10000, 32767, -32768)
  //     val expected = expectedSingleLayer(W, A, bias, m, k, nn, 0, 0, 6, false)
  //     val result = runSingleLayer(dut, W, A, bias, m, k, nn, 0, 0, 6, false)
  //     assertResult(result, expected, m, nn, "large bias values")
  //   }
  // }
  it should "handle back-to-back runs without stalling" in {
    simulate(new FullSystemHarness(n)) { dut =>
      dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)

      val m = 4; val k = 4; val nn = 4
      val W = Array.tabulate(m, k)((r, c) => if (r == c) 1 else 0)
      val bias = Array.fill(nn)(0)

      // Send config and weights once
      sendConfig(dut, Seq(Map(
        "weight_base" -> 0, "bias_base" -> 0,
        "M" -> m, "K" -> k, "N" -> nn,
        "activation" -> 0, "shift" -> 0,
        "relu6_thresh" -> 6, "clamp_en" -> 0)))
      sendWeights(dut, W, m, k, 0)
      sendBias(dut, bias, nn, 0)

      // Run 5 consecutive inferences with different inputs
      for (run <- 0 until 5) {
        val A = Array.tabulate(k, nn)((r, c) => r * nn + c + 1 + run * 10)
        val expected = matMul(W, A, m, k, nn)

        println(s"  Run $run: sending activations...")
        sendActivations(dut, A, k, nn, 0)
        sendRun(dut, 0, 2048)

        val cycles = waitDone(dut)
        println(s"  Run $run: done in $cycles cycles")

        val result = readOutput(dut, m, nn)
        assertResult(result, expected, m, nn, s"back-to-back run $run")
      }
      println("  Back-to-back 5 runs PASSED")
    }
  }

  it should "handle back-to-back two-layer runs" in {
    simulate(new FullSystemHarness(n)) { dut =>
      dut.io.rx_valid.poke(false.B); dut.io.tx_ready.poke(true.B); waitForInit(dut)

      val rng = new Random(800)
      val m0 = 4; val k0 = 4; val n0 = 4
      val m1 = 4; val k1 = 4; val n1 = 4
      val W0 = Array.fill(m0, k0)(rng.nextInt(10) - 5)
      val W1 = Array.fill(m1, k1)(rng.nextInt(10) - 5)
      val bias0 = Array.fill(n0)(rng.nextInt(50) - 25)
      val bias1 = Array.fill(n1)(0)

      val w0Base = 0
      val w1Base = (m0 / n) * (k0 / n) * n
      val b0Base = 0
      val b1Base = n0 / n

      val configs = Seq(
        Map("weight_base" -> w0Base, "bias_base" -> b0Base,
          "M" -> m0, "K" -> k0, "N" -> n0,
          "activation" -> 1, "shift" -> 4, "relu6_thresh" -> 6, "clamp_en" -> 1),
        Map("weight_base" -> w1Base, "bias_base" -> b1Base,
          "M" -> m1, "K" -> k1, "N" -> n1,
          "activation" -> 0, "shift" -> 0, "relu6_thresh" -> 6, "clamp_en" -> 0))

      // Upload model once
      sendConfig(dut, configs)
      sendWeights(dut, W0, m0, k0, w0Base)
      sendWeights(dut, W1, m1, k1, w1Base)
      sendBias(dut, bias0, n0, b0Base)
      sendBias(dut, bias1, n1, b1Base)

      // Run 3 consecutive inferences with different inputs
      for (run <- 0 until 3) {
        val A0 = Array.fill(k0, n0)(rng.nextInt(10) - 5)
        val expected = expectedMultiLayer(Seq(W0, W1), Seq(bias0, bias1), A0, configs)

        println(s"  Two-layer run $run: sending activations...")
        sendActivations(dut, A0, k0, n0, 0)
        sendRun(dut, 0, 2048)

        val cycles = waitDone(dut)
        println(s"  Two-layer run $run: done in $cycles cycles")

        val result = readOutput(dut, m1, n1)
        assertResult(result, expected, m1, n1, s"back-to-back two-layer run $run")
      }
      println("  Back-to-back two-layer 3 runs PASSED")
    }
  }
}