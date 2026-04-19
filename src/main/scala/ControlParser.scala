import chisel3._
import chisel3.util._

object CmdState extends ChiselEnum {
  val IDLE, READ_CMD,
      READ_NUM_LAYERS, READ_CONFIG_BYTE,
      READ_WEIGHT_HEADER, READ_WEIGHT_DATA,
      READ_BIAS_HEADER, READ_BIAS_DATA,
      READ_ACT_HEADER, READ_ACT_DATA,
      READ_RUN_HEADER,
      READ_OUTPUT_HEADER, SEND_READ_ADDR, SEND_WAIT_MEM, SEND_BYTE, SEND_WAIT_TX,
      RUN_WAIT, RUN_WAIT_START, RUN_SEND_ACK = Value 
}

class CommandParser(val n: Int = 4) extends Module {
  val io = IO(new Bundle {
    val rx_data = Input(UInt(8.W))
    val rx_valid = Input(Bool())
    val tx_data = Output(UInt(8.W))
    val tx_valid = Output(Bool())
    val tx_ready = Input(Bool())

    val seq_start = Output(Bool())
    val seq_busy = Input(Bool())
    val seq_done = Input(Bool())
    val seq_num_layers = Output(UInt(8.W))
    val seq_input_base = Output(UInt(16.W))
    val seq_buffer_b_base = Output(UInt(16.W))
    val seq_result_base = Input(UInt(16.W))
    val seq_result_M = Input(UInt(16.W))
    val seq_result_N = Input(UInt(16.W))

    val config_wr_en = Output(Bool())
    val config_wr_idx = Output(UInt(4.W))
    val config_wr_data = Output(new LayerConfig)

    val wt_wr_en = Output(Bool())
    val wt_wr_addr = Output(UInt(16.W))
    val wt_wr_data = Output(Vec(n, SInt(8.W)))

    val bias_wr_en = Output(Bool())
    val bias_wr_addr = Output(UInt(16.W))
    val bias_wr_data = Output(Vec(n, SInt(32.W)))

    val act_wr_en = Output(Bool())
    val act_wr_addr = Output(UInt(16.W))
    val act_wr_data = Output(Vec(n, SInt(8.W)))

    val out_rd_addr = Output(UInt(16.W))
    val out_rd_data = Input(Vec(n, SInt(32.W)))

    val out_wr_en = Output(Bool())
    val out_wr_addr = Output(UInt(16.W))
    val out_wr_data = Output(Vec(n, SInt(32.W)))

    val busy = Output(Bool())
    val stateOut = Output(CmdState())
  })

  val state = RegInit(CmdState.IDLE)
  io.stateOut := state

  val byteBuffer = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))
  val byteIdx = RegInit(0.U(8.W))

  val numLayers = RegInit(0.U(8.W))
  val configIdx = RegInit(0.U(4.W))
  val configByteIdx = RegInit(0.U(8.W))
  val configReg = Reg(new LayerConfig)

  val loadAddr = RegInit(0.U(16.W))
  val loadRemaining = RegInit(0.U(16.W))
  val loadByteIdx = RegInit(0.U(8.W))

  val dataBuf = RegInit(VecInit(Seq.fill(n)(0.U(8.W))))
  val biasByteBuf = RegInit(VecInit(Seq.fill(n * 4)(0.U(8.W))))

  val sendAddr = RegInit(0.U(16.W))
  val sendRemaining = RegInit(0.U(16.W))
  val sendByteIdx = RegInit(0.U(8.W))
  val sendDataBuf = RegInit(VecInit(Seq.fill(n * 4)(0.U(8.W))))

  val inputBase    = RegInit(0.U(16.W))
  val bufferBBase  = RegInit(0.U(16.W))

  val seqStartPending = RegInit(false.B)

  // Defaults
  io.seq_start         := seqStartPending
  seqStartPending      := false.B        

  io.seq_num_layers    := numLayers
  io.seq_input_base    := inputBase
  io.seq_buffer_b_base := bufferBBase
  io.config_wr_en      := false.B
  io.config_wr_idx     := configIdx
  io.config_wr_data    := configReg
  io.wt_wr_en          := false.B
  io.wt_wr_addr        := loadAddr
  io.wt_wr_data        := VecInit(dataBuf.map(_.asSInt))
  io.bias_wr_en        := false.B
  io.bias_wr_addr      := loadAddr
  io.bias_wr_data      := VecInit(Seq.fill(n)(0.S(32.W)))
  io.act_wr_en         := false.B
  io.act_wr_addr       := loadAddr
  io.act_wr_data       := VecInit(dataBuf.map(_.asSInt))
  io.out_rd_addr       := sendAddr
  io.out_wr_en         := false.B
  io.out_wr_addr       := 0.U
  io.out_wr_data       := VecInit(Seq.fill(n)(0.S(32.W)))
  io.busy              := state =/= CmdState.IDLE
  io.tx_valid          := false.B
  io.tx_data           := 0.U

  switch(state) {
    is(CmdState.IDLE) {
      when(io.rx_valid) {
        byteBuffer(0) := io.rx_data
        byteIdx       := 0.U
        state         := CmdState.READ_CMD
      }
    }

    is(CmdState.READ_CMD) {
      switch(byteBuffer(0)) {
        is(0x01.U) { state := CmdState.READ_NUM_LAYERS }
        is(0x02.U) { byteIdx := 0.U; state := CmdState.READ_WEIGHT_HEADER }
        is(0x03.U) { byteIdx := 0.U; state := CmdState.READ_BIAS_HEADER }
        is(0x04.U) { byteIdx := 0.U; state := CmdState.READ_ACT_HEADER }
        is(0x05.U) { byteIdx := 0.U; state := CmdState.READ_RUN_HEADER }
        is(0x06.U) { byteIdx := 0.U; state := CmdState.READ_OUTPUT_HEADER
        printf("READING OUTPUT HEADER\n") }
      }
    }

    is(CmdState.READ_NUM_LAYERS) {
      when(io.rx_valid) {
        numLayers    := io.rx_data
        configIdx    := 0.U
        configByteIdx := 0.U
        state        := CmdState.READ_CONFIG_BYTE
      }
    }

    is(CmdState.READ_CONFIG_BYTE) {
      when(io.rx_valid) {
        byteBuffer(configByteIdx) := io.rx_data
        val nextByteIdx = configByteIdx + 1.U
        configByteIdx := nextByteIdx

when(configByteIdx === 14.U) {
  val parsedConfig = Wire(new LayerConfig)
  parsedConfig.weight_base  := Cat(byteBuffer(0), byteBuffer(1))
  parsedConfig.bias_base    := Cat(byteBuffer(2), byteBuffer(3))
  parsedConfig.M            := Cat(byteBuffer(4), byteBuffer(5))
  parsedConfig.K            := Cat(byteBuffer(6), byteBuffer(7))
  parsedConfig.N            := Cat(byteBuffer(8), byteBuffer(9))
  parsedConfig.activation   := byteBuffer(10)(1, 0)
  parsedConfig.shift        := byteBuffer(11)(4, 0)
  parsedConfig.relu6_thresh := Cat(byteBuffer(12), byteBuffer(13)).asSInt
  parsedConfig.clamp_en     := io.rx_data(0).asBool

  io.config_wr_data := parsedConfig
  io.config_wr_en   := true.B
  io.config_wr_idx  := configIdx

  configReg := parsedConfig

  configByteIdx := 0.U
  val nextConfig = configIdx + 1.U
  configIdx := nextConfig
  when(nextConfig === numLayers) {
    state := CmdState.IDLE
  }
}
      }
    }

    is(CmdState.READ_WEIGHT_HEADER) {
      when(io.rx_valid) {
        byteBuffer(byteIdx) := io.rx_data
        byteIdx := byteIdx + 1.U
        when(byteIdx === 3.U) {
          loadAddr      := Cat(byteBuffer(0), byteBuffer(1))
          loadRemaining := Cat(byteBuffer(2), io.rx_data)
          loadByteIdx   := 0.U
          state         := CmdState.READ_WEIGHT_DATA
        }
      }
    }

    is(CmdState.READ_WEIGHT_DATA) {
      when(io.rx_valid) {
        dataBuf(loadByteIdx) := io.rx_data
        loadByteIdx          := loadByteIdx + 1.U

        when(loadByteIdx === (n - 1).U) {
          io.wt_wr_en  := true.B
          io.wt_wr_addr := loadAddr
          for (i <- 0 until n) {
            if (i == n - 1) {
              io.wt_wr_data(i) := io.rx_data.asSInt
            } else {
              io.wt_wr_data(i) := dataBuf(i).asSInt
            }
          }
          loadAddr      := loadAddr + 1.U
          loadRemaining := loadRemaining - 1.U
          loadByteIdx   := 0.U
          when(loadRemaining === 1.U) {
            state := CmdState.IDLE
          }
        }
      }
    }

    is(CmdState.READ_BIAS_HEADER) {
      when(io.rx_valid) {
        byteBuffer(byteIdx) := io.rx_data
        byteIdx := byteIdx + 1.U
        when(byteIdx === 3.U) {
          loadAddr      := Cat(byteBuffer(0), byteBuffer(1))
          loadRemaining := Cat(byteBuffer(2), io.rx_data)
          loadByteIdx   := 0.U
          state         := CmdState.READ_BIAS_DATA
        }
      }
    }

    is(CmdState.READ_BIAS_DATA) {
      when(io.rx_valid) {
        biasByteBuf(loadByteIdx) := io.rx_data
        loadByteIdx              := loadByteIdx + 1.U

        when(loadByteIdx === (n * 4 - 1).U) {
          io.bias_wr_en  := true.B
          io.bias_wr_addr := loadAddr
          for (i <- 0 until n) {
            val b0 = biasByteBuf(i * 4)
            val b1 = biasByteBuf(i * 4 + 1)
            val b2 = biasByteBuf(i * 4 + 2)
            val b3 = if (i * 4 + 3 == n * 4 - 1) io.rx_data else biasByteBuf(i * 4 + 3)
            io.bias_wr_data(i) := Cat(b3, b2, b1, b0).asSInt
          }
          loadAddr      := loadAddr + 1.U
          loadRemaining := loadRemaining - 1.U
          loadByteIdx   := 0.U
          when(loadRemaining === 1.U) {
            state := CmdState.IDLE
          }
        }
      }
    }

    is(CmdState.READ_ACT_HEADER) {
      when(io.rx_valid) {
        byteBuffer(byteIdx) := io.rx_data
        byteIdx := byteIdx + 1.U
        when(byteIdx === 3.U) {
          loadAddr      := Cat(byteBuffer(0), byteBuffer(1))
          loadRemaining := Cat(byteBuffer(2), io.rx_data)
          loadByteIdx   := 0.U
          state         := CmdState.READ_ACT_DATA
        }
      }
    }

    is(CmdState.READ_ACT_DATA) {
      when(io.rx_valid) {
        dataBuf(loadByteIdx) := io.rx_data
        loadByteIdx          := loadByteIdx + 1.U

        when(loadByteIdx === (n - 1).U) {
          io.act_wr_en  := true.B
          io.act_wr_addr := loadAddr
          for (i <- 0 until n) {
            if (i == n - 1) {
              io.act_wr_data(i) := io.rx_data.asSInt
            } else {
              io.act_wr_data(i) := dataBuf(i).asSInt
            }
          }
          loadAddr      := loadAddr + 1.U
          loadRemaining := loadRemaining - 1.U
          loadByteIdx   := 0.U
          when(loadRemaining === 1.U) {
            state := CmdState.IDLE
          }
        }
      }
    }

  
  is(CmdState.READ_RUN_HEADER) {
      when(io.rx_valid) {
        byteBuffer(byteIdx) := io.rx_data
        byteIdx := byteIdx + 1.U
        when(byteIdx === 3.U) {
          inputBase := Cat(byteBuffer(0), byteBuffer(1))
          bufferBBase := Cat(byteBuffer(2), io.rx_data)
          seqStartPending := true.B
          state := CmdState.RUN_WAIT_START
        }
      }
    }

    is(CmdState.RUN_WAIT_START) {
      // when(io.seq_busy) {
        state := CmdState.RUN_WAIT
      // }
    }

is(CmdState.RUN_WAIT) {
  when(io.seq_done || !io.seq_busy) {
    state := CmdState.RUN_SEND_ACK
  }
}

    is(CmdState.RUN_SEND_ACK) {
      io.tx_data := 0xAA.U
      io.tx_valid := true.B
      when(io.tx_ready) {
        state := CmdState.IDLE
      }
    }

    is(CmdState.READ_OUTPUT_HEADER) {
      when(io.rx_valid) {
        byteBuffer(byteIdx) := io.rx_data
        byteIdx             := byteIdx + 1.U
        when(byteIdx === 3.U) {
          sendAddr      := Cat(byteBuffer(0), byteBuffer(1))
          sendRemaining := Cat(byteBuffer(2), io.rx_data)
          state         := CmdState.SEND_READ_ADDR
          // printf(p"SENDING READ ADDR addr ${sendAddr}, remaining ${sendRemaining}")
        }
      }
    }

    is(CmdState.SEND_READ_ADDR) {
      io.out_rd_addr := sendAddr
      state          := CmdState.SEND_WAIT_MEM
    }

    is(CmdState.SEND_WAIT_MEM) {
      io.out_rd_addr := sendAddr
      for (i <- 0 until n) {
        val val32 = io.out_rd_data(i).asUInt
        sendDataBuf(i * 4)     := val32(7, 0)
        sendDataBuf(i * 4 + 1) := val32(15, 8)
        sendDataBuf(i * 4 + 2) := val32(23, 16)
        sendDataBuf(i * 4 + 3) := val32(31, 24)
      }
      sendByteIdx := 0.U
      state       := CmdState.SEND_BYTE
    }

    is(CmdState.SEND_BYTE) {
      io.tx_data  := sendDataBuf(sendByteIdx)
      io.tx_valid := true.B
      when(io.tx_ready) {
        sendByteIdx := sendByteIdx + 1.U
        when(sendByteIdx === (n * 4 - 1).U) {
          sendAddr      := sendAddr + 1.U
          sendRemaining := sendRemaining - 1.U
          when(sendRemaining === 1.U) {
            state := CmdState.SEND_WAIT_TX
          }.otherwise {
            state := CmdState.SEND_READ_ADDR
          }
        }
      }
    }

    is(CmdState.SEND_WAIT_TX) {
      // printf("WAITING TX PLS")
      io.tx_data  := 0xFF.U
      io.tx_valid := true.B
      when(io.tx_ready) {
        state := CmdState.IDLE
      }
    }
  }
}