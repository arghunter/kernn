import chisel3._
import chisel3.util._

object SeqState extends ChiselEnum {
  val IDLE, READ_CONFIG, CONFIGURE, RUN_LAYER, WAIT_LAYER, COPY_START, COPY_READ, COPY_WRITE, DONE = Value
}

class LayerConfig extends Bundle {
  val weight_base = UInt(16.W)
  val bias_base = UInt(16.W)
  val M = UInt(16.W)
  val K = UInt(16.W)
  val N = UInt(16.W)
  val activation = UInt(2.W)
  val shift = UInt(5.W)
  val relu6_thresh = SInt(32.W)
  val clamp_en = Bool()
}

class LayerSequencer(val n: Int = 4, val addr_width: Int = 16, val max_layers: Int = 16) extends Module {
  val io = IO(new Bundle {
    // Control
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val num_layers = Input(UInt(8.W))

    // Layer config write port (host loads before start)
    val config_wr_en = Input(Bool())
    val config_wr_idx = Input(UInt(log2Ceil(max_layers).W))
    val config_wr_data = Input(new LayerConfig)

    // Weight memory port
    val weight_addr = Output(UInt(addr_width.W))
    val weight_data = Input(Vec(n, SInt(8.W)))

    // Activation memory — dual buffered
    // Read port (current layer input)
    val act_rd_addr = Output(UInt(addr_width.W))
    val act_rd_data = Input(Vec(n, SInt(8.W)))
    // Write port (copy output back as next layer input)
    val act_wr_addr = Output(UInt(addr_width.W))
    val act_wr_data = Output(Vec(n, SInt(8.W)))
    val act_wr_en = Output(Bool())

    // Output memory — dual ported
    val output_rd_addr = Output(UInt(addr_width.W))
    val output_data_r = Input(Vec(n, SInt(32.W)))
    val output_wr_addr = Output(UInt(addr_width.W))
    val output_data_wr = Output(Vec(n, SInt(32.W)))
    val output_wen = Output(Bool())

    // Bias memory
    val bias_addr = Output(UInt(addr_width.W))
    val bias_data = Input(Vec(n, SInt(32.W)))

    // Input activation base (where host loaded input)
    val input_base = Input(UInt(addr_width.W))
    // Buffer B base address
    val buffer_b_base = Input(UInt(addr_width.W))

    // Result location (which buffer has final output)
    val result_base = Output(UInt(addr_width.W))
    val result_M = Output(UInt(16.W))
    val result_N = Output(UInt(16.W))

    val bias_values = Output(Vec(n, SInt(32.W)))
    val bias_en = Output(Bool())
    val isLastK = Output(Bool())

    val stateOut = Output(SeqState())
    val tiledState = Output(TiledState())

  })

  // Tiled matmul controller
  val matmul = Module(new TiledMatMulController(n, addr_width))

  io.bias_values := matmul.io.bias_values
  io.bias_en := matmul.io.bias_en
  io.isLastK := matmul.io.isLastK
  io.tiledState := matmul.io.stateOut

  // Activation function bank
  val actFunc = Module(new ActivationFunctionBank(n, 32))
  

  // Layer config storage
  val configMem = Mem(max_layers, new LayerConfig)

  // State
  val state = RegInit(SeqState.IDLE)

  val layerIdx = RegInit(0.U(8.W))
    io.stateOut:= state
  val numLayers = RegInit(0.U(8.W))

  // Current layer config
  val curConfig = Reg(new LayerConfig)
    actFunc.io.clamp_en := curConfig.clamp_en;


  // Double buffer tracking
  // readBuf = which base address to read activations from
  // writeBuf = which base address to write output activations to
  val readBufBase = RegInit(0.U(addr_width.W))
  val writeBufBase = RegInit(0.U(addr_width.W))
  val bufferBBase = RegInit(0.U(addr_width.W))

  // Copy state for output→activation conversion
  val copyTileR = RegInit(0.U(16.W))
  val copyTileC = RegInit(0.U(16.W))
  val copyRow = RegInit(0.U(16.W))
  val copyTilesM = RegInit(0.U(16.W))
  val copyTilesN = RegInit(0.U(16.W))
  val copyData = Reg(Vec(n, SInt(8.W)))

  // Config write port
  when(io.config_wr_en) {
    configMem.write(io.config_wr_idx, io.config_wr_data)
  }

  // === Matmul controller connections ===
  matmul.io.weight_data := io.weight_data
  matmul.io.activation_data := io.act_rd_data
  matmul.io.output_data_r := io.output_data_r
  matmul.io.bias_data := io.bias_data
  matmul.io.start := false.B
  matmul.io.rst_hard := false.B

  // Default: matmul drives memory ports
  io.weight_addr := matmul.io.weight_addr
  io.act_rd_addr := matmul.io.activation_addr
  io.output_rd_addr := matmul.io.output_rd_addr
  io.output_wr_addr := matmul.io.output_wr_addr
  io.output_wen := matmul.io.output_wen
  io.bias_addr := matmul.io.bias_addr

  // Activation function bank
  actFunc.io.in := matmul.io.output_data_wr
  actFunc.io.bias := matmul.io.bias_values
  actFunc.io.bias_en := matmul.io.bias_en
  actFunc.io.func := ActivationFunc.IDENTITY
  actFunc.io.shift := 0.U
  actFunc.io.relu6_threshold := 0.S

  // Output write data: apply activation on last K tile
  val writeData = Wire(Vec(n, SInt(32.W)))
  when(matmul.io.isLastK) {
    writeData := actFunc.io.out
  }.otherwise {
    writeData := matmul.io.output_data_wr
  }
  io.output_data_wr := writeData

  // Activation write port defaults
  io.act_wr_addr := 0.U
  io.act_wr_data := VecInit(Seq.fill(n)(0.S(8.W)))
  io.act_wr_en := false.B

  // Matmul base addresses (overridden per state)
  matmul.io.weight_base_addr := 0.U
  matmul.io.activation_base_addr := 0.U
  matmul.io.output_base_addr := 0.U
  matmul.io.bias_base_addr := 0.U
  matmul.io.M := 0.U
  matmul.io.K := 0.U
  matmul.io.N := 0.U

  // Status
  io.busy := state =/= SeqState.IDLE && state =/= SeqState.DONE
  io.done := state === SeqState.DONE
  io.result_base := readBufBase // after last layer, result is in the last write buffer which became readBuf
  io.result_M := curConfig.M
  io.result_N := curConfig.N

  switch(state) {
    is(SeqState.IDLE) {
      when(io.start) {
        numLayers := io.num_layers
        layerIdx := 0.U
        readBufBase := io.input_base
        bufferBBase := io.buffer_b_base
        writeBufBase := io.buffer_b_base
        state := SeqState.READ_CONFIG
      }
    }

    is(SeqState.READ_CONFIG) {
      // Read layer config (combinational Mem read)
      curConfig := configMem.read(layerIdx)
      state := SeqState.CONFIGURE
    }

    is(SeqState.CONFIGURE) {
      // Set up matmul controller
      matmul.io.weight_base_addr := curConfig.weight_base
      matmul.io.activation_base_addr := readBufBase
      matmul.io.output_base_addr := 0.U // output goes to output memory at base 0
      matmul.io.bias_base_addr := curConfig.bias_base
      matmul.io.M := curConfig.M
      matmul.io.K := curConfig.K
      matmul.io.N := curConfig.N

      // Configure activation function
      actFunc.io.func := curConfig.activation.asTypeOf(ActivationFunc())
      actFunc.io.shift := curConfig.shift
      actFunc.io.relu6_threshold := curConfig.relu6_thresh

      // Start matmul
  when(!matmul.io.busy) {
    matmul.io.start := true.B
    state := SeqState.RUN_LAYER
  }
    }

    is(SeqState.RUN_LAYER) {
      // Keep config valid while matmul runs
      matmul.io.weight_base_addr := curConfig.weight_base
      matmul.io.activation_base_addr := readBufBase
      matmul.io.output_base_addr := 0.U
      matmul.io.bias_base_addr := curConfig.bias_base
      matmul.io.M := curConfig.M
      matmul.io.K := curConfig.K
      matmul.io.N := curConfig.N

      actFunc.io.func := curConfig.activation.asTypeOf(ActivationFunc())
      actFunc.io.shift := curConfig.shift
      actFunc.io.relu6_threshold := curConfig.relu6_thresh

      when(!matmul.io.busy) {
        // Matmul done, check if we need to copy to activation buffer
        when(layerIdx === numLayers - 1.U) {
          // Last layer — no copy needed, we're done
          state := SeqState.DONE
        }.otherwise {
          // Need to copy output (32-bit, clamped to int8) into activation buffer
          copyTileR := 0.U
          copyTileC := 0.U
          copyRow := 0.U
          copyTilesM := curConfig.M / n.U
          copyTilesN := curConfig.N / n.U
          state := SeqState.COPY_START
        }
      }
    }

    is(SeqState.COPY_START) {
      // Issue read address for output memory
      // Output is stored in tiled layout: (tR * tilesN + tC) * n + row
      val outAddr = (copyTileR * copyTilesN + copyTileC) * n.U + copyRow
      io.output_rd_addr := outAddr
      state := SeqState.COPY_READ
    }

    is(SeqState.COPY_READ) {
      // Data from output memory is available (1-cycle latency)
      // Clamp 32-bit to int8
      val outAddr = (copyTileR * copyTilesN + copyTileC) * n.U + copyRow
      io.output_rd_addr := outAddr // keep address stable

      for (c <- 0 until n) {
        val val32 = io.output_data_r(c)
        when(curConfig.clamp_en) {
          when(val32 > 127.S) {
            copyData(c) := 127.S(8.W)
          }.elsewhen(val32 < -128.S) {
            copyData(c) := -128.S(8.W)
          }.otherwise {
            copyData(c) := val32(7, 0).asSInt
          }
        }.otherwise {
          copyData(c) := val32(7, 0).asSInt
        }
      }
      state := SeqState.COPY_WRITE
    }

    is(SeqState.COPY_WRITE) {
      // Write clamped int8 values into activation buffer (write buffer)
      // Activation layout matches what matmul expects:
      // For the NEXT layer, this output becomes the activation input
      // Next layer's K = this layer's M (if feeding output rows as next input)
      // Actually: output tile (tR, tC) row r = result row [tR*n+r], cols [tC*n..tC*n+n-1]
      // Next layer reads activations as: tile (tK, tC) at base + (tK*tilesN+tC)*n + t
      // Where tK indexes rows and tC indexes column groups
      // So output row maps to activation row — same layout works if M_out becomes K_next
      val actAddr = writeBufBase + (copyTileR * copyTilesN + copyTileC) * n.U + copyRow
      io.act_wr_en := true.B
      io.act_wr_addr := actAddr
      io.act_wr_data := copyData

      // Advance through all rows and tiles
      copyRow := copyRow + 1.U
      when(copyRow === (n - 1).U) {
        copyRow := 0.U
        copyTileC := copyTileC + 1.U
        when(copyTileC === copyTilesN - 1.U) {
          copyTileC := 0.U
          copyTileR := copyTileR + 1.U
          when(copyTileR === copyTilesM - 1.U) {
            // Copy complete — advance to next layer
            layerIdx := layerIdx + 1.U

            // Swap buffers: next layer reads from where we just wrote
            val oldWrite = writeBufBase
            val oldRead = readBufBase
            readBufBase := oldWrite
            // Write buffer for next layer's output copy goes to the other buffer
            when(oldWrite === bufferBBase) {
              writeBufBase := io.input_base
            }.otherwise {
              writeBufBase := bufferBBase
            }

            state := SeqState.READ_CONFIG
          }.otherwise {
            state := SeqState.COPY_START
          }
        }.otherwise {
          state := SeqState.COPY_START
        }
      }.otherwise {
        state := SeqState.COPY_START
      }
    }

    is(SeqState.DONE) {
      // when(io.start) {
        state := SeqState.IDLE
      // }
    }
  }
// when(io.busy) {
//     printf(p"Seq State: ${state}, layer_idx: ${layerIdx}\n")
// }
}