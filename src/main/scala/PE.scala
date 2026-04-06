
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage


class PE extends Module {
  val io = IO(new Bundle {
    val weightIn  = Input(SInt(8.W))
    val weightOut = Output(SInt(8.W))
    val actIn     = Input(SInt(32.W))
    val actOut    = Output(SInt(32.W))
    val resetIn   = Input(Bool())
    val resetOut  = Output(Bool())
  })


  val acc     = RegInit(0.S(32.W))
  val lastRst = RegNext(io.resetIn, false.B)

  io.weightOut := RegNext(io.weightIn)
  io.resetOut  := lastRst

when(io.resetIn && !lastRst) {
    io.actOut := acc
    acc := 0.S
}.elsewhen(io.resetIn && lastRst) {
    io.actOut := io.actIn
}.otherwise {
    io.actOut := RegNext(io.actIn)
    acc := acc + (io.weightIn * io.actIn(7, 0).asSInt)
}
}
