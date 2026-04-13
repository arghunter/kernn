import chisel3._
import chisel3.util._

object ActivationFunc extends ChiselEnum {
  val IDENTITY, RELU, LEAKY_RELU, RELU6 = Value
}

class ActivationFunctionBank(val n: Int = 4, val width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, SInt(width.W)))
    val out = Output(Vec(n, SInt(width.W)))
    val bias = Input(Vec(n, SInt(width.W)))
    val bias_en = Input(Bool())
    val func = Input(ActivationFunc())
    val shift = Input(UInt(5.W))
    val relu6_threshold = Input(SInt(width.W))
    val clamp_en = Input(Bool())
  })

  for (i <- 0 until n) {
    val biased = Mux(io.bias_en, io.in(i) + io.bias(i), io.in(i))
    val shifted = (biased >> io.shift).asSInt
    val result = WireDefault(shifted)

    switch(io.func) {
      is(ActivationFunc.IDENTITY) { result := shifted }
      is(ActivationFunc.RELU) {
        when(shifted < 0.S) { result := 0.S }
      }
      is(ActivationFunc.LEAKY_RELU) {
        when(shifted < 0.S) { result := shifted >> 3 }
      }
      is(ActivationFunc.RELU6) {
        when(shifted < 0.S) { result := 0.S }
        .elsewhen(shifted > io.relu6_threshold) { result := io.relu6_threshold }
      }
    }
    val clamped = Mux(result > 127.S, 127.S, Mux(result < -128.S, -128.S, result))
    io.out(i) := Mux(io.clamp_en, clamped,result)
  }
}