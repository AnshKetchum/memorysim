package memorysim.memctrl

import chisel3._
import chisel3.util._

/** SyncReadMem wrapper that exposes MemorySystemIO interface with proper FSM timing
  *
  * FSM States:
  *   - sIdle: Ready to accept new requests
  *   - sReadDelay/sWriteDelay: Simulating memory access latency
  *   - sReadWait/sWriteWait: Waiting for SyncReadMem pipeline
  *   - sReadResp/sWriteResp: Holding response until consumer accepts (io.out.fire)
  */
class SyncReadMemWrapper(
  depth:       Int,
  memParams:   SingleChannelMemoryConfigurationParams,
  localConfig: LocalConfigurationParameters,
  readDelay:   Int = 10,
  writeDelay:  Int = 5)
    extends Module {

  val params           = memParams.memConfiguration
  val trackPerformance = memParams.trackPerformance
  val io               = IO(new MemorySystemIO(params))

  val wordBytes = params.dataWidth / 8
  require(wordBytes > 0, "dataWidth must be byte-aligned")

  // The actual memory
  val mem = SyncReadMem(depth, UInt(params.dataWidth.W))

  // Address conversion: byte address to word index
  def addrToIndex(addr: UInt): UInt = addr >> log2Ceil(wordBytes)

  // FSM states
  val sIdle :: sReadDelay :: sReadWait :: sReadResp :: sWriteDelay :: sWriteResp :: Nil = Enum(6)
  val state                                                                             = RegInit(sIdle)

  // Request metadata
  val currentRequestId = RegInit(1.U(params.requestIDBits.W))
  val reqAddr          = RegInit(0.U(params.addressWidth.W))
  val reqRequestId     = RegInit(0.U(params.requestIDBits.W))
  val reqIsRead        = RegInit(false.B)
  val reqIsWrite       = RegInit(false.B)
  val reqWriteData     = RegInit(0.U(params.dataWidth.W))

  // Delay counter
  val delayCounter = RegInit(0.U(32.W))

  // Memory read pipeline
  val memReadEn        = RegInit(false.B)
  val memReadAddr      = RegInit(0.U(log2Ceil(depth).W))
  val memReadData      = mem.read(memReadAddr, memReadEn)
  val readDataValid    = RegNext(memReadEn, false.B)
  val capturedReadData = RegInit(0.U(params.dataWidth.W))

  // Input ready only in idle state
  io.in.ready := (state === sIdle)

  // Accept new requests
  when(io.in.fire) {
    reqAddr      := io.in.bits.addr
    reqRequestId := currentRequestId
    reqIsRead    := io.in.bits.rd_en
    reqIsWrite   := io.in.bits.wr_en
    reqWriteData := io.in.bits.wdata

    when(io.in.bits.rd_en) {
      state        := sReadDelay
      delayCounter := readDelay.U
    }.elsewhen(io.in.bits.wr_en) {
      state        := sWriteDelay
      delayCounter := writeDelay.U
    }

    currentRequestId := currentRequestId + 1.U
  }

  // Default: no memory read
  memReadEn := false.B

  // FSM transitions
  switch(state) {
    is(sIdle) {
      // Waiting for requests
    }

    is(sReadDelay) {
      when(delayCounter > 0.U) {
        delayCounter := delayCounter - 1.U
      }.otherwise {
        // Issue read to SyncReadMem
        memReadAddr := addrToIndex(reqAddr)
        memReadEn   := true.B
        state       := sReadWait
      }
    }

    is(sReadWait) {
      // Wait for SyncReadMem pipeline (readDataValid goes high next cycle after memReadEn)
      when(readDataValid) {
        capturedReadData := memReadData
        state            := sReadResp
      }
    }

    is(sReadResp) {
      // Hold response until consumer accepts
      when(io.out.fire) {
        state := sIdle
      }
    }

    is(sWriteDelay) {
      when(delayCounter > 0.U) {
        delayCounter := delayCounter - 1.U
      }.otherwise {
        // Perform write
        val idx = addrToIndex(reqAddr)
        mem.write(idx, reqWriteData)
        state := sWriteResp
      }
    }

    is(sWriteResp) {
      // Hold response until consumer accepts
      when(io.out.fire) {
        state := sIdle
      }
    }
  }

  // Output response - valid only in response states
  io.out.valid := (state === sReadResp) || (state === sWriteResp)

  io.out.bits.out.rd_en                 := reqIsRead
  io.out.bits.out.wr_en                 := reqIsWrite
  io.out.bits.out.addr                  := reqAddr
  io.out.bits.out.wdata                 := reqWriteData
  io.out.bits.out.data                  := Mux(reqIsRead, capturedReadData, 0.U)
  io.out.bits.out.request_id            := reqRequestId
  io.out.bits.next_available_request_id := currentRequestId

  // Optional performance tracking
  if (trackPerformance) {
    val perfStats = Module(new SystemQueuePerformanceStatistics(params))

    val controllerReq = Wire(new ControllerRequest(params))
    controllerReq.rd_en      := io.in.bits.rd_en
    controllerReq.wr_en      := io.in.bits.wr_en
    controllerReq.addr       := io.in.bits.addr
    controllerReq.wdata      := io.in.bits.wdata
    controllerReq.request_id := currentRequestId

    perfStats.io.in_fire  := io.in.fire
    perfStats.io.in_bits  := controllerReq
    perfStats.io.out_fire := io.out.fire
    perfStats.io.out_bits := io.out.bits.out
  }

  // Mock debug outputs
  io.rankState         := VecInit(Seq.fill(params.numberOfRanks)(0.U(3.W)))
  io.reqQueueCount     := Mux(state =/= sIdle, 1.U, 0.U)
  io.respQueueCount    := Mux(io.out.valid, 1.U, 0.U)
  io.fsmReqQueueCounts := VecInit(Seq.fill(params.numberOfRanks * params.numberOfBanks)(0.U(3.W)))
  io.activeRanks       := Mux(state =/= sIdle, 1.U, 0.U)
}
