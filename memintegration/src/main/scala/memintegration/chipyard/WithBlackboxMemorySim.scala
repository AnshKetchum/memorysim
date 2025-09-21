// package memorysim

// import chisel3._
// import chisel3.util._
// import chisel3.reflect.DataMirror
// import chisel3.experimental.Direction
// import chipyard.iobinders._

// import org.chipsalliance.cde.config.{Field, Config, Parameters}
// import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImpLike}
// import freechips.rocketchip.system.{SimAXIMem}
// import freechips.rocketchip.subsystem._
// import freechips.rocketchip.util._
// import freechips.rocketchip.jtag.{JTAGIO}
// import freechips.rocketchip.devices.debug.{SimJTAG}
// import chipyard.iocell._
// import testchipip.dram.{SimDRAM}
// import testchipip.dram.{SimDRAM}
// import testchipip.tsi.{SimTSI, SerialRAM, TSI, TSIIO}
// import testchipip.soc.{TestchipSimDTM}
// import testchipip.spi.{SimSPIFlashModel}
// import testchipip.uart.{UARTAdapter, UARTToSerial}
// import testchipip.serdes._
// import testchipip.iceblk.{SimBlockDevice, BlockDeviceModel}
// import testchipip.cosim.{SpikeCosim}
// import icenet.{NicLoopback, SimNetwork}
// import chipyard._
// import chipyard.clocking.{HasChipyardPRCI}

// class WithBlackBoxMemorySim(additionalLatency: Int = 0) extends HarnessBinder({
//   case (th: HasHarnessInstantiators, port: AXI4MemPort, chipId: Int) => {
//     // TODO FIX: This currently makes each SimMemorySim contain the entire memory space
//     val memSize = port.params.master.size
//     val memBase = port.params.master.base
//     val lineSize = 64 // cache block size
//     val clockFreq = port.clockFreqMHz
//     val mem = Module(new SimMemorySim(memSize, lineSize, clockFreq, memBase, port.edge.bundle, chipId)).suggestName("simdram")

//     mem.io.clock := port.io.clock
//     mem.io.reset := th.harnessBinderReset.asAsyncReset
//     mem.io.axi <> port.io.bits
//     // Bug in Chisel implementation. See https://github.com/chipsalliance/chisel3/pull/1781
//     def Decoupled[T <: Data](irr: IrrevocableIO[T]): DecoupledIO[T] = {
//       require(DataMirror.directionOf(irr.bits) == Direction.Output, "Only safe to cast produced Irrevocable bits to Decoupled.")
//       val d = Wire(new DecoupledIO(chiselTypeOf(irr.bits)))
//       d.bits := irr.bits
//       d.valid := irr.valid
//       irr.ready := d.ready
//       d
//     }
//     if (additionalLatency > 0) {
//       withClock (port.io.clock) {
//         mem.io.axi.aw  <> (0 until additionalLatency).foldLeft(Decoupled(port.io.bits.aw))((t, _) => Queue(t, 1, pipe=true))
//         mem.io.axi.w   <> (0 until additionalLatency).foldLeft(Decoupled(port.io.bits.w ))((t, _) => Queue(t, 1, pipe=true))
//         port.io.bits.b <> (0 until additionalLatency).foldLeft(Decoupled(mem.io.axi.b   ))((t, _) => Queue(t, 1, pipe=true))
//         mem.io.axi.ar  <> (0 until additionalLatency).foldLeft(Decoupled(port.io.bits.ar))((t, _) => Queue(t, 1, pipe=true))
//         port.io.bits.r <> (0 until additionalLatency).foldLeft(Decoupled(mem.io.axi.r   ))((t, _) => Queue(t, 1, pipe=true))
//       }
//     }
//   }
// })
