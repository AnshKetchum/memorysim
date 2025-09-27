package memorysim.memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

class OpenPageBankScheduler(
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
  val selfRefreshThreshold = params.tSelfRFC.U
  val activateTimes        = Reg(Vec(memoryConfig.numberOfBanks, UInt(64.W)))
  val actPtr               = RegInit(0.U(log2Ceil(memoryConfig.numberOfBanks).W))

  // --------------------------------------------------
  // Refresh counter for internal request ID generation
  val refreshCounter = RegInit(1.U(memoryConfig.requestIDBits.W)) // Start at 1, increment for each refresh op

  // --------------------------------------------------
  // Track currently open row for this bank
  val rowBits      = 32 - (log2Ceil(memoryConfig.numberOfRanks) + log2Ceil(memoryConfig.numberOfBanks))
  // store open row and an explicit valid bit so reset values are literal constants
  val openRow      = Reg(UInt(rowBits.W))
  val openRowValid = RegInit(false.B)

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
  val idleCounter     = RegInit(0.U(32.W))

  // --------------------------------------------------
  // FSM states
  val sIdle :: sActivate :: sRead :: sWrite :: sDone :: sPrecharge :: sRefresh :: sSrefEnter :: sSref :: sSrefExit :: Nil =
    Enum(10)
  val state                                                                                                               = RegInit(sIdle)
  val prevState                                                                                                           = RegNext(state)
  val sentCmd                                                                                                             = RegInit(false.B)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Calculate bit widths for refresh ID
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
  // Extract row from an address
  def rowField(addr: UInt): UInt = {
    addr(addr.getWidth - 1, bankBitsWidth + rankBitsWidth)
  }
  val reqRow = Wire(UInt(rowBits.W))
  reqRow := rowField(reqAddrReg)

  // --------------------------------------------------
  // Default I/O
  io.req.ready := (state === sIdle)
  val cmdReg = Wire(new PhysicalMemoryCommand(memoryConfig))
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.cs         := true.B
  cmdReg.ras        := false.B
  cmdReg.cas        := false.B
  cmdReg.we         := false.B
  cmdReg.request_id := reqPacketReg
  io.cmdOut.bits    := cmdReg

  val issueStates = Seq(sActivate, sRead, sWrite, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd && !cmdReg.cs

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
      when(requestActive) {
        when(idleCounter >= selfRefreshThreshold && elapsed(lastRefresh, params.tREFI.U)) {
          state          := sSrefEnter
          reqPacketReg   := refreshReqPacket
          refreshCounter := refreshCounter + 1.U
        }.elsewhen(elapsed(lastRefresh, params.tREFI.U)) {
          reqPacketReg   := refreshReqPacket
          reqAddrReg     := refreshAddr
          state          := sRefresh
          refreshCounter := refreshCounter + 1.U
        }.elsewhen(!openRowValid || (openRow =/= reqRow)) {
          state := sActivate
        }.elsewhen(reqIsRead) {
          state := sRead
        }.otherwise {
          state := sWrite
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
      when(sentCmd && io.phyResp.fire) {
        openRow      := reqRow
        openRowValid := true.B
        lastActivate := cycleCounter
        sentCmd      := false.B
        state        := Mux(reqIsRead, sRead, sWrite)

        if (localConfiguration.verbose) {
          printf(p"[Cycle $cycleCounter] CMD FIRE: ACTIVATE\n")
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
      }
      when(
        sentCmd && io.phyResp.fire &&
          io.phyResp.bits.addr === issuedAddrReg &&
          requestPacketMatch(io.phyResp.bits.request_id, issuedPacketReg)
      ) {
        responseDataReg := io.phyResp.bits.data
        lastReadEnd     := cycleCounter
        sentCmd         := false.B
        state           := sDone // skip precharge for open-page

        if (localConfiguration.verbose) {
          printf(p"[Cycle $cycleCounter] CMD FIRE: READ\n")
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
        responseDataReg := io.phyResp.bits.data
        state           := sDone // skip precharge for open-page
        if (localConfiguration.verbose) {
          printf(p"[Cycle $cycleCounter] CMD FIRE: WRITE\n")
        }
      }
    }

    is(sPrecharge) {
      // now unused in open-page policy
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
          printf(p"[Cycle $cycleCounter] CMD FIRE: PRECHARGE\n")
        }
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
      }
    }

    is(sDone) {
      when(io.resp.fire) {
        requestActive := false.B
        state         := sIdle
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        cmdReg.cs         := false.B; cmdReg.ras := false.B; cmdReg.cas := false.B; cmdReg.we := true.B
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
