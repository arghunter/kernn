// import chisel3._
// import chisel3.util._
// import _root_.circt.stage.ChiselStage

// object TiledState extends ChiselEnum {
//   val INIT, INIT_1, IDLE, FEED, FLUSH, DRAIN_WAIT, DRAIN, NEXT_TILE = Value
// }

// class MatMulController(val n: Int = 8, val addr_width: Int = 16) extends Module {
//   val io = IO(new Bundle {
//     val weight_base_addr = Input(UInt(addr_width.W))
//     val weight_addr = Output(UInt(addr_width.W))
//     val weight_data = Input(Vec(n, SInt(8.W)))

//     val activation_base_addr = Input(UInt(addr_width.W))
//     val activation_addr = Output(UInt(addr_width.W))
//     val activation_data = Input(Vec(n, SInt(8.W)))

//     val output_base_addr = Input(UInt(addr_width.W))
//     val output_addr = Output(UInt(addr_width.W))
//     val output_data_wr = Output(Vec(n, SInt(32.W)))
//     val output_data_r = Input(Vec(n, SInt(32.W)))
//     val output_wen = Output(Bool())

//     val M = Input(UInt(24.W))
//     val K = Input(UInt(24.W))
//     val N = Input(UInt(24.W))

//     val busy = Output(Bool())
//     val start = Input(Bool())

//     val rst_hard = Input(Bool())
//   })

//   val sysarr = Module(new SysArrayController(n, addr_width, addr_width, addr_width))

//   val state = RegInit(TiledState.IDLE)

//   val tileR = RegInit(0.U(24.W))
//   val tileC = RegInit(0.U(24.W))
//   val tileK = RegInit(0.U(24.W))

//   val M_reg = RegInit(0.U(24.W))
//   val K_reg = RegInit(0.U(24.W))
//   val N_reg = RegInit(0.U(24.W))
//   val tilesM = RegInit(0.U(24.W))
//   val tilesK = RegInit(0.U(24.W))
//   val tilesN = RegInit(0.U(24.W))

//   val weight_base = RegInit(0.U(addr_width.W))
//   val act_base = RegInit(0.U(addr_width.W))
//   val out_base = RegInit(0.U(addr_width.W))

//   // For read-modify-write during drain
//   // The sysarr controller handles its own drain internally.
//   // We intercept its output memory port to do accumulation.
//   val drainCount = RegInit(0.U(24.W))
//   val drainActive = RegInit(false.B)

//   // Accumulation pipeline registers
//   // Stage 1: sysarr writes output → we capture the address and data
//   // Stage 2: we read existing value from output mem at that address
//   // Stage 3: we add and write back
//   val pipe_valid_1 = RegInit(false.B)
//   val pipe_addr_1 = RegInit(0.U(addr_width.W))
//   val pipe_data_1 = RegInit(VecInit(Seq.fill(n)(0.S(32.W))))

//   val pipe_valid_2 = RegInit(false.B)
//   val pipe_addr_2 = RegInit(0.U(addr_width.W))
//   val pipe_data_2 = RegInit(VecInit(Seq.fill(n)(0.S(32.W))))

//   // Default outputs
//   io.busy := state =/= TiledState.IDLE
//   io.output_addr := 0.U
//   io.output_data_wr := VecInit(Seq.fill(n)(0.S(32.W)))
//   io.output_wen := false.B

//   // Wire sysarr to weight/activation memory directly
//   io.weight_addr := sysarr.io.weight_addr
//   io.activation_addr := sysarr.io.activation_addr
//   sysarr.io.weight_data := io.weight_data
//   sysarr.io.activation_data := io.activation_data

//   // Sysarr control defaults
//   sysarr.io.start := false.B
//   sysarr.io.rst_hard := false.B
//   sysarr.io.weight_base_addr := 0.U
//   sysarr.io.activation_base_addr := 0.U
//   sysarr.io.output_base_addr := 0.U
//   sysarr.io.weight_dims(0) := n.U
//   sysarr.io.weight_dims(1) := n.U
//   sysarr.io.activation_dims(0) := n.U
//   sysarr.io.activation_dims(1) := n.U

//   // Intercept sysarr output port for accumulation
//   // sysarr thinks it's writing to memory, but we capture it
//   sysarr.io.output_data_r := io.output_data_r

//   // Accumulation pipeline
//   // Stage 1: capture sysarr output when it writes
//   pipe_valid_1 := false.B
//   when(sysarr.io.output_wen && state === TiledState.DRAIN) {
//     pipe_valid_1 := true.B
//     pipe_addr_1 := sysarr.io.output_addr
//     pipe_data_1 := sysarr.io.output_data_wr
//   }

//   // Between stage 1 and 2: issue read to output memory
//   when(pipe_valid_1) {
//     io.output_addr := pipe_addr_1 // read existing value
//   }

//   // Stage 2: read data arrives (combinational mem), add and prepare write
//   pipe_valid_2 := pipe_valid_1
//   pipe_addr_2 := pipe_addr_1
//   when(pipe_valid_1) {
//     for (c <- 0 until n) {
//       pipe_data_2(c) := pipe_data_1(c)
//     }
//   }

//   // Stage 3 (combinational for comb mem): write back accumulated result
//   when(pipe_valid_2) {
//     io.output_wen := true.B
//     io.output_addr := pipe_addr_2
//     for (c <- 0 until n) {
//       // For first tileK (tileK==0), just write directly (no accumulation)
//       // For subsequent tileK, add to existing value
//       io.output_data_wr(c) := pipe_data_2(c) + io.output_data_r(c)
//     }
//   }

//   // But wait — for tileK==0, we don't want to add, we want to overwrite.
//   // We need to know if this is the first inner tile.
//   val isFirstK = RegInit(true.B)

//   when(pipe_valid_2) {
//     io.output_wen := true.B
//     io.output_addr := pipe_addr_2
//     for (c <- 0 until n) {
//       when(isFirstK) {
//         io.output_data_wr(c) := pipe_data_2(c) // overwrite
//       }.otherwise {
//         io.output_data_wr(c) := pipe_data_2(c) + io.output_data_r(c) // accumulate
//       }
//     }
//   }

//   switch(state) {
//     is(TiledState.IDLE) {
//       when(io.start) {
//         M_reg := io.M
//         K_reg := io.K
//         N_reg := io.N
//         tilesM := io.M / n.U
//         tilesK := io.K / n.U
//         tilesN := io.N / n.U
//         weight_base := io.weight_base_addr
//         act_base := io.activation_base_addr
//         out_base := io.output_base_addr
//         tileR := 0.U
//         tileC := 0.U
//         tileK := 0.U
//         isFirstK := true.B
//         state := TiledState.FEED
//       }
//     }

//     is(TiledState.FEED) {
//       // Compute base addresses for current tile
//       // Weight tile: rows [tileR*n .. (tileR+1)*n-1], cols [tileK*n .. (tileK+1)*n-1]
//       // Weight addr base = weight_base + tileR*n*K + tileK*n
//       // Activation tile: rows [tileK*n .. (tileK+1)*n-1], cols [tileC*n .. (tileC+1)*n-1]
//       // Act addr base = act_base + tileK*n*N + tileC*n
//       // Output tile: rows [tileR*n .. (tileR+1)*n-1], cols [tileC*n .. (tileC+1)*n-1]
//       // Out addr base = out_base + tileR*n*N + tileC*n (but sysarr addresses within tile as base+offset)

//       sysarr.io.weight_base_addr := weight_base + tileR * n.U * K_reg + tileK * n.U
//       sysarr.io.activation_base_addr := act_base + tileK * n.U * N_reg + tileC * n.U
//       sysarr.io.output_base_addr := out_base + tileR * n.U * N_reg + tileC * n.U
//       sysarr.io.start := true.B
//       state := TiledState.DRAIN
//     }

//     is(TiledState.DRAIN) {
//       // Keep base addresses valid while sysarr runs
//       sysarr.io.weight_base_addr := weight_base + tileR * n.U * K_reg + tileK * n.U
//       sysarr.io.activation_base_addr := act_base + tileK * n.U * N_reg + tileC * n.U
//       sysarr.io.output_base_addr := out_base + tileR * n.U * N_reg + tileC * n.U

//       // Wait for sysarr to finish
//       when(!sysarr.io.busy) {
//         state := TiledState.NEXT_TILE
//       }
//     }

//     is(TiledState.NEXT_TILE) {
//       // Advance tile indices
//       tileK := tileK + 1.U
//       isFirstK := false.B
//       when(tileK === tilesK - 1.U) {
//         tileK := 0.U
//         isFirstK := true.B
//         tileC := tileC + 1.U
//         when(tileC === tilesN - 1.U) {
//           tileC := 0.U
//           tileR := tileR + 1.U
//           when(tileR === tilesM - 1.U) {
//             state := TiledState.IDLE
//           }.otherwise {
//             state := TiledState.FEED
//           }
//         }.otherwise {
//           state := TiledState.FEED
//         }
//       }.otherwise {
//         state := TiledState.FEED
//       }
//     }
//   }

//   when(io.rst_hard) {
//     state := TiledState.IDLE
//     tileR := 0.U
//     tileC := 0.U
//     tileK := 0.U
//   }
// }