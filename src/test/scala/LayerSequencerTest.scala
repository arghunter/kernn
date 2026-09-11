import chisel3._
import chisel3.simulator._
import chisel3.simulator.scalatest.{HasCliOptions, Cli}
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class LayerSequencerTest extends AnyFlatSpec with HasCliOptions with Cli.EmitVcd with ChiselSim {

  val n = 15

  // Memory depths scaled to n so they're always large enough
  val wtMemDepth   = 65536
  val actMemDepth  = 65536
  val outMemDepth  = 65536
  val biasMemDepth = 4096

  class SequencerTestHarness(val n: Int,
                              wtMemDepth: Int = 65536,
                              actMemDepth: Int = 65536,
                              outMemDepth: Int = 65536,
                              biasMemDepth: Int = 4096) extends Module {
    val io = IO(new Bundle {
      val start          = Input(Bool())
      val busy           = Output(Bool())
      val done           = Output(Bool())
      val num_layers     = Input(UInt(8.W))
      val config_wr_en   = Input(Bool())
      val config_wr_idx  = Input(UInt(4.W))
      val config_wr_data = Input(new LayerConfig)
      val input_base     = Input(UInt(16.W))
      val buffer_b_base  = Input(UInt(16.W))
      val result_base    = Output(UInt(16.W))
      val result_M       = Output(UInt(16.W))
      val result_N       = Output(UInt(16.W))
      val wt_wr_en       = Input(Bool())
      val wt_wr_addr     = Input(UInt(16.W))
      val wt_wr_data     = Input(Vec(n, SInt(8.W)))
      val act_wr_en      = Input(Bool())
      val act_wr_addr    = Input(UInt(16.W))
      val act_wr_data    = Input(Vec(n, SInt(8.W)))
      val act_rd_addr    = Input(UInt(16.W))
      val act_rd_data    = Output(Vec(n, SInt(8.W)))
      val bias_wr_en     = Input(Bool())
      val bias_wr_addr   = Input(UInt(16.W))
      val bias_wr_data   = Input(Vec(n, SInt(32.W)))
      val out_rd_addr    = Input(UInt(16.W))
      val out_rd_data    = Output(Vec(n, SInt(32.W)))
      val out_wr_en      = Input(Bool())
      val out_wr_addr    = Input(UInt(16.W))
      val out_wr_data    = Input(Vec(n, SInt(32.W)))
    })

    val seq = Module(new LayerSequencer(n, 16, 16))

    val weightMem = SyncReadMem(wtMemDepth,   Vec(n, SInt(8.W)))
    val actMem    = SyncReadMem(actMemDepth,  Vec(n, SInt(8.W)))
    val outMem    = SyncReadMem(outMemDepth,  Vec(n, SInt(32.W)))
    val biasMem   = SyncReadMem(biasMemDepth, Vec(n, SInt(32.W)))

    when(io.wt_wr_en) { weightMem.write(io.wt_wr_addr, io.wt_wr_data) }
    seq.io.weight_data := weightMem.read(seq.io.weight_addr)

    when(seq.io.act_wr_en) {
      actMem.write(seq.io.act_wr_addr, seq.io.act_wr_data)
    }.elsewhen(io.act_wr_en) {
      actMem.write(io.act_wr_addr, io.act_wr_data)
    }
    seq.io.act_rd_data := actMem.read(seq.io.act_rd_addr)
    io.act_rd_data     := actMem.read(io.act_rd_addr)

    when(seq.io.output_wen) {
      outMem.write(seq.io.output_wr_addr, seq.io.output_data_wr)
    }.elsewhen(io.out_wr_en) {
      outMem.write(io.out_wr_addr, io.out_wr_data)
    }
    seq.io.output_data_r := outMem.read(seq.io.output_rd_addr)
    io.out_rd_data       := outMem.read(io.out_rd_addr)

    when(io.bias_wr_en) { biasMem.write(io.bias_wr_addr, io.bias_wr_data) }
    seq.io.bias_data := biasMem.read(seq.io.bias_addr)

    seq.io.start          := io.start
    seq.io.num_layers     := io.num_layers
    seq.io.config_wr_en   := io.config_wr_en
    seq.io.config_wr_idx  := io.config_wr_idx
    seq.io.config_wr_data := io.config_wr_data
    seq.io.input_base     := io.input_base
    seq.io.buffer_b_base  := io.buffer_b_base
    io.busy               := seq.io.busy
    io.done               := seq.io.done
    io.result_base        := seq.io.result_base
    io.result_M           := seq.io.result_M
    io.result_N           := seq.io.result_N
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  def signExtend(v: BigInt): Long =
    if (v > Int.MaxValue) (v - BigInt(4294967296L)).toLong else v.toLong

  def signExtend8(v: BigInt): Int = {
    val i = v.toInt & 0xFF
    if (i >= 128) i - 256 else i
  }

  def waitForDone(dut: SequencerTestHarness, maxCycles: Int = 5000000): Int = {
    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < maxCycles, s"Timed out after $maxCycles cycles")
    cycles
  }

  def waitForIdle(dut: SequencerTestHarness, maxCycles: Int = 500000): Int = {
    var cycles = 0
    while (dut.io.busy.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    cycles
  }

  def loadWeights(dut: SequencerTestHarness, W: Array[Array[Int]], m: Int, k: Int, baseAddr: Int): Unit = {
    val tilesK = k / n
    for (tR <- 0 until m / n; tK <- 0 until tilesK) {
      val base = baseAddr + (tR * tilesK + tK) * n
      for (t <- 0 until n) {
        dut.io.wt_wr_en.poke(true.B)
        dut.io.wt_wr_addr.poke((base + t).U)
        for (r <- 0 until n)
          dut.io.wt_wr_data(r).poke(W(tR * n + r)(tK * n + t).S)
        dut.clock.step()
      }
    }
    dut.io.wt_wr_en.poke(false.B)
  }

  def loadActivations(dut: SequencerTestHarness, A: Array[Array[Int]], k: Int, nn: Int, baseAddr: Int): Unit = {
    val tilesN = nn / n
    for (tK <- 0 until k / n; tC <- 0 until tilesN) {
      val base = baseAddr + (tK * tilesN + tC) * n
      for (t <- 0 until n) {
        dut.io.act_wr_en.poke(true.B)
        dut.io.act_wr_addr.poke((base + t).U)
        for (c <- 0 until n)
          dut.io.act_wr_data(c).poke(A(tK * n + t)(tC * n + c).S)
        dut.clock.step()
      }
    }
    dut.io.act_wr_en.poke(false.B)
  }

  def loadBias(dut: SequencerTestHarness, bias: Array[Int], nn: Int, baseAddr: Int): Unit = {
    val tilesN = nn / n
    for (tC <- 0 until tilesN) {
      dut.io.bias_wr_en.poke(true.B)
      dut.io.bias_wr_addr.poke((baseAddr + tC).U)
      for (c <- 0 until n)
        dut.io.bias_wr_data(c).poke(bias(tC * n + c).S)
      dut.clock.step()
    }
    dut.io.bias_wr_en.poke(false.B)
  }

  def clearOutputMem(dut: SequencerTestHarness, count: Int = 4096): Unit = {
    for (addr <- 0 until count) {
      dut.io.out_wr_en.poke(true.B)
      dut.io.out_wr_addr.poke(addr.U)
      for (c <- 0 until n) dut.io.out_wr_data(c).poke(0.S)
      dut.clock.step()
    }
    dut.io.out_wr_en.poke(false.B)
  }

  def readOutputResults(dut: SequencerTestHarness, m: Int, nn: Int): Array[Array[Long]] = {
    val tilesN = nn / n
    val result = Array.ofDim[Long](m, nn)
    for (tR <- 0 until m / n; tC <- 0 until tilesN) {
      val base = (tR * tilesN + tC) * n
      for (r <- 0 until n) {
        dut.io.out_rd_addr.poke((base + r).U)
        dut.clock.step()
        dut.clock.step()
        for (c <- 0 until n)
          result(tR * n + r)(tC * n + c) = signExtend(dut.io.out_rd_data(c).peek().litValue)
      }
    }
    result
  }

  def readActResults(dut: SequencerTestHarness, m: Int, nn: Int, baseAddr: Int): Array[Array[Int]] = {
    val tilesN = nn / n
    val result = Array.ofDim[Int](m, nn)
    for (tR <- 0 until m / n; tC <- 0 until tilesN) {
      val base = baseAddr + (tR * tilesN + tC) * n
      for (r <- 0 until n) {
        dut.io.act_rd_addr.poke((base + r).U)
        dut.clock.step()
        dut.clock.step()
        for (c <- 0 until n)
          result(tR * n + r)(tC * n + c) = signExtend8(dut.io.act_rd_data(c).peek().litValue)
      }
    }
    result
  }

  def writeConfig(dut: SequencerTestHarness, idx: Int, weightBase: Int, biasBase: Int,
                  m: Int, k: Int, nn: Int, activation: Int, shift: Int,
                  relu6Thresh: Int, clampEn: Boolean): Unit = {
    dut.io.config_wr_en.poke(true.B)
    dut.io.config_wr_idx.poke(idx.U)
    dut.io.config_wr_data.weight_base.poke(weightBase.U)
    dut.io.config_wr_data.bias_base.poke(biasBase.U)
    dut.io.config_wr_data.M.poke(m.U)
    dut.io.config_wr_data.K.poke(k.U)
    dut.io.config_wr_data.N.poke(nn.U)
    dut.io.config_wr_data.activation.poke(activation.U)
    dut.io.config_wr_data.shift.poke(shift.U)
    dut.io.config_wr_data.relu6_thresh.poke(relu6Thresh.S)
    dut.io.config_wr_data.clamp_en.poke(clampEn.B)
    dut.clock.step()
    dut.io.config_wr_en.poke(false.B)
  }

  // ─── Software reference model ────────────────────────────────────────────────

  def matMul(W: Array[Array[Int]], A: Array[Array[Int]], m: Int, k: Int, nn: Int): Array[Array[Long]] = {
    val result = Array.ofDim[Long](m, nn)
    for (r <- 0 until m; c <- 0 until nn)
      result(r)(c) = (0 until k).map(i => W(r)(i).toLong * A(i)(c).toLong).sum
    result
  }

  def applyBiasActClamp(raw: Array[Array[Long]], bias: Array[Int],
                         m: Int, nn: Int, func: Int, shift: Int,
                         relu6Thresh: Int, clampEn: Boolean): Array[Array[Int]] = {
    Array.tabulate(m, nn) { (r, c) =>
      val biased    = raw(r)(c) + bias(c).toLong
      val shifted   = biased >> shift
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
        if (activated > 127) 127
        else if (activated < -128) -128
        else activated.toInt
      } else activated.toInt
    }
  }

  def layerMemFootprint(m: Int, k: Int, nn: Int): (Int, Int) = {
    val wtWords   = (m / n) * (k / n) * n
    val biasWords = nn / n
    (wtWords, biasWords)
  }

  // bufferBBase must be beyond the activation input buffer.
  // For a layer with K rows and N cols, the activation footprint is (K/n)*(N/n)*n words.
  def safeBufferBBase(k: Int, nn: Int): Int = (k / n) * (nn / n) * n * 4 + 256

  // ─── Common test driver for a single large layer ──────────────────────────────

  def runSingleLayerTest(m: Int, k: Int, nn: Int,
                          activation: Int, shift: Int,
                          relu6Thresh: Int, clampEn: Boolean,
                          seed: Long = 0L): Unit = {
    require(m % n == 0 && k % n == 0 && nn % n == 0,
      s"All dimensions must be multiples of n=$n, got M=$m K=$k N=$nn")

    val inputBase   = 0
    val bufferBBase = safeBufferBBase(k, nn)

    simulate(new SequencerTestHarness(n)) { dut =>
      val rng  = new Random(seed)
      val W    = Array.fill(m, k)(rng.nextInt(20) - 10)
      val A    = Array.fill(k, nn)(rng.nextInt(20) - 10)
      val bias = Array.fill(nn)(rng.nextInt(100) - 50)

      dut.io.start.poke(false.B); dut.io.config_wr_en.poke(false.B)
      dut.io.wt_wr_en.poke(false.B); dut.io.act_wr_en.poke(false.B)
      dut.io.bias_wr_en.poke(false.B); dut.io.out_wr_en.poke(false.B)
      dut.clock.step(50)

      loadWeights(dut, W, m, k, 0)
      loadActivations(dut, A, k, nn, inputBase)
      loadBias(dut, bias, nn, 0)
      clearOutputMem(dut, (m / n) * (nn / n) * n + 64)

      writeConfig(dut, 0, 0, 0, m, k, nn, activation, shift, relu6Thresh, clampEn)

      dut.io.num_layers.poke(1.U)
      dut.io.input_base.poke(inputBase.U)
      dut.io.buffer_b_base.poke(bufferBBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cycles = waitForDone(dut)
      println(s"[n=$n M=$m K=$k N=$nn act=$activation shift=$shift clamp=$clampEn] done in $cycles cycles")

      val raw      = matMul(W, A, m, k, nn)
      val expected = applyBiasActClamp(raw, bias, m, nn, activation, shift, relu6Thresh, clampEn)
      val result   = readOutputResults(dut, m, nn)

      for (r <- 0 until m; c <- 0 until nn)
        assert(result(r)(c) == expected(r)(c),
          s"[n=$n] M=$m K=$k N=$nn: mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  // ─── Common test driver for a chain of layers ────────────────────────────────

  case class LayerSpec(m: Int, k: Int, nn: Int, activation: Int, shift: Int,
                        relu6Thresh: Int, clampEn: Boolean)

  def runMultiLayerTest(layers: Seq[LayerSpec], seed: Long = 0L): Unit = {
    require(layers.nonEmpty)
    for (i <- 1 until layers.length) {
      require(layers(i).k == layers(i-1).m,
        s"Layer $i K=${layers(i).k} must equal layer ${i-1} M=${layers(i-1).m}")
      require(layers(i).nn == layers(i-1).nn,
        s"Layer $i N=${layers(i).nn} must equal layer ${i-1} N=${layers(i-1).nn}")
    }

    val inputBase   = 0
    val maxActWords = layers.map(l => (l.k / n) * (l.nn / n) * n).max
    val bufferBBase = maxActWords * 4 + 256

    simulate(new SequencerTestHarness(n)) { dut =>
      val rng = new Random(seed)

      val Ws     = layers.map(l => Array.fill(l.m, l.k)(rng.nextInt(20) - 10))
      val biases = layers.map(l => Array.fill(l.nn)(rng.nextInt(100) - 50))
      val A0     = Array.fill(layers.head.k, layers.head.nn)(rng.nextInt(20) - 10)

      dut.io.start.poke(false.B); dut.io.config_wr_en.poke(false.B)
      dut.io.wt_wr_en.poke(false.B); dut.io.act_wr_en.poke(false.B)
      dut.io.bias_wr_en.poke(false.B); dut.io.out_wr_en.poke(false.B)
      dut.clock.step(50)

      var wtOff = 0; var biasOff = 0
      for (i <- layers.indices) {
        val l = layers(i)
        loadWeights(dut, Ws(i), l.m, l.k, wtOff)
        loadBias(dut, biases(i), l.nn, biasOff)
        val (wt, b) = layerMemFootprint(l.m, l.k, l.nn)
        wtOff += wt; biasOff += b
      }

      loadActivations(dut, A0, layers.head.k, layers.head.nn, inputBase)
      clearOutputMem(dut, 4096)

      wtOff = 0; biasOff = 0
      for (i <- layers.indices) {
        val l = layers(i)
        writeConfig(dut, i, wtOff, biasOff, l.m, l.k, l.nn, l.activation, l.shift, l.relu6Thresh, l.clampEn)
        val (wt, b) = layerMemFootprint(l.m, l.k, l.nn)
        wtOff += wt; biasOff += b
      }

      dut.io.num_layers.poke(layers.length.U)
      dut.io.input_base.poke(inputBase.U)
      dut.io.buffer_b_base.poke(bufferBBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cycles = waitForDone(dut)
      println(s"[n=$n ${layers.length} layers] done in $cycles cycles")

      var cur: Array[Array[Int]] = A0
      for (i <- layers.indices) {
        val l   = layers(i)
        val raw = matMul(Ws(i), cur, l.m, l.k, l.nn)
        cur = applyBiasActClamp(raw, biases(i), l.m, l.nn, l.activation, l.shift, l.relu6Thresh, l.clampEn)
      }

      val last   = layers.last
      val result = readOutputResults(dut, last.m, last.nn)

      for (r <- 0 until last.m; c <- 0 until last.nn)
        assert(result(r)(c) == cur(r)(c).toLong,
          s"[n=$n] Mismatch at layer${layers.length-1} ($r,$c): got ${result(r)(c)}, expected ${cur(r)(c)}")
    }
  }

  // ─── Inline tests (sized relative to n) ──────────────────────────────────────

  "LayerSequencer" should "run a single layer (identity, no clamp)" in {
    simulate(new SequencerTestHarness(n)) { dut =>
      val m = n; val k = n; val nn = n
      val inputBase   = 0
      val bufferBBase = safeBufferBBase(k, nn)
      val rng  = new Random(99)
      val W    = Array.fill(m, k)(rng.nextInt(10) - 5)
      val A    = Array.fill(k, nn)(rng.nextInt(10) - 5)
      val bias = Array.fill(nn)(0)

      dut.io.start.poke(false.B); dut.io.config_wr_en.poke(false.B)
      dut.io.wt_wr_en.poke(false.B); dut.io.act_wr_en.poke(false.B)
      dut.io.bias_wr_en.poke(false.B); dut.io.out_wr_en.poke(false.B)
      dut.clock.step(50)

      loadWeights(dut, W, m, k, 0)
      loadActivations(dut, A, k, nn, inputBase)
      loadBias(dut, bias, nn, 0)
      clearOutputMem(dut, 512)
      writeConfig(dut, 0, 0, 0, m, k, nn, 0, 0, 6, false)

      dut.io.num_layers.poke(1.U)
      dut.io.input_base.poke(inputBase.U)
      dut.io.buffer_b_base.poke(bufferBBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cycles = waitForDone(dut)
      println(s"[n=$n] Single ${n}x${n} layer done in $cycles cycles")

      val raw      = matMul(W, A, m, k, nn)
      val expected = applyBiasActClamp(raw, bias, m, nn, 0, 0, 6, false)
      val result   = readOutputResults(dut, m, nn)

      for (r <- 0 until m)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until m; c <- 0 until nn)
        assert(result(r)(c) == expected(r)(c),
          s"[n=$n] Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "run two layers with ReLU and clamp" in {
    simulate(new SequencerTestHarness(n)) { dut =>
      val m0 = n; val k0 = n; val n0 = n
      val m1 = n; val k1 = n; val n1 = n
      val inputBase   = 0
      val bufferBBase = safeBufferBBase(k0, n0)
      val rng   = new Random(42)
      val W0    = Array.fill(m0, k0)(rng.nextInt(20) - 10)
      val W1    = Array.fill(m1, k1)(rng.nextInt(20) - 10)
      val A0    = Array.fill(k0, n0)(rng.nextInt(20) - 10)
      val bias0 = Array.fill(n0)(rng.nextInt(100) - 50)
      val bias1 = Array.fill(n1)(0)

      val (w0Words, b0Words) = layerMemFootprint(m0, k0, n0)
      val w0Base = 0; val w1Base = w0Words
      val b0Base = 0; val b1Base = b0Words

      dut.io.start.poke(false.B); dut.io.config_wr_en.poke(false.B)
      dut.io.wt_wr_en.poke(false.B); dut.io.act_wr_en.poke(false.B)
      dut.io.bias_wr_en.poke(false.B); dut.io.out_wr_en.poke(false.B)
      dut.clock.step(50)

      loadWeights(dut, W0, m0, k0, w0Base)
      loadWeights(dut, W1, m1, k1, w1Base)
      loadActivations(dut, A0, k0, n0, inputBase)
      loadBias(dut, bias0, n0, b0Base)
      loadBias(dut, bias1, n1, b1Base)
      clearOutputMem(dut, 512)

      writeConfig(dut, 0, w0Base, b0Base, m0, k0, n0, 1, 4, 6, true)
      writeConfig(dut, 1, w1Base, b1Base, m1, k1, n1, 0, 0, 6, false)

      dut.io.num_layers.poke(2.U)
      dut.io.input_base.poke(inputBase.U)
      dut.io.buffer_b_base.poke(bufferBBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cycles = waitForDone(dut)
      println(s"[n=$n] Two ${n}x${n} layers done in $cycles cycles")

      val raw0     = matMul(W0, A0, m0, k0, n0)
      val act0     = applyBiasActClamp(raw0, bias0, m0, n0, 1, 4, 6, true)
      val raw1     = matMul(W1, act0.map(_.map(_.toInt)), m1, k1, n1)
      val expected = applyBiasActClamp(raw1, bias1, m1, n1, 0, 0, 6, false)
      val result   = readOutputResults(dut, m1, n1)

      for (r <- 0 until m1)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until m1; c <- 0 until n1)
        assert(result(r)(c) == expected(r)(c),
          s"[n=$n] Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  it should "run three layers with different activations" in {
    simulate(new SequencerTestHarness(n)) { dut =>
      val rng    = new Random(100)
      val sizes  = Seq((n,n,n), (n,n,n), (n,n,n))
      val funcs  = Seq(1, 2, 0)
      val shifts = Seq(4, 3, 0)
      val clamps = Seq(true, true, false)
      val inputBase   = 0
      val bufferBBase = safeBufferBBase(sizes(0)._2, sizes(0)._3)

      val weights = sizes.map { case (m, k, _) => Array.fill(m, k)(rng.nextInt(10) - 5) }
      val biases  = sizes.map { case (_, _, nn) => Array.fill(nn)(rng.nextInt(20) - 10) }
      val input   = Array.fill(sizes(0)._2, sizes(0)._3)(rng.nextInt(20) - 10)

      dut.io.start.poke(false.B); dut.io.config_wr_en.poke(false.B)
      dut.io.wt_wr_en.poke(false.B); dut.io.act_wr_en.poke(false.B)
      dut.io.bias_wr_en.poke(false.B); dut.io.out_wr_en.poke(false.B)
      dut.clock.step(50)

      var wtOffset = 0; var biasOffset = 0
      for (i <- 0 until 3) {
        val (m, k, nn) = sizes(i)
        loadWeights(dut, weights(i), m, k, wtOffset)
        val (wt, _) = layerMemFootprint(m, k, nn)
        wtOffset += wt
      }
      for (i <- 0 until 3) {
        val (_, _, nn) = sizes(i)
        loadBias(dut, biases(i), nn, biasOffset)
        val (_, b) = layerMemFootprint(sizes(i)._1, sizes(i)._2, nn)
        biasOffset += b
      }
      loadActivations(dut, input, sizes(0)._2, sizes(0)._3, inputBase)
      clearOutputMem(dut, 512)

      wtOffset = 0; biasOffset = 0
      for (i <- 0 until 3) {
        val (m, k, nn) = sizes(i)
        writeConfig(dut, i, wtOffset, biasOffset, m, k, nn, funcs(i), shifts(i), 6, clamps(i))
        val (wt, b) = layerMemFootprint(m, k, nn)
        wtOffset += wt; biasOffset += b
      }

      dut.io.num_layers.poke(3.U)
      dut.io.input_base.poke(inputBase.U)
      dut.io.buffer_b_base.poke(bufferBBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)

      val cycles = waitForDone(dut)
      println(s"[n=$n] Three ${n}x${n} layers done in $cycles cycles")

      var cur = input.map(_.map(_.toInt))
      for (i <- 0 until 3) {
        val (m, k, nn) = sizes(i)
        val raw = matMul(weights(i), cur, m, k, nn)
        cur = applyBiasActClamp(raw, biases(i), m, nn, funcs(i), shifts(i), 6, clamps(i))
      }
      val expected = cur
      val result   = readOutputResults(dut, sizes(2)._1, sizes(2)._3)

      for (r <- 0 until n)
        println(s"  Row $r: ${result(r).mkString(", ")} (expected: ${expected(r).mkString(", ")})")
      for (r <- 0 until n; c <- 0 until n)
        assert(result(r)(c) == expected(r)(c).toLong,
          s"[n=$n] Mismatch at ($r,$c): got ${result(r)(c)}, expected ${expected(r)(c)}")
    }
  }

  // ─── Larger single-layer tests ───────────────────────────────────────────────

  it should s"run a ${2*n}x${2*n}x${2*n} single layer (identity, no clamp)" in {
    runSingleLayerTest(2*n, 2*n, 2*n, 0, 0, 6, false, seed = 1L)
  }

  it should s"run a ${2*n}x${2*n}x${2*n} single layer with ReLU and clamp" in {
    runSingleLayerTest(2*n, 2*n, 2*n, 1, 4, 6, true, seed = 2L)
  }

  it should s"run a ${4*n}x${4*n}x${4*n} single layer (identity, no clamp)" in {
    runSingleLayerTest(4*n, 4*n, 4*n, 0, 0, 6, false, seed = 3L)
  }

  it should s"run a ${4*n}x${4*n}x${4*n} single layer with ReLU and clamp" in {
    runSingleLayerTest(4*n, 4*n, 4*n, 1, 4, 6, true, seed = 4L)
  }

  it should s"run a ${4*n}x${4*n}x${4*n} single layer with Leaky ReLU and clamp" in {
    runSingleLayerTest(4*n, 4*n, 4*n, 2, 3, 6, true, seed = 5L)
  }

  it should s"run a ${4*n}x${2*n}x${4*n} non-square layer (identity, no clamp)" in {
    runSingleLayerTest(4*n, 2*n, 4*n, 0, 0, 6, false, seed = 6L)
  }

  it should s"run a ${8*n}x${4*n}x${2*n} non-square layer with ReLU and clamp" in {
    runSingleLayerTest(8*n, 4*n, 2*n, 1, 4, 6, true, seed = 7L)
  }

  it should s"run a ${8*n}x${8*n}x${8*n} single layer (identity, no clamp)" in {
    runSingleLayerTest(8*n, 8*n, 8*n, 0, 0, 6, false, seed = 8L)
  }

  // ─── Larger multi-layer tests ────────────────────────────────────────────────

  it should s"run two ${2*n}x${2*n}x${2*n} layers with ReLU->identity" in {
    runMultiLayerTest(Seq(
      LayerSpec(2*n, 2*n, 2*n, 1, 4, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 0, 0, 6, false)
    ), seed = 10L)
  }

  it should s"run two ${4*n}x${4*n}x${4*n} layers with ReLU->identity" in {
    runMultiLayerTest(Seq(
      LayerSpec(4*n, 4*n, 4*n, 1, 4, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 0, 0, 6, false)
    ), seed = 11L)
  }

  it should s"run a 3-layer ${2*n}x${2*n}x${2*n} chain (relu->leaky->identity)" in {
    runMultiLayerTest(Seq(
      LayerSpec(2*n, 2*n, 2*n, 1, 4, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 2, 3, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 0, 0, 6, false)
    ), seed = 12L)
  }

  it should s"run a 3-layer ${4*n}x${4*n}x${4*n} chain (relu->leaky->identity)" in {
    runMultiLayerTest(Seq(
      LayerSpec(4*n, 4*n, 4*n, 1, 4, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 2, 3, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 0, 0, 6, false)
    ), seed = 13L)
  }

  it should "run a dimension-shrinking chain" in {
    runMultiLayerTest(Seq(
      LayerSpec(8*n, 8*n, 2*n, 1, 4, 6, true),
      LayerSpec(4*n, 8*n, 2*n, 2, 3, 6, true),
      LayerSpec(2*n, 4*n, 2*n, 0, 0, 6, false)
    ), seed = 14L)
  }

  it should "run a dimension-growing chain" in {
    runMultiLayerTest(Seq(
      LayerSpec(  n,   n, 2*n, 1, 4, 6, true),
      LayerSpec(2*n,   n, 2*n, 2, 3, 6, true),
      LayerSpec(4*n, 2*n, 2*n, 0, 0, 6, false)
    ), seed = 15L)
  }

  it should s"run a 4-layer ${2*n}x${2*n}x${2*n} chain with all activation types" in {
    runMultiLayerTest(Seq(
      LayerSpec(2*n, 2*n, 2*n, 1, 4, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 2, 3, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 3, 2, 6, true),
      LayerSpec(2*n, 2*n, 2*n, 0, 0, 6, false)
    ), seed = 16L)
  }

  it should s"run a 4-layer ${4*n}x${4*n}x${4*n} chain with all activation types" in {
    runMultiLayerTest(Seq(
      LayerSpec(4*n, 4*n, 4*n, 1, 4, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 2, 3, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 3, 2, 6, true),
      LayerSpec(4*n, 4*n, 4*n, 0, 0, 6, false)
    ), seed = 17L)
  }

  it should s"stress-test: 5-layer ${4*n}x${4*n}x${4*n} all-relu chain" in {
    runMultiLayerTest(Seq.tabulate(5) { i =>
      val isLast = i == 4
      LayerSpec(4*n, 4*n, 4*n,
        activation  = if (isLast) 0 else 1,
        shift       = if (isLast) 0 else 4,
        relu6Thresh = 6,
        clampEn     = !isLast)
    }, seed = 20L)
  }
}