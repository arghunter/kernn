import chisel3._
import chisel3.util._

object SysState extends ChiselEnum {
  val INIT, INIT_1, IDLE, FEED, FLUSH, DRAIN_SKIP, DRAIN_READ, DRAIN_LAST = Value
}

class SysArrayController(val n: Int = 8, val weight_addr_width: Int = 16, val activation_addr_width: Int = 16, val output_addr_width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val weight_base_addr = Input(UInt(weight_addr_width.W))
    val weight_addr = Output(UInt(weight_addr_width.W))
    val weight_data = Input(Vec(n, SInt(8.W)))

    val activation_base_addr = Input(UInt(activation_addr_width.W))
    val activation_addr = Output(UInt(activation_addr_width.W))
    val activation_data = Input(Vec(n, SInt(8.W)))

    val output_base_addr = Input(UInt(output_addr_width.W))

    // Port A: read
    val output_rd_addr = Output(UInt(output_addr_width.W))
    val output_data_r = Input(Vec(n, SInt(32.W)))

    // Port B: write
    val output_wr_addr = Output(UInt(output_addr_width.W))
    val output_data_wr = Output(Vec(n, SInt(32.W)))
    val output_wen = Output(Bool())

    val accumulate = Input(Bool())
    val busy = Output(Bool())
    val start = Input(Bool())

    val rst_hard = Input(Bool())
  })

  val array = Module(new SysArray(n))
  val state = RegInit(SysState.INIT)
  val count = RegInit(0.U(24.W))

  io.busy := state =/= SysState.IDLE

  val weight_base_addr_reg = RegInit(0.U(weight_addr_width.W))
  val activation_base_addr_reg = RegInit(0.U(activation_addr_width.W))
  val output_base_addr_reg = RegInit(0.U(output_addr_width.W))
  val accumulate_reg = RegInit(false.B)

  val drainResult = Reg(Vec(n, SInt(32.W)))
  val drainAddr = Reg(UInt(output_addr_width.W))

  // Defaults
  io.weight_addr := weight_base_addr_reg + count
  io.activation_addr := activation_base_addr_reg + count
  io.output_rd_addr := 0.U
  io.output_wr_addr := 0.U
  io.output_data_wr := VecInit(Seq.fill(n)(0.S(32.W)))
  io.output_wen := false.B

  array.io.weightIn := VecInit(Seq.fill(n)(0.S(8.W)))
  array.io.actIn := VecInit(Seq.fill(n)(0.S(8.W)))
  array.io.resetIn := false.B

  switch(state) {
    is(SysState.INIT) {
      array.io.resetIn := true.B
      count := count + 1.U
      when(count === n.U) {
        count := 0.U
        state := SysState.INIT_1
      }
    }

is(SysState.INIT_1) {
      count := count + 1.U
      when(count === (2 * n).U) {
        count := 1.U
        // Override defaults to issue base + 0
        io.weight_addr := weight_base_addr_reg
        io.activation_addr := activation_base_addr_reg
        state := SysState.FEED
      }
    }

    is(SysState.IDLE) {
      when(io.start) {
        weight_base_addr_reg := io.weight_base_addr
        activation_base_addr_reg := io.activation_base_addr
        output_base_addr_reg := io.output_base_addr
        accumulate_reg := io.accumulate
        count := 0.U
        state := SysState.INIT
      }
    }

    is(SysState.FEED) {
      array.io.weightIn := io.weight_data
      array.io.actIn := io.activation_data
      count := count + 1.U
      when(count === n.U) {
        count := 0.U
        state := SysState.FLUSH
      }
    }

    is(SysState.FLUSH) {
      count := count + 1.U
      when(count === (2 * n - 1).U) {
        count := 1.U
        state := SysState.DRAIN_SKIP
      }
    }

    is(SysState.DRAIN_SKIP) {
      // Rising edge of resetIn — first drain row appears next cycle
      array.io.resetIn := true.B

      // Issue read address for first row's accumulation
      io.output_rd_addr := output_base_addr_reg + n.U - 1.U

      count := 1.U
      state := SysState.DRAIN_READ
    }

    is(SysState.DRAIN_READ) {
      array.io.resetIn := true.B

      // Port A: read data from address issued LAST cycle is now available
      // Port B: write the accumulated result
      io.output_wen := true.B
      io.output_wr_addr := output_base_addr_reg + n.U - count
      for (c <- 0 until n) {
        when(accumulate_reg) {
          io.output_data_wr(c) := array.io.actOut(c) + io.output_data_r(c)
        }.otherwise {
          io.output_data_wr(c) := array.io.actOut(c)
        }
      }

      // Issue read address for NEXT row's accumulation
      count := count + 1.U
      io.output_rd_addr := output_base_addr_reg + n.U - (count + 1.U)

      when(count === n.U) {
        count := 0.U
        state := SysState.IDLE
      }
    }
  }

  when(io.rst_hard) {
    state := SysState.INIT
    count := 0.U
  }
}