package memorysim.memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Top-level interface bundles (renamed)
//----------------------------------------------------------------------

/** Controller Request interface * */
class ControllerRequest(params: MemoryConfigurationParameters) extends Bundle {
  val rd_en      = Bool()
  val wr_en      = Bool()
  val addr       = UInt(params.addressWidth.W)
  val wdata      = UInt(params.dataWidth.W)
  val request_id = UInt(params.requestIDBits.W)
}

/** Controller Response interface * */
class ControllerResponse(params: MemoryConfigurationParameters) extends Bundle {
  val rd_en      = Bool()
  val wr_en      = Bool()
  val addr       = UInt(params.addressWidth.W)
  val wdata      = UInt(params.dataWidth.W)
  val data       = UInt(params.dataWidth.W) // Keep data since responses might need to return data
  val request_id = UInt(params.requestIDBits.W)
}


case class MemoryControllerParameters(
  queueSize:      Int = 256,
  openPagePolicy: Boolean = true)
