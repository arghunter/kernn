import chisel3._
import _root_.circt.stage.ChiselStage

object SysArrayControllerGen extends App {
  ChiselStage.emitSystemVerilogFile(
    new SysArrayController(8),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-lowering-options=disallowLocalVariables,disallowPackedArrays"
    ),
    args = Array("--target-dir", "generated")
  )
}