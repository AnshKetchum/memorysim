package memorysim.memctrl

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** Monitors input requests.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class CommandQueuePerformanceStatisticsInput(val memParams: MemoryConfigurationParameters)
    extends BlackBox(
      Map(
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

  addResource("/vsrc/CommandQueuePerformanceStatisticsInput.sv")
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class CommandQueuePerformanceStatisticsOutput(val memParams: MemoryConfigurationParameters)
    extends BlackBox(
      Map(
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

  addResource("/vsrc/CommandQueuePerformanceStatisticsOutput.sv")
}

/** Top-level performance statistics module for the command queue between the controller and physical memory.
  *
  * This module "taps" both the input request and output response streams. The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class CommandQueuePerformanceStatistics(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val in_fire  = Input(Bool())
    val in_bits  = Input(new PhysicalMemoryCommand(params))
    val out_fire = Input(Bool())
    val out_bits = Input(new PhysicalMemoryResponse(params))
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(params.globalCycleCountBits.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfIn  = Module(new CommandQueuePerformanceStatisticsInput(params))
  val perfOut = Module(new CommandQueuePerformanceStatisticsOutput(params))

  // Connect clock and reset
  perfIn.io.clk    := clock
  perfIn.io.reset  := reset.asBool
  perfOut.io.clk   := clock
  perfOut.io.reset := reset.asBool

  // Connect input request logging - extract RequestPacket fields
  perfIn.io.req_fire        := io.in_fire
  perfIn.io.addr            := io.in_bits.addr
  perfIn.io.data            := io.in_bits.data
  perfIn.io.op              := io.in_bits.op
  perfIn.io.request_id      := io.in_bits.request_id.request_id
  perfIn.io.internal_req_id := io.in_bits.request_id.internal_req_id
  perfIn.io.channel_id      := io.in_bits.request_id.channel_id
  perfIn.io.rank_id         := io.in_bits.request_id.rank_id
  perfIn.io.bank_id         := io.in_bits.request_id.bank_id
  perfIn.io.scheduler_id    := io.in_bits.request_id.scheduler_identifier
  perfIn.io.globalCycle     := cycleCounter

  // Connect output response logging - extract RequestPacket fields
  perfOut.io.resp_fire       := io.out_fire
  perfOut.io.addr            := io.out_bits.addr
  perfOut.io.data            := io.out_bits.data
  perfOut.io.request_id      := io.out_bits.request_id.request_id
  perfOut.io.internal_req_id := io.out_bits.request_id.internal_req_id
  perfOut.io.channel_id      := io.out_bits.request_id.channel_id
  perfOut.io.rank_id         := io.out_bits.request_id.rank_id
  perfOut.io.bank_id         := io.out_bits.request_id.bank_id
  perfOut.io.scheduler_id    := io.out_bits.request_id.scheduler_identifier
  perfOut.io.globalCycle     := cycleCounter
}
