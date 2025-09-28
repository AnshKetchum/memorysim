package memorysim.memctrl

import chisel3._
import chisel3.util._

/** System Request interface * */
class SystemRequest(params: MemoryConfigurationParameters) extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(params.addressWidth.W)
  val wdata = UInt(params.dataWidth.W)
}

/** System Request interface * */
class SystemResponse(params: MemoryConfigurationParameters) extends Bundle {
  val out = new ControllerResponse(params)
  val next_available_request_id = UInt(params.requestIDBits.W)
}

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO(params: MemoryConfigurationParameters) extends Bundle {
  val in  = Flipped(Decoupled(new SystemRequest(params)))
  val out = Decoupled(new SystemResponse(params))

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
