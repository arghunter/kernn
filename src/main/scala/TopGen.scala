import chisel3._
import _root_.circt.stage.ChiselStage

object AlchitryTopGen extends App {
  ChiselStage.emitSystemVerilogFile(
    new AlchitryTop(n = 4, clockFreq = 100000000, baudRate = 115200),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-lowering-options=disallowLocalVariables,disallowPackedArrays"
    ),
    args = Array("--target-dir", "generated")
  )
}