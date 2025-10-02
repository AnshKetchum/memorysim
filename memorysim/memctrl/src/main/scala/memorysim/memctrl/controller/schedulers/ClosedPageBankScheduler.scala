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
  val cycleCounter = RegInit(0.U(memoryConfig.globalCycleCountBits.W))
  cycleCounter := cycleCounter + 1.U

  val lastRefresh = RegInit(0.U(memoryConfig.globalCycleCountBits.W))

  // --------------------------------------------------
  // Refresh counter for internal request ID generation
  val refreshCounter = RegInit(1.U(memoryConfig.requestIDBits.W))

  // --------------------------------------------------
  // Internal request ID constants for different memory commands
  val INTERNAL_ID_ACTIVATE = 1.U(memoryConfig.requestIDBits.W)
  val INTERNAL_ID_READ     = 2.U(memoryConfig.requestIDBits.W)
  val INTERNAL_ID_WRITE    = 3.U(memoryConfig.requestIDBits.W)

  // --------------------------------------------------
  // Helper function to create RequestPacket
  def createRequestPacket(extReqId: UInt, internalId: UInt): RequestPacket = {
    val reqPacket = Wire(new RequestPacket(memoryConfig))
    reqPacket.request_id           := extReqId
    reqPacket.internal_req_id      := internalId
    reqPacket.channel_id           := localConfiguration.channelIndex.U
    reqPacket.rank_id              := localConfiguration.rankIndex.U
    reqPacket.bank_id              := localConfiguration.bankIndex.U
    reqPacket.scheduler_identifier := localConfiguration.bankIndex.U
    reqPacket
  }

  // --------------------------------------------------
  // Latch incoming request fields
  val reqReg      = Reg(new ControllerRequest(memoryConfig))
  val reqIsRead   = RegInit(false.B)
  val reqIsWrite  = RegInit(false.B)
  val reqAddrReg  = RegInit(0.U(memoryConfig.addressWidth.W))
  val reqWdataReg = RegInit(0.U(memoryConfig.dataWidth.W))
  val reqIdReg    = RegInit(0.U(memoryConfig.requestIDBits.W))

  // Request packets for activate, read/write, and refresh with distinct internal IDs
  val activateReqPacket  = Reg(new RequestPacket(memoryConfig))
  val readWriteReqPacket = Reg(new RequestPacket(memoryConfig))
  val refreshReqPacket   = Reg(new RequestPacket(memoryConfig))

  // Response data register
  val responseDataReg = RegInit(0.U(memoryConfig.dataWidth.W))

  // --------------------------------------------------
  // FSM states - 5-state cycle with separate response state
  val sIdle :: sActivate :: sReadWrite :: sResponse :: sRefresh :: Nil = Enum(5)
  val state                                                            = RegInit(sIdle)
  val prevState                                                        = RegNext(state)
  val sentCmd                                                          = RegInit(false.B)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Refresh address generator
  private val reqIDGen = Module(new RefreshAddressGenerator(memoryConfig, params, localConfiguration))
  val refreshAddr      = reqIDGen.io.refreshAddr

  // --------------------------------------------------
  // Default I/O
  io.req.ready := (state === sIdle)
  val cmdReg = Wire(new PhysicalMemoryCommand(memoryConfig))

  // Default command fields
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.op         := DRAMOp.ACTIVATE // Default, will be overridden
  cmdReg.request_id := activateReqPacket // Default, will be overridden
  io.cmdOut.bits    := cmdReg

  // Issue commands when in command states and haven't sent yet
  val issueStates = Seq(sActivate, sReadWrite, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd

  // Response data wire
  val responseDataWire = Wire(UInt(memoryConfig.dataWidth.W))
  responseDataWire := responseDataReg

  val respReg = Wire(new ControllerResponse(memoryConfig))
  respReg.addr       := reqAddrReg
  respReg.wr_en      := reqIsWrite
  respReg.rd_en      := reqIsRead
  respReg.wdata      := reqWdataReg
  respReg.data       := responseDataWire
  respReg.request_id := reqIdReg
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
  // Simple FSM: Accept request → Activate → Read/Write → Response → Refresh → Repeat
  switch(state) {
    is(sIdle) {
      when(io.req.fire) {
        // Latch the request
        reqReg      := io.req.bits
        reqIsRead   := io.req.bits.rd_en
        reqIsWrite  := io.req.bits.wr_en
        reqAddrReg  := io.req.bits.addr
        reqWdataReg := io.req.bits.wdata
        reqIdReg    := io.req.bits.request_id

        // Create request packets with SAME request_id but DIFFERENT internal_req_id
        activateReqPacket  := createRequestPacket(io.req.bits.request_id, INTERNAL_ID_ACTIVATE)
        readWriteReqPacket := createRequestPacket(
          io.req.bits.request_id,
          Mux(io.req.bits.rd_en, INTERNAL_ID_READ, INTERNAL_ID_WRITE)
        )
        refreshReqPacket   := createRequestPacket(0.U, refreshCounter)

        // Always go to activate
        state := sActivate
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        // Send ACTIVATE command
        cmdReg.op         := DRAMOp.ACTIVATE
        cmdReg.request_id := activateReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, activateReqPacket)) {
        sentCmd := false.B
        state   := sReadWrite
      }
    }

    is(sReadWrite) {
      when(!sentCmd) {
        // Send READ or WRITE command
        cmdReg.op         := Mux(reqIsRead, DRAMOp.READ, DRAMOp.WRITE)
        cmdReg.request_id := readWriteReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, readWriteReqPacket)) {
        sentCmd := false.B

        // Latch response data if this was a read
        when(reqIsRead) {
          responseDataReg := io.phyResp.bits.data
        }

        state := sResponse
      }
    }

    is(sResponse) {
      // Send response back to requester
      io.resp.valid := true.B
      when(io.resp.fire) {
        if (localConfiguration.verbose) {
          printf("Sending back data 0%x read - 0%x write - 0%x\n", responseDataWire, responseDataReg, reqWdataReg)
        }
        state := sRefresh
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        // Send REFRESH command
        cmdReg.op         := DRAMOp.REFRESH
        cmdReg.addr       := refreshAddr
        cmdReg.request_id := refreshReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, refreshReqPacket)) {
        lastRefresh    := cycleCounter
        sentCmd        := false.B
        refreshCounter := refreshCounter + 1.U
        state          := sIdle
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
    (state === sActivate && requestPacketMatch(io.phyResp.bits.request_id, activateReqPacket)) ||
      (state === sReadWrite && requestPacketMatch(io.phyResp.bits.request_id, readWriteReqPacket)) ||
      (state === sRefresh && requestPacketMatch(io.phyResp.bits.request_id, refreshReqPacket))
  ) &&
    (respDec.io.rankIndex === localConfiguration.rankIndex.U) &&
    (respDec.io.bankIndex === localConfiguration.bankIndex.U)
}