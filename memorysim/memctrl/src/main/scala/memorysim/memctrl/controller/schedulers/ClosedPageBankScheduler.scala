package memorysim.memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

class ClosedPageBankScheduler(
  params:             DRAMBankParameters,
  localConfiguration: LocalConfigurationParameters,
  memoryConfig:       MemoryConfigurationParameters,
  trackPerformance:   Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val req      = Flipped(Decoupled(new ControllerRequest(memoryConfig)))
    val resp     = Decoupled(new ControllerResponse(memoryConfig))
    val cmdOut   = Decoupled(new PhysicalMemoryCommand(memoryConfig))
    val phyResp  = Flipped(Decoupled(new PhysicalMemoryResponse(memoryConfig)))
    val stateOut = Output(UInt(3.W))
  })

  // --------------------------------------------------
  // Address decoder for response filtering
  val respDec = Module(new AddressDecoder(memoryConfig, params))
  respDec.io.addr := io.phyResp.bits.addr

  // --------------------------------------------------
  // Global cycle counter & timing regs
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  val lastRefresh = RegInit(0.U(64.W))

  // --------------------------------------------------
  // Refresh counter for internal request ID generation
  val refreshCounter = RegInit(1.U(memoryConfig.requestIDBits.W))

  // --------------------------------------------------
  // Helper function to create RequestPacket
  def createRequestPacket(extReqId: UInt, isInternal: Bool, internalId: UInt): RequestPacket = {
    val reqPacket = Wire(new RequestPacket(memoryConfig))
    reqPacket.request_id           := Mux(isInternal, 0.U, extReqId)
    reqPacket.internal_req_id      := Mux(isInternal, internalId, 0.U)
    reqPacket.channel_id           := localConfiguration.channelIndex.U
    reqPacket.rank_id              := localConfiguration.rankIndex.U
    reqPacket.bank_id              := localConfiguration.bankIndex.U
    reqPacket.scheduler_identifier := localConfiguration.bankIndex.U
    reqPacket
  }

  // --------------------------------------------------
  // Latch incoming request fields
  val reqReg       = Reg(new ControllerRequest(memoryConfig))
  val reqIsRead    = RegInit(false.B)
  val reqIsWrite   = RegInit(false.B)
  val reqAddrReg   = RegInit(0.U(32.W))
  val reqWdataReg  = RegInit(0.U(32.W))
  val reqPacketReg = Reg(new RequestPacket(memoryConfig))

  // --------------------------------------------------
  // FSM states - simple 4-state cycle
  val sIdle :: sActivate :: sReadWrite :: sRefresh :: Nil = Enum(4)
  val state                                               = RegInit(sIdle)
  val prevState                                           = RegNext(state)
  val sentCmd                                             = RegInit(false.B)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Refresh address generator
  private val reqIDGen = Module(new RefreshAddressGenerator(memoryConfig, params, localConfiguration))
  val refreshAddr      = reqIDGen.io.refreshAddr
  val refreshReqPacket = Wire(new RequestPacket(memoryConfig))
  refreshReqPacket := createRequestPacket(0.U, true.B, refreshCounter)

  // --------------------------------------------------
  // Default I/O
  io.req.ready := (state === sIdle)
  val cmdReg = Wire(new PhysicalMemoryCommand(memoryConfig))

  // Default command fields
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.cs         := true.B
  cmdReg.ras        := false.B
  cmdReg.cas        := false.B
  cmdReg.we         := false.B
  cmdReg.request_id := reqPacketReg
  io.cmdOut.bits    := cmdReg

  // Issue commands when in command states and haven't sent yet
  val issueStates = Seq(sActivate, sReadWrite, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd && !cmdReg.cs

  // Response data wire - computed based on current state and data availability
  val responseDataWire = Wire(UInt(32.W))
  responseDataWire := Mux(reqIsRead, io.phyResp.bits.data, reqWdataReg)

  val respReg = Wire(new ControllerResponse(memoryConfig))
  respReg.addr       := reqAddrReg
  respReg.wr_en      := reqIsWrite
  respReg.rd_en      := reqIsRead
  respReg.wdata      := reqWdataReg
  respReg.data       := responseDataWire
  respReg.request_id := reqPacketReg.request_id
  io.resp.bits       := respReg
  io.resp.valid      := false.B // Will be set in FSM

  // Helper function to compare RequestPackets
  def requestPacketMatch(a: RequestPacket, b: RequestPacket): Bool = {
    (a.request_id === b.request_id) &&
    (a.internal_req_id === b.internal_req_id) &&
    (a.channel_id === b.channel_id) &&
    (a.rank_id === b.rank_id) &&
    (a.bank_id === b.bank_id) &&
    (a.scheduler_identifier === b.scheduler_identifier)
  }

  // --------------------------------------------------
  // Simple FSM: Accept request → Activate → Read/Write → Refresh → Repeat
  switch(state) {
    is(sIdle) {
      when(io.req.fire) {
        // Latch the request
        reqReg       := io.req.bits
        reqIsRead    := io.req.bits.rd_en
        reqIsWrite   := io.req.bits.wr_en
        reqAddrReg   := io.req.bits.addr
        reqWdataReg  := io.req.bits.wdata
        reqPacketReg := createRequestPacket(io.req.bits.request_id, false.B, 0.U)

        // Always go to activate
        state := sActivate
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        // Send ACTIVATE command
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := true.B
        cmdReg.we  := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, reqPacketReg)) {
        sentCmd := false.B
        state   := sReadWrite
      }
    }

    is(sReadWrite) {
      when(!sentCmd) {
        // Send READ or WRITE command
        cmdReg.cs  := false.B
        cmdReg.ras := true.B
        cmdReg.cas := false.B
        cmdReg.we  := Mux(reqIsRead, true.B, false.B)
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, reqPacketReg)) {
        sentCmd := false.B

        // Send response and go to refresh
        io.resp.valid := true.B
        when(io.resp.fire) {
          if (localConfiguration.verbose) {
            printf(
              "Sending back data 0%x read - 0%x write - 0%x\n",
              responseDataWire,
              io.phyResp.bits.data,
              reqWdataReg
            )
          }
          state          := sRefresh
          refreshCounter := refreshCounter + 1.U
        }
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        // Send REFRESH command
        cmdReg.cs         := false.B
        cmdReg.ras        := false.B
        cmdReg.cas        := false.B
        cmdReg.we         := true.B
        cmdReg.addr       := refreshAddr
        cmdReg.request_id := refreshReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, refreshReqPacket)) {
        lastRefresh := cycleCounter
        sentCmd     := false.B
        state       := sIdle
      }
    }
  }

  // --------------------------------------------------
  // Performance tracker
  if (trackPerformance) {
    val perf = Module(new BankSchedulerPerformanceStatistics(localConfiguration, memoryConfig))
    perf.io.in_fire           := io.req.fire
    perf.io.in_bits           := io.req.bits
    perf.io.out_fire          := io.resp.fire
    perf.io.out_bits          := io.resp.bits
    perf.io.mem_request_fire  := io.cmdOut.fire
    perf.io.mem_request_bits  := io.cmdOut.bits
    perf.io.mem_response_fire := io.phyResp.fire
    perf.io.mem_response_bits := io.phyResp.bits
  }

  // --------------------------------------------------
  // Accept responses for any command we're waiting for
  io.phyResp.ready := sentCmd && (
    (state === sActivate && requestPacketMatch(io.phyResp.bits.request_id, reqPacketReg)) ||
      (state === sReadWrite && requestPacketMatch(io.phyResp.bits.request_id, reqPacketReg)) ||
      (state === sRefresh && requestPacketMatch(io.phyResp.bits.request_id, refreshReqPacket))
  ) &&
    (respDec.io.rankIndex === localConfiguration.rankIndex.U) &&
    (respDec.io.bankIndex === localConfiguration.bankIndex.U)
}
