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

  val lastActivate         = RegInit(0.U(64.W))
  val lastPrecharge        = RegInit(0.U(64.W))
  val lastReadEnd          = RegInit(0.U(64.W))
  val lastWriteEnd         = RegInit(0.U(64.W))
  val lastRefresh          = RegInit(0.U(64.W))
  val selfRefreshThreshold = 1000.U
  val activateTimes        = Reg(Vec(memoryConfig.numberOfBanks, UInt(64.W)))
  val actPtr               = RegInit(0.U(log2Ceil(memoryConfig.numberOfBanks).W))

  // --------------------------------------------------
  // Refresh counter for internal request ID generation
  val refreshCounter = RegInit(1.U(memoryConfig.requestIDBits.W)) // Start at 1, increment for each refresh op

  // --------------------------------------------------
  // Helper function to create RequestPacket
  def createRequestPacket(extReqId: UInt, isInternal: Bool, internalId: UInt): RequestPacket = {
    val reqPacket = Wire(new RequestPacket(memoryConfig))
    reqPacket.request_id           := Mux(isInternal, 0.U, extReqId)
    reqPacket.internal_req_id      := Mux(isInternal, internalId, 0.U)
    reqPacket.channel_id           := localConfiguration.channelIndex.U
    reqPacket.rank_id              := localConfiguration.rankIndex.U
    reqPacket.bank_id              := localConfiguration.bankIndex.U
    reqPacket.scheduler_identifier := localConfiguration.bankIndex.U // Use bankIndex as scheduler ID
    reqPacket
  }

  // --------------------------------------------------
  // Latch incoming request fields
  val reqReg          = Reg(new ControllerRequest(memoryConfig))
  val reqIsRead       = RegInit(false.B)
  val reqIsWrite      = RegInit(false.B)
  val reqAddrReg      = RegInit(0.U(32.W))
  val reqWdataReg     = RegInit(0.U(32.W))
  val reqPacketReg    = Reg(new RequestPacket(memoryConfig))
  val requestActive   = RegInit(false.B)
  val issuedAddrReg   = RegInit(0.U(32.W))
  val issuedPacketReg = Reg(new RequestPacket(memoryConfig))
  val responseDataReg = RegInit(0.U(32.W))

  // --------------------------------------------------
  // FSM states
  val sIdle :: sActivate :: sRead :: sWrite :: sPrecharge :: sDone :: sRefresh :: sSrefEnter :: sSref :: sSrefExit :: Nil =
    Enum(10)
  val state                                                                                                               = RegInit(sIdle)
  val prevState                                                                                                           = RegNext(state)
  val counter                                                                                                             = RegInit(0.U(32.W))
  val idleCounter                                                                                                         = RegInit(0.U(32.W))
  val sentCmd                                                                                                             = RegInit(false.B)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Calculate bit widths for refresh ID composition
  val rankBitsWidth   = log2Ceil(memoryConfig.numberOfRanks)
  val bankBitsWidth   = log2Ceil(memoryConfig.numberOfBanks)
  val columnBitsWidth = 32 - (rankBitsWidth + bankBitsWidth)

  // instantiate the generator for refresh ID and address
  private val reqIDGen = Module(new RefreshAddressGenerator(memoryConfig, params, localConfiguration))

  // wire up refresh request ID and address from the generator
  val refreshReqId = reqIDGen.io.refreshReqId
  val refreshAddr  = reqIDGen.io.refreshAddr

  // Create refresh request packet
  val refreshReqPacket = createRequestPacket(0.U, true.B, refreshCounter)

  // --------------------------------------------------
  // Accept incoming request only in Idle
  io.req.ready := (state === sIdle)
  when(state =/= sSref) {
    when(io.req.fire && state === sIdle) {
      reqReg        := io.req.bits
      reqIsRead     := io.req.bits.rd_en
      reqIsWrite    := io.req.bits.wr_en
      reqAddrReg    := io.req.bits.addr
      reqWdataReg   := io.req.bits.wdata
      reqPacketReg  := createRequestPacket(io.req.bits.request_id, false.B, 0.U)
      requestActive := true.B
      idleCounter   := 0.U
    }.elsewhen(state === sIdle) {
      idleCounter := idleCounter + 1.U
    }
  }

  // --------------------------------------------------
  // Command register default
  val cmdReg = Wire(new PhysicalMemoryCommand(memoryConfig))
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.cs         := true.B
  cmdReg.ras        := false.B
  cmdReg.cas        := false.B
  cmdReg.we         := false.B
  cmdReg.request_id := reqPacketReg
  io.cmdOut.bits    := cmdReg

  val issueStates = Seq(sActivate, sRead, sWrite, sPrecharge, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd && !cmdReg.cs

  // --------------------------------------------------
  // Response register
  val respReg = Wire(new ControllerResponse(memoryConfig))
  respReg.addr       := reqAddrReg
  respReg.wr_en      := reqIsWrite
  respReg.rd_en      := reqIsRead
  respReg.wdata      := reqWdataReg
  respReg.data       := responseDataReg
  respReg.request_id := reqPacketReg.request_id // Extract external request ID for response
  io.resp.bits       := respReg
  io.resp.valid      := (state === sDone)

  // timing helper
  def elapsed(since: UInt, d: UInt): Bool = (cycleCounter - since) >= d

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
  // FSM logic
  switch(state) {
    is(sIdle) {
      when(idleCounter >= selfRefreshThreshold && elapsed(lastRefresh, params.tREFI.U)) {
        state          := sSrefEnter
        reqPacketReg   := refreshReqPacket
        refreshCounter := refreshCounter + 1.U
      }.elsewhen(elapsed(lastRefresh, params.tREFI.U)) {
        // issue refresh
        reqPacketReg   := refreshReqPacket
        reqAddrReg     := refreshAddr
        state          := sRefresh
        refreshCounter := refreshCounter + 1.U
      }.elsewhen(requestActive) {
        state := sActivate
      }
    }

    is(sSrefEnter) {
      when(!sentCmd) {
        cmdReg.cs         := false.B; cmdReg.ras := false.B; cmdReg.cas := false.B; cmdReg.we := false.B
        cmdReg.request_id := refreshReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedPacketReg := refreshReqPacket
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        state   := sSref
        sentCmd := false.B

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: SREF_ENTER -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sSref) {
      when(io.req.valid) {
        state          := sSrefExit
        // Generate new refresh packet for exit
        reqPacketReg   := createRequestPacket(0.U, true.B, refreshCounter)
        refreshCounter := refreshCounter + 1.U
      }
    }

    is(sSrefExit) {
      when(!sentCmd) {
        cmdReg.cs         := false.B; cmdReg.ras := true.B; cmdReg.cas := true.B; cmdReg.we := true.B
        cmdReg.request_id := reqPacketReg
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedPacketReg := reqPacketReg
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        state   := sIdle
        sentCmd := false.B

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: SREF_EXIT -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedPacketReg := reqPacketReg

        if (localConfiguration.verbose) {
          printf("Issued activate.\n")
        }
      }
      when(sentCmd && !io.phyResp.fire) {
        if (localConfiguration.verbose) {
          printf(
            "[Cycle %d]; rdy=%d valid=%d  waiting for ACTIVATE response; addr=%d ext_req_id=%d int_req_id=%d \n",
            cycleCounter,
            io.phyResp.ready,
            io.phyResp.valid,
            reqAddrReg,
            reqPacketReg.request_id,
            reqPacketReg.internal_req_id
          )
          printf(
            "[Cycle %d]; rankIdx=%d bankIdx=%d respIdx=%d respBI=%d addr=%d ext_req_id=%d int_req_id=%d \n",
            cycleCounter,
            localConfiguration.rankIndex.U,
            localConfiguration.bankIndex.U,
            respDec.io.rankIndex,
            respDec.io.bankIndex,
            io.phyResp.bits.addr,
            io.phyResp.bits.request_id.request_id,
            io.phyResp.bits.request_id.internal_req_id
          )
        }
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        lastActivate          := cycleCounter
        activateTimes(actPtr) := cycleCounter
        actPtr                := actPtr + 1.U
        sentCmd               := false.B
        state                 := Mux(reqIsRead, sRead, sWrite)

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: ACTIVATE -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sRead) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedAddrReg   := reqAddrReg
        issuedPacketReg := reqPacketReg
        counter         := params.CL.U
      }
      when(
        sentCmd && io.phyResp.fire &&
          io.phyResp.bits.addr === issuedAddrReg &&
          requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)
      ) {
        responseDataReg := io.phyResp.bits.data
        lastReadEnd     := cycleCounter
        sentCmd         := false.B
        state           := sPrecharge

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: READ -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sWrite) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        lastWriteEnd    := cycleCounter + params.CWL.U + params.tWR.U
        issuedPacketReg := reqPacketReg
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        sentCmd         := false.B
        state           := sPrecharge
        responseDataReg := io.phyResp.bits.data

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: WRITE -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sPrecharge) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedPacketReg := reqPacketReg
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        lastPrecharge := cycleCounter
        sentCmd       := false.B
        state         := sDone

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: PRECHARGE -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        cmdReg.cs         := false.B
        cmdReg.ras        := false.B
        cmdReg.cas        := false.B
        cmdReg.we         := true.B
        cmdReg.addr       := refreshAddr
        cmdReg.request_id := refreshReqPacket
      }
      when(io.cmdOut.fire) {
        sentCmd         := true.B
        issuedPacketReg := refreshReqPacket
      }
      when(sentCmd && io.phyResp.fire && requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)) {
        lastRefresh := cycleCounter
        sentCmd     := false.B
        state       := sIdle

        if (localConfiguration.verbose) {
          printf(
            p"[Cycle $cycleCounter] CMD FIRE: REFRESH -> Rank ${localConfiguration.rankIndex}, Bank ${localConfiguration.bankIndex}\n"
          )
        }
      }
    }

    is(sDone) {
      when(io.resp.fire) {
        requestActive := false.B
        state         := sIdle
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
  // Accept phyResp only with matching RequestPacket and decoded indices
  val waitingForResp = WireDefault(false.B)
  when(state =/= sSref) {
    waitingForResp := issueStates.map(_ === state).reduce(_ || _) && sentCmd
  }

  val expectedPacket = Wire(new RequestPacket(memoryConfig))
  expectedPacket := Mux(
    state === sSrefEnter || state === sSrefExit || state === sRefresh,
    issuedPacketReg,
    reqPacketReg
  )

  io.phyResp.ready := waitingForResp &&
    requestPacketMatch(io.phyResp.bits.request_id, expectedPacket) &&
    (respDec.io.rankIndex === localConfiguration.rankIndex.U) &&
    (respDec.io.bankIndex === localConfiguration.bankIndex.U)
}
