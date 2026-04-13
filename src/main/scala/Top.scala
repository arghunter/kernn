import chisel3._
import chisel3.util._

class AlchitryTop(val n: Int = 4, val clockFreq: Int = 100000000, val baudRate: Int = 115200) extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(8.W))
    val usb_rx = Input(Bool())
    val usb_tx = Output(Bool())
  })

  // === UART ===
  val rx = Module(new UartRx(clockFreq, baudRate))
  val tx = Module(new UartTx(clockFreq, baudRate))
  rx.io.rxd := io.usb_rx
  io.usb_tx := tx.io.txd

  // === Command Parser ===
  val parser = Module(new CommandParser(n))
  parser.io.rx_data := rx.io.data
  parser.io.rx_valid := rx.io.valid
  tx.io.data := parser.io.tx_data
  tx.io.valid := parser.io.tx_valid
  parser.io.tx_ready := tx.io.ready

  // === Layer Sequencer ===
  val seq = Module(new LayerSequencer(n, 16, 16))

  // Parser ↔ Sequencer control
  seq.io.start := parser.io.seq_start
  parser.io.seq_busy := seq.io.busy
  parser.io.seq_done := seq.io.done
  seq.io.num_layers := parser.io.seq_num_layers
  seq.io.input_base := parser.io.seq_input_base
  seq.io.buffer_b_base := parser.io.seq_buffer_b_base
  parser.io.seq_result_base := seq.io.result_base
  parser.io.seq_result_M := seq.io.result_M
  parser.io.seq_result_N := seq.io.result_N

  // Parser ↔ Config
  seq.io.config_wr_en := parser.io.config_wr_en
  seq.io.config_wr_idx := parser.io.config_wr_idx
  seq.io.config_wr_data := parser.io.config_wr_data

  // === Memories ===
  val weightMem = SyncReadMem(16384, Vec(n, SInt(8.W)))
  val actMem = SyncReadMem(4096, Vec(n, SInt(8.W)))
  val outMem = SyncReadMem(4096, Vec(n, SInt(32.W)))
  val biasMem = SyncReadMem(256, Vec(n, SInt(32.W)))
  

  // === Weight Memory ===
  // 1 Write, 1 Read -> Infers properly
  when(parser.io.wt_wr_en) {
    weightMem.write(parser.io.wt_wr_addr, parser.io.wt_wr_data)
  }
  seq.io.weight_data := weightMem.read(seq.io.weight_addr)

  // === Activation Memory ===
  // Multiplexing the write port to prevent generating a 3rd port
// === Activation Memory ===
val act_wr_en   = seq.io.act_wr_en || parser.io.act_wr_en
val act_wr_addr = Mux(seq.io.act_wr_en, seq.io.act_wr_addr, parser.io.act_wr_addr)
val act_wr_data = Mux(seq.io.act_wr_en, seq.io.act_wr_data, parser.io.act_wr_data)

when(act_wr_en) {
  actMem.write(act_wr_addr, act_wr_data)
}
seq.io.act_rd_data := actMem.read(seq.io.act_rd_addr)

  
  // Single read port
  seq.io.act_rd_data := actMem.read(seq.io.act_rd_addr)

  // === Bias Memory ===
  // 1 Write, 1 Read -> Infers properly
  when(parser.io.bias_wr_en) {
    biasMem.write(parser.io.bias_wr_addr, parser.io.bias_wr_data)
  }
  seq.io.bias_data := biasMem.read(seq.io.bias_addr)

  // === Output Memory ===
  // Multiplexing the write port
// === Output Memory ===
val out_wr_en   = seq.io.output_wen || parser.io.out_wr_en
val out_wr_addr = Mux(seq.io.output_wen, seq.io.output_wr_addr, parser.io.out_wr_addr)
val out_wr_data = Mux(seq.io.output_wen, seq.io.output_data_wr, parser.io.out_wr_data)

when(out_wr_en) {
  outMem.write(out_wr_addr, out_wr_data)
}

val out_rd_addr = Mux(seq.io.busy, seq.io.output_rd_addr, parser.io.out_rd_addr)
val out_rd_data = outMem.read(out_rd_addr)
seq.io.output_data_r := out_rd_data
parser.io.out_rd_data := out_rd_data

  // Multiplexing the read port
  // Assuming the sequencer takes priority while busy, and parser reads when done
  // val out_rd_addr = Mux(seq.io.busy, seq.io.output_rd_addr, parser.io.out_rd_addr)
  // val out_rd_data = outMem.read(out_rd_addr)

  // // Route the shared single-port read data back to both modules
  // seq.io.output_data_r := out_rd_data
  // parser.io.out_rd_data := out_rd_data

  // === LED Status ===
  io.led := Cat(
    seq.io.done,                          // led[7]: inference complete
    seq.io.busy,                          // led[6]: sequencer running
    parser.io.busy,                       // led[5]: parser busy
    rx.io.valid,                          // led[4]: UART receiving
    0.U(4.W)                              // led[3:0]: unused
  )
}