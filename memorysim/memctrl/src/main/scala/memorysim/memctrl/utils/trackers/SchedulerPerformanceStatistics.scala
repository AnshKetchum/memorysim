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
class BankSchedulerPerformanceStatisticsInput(
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
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val req_fire    = Input(Bool())
    val rd_en       = Input(Bool())
    val wr_en       = Input(Bool())
    val addr        = Input(UInt(memParams.addressWidth.W))
    val globalCycle = Input(UInt(memParams.globalCycleCountBits.W))
    val request_id  = Input(UInt(memParams.requestIDBits.W))
  })

  addResource("/vsrc/BankSchedulerPerformanceStatisticsInput.sv")
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class BankSchedulerPerformanceStatisticsOutput(
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
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val resp_fire   = Input(Bool())
    val rd_en       = Input(Bool())
    val wr_en       = Input(Bool())
    val addr        = Input(UInt(memParams.addressWidth.W))
    val data        = Input(UInt(memParams.dataWidth.W))
    val globalCycle = Input(UInt(memParams.globalCycleCountBits.W))
    val request_id  = Input(UInt(memParams.requestIDBits.W))
  })

  addResource("/vsrc/BankSchedulerPerformanceStatisticsOutput.sv")
}

/** Top-level performance statistics module for the command queue between the controller and physical memory.
  *
  * This module "taps" both the input request and output response streams. The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class BankSchedulerPerformanceStatistics(
  localConfiguration: LocalConfigurationParameters,
  params:             MemoryConfigurationParameters)
    extends Module {
  val io = IO(new Bundle {
    val in_fire           = Input(Bool())
    val in_bits           = Input(new ControllerRequest(params))
    val out_fire          = Input(Bool())
    val out_bits          = Input(new ControllerResponse(params))
    val mem_request_fire  = Input(Bool())
    val mem_request_bits  = Input(new PhysicalMemoryCommand(params))
    val mem_response_fire = Input(Bool())
    val mem_response_bits = Input(new PhysicalMemoryResponse(params))
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(params.globalCycleCountBits.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfIn           = Module(
    new BankSchedulerPerformanceStatisticsInput(
      localConfiguration.channelIndex,
      localConfiguration.rankIndex,
      localConfiguration.bankIndex,
      params
    )
  )
  val perfOut          = Module(
    new BankSchedulerPerformanceStatisticsOutput(
      localConfiguration.channelIndex,
      localConfiguration.rankIndex,
      localConfiguration.bankIndex,
      params
    )
  )
  val perfMemRequests  = Module(
    new BankSchedulerPhysicalMemoryRequestPerformanceStatistics(
      localConfiguration.channelIndex,
      localConfiguration.rankIndex,
      localConfiguration.bankIndex,
      params
    )
  )
  val perfMemResponses = Module(
    new BankSchedulerPhysicalMemoryResponsePerformanceStatistics(
      localConfiguration.channelIndex,
      localConfiguration.rankIndex,
      localConfiguration.bankIndex,
      params
    )
  )

  // // Connect clock and reset
  // Seq(perfIn.io, perfOut.io, perfMemRequests.io, perfMemResponses.io).foreach { bb =>
  //   bbi.clk   := io.clk
  //   bbi.reset := io.reset.asBool
  // }

  /* Request / Response Interface */
  // Connect input request logging
  perfIn.io.clk         := clock
  perfIn.io.reset       := reset
  perfIn.io.req_fire    := io.in_fire
  perfIn.io.rd_en       := io.in_bits.rd_en
  perfIn.io.wr_en       := io.in_bits.wr_en
  perfIn.io.addr        := io.in_bits.addr
  perfIn.io.request_id  := io.in_bits.request_id
  perfIn.io.globalCycle := cycleCounter

  // Connect output response logging
  perfOut.io.clk         := clock
  perfOut.io.reset       := reset
  perfOut.io.resp_fire   := io.out_fire
  perfOut.io.rd_en       := io.out_bits.rd_en
  perfOut.io.wr_en       := io.out_bits.wr_en
  perfOut.io.addr        := io.out_bits.addr
  perfOut.io.data        := io.out_bits.data
  perfOut.io.request_id  := io.out_bits.request_id
  perfOut.io.globalCycle := cycleCounter

  /* Memory Logging Interface */
  // Connect input request logging - extract RequestPacket fields
  perfMemRequests.io.clk             := clock
  perfMemRequests.io.reset           := reset
  perfMemRequests.io.req_fire        := io.mem_request_fire
  perfMemRequests.io.addr            := io.mem_request_bits.addr
  perfMemRequests.io.data            := io.mem_request_bits.data
  perfMemRequests.io.cs              := io.mem_request_bits.cs
  perfMemRequests.io.ras             := io.mem_request_bits.ras
  perfMemRequests.io.cas             := io.mem_request_bits.cas
  perfMemRequests.io.we              := io.mem_request_bits.we
  perfMemRequests.io.request_id      := io.mem_request_bits.request_id.request_id
  perfMemRequests.io.internal_req_id := io.mem_request_bits.request_id.internal_req_id
  perfMemRequests.io.channel_id      := io.mem_request_bits.request_id.channel_id
  perfMemRequests.io.rank_id         := io.mem_request_bits.request_id.rank_id
  perfMemRequests.io.bank_id         := io.mem_request_bits.request_id.bank_id
  perfMemRequests.io.scheduler_id    := io.mem_request_bits.request_id.scheduler_identifier
  perfMemRequests.io.globalCycle     := cycleCounter

  // Connect output response logging - extract RequestPacket fields
  perfMemResponses.io.clk             := clock
  perfMemResponses.io.reset           := reset
  perfMemResponses.io.resp_fire       := io.mem_response_fire
  perfMemResponses.io.addr            := io.mem_response_bits.addr
  perfMemResponses.io.data            := io.mem_response_bits.data
  perfMemResponses.io.request_id      := io.mem_response_bits.request_id.request_id
  perfMemResponses.io.internal_req_id := io.mem_response_bits.request_id.internal_req_id
  perfMemResponses.io.channel_id      := io.mem_response_bits.request_id.channel_id
  perfMemResponses.io.rank_id         := io.mem_response_bits.request_id.rank_id
  perfMemResponses.io.bank_id         := io.mem_response_bits.request_id.bank_id
  perfMemResponses.io.scheduler_id    := io.mem_response_bits.request_id.scheduler_identifier
  perfMemResponses.io.globalCycle     := cycleCounter
}
