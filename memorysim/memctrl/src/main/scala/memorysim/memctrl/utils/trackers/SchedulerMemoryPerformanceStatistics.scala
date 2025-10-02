package memorysim.memctrl

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** Monitors requests issued to physical memory.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class BankSchedulerPhysicalMemoryRequestPerformanceStatistics(
  val channel:      Int,
  val rank:      Int,
  val bank:      Int,
  val memParams: MemoryConfigurationParameters)
    extends BlackBox(
      Map(
        "CHANNEL"              -> channel,
        "RANK"              -> rank,
        "BANK"              -> bank,
        "ADDRESS_WIDTH"     -> memParams.addressWidth,
        "DATA_WIDTH"        -> memParams.dataWidth,
        "GLOBAL_CYCLE_BITS" -> memParams.globalCycleCountBits,
        "REQUEST_ID_BITS"   -> memParams.requestIDBits
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clk             = Input(Clock())
    val reset           = Input(Bool())
    val req_fire        = Input(Bool())
    val addr            = Input(UInt(memParams.addressWidth.W))
    val data            = Input(UInt(memParams.dataWidth.W))
    val op              = Input(UInt(memParams.dataWidth.W))
    val globalCycle     = Input(UInt(memParams.globalCycleCountBits.W))
    val request_id      = Input(UInt(memParams.requestIDBits.W))
    val internal_req_id = Input(UInt(memParams.requestIDBits.W))
    val channel_id      = Input(UInt(memParams.requestIDBits.W))
    val rank_id         = Input(UInt(memParams.requestIDBits.W))
    val bank_id         = Input(UInt(memParams.requestIDBits.W))
    val scheduler_id    = Input(UInt(memParams.requestIDBits.W))
  })

  addResource("/vsrc/BankSchedulerPhysicalMemoryRequestPerformanceStatistics.sv")
}

/** Monitors responses from physical memory. */
class BankSchedulerPhysicalMemoryResponsePerformanceStatistics(
  val channel:      Int,
  val rank:      Int,
  val bank:      Int,
  val memParams: MemoryConfigurationParameters)
    extends BlackBox(
      Map(
        "CHANNEL"              -> channel,
        "RANK"              -> rank,
        "BANK"              -> bank,
        "ADDRESS_WIDTH"     -> memParams.addressWidth,
        "DATA_WIDTH"        -> memParams.dataWidth,
        "GLOBAL_CYCLE_BITS" -> memParams.globalCycleCountBits,
        "REQUEST_ID_BITS"   -> memParams.requestIDBits
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clk             = Input(Clock())
    val reset           = Input(Bool())
    val resp_fire       = Input(Bool())
    val addr            = Input(UInt(memParams.addressWidth.W))
    val data            = Input(UInt(memParams.dataWidth.W))
    val globalCycle     = Input(UInt(memParams.globalCycleCountBits.W))
    val request_id      = Input(UInt(memParams.requestIDBits.W))
    val internal_req_id = Input(UInt(memParams.requestIDBits.W))
    val channel_id      = Input(UInt(memParams.requestIDBits.W))
    val rank_id         = Input(UInt(memParams.requestIDBits.W))
    val bank_id         = Input(UInt(memParams.requestIDBits.W))
    val scheduler_id    = Input(UInt(memParams.requestIDBits.W))
  })

  addResource("/vsrc/BankSchedulerPhysicalMemoryResponsePerformanceStatistics.sv")
}
