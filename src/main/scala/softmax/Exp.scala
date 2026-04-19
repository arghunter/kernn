package softmax


import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage


class PE extends Module {
  val io = IO(new Bundle {
    val valIn  = Input(SInt(8.W))
    val valOut = Output(SInt(16.W)) // Using Q1.15, Chisel FixedPoint is cool but experiemtnal
    val validIn     = Input(Bool())
  })

  //e^x = 2^(x * log2(e)) = 2^(x * 1.4427)
  val nexp = ((valIn * 185.S) >> 7.U)(15,0);
  val nexp_reg = RegInit(0.S(16.W));

  val lut = Module(new Pow2LUT);
  lut.io.frac := nexp_reg(7,0);

  val mantissa = RegNext(Cat(1.U,lut.io.result),0.U);

  val shifted = RegNext({mantissa,7'b0}>>(Mux(-nexp_reg>15.S, 15.S,(-n)(3,0))))
  io.valOut := shifted;

  
  when(io.validIn){
    nexp_reg := nexp;
  }




  
}
