import chisel3._
import chisel3.util._
class UartCommandInterface(val n: Int = 4, val clockFreq: Int = 50000000, val baudRate: Int = 115200) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val txd = Output(Bool())

    val seq_start = Output(Bool())
    val seq_busy = Input(Bool())
    val seq_done = Input(Bool())
    val seq_num_layers = Output(UInt(8.W))
    val seq_input_base = Output(UInt(16.W))
    val seq_buffer_b_base = Output(UInt(16.W))
    val seq_result_base = Input(UInt(16.W))
    val seq_result_M = Input(UInt(16.W))
    val seq_result_N = Input(UInt(16.W))

    val config_wr_en = Output(Bool())
    val config_wr_idx = Output(UInt(4.W))
    val config_wr_data = Output(new LayerConfig)

    val wt_wr_en = Output(Bool())
    val wt_wr_addr = Output(UInt(16.W))
    val wt_wr_data = Output(Vec(n, SInt(8.W)))

    val bias_wr_en = Output(Bool())
    val bias_wr_addr = Output(UInt(16.W))
    val bias_wr_data = Output(Vec(n, SInt(32.W)))

    val act_wr_en = Output(Bool())
    val act_wr_addr = Output(UInt(16.W))
    val act_wr_data = Output(Vec(n, SInt(8.W)))

    val out_rd_addr = Output(UInt(16.W))
    val out_rd_data = Input(Vec(n, SInt(32.W)))

    val out_wr_en = Output(Bool())
    val out_wr_addr = Output(UInt(16.W))
    val out_wr_data = Output(Vec(n, SInt(32.W)))

    val busy = Output(Bool())
  })

  val rx = Module(new UartRx(clockFreq, baudRate))
  val tx = Module(new UartTx(clockFreq, baudRate))
  val parser = Module(new CommandParser(n))

  rx.io.rxd := io.rxd
  io.txd := tx.io.txd

  parser.io.rx_data := rx.io.data
  parser.io.rx_valid := rx.io.valid
  tx.io.data := parser.io.tx_data
  tx.io.valid := parser.io.tx_valid
  parser.io.tx_ready := tx.io.ready

  io.seq_start := parser.io.seq_start
  parser.io.seq_busy := io.seq_busy
  parser.io.seq_done := io.seq_done
  io.seq_num_layers := parser.io.seq_num_layers
  io.seq_input_base := parser.io.seq_input_base
  io.seq_buffer_b_base := parser.io.seq_buffer_b_base
  parser.io.seq_result_base := io.seq_result_base
  parser.io.seq_result_M := io.seq_result_M
  parser.io.seq_result_N := io.seq_result_N

  io.config_wr_en := parser.io.config_wr_en
  io.config_wr_idx := parser.io.config_wr_idx
  io.config_wr_data := parser.io.config_wr_data

  io.wt_wr_en := parser.io.wt_wr_en
  io.wt_wr_addr := parser.io.wt_wr_addr
  io.wt_wr_data := parser.io.wt_wr_data

  io.bias_wr_en := parser.io.bias_wr_en
  io.bias_wr_addr := parser.io.bias_wr_addr
  io.bias_wr_data := parser.io.bias_wr_data

  io.act_wr_en := parser.io.act_wr_en
  io.act_wr_addr := parser.io.act_wr_addr
  io.act_wr_data := parser.io.act_wr_data

  io.out_rd_addr := parser.io.out_rd_addr
  parser.io.out_rd_data := io.out_rd_data

  io.out_wr_en := parser.io.out_wr_en
  io.out_wr_addr := parser.io.out_wr_addr
  io.out_wr_data := parser.io.out_wr_data

  io.busy := parser.io.busy
}