import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

object SysState extends ChiselEnum {
  val INIT, INIT_1, IDLE, PREFEED, FEED, FLUSH, DRAIN, WRITE = Value
}

class SysArrayController(val n: Int = 8, val weight_addr_width: Int = 16, val activation_addr_width: Int = 16, val output_addr_width: Int = 16) extends Module {
  val io = IO(new Bundle {
    val weight_base_addr = Input(UInt(weight_addr_width.W))
    val weight_addr = Output(UInt(weight_addr_width.W))
    val weight_data = Input(Vec(n, SInt(8.W)))

    val activation_base_addr = Input(UInt(activation_addr_width.W))
    val activation_addr = Output(UInt(activation_addr_width.W))
    val activation_data = Input(Vec(n, SInt(8.W)))

    val output_base_addr = Input(UInt(activation_addr_width.W))
    val output_addr = Output(UInt(activation_addr_width.W))
    val output_data_wr = Output(Vec(n, SInt(32.W)))
    val output_data_r = Input(Vec(n, SInt(32.W)))
    val output_wen = Output(Bool())

    val weight_dims = Input(Vec(2, UInt(24.W)))
    val activation_dims = Input(Vec(2, UInt(24.W)))

    val busy = Output(Bool())
    val start = Input(Bool())

    val rst_hard   = Input(Bool())              
   
  })

  val array = Module(new SysArray(n))

  val state = RegInit(SysState.INIT)
  val count = RegInit(0.U(24.W))
  io.busy := state =/= SysState.IDLE

  val weight_base_addr_reg = RegInit(0.U(weight_addr_width.W))
  val activation_base_addr_reg = RegInit(0.U(weight_addr_width.W))
  val output_base_addr_reg = RegInit(0.U(weight_addr_width.W))
  

  io.weight_addr := weight_base_addr_reg + count;
  io.activation_addr := activation_base_addr_reg + count;
  io.output_addr := output_base_addr_reg +n.U - count;
  io.output_data_wr := array.io.actOut


  array.io.weightIn := VecInit(Seq.fill(n)(0.S(8.W)))
  array.io.actIn := VecInit(Seq.fill(n)(0.S(8.W)))
  array.io.resetIn := false.B
  io.output_wen := false.B



  switch(state){

    is(SysState.INIT) {
      array.io.resetIn := true.B
      count := count + 1.U
      when(count === (n.U)) {
        array.io.resetIn := false.B
        count := 0.U
        state:= SysState.INIT_1
      }

    }
    is(SysState.INIT_1){
      array.io.resetIn := false.B
      count := count + 1.U
      when(count === ((2*n).U)) {
        count := 0.U
        state:= SysState.IDLE
      }
    }

    is(SysState.IDLE){
      when(io.start){
        weight_base_addr_reg := io.weight_base_addr
        activation_base_addr_reg := io.activation_base_addr
        output_base_addr_reg := io.output_base_addr
        state := SysState.FEED
        count:=0.U;
        
      }
    }

    is(SysState.FEED){
      array.io.weightIn := io.weight_data
      array.io.actIn := io.activation_data
      count := count + 1.U;
      when(count === ((n-1).U)){
        count := 0.U
        state := SysState.FLUSH
      }

    }
    is(SysState.FLUSH){
      count := count + 1.U;
      when(count === ((2*n-1).U)){
        count := 0.U
        state := SysState.DRAIN

      }
    }

    is(SysState.DRAIN) {
        array.io.resetIn := true.B
        count := count + 1.U
        when(count === 0.U) {
        
        }.otherwise {
          io.output_wen := true.B
          io.output_addr := output_base_addr_reg + n.U - count
        }
        when(count === n.U) {
          count := 0.U
          state := SysState.IDLE
        }
    }




  }

  

}