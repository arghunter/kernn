import chisel3._
import chisel3.util._

object TiledState extends ChiselEnum {
  val INIT_WAIT, IDLE, LOAD_BIAS, LATCH_BIAS, START_TILE, WAIT_TILE, NEXT_TILE = Value
}

class TiledMatMulController(val n: Int = 4, val addr_width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val weight_addr = Output(UInt(addr_width.W))
    val weight_data = Input(Vec(n, SInt(8.W)))
    val activation_addr = Output(UInt(addr_width.W))
    val activation_data = Input(Vec(n, SInt(8.W)))
    val output_rd_addr = Output(UInt(addr_width.W))
    val output_data_r = Input(Vec(n, SInt(32.W)))
    val output_wr_addr = Output(UInt(addr_width.W))
    val output_data_wr = Output(Vec(n, SInt(32.W)))
    val output_wen = Output(Bool())

    val bias_base_addr = Input(UInt(addr_width.W))
    val bias_addr = Output(UInt(addr_width.W))
    val bias_data = Input(Vec(n, SInt(32.W)))
    val bias_values = Output(Vec(n, SInt(32.W)))
    val bias_en = Output(Bool())

    val M = Input(UInt(24.W))
    val K = Input(UInt(24.W))
    val N = Input(UInt(24.W))
    val weight_base_addr = Input(UInt(addr_width.W))
    val activation_base_addr = Input(UInt(addr_width.W))
    val output_base_addr = Input(UInt(addr_width.W))
    val start = Input(Bool())
    val busy = Output(Bool())
    val rst_hard = Input(Bool())

    val isLastK = Output(Bool())
    val stateOut = Output(TiledState())
  })

  val sysarr = Module(new SysArrayController(n, addr_width, addr_width, addr_width))
  val state = RegInit(TiledState.INIT_WAIT)
  io.stateOut := state

  val tileR = RegInit(0.U(24.W))
  val tileC = RegInit(0.U(24.W))
  val tileK = RegInit(0.U(24.W))
  val tilesM = RegInit(0.U(24.W))
  val tilesK = RegInit(0.U(24.W))
  val tilesN = RegInit(0.U(24.W))

  val wt_base = RegInit(0.U(addr_width.W))
  val act_base = RegInit(0.U(addr_width.W))
  val out_base = RegInit(0.U(addr_width.W))
  val bias_base = RegInit(0.U(addr_width.W))

  val biasReg = Reg(Vec(n, SInt(32.W)))
  val biasValid = RegInit(false.B)

  val weightTileBase = wt_base + (tileR * tilesK + tileK) * n.U
  val actTileBase = act_base + (tileK * tilesN + tileC) * n.U
  val outTileBase = out_base + (tileR * tilesN + tileC) * n.U

  sysarr.io.weight_data := io.weight_data
  sysarr.io.activation_data := io.activation_data
  sysarr.io.output_data_r := io.output_data_r
  sysarr.io.start := false.B
  sysarr.io.rst_hard := false.B
  sysarr.io.weight_base_addr := weightTileBase
  sysarr.io.activation_base_addr := actTileBase
  sysarr.io.output_base_addr := outTileBase
  sysarr.io.accumulate := tileK =/= 0.U

  io.weight_addr := sysarr.io.weight_addr
  io.activation_addr := sysarr.io.activation_addr
  io.output_rd_addr := sysarr.io.output_rd_addr
  io.output_wr_addr := sysarr.io.output_wr_addr
  io.output_data_wr := sysarr.io.output_data_wr
  io.output_wen := sysarr.io.output_wen
  io.busy := state =/= TiledState.IDLE
  io.isLastK := tileK === tilesK - 1.U

  io.bias_addr := 0.U
  io.bias_values := biasReg
  io.bias_en := biasValid && (tileK === tilesK - 1.U)

  switch(state) {
    is(TiledState.INIT_WAIT) {
      when(!sysarr.io.busy) {
        state := TiledState.IDLE
      }
    }

    is(TiledState.IDLE) {
      when(io.start) {
        tilesM := io.M / n.U
        tilesK := io.K / n.U
        tilesN := io.N / n.U
        wt_base := io.weight_base_addr
        act_base := io.activation_base_addr
        out_base := io.output_base_addr
        bias_base := io.bias_base_addr
        tileR := 0.U
        tileC := 0.U
        tileK := 0.U
        biasValid := false.B
        state := TiledState.LOAD_BIAS
      }
    }

    is(TiledState.LOAD_BIAS) {
      // Issue read address — data arrives next cycle
      io.bias_addr := bias_base + tileC
      state := TiledState.LATCH_BIAS
    }

    is(TiledState.LATCH_BIAS) {
      // Latch bias data that arrived from previous cycle's read
      for (c <- 0 until n) {
        biasReg(c) := io.bias_data(c)
      }
      biasValid := true.B
      state := TiledState.START_TILE
    }

    is(TiledState.START_TILE) {
      sysarr.io.start := true.B
      state := TiledState.WAIT_TILE
    }

    is(TiledState.WAIT_TILE) {
      when(!sysarr.io.busy) {
        state := TiledState.NEXT_TILE
      }
    }

    is(TiledState.NEXT_TILE) {
      tileK := tileK + 1.U
      when(tileK === tilesK - 1.U) {
        tileK := 0.U
        tileC := tileC + 1.U
        when(tileC === tilesN - 1.U) {
          tileC := 0.U
          tileR := tileR + 1.U
          when(tileR === tilesM - 1.U) {
            state := TiledState.IDLE
          }.otherwise {
            state := TiledState.LOAD_BIAS
          }
        }.otherwise {
          state := TiledState.LOAD_BIAS
        }
      }.otherwise {
        state := TiledState.START_TILE
      }
    }
  }

  when(io.rst_hard) {
    state := TiledState.INIT_WAIT
    tileR := 0.U
    tileC := 0.U
    tileK := 0.U
    biasValid := false.B
  }
}