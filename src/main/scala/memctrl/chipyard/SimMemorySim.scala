package memctrl

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util.HasBlackBoxResource
import freechips.rocketchip.amba.axi4.{AXI4BundleParameters, AXI4Bundle}

/** Replacement for the previous BlackBox-based SimDRAM.
  * This wrapper instantiates the Chisel DRAM model and prints top-level AXI handshake events
  * using plain printf("...", args...).
  *
  * Note: run simulation with Verilator (e.g. chiseltest + VerilatorBackendAnnotation)
  * to see the $fwrite output from generated Verilog.
  */
class SimMemorySim(memSize: BigInt, lineSize: Int, clockFreqHz: BigInt, memBase: BigInt,
              params: AXI4BundleParameters, chipId: Int) extends Module {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val axi = Flipped(new AXI4Bundle(params))
  })

  // instantiate the chisel DRAM model using the incoming clock/reset
  val dram = withClockAndReset(io.clock, io.reset) {
    Module(new SimMemorySimExecutor(memSize, lineSize, memBase, params, chipId))
  }

  // wire axi through
  io.axi <> dram.io.axi

}
