package memorysim.memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil
import chisel3.util.experimental.loadMemoryFromFile

/** DRAM bank FSM that defers timing to an external TimingEngine. Accepts a `waitCycles` input per command and enforces
  * it.
  */
class DRAMBankWithWait(
  params:           DRAMBankParameters,
  memConfig:        MemoryConfigurationParameters,
  localConfig:      LocalConfigurationParameters,
  trackPerformance: Boolean = false)
    extends PhysicalBankModuleBase(memConfig) {

  // I/O
  val cmd        = io.memCmd  // Decoupled[BankMemoryCommand]
  val resp       = io.phyResp // Decoupled[BankMemoryResponse]
  val waitCycles = io.waitCycles

  // fixed indices
  private val rankIdx = localConfig.rankIndex.U
  private val bankIdx = localConfig.bankIndex.U

  // FSM states: Idle -> Wait -> Execute -> Resp
  val sIdle :: sWait :: sExec :: sResp :: Nil = Enum(4)
  val state                                   = RegInit(sIdle)

  // latch incoming command
  val pending = Reg(new BankMemoryCommand(memConfig))

  // latch response data
  val respData = RegInit(0.U(memConfig.dataWidth.W))

  // countdown register
  val timer = RegInit(0.U(32.W))

  // row buffer tracking
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(log2Ceil(params.numRows).W))

  // underlying memory array
  val mem = Mem(params.addressSpaceSize, UInt(memConfig.dataWidth.W))
  // loadMemoryFromFile(mem, "/workspace/chipyard/generators/memorysim/zero_init.hex")

  // decode operation from pending.op
  val doActivate  = pending.op === DRAMOp.ACTIVATE
  val doRead      = pending.op === DRAMOp.READ
  val doWrite     = pending.op === DRAMOp.WRITE
  val doPrecharge = pending.op === DRAMOp.PRECHARGE
  val doRefresh   = pending.op === DRAMOp.REFRESH
  val doSrefEnter = pending.op === DRAMOp.SREF_ENTER
  val doSrefExit  = pending.op === DRAMOp.SREF_EXIT

  // instantiate AddressDecoder for row/column
  private val addrDecoder = Module(new AddressDecoder(memConfig, params))
  addrDecoder.io.addr := pending.addr

  // now pull row & column from the decoder
  val reqRow = addrDecoder.io.rowIndex
  val reqCol = addrDecoder.io.columnIndex

  // defaults
  cmd.ready            := (state === sIdle)
  resp.valid           := false.B
  resp.bits.request_id := pending.request_id
  resp.bits.addr       := pending.addr
  resp.bits.data       := respData

  switch(state) {
    is(sIdle) {
      when(cmd.fire) {
        if (localConfig.verbose) {
          printf("[Bank Model] Received command. time = %d \n", waitCycles)
        }
        pending := cmd.bits
        timer   := waitCycles
        state   := sWait
      }
    }

    is(sWait) {
      // count down external wait
      when(timer === 0.U) {
        if (localConfig.verbose) {
          printf("[Bank Model] Timer hit zero.\n")
        }
        state := sExec
      }.otherwise {
        timer := timer - 1.U
      }
    }

    is(sExec) {
      // perform the operation and prepare response data
      when(doActivate) {
        if (localConfig.verbose) {
          printf("Activate\n");
        }
        rowActive := true.B
        activeRow := reqRow
        respData  := 0.U(memConfig.dataWidth.W)
      }.elsewhen(doRead) {
        val data = mem.read(activeRow * params.numCols.U + reqCol)
        if (localConfig.verbose) {
          printf("Read %x\n", pending.addr);
        }
        respData := data
      }.elsewhen(doWrite) {
        val idx = activeRow * params.numCols.U + reqCol
        if (localConfig.verbose) {
          printf("Write\n");
        }
        mem.write(idx, pending.data)
        respData := 0.U(memConfig.dataWidth.W)
      }.elsewhen(doPrecharge) {
        if (localConfig.verbose) {
          printf("Precharge\n");
        }
        rowActive := false.B
        respData  := 0.U(memConfig.dataWidth.W)
      }.elsewhen(doRefresh) {
        if (localConfig.verbose) {
          printf("Refresh\n");
        }
        rowActive := false.B
        respData  := 0.U(memConfig.dataWidth.W)
      }.elsewhen(doSrefEnter || doSrefExit) {
        if (localConfig.verbose) {
          printf("SREF\n");
        }
        respData := 0.U(memConfig.dataWidth.W)
      }

      state := sResp
    }

    is(sResp) {
      // handle response handshake
      resp.valid := true.B
      when(resp.fire) {
        state := sIdle
      }
    }
  }

  io.activeSubMemories := Mux(state === sExec, 1.U, 0.U)

  // optional performance tracking
  if (trackPerformance) {
    val perf = Module(new BankPerformanceStatistics(localConfig, memConfig))
    perf.io.mem_request_fire  := io.memCmd.fire
    perf.io.mem_request_bits  := io.memCmd.bits
    perf.io.mem_response_fire := io.phyResp.fire
    perf.io.mem_response_bits := io.phyResp.bits
    perf.io.active_row        := reqRow
    perf.io.active_col        := reqCol
  }
}
