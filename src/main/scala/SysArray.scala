import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage


//btw i think that if i ping pong accumulators i can signficant;y up throughput, but thats not really the bottle neck rn
class SysArray(val n: Int = 12) extends Module {
  val io = IO(new Bundle {
    val weightIn  = Input(Vec(n, SInt(8.W)))   
    val actIn     = Input(Vec(n, SInt(8.W)))  
    val actOut    = Output(Vec(n, SInt(32.W))) 
    val resetIn   = Input(Bool())              
    val resetOut  = Output(Vec(n, Bool()))     
  })


  val array = Seq.tabulate(n, n)((r, c) => Module(new PE))

  val weightSkew = Seq.tabulate(n) { r =>
    val chain = Seq.iterate(io.weightIn(r), r + 1)(RegNext(_))
    chain.last
  }

  val actSkew = Seq.tabulate(n) { c =>
    val chain = Seq.iterate(io.actIn(c), c + 1)(RegNext(_))
    chain.last
  }

  val rst = RegNext(io.resetIn);
  for (r <- 0 until n) {
    for (c <- 0 until n) {
      val pe = array(r)(c)
      pe.io.weightIn := (if (c == 0) weightSkew(r) else array(r)(c-1).io.weightOut)
      pe.io.actIn    := (if (r == 0) actSkew(c).pad(32).asSInt else array(r-1)(c).io.actOut)
      pe.io.resetIn := (if (r == n-1) rst else array(r+1)(c).io.resetOut)
    }
  }

  for (c <- 0 until n) {
      io.actOut(c)   := array(n-1)(c).io.actOut
      io.resetOut(c) := array(0)(c).io.resetOut
  }

}