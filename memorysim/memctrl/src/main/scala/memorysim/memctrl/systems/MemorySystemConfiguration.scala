package memorysim.memctrl

import chisel3._
import chisel3.util._

/** System Request interface * */
class SystemRequest(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
}

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO(params: MemoryConfigurationParameters) extends Bundle {
  val in  = Flipped(Decoupled(new SystemRequest(params.dataWidth, params.addressWidth)))
  val out = Decoupled(new ControllerResponse(params.dataWidth, params.addressSpaceSize, params.requestIDBits))

  // Internals-Monitoring Signals
  val rankState         = Output(Vec(params.numberOfRanks, UInt(3.W)))
  val reqQueueCount     = Output(UInt(4.W))
  val respQueueCount    = Output(UInt(4.W))
  val fsmReqQueueCounts = Output(
    Vec(params.numberOfRanks * params.numberOfBanks, UInt(3.W))
  )

  // New signal to expose active ranks count
  val activeRanks = Output(UInt(log2Ceil(params.numberOfRanks + 1).W))
}

case class SingleChannelMemoryConfigurationParams(
  memConfiguration:        MemoryConfigurationParameters = MemoryConfigurationParameters(),
  bankConfiguration:       DRAMBankParameters = DRAMBankParameters(),
  controllerConfiguration: MemoryControllerParameters = MemoryControllerParameters(),
  trackPerformance:        Boolean = true)
