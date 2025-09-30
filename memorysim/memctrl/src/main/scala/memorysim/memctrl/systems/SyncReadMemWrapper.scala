package memorysim.memctrl

import chisel3._
import chisel3.util._

/** SyncReadMem wrapper that exposes MemorySystemIO interface
  *
  * This wrapper makes SyncReadMem look exactly like MultiChannelSystem from the perspective of the AXI bridge,
  * eliminating API differences.
  */
class SyncReadMemWrapper(
  depth:       Int,
  memParams:   SingleChannelMemoryConfigurationParams,
  localConfig: LocalConfigurationParameters = LocalConfigurationParameters(),
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

  // Request ID tracking for responses
  val currentRequestId = RegInit(1.U(params.requestIDBits.W))

  // Read pipeline to match SyncReadMem timing
  val readPending   = RegInit(false.B)
  val readAddr      = RegInit(0.U(params.addressWidth.W))
  val readRequestId = RegInit(0.U(params.requestIDBits.W))
  val readIsRead    = RegInit(false.B)
  val readIsWrite   = RegInit(false.B)
  val readWriteData = RegInit(0.U(params.dataWidth.W))

  // Pipeline stage 1: Memory read enable
  val memReadEn   = RegInit(false.B)
  val memReadAddr = RegInit(0.U(log2Ceil(depth).W))

  // Pipeline stage 2: Data valid (matches SyncReadMem timing)
  val readDataValid = RegNext(memReadEn, false.B)
  val readData      = mem.read(memReadAddr, memReadEn)

  // Input handling
  io.in.ready := !readPending

  when(io.in.fire) {
    readPending   := true.B
    readAddr      := io.in.bits.addr
    readRequestId := currentRequestId
    readIsRead    := io.in.bits.rd_en
    readIsWrite   := io.in.bits.wr_en
    readWriteData := io.in.bits.wdata

    // Handle write immediately
    when(io.in.bits.wr_en) {
      val idx = addrToIndex(io.in.bits.addr)
      mem.write(idx, io.in.bits.wdata)
    }

    // Handle read - issue to memory pipeline
    when(io.in.bits.rd_en) {
      memReadAddr := addrToIndex(io.in.bits.addr)
      memReadEn   := true.B
    }.otherwise {
      memReadEn := false.B
    }

    // Increment request ID for next request
    currentRequestId := currentRequestId + 1.U
  }.otherwise {
    memReadEn := false.B
  }

  // Output response
  io.out.valid := readPending && (
    (readIsWrite) ||                // Write completes immediately
      (readIsRead && readDataValid) // Read completes when data is valid
  )

  io.out.bits.out.rd_en                 := readIsRead
  io.out.bits.out.wr_en                 := readIsWrite
  io.out.bits.out.addr                  := readAddr
  io.out.bits.out.wdata                 := readWriteData
  io.out.bits.out.data                  := Mux(readIsRead, readData, 0.U)
  io.out.bits.out.request_id            := readRequestId
  io.out.bits.next_available_request_id := currentRequestId

  // Clear pending when response is accepted
  when(io.out.fire) {
    readPending := false.B
  }

  // Optional performance tracking
  if (trackPerformance) {
    val perfStats = Module(new SystemQueuePerformanceStatistics(params))

    // Convert SystemRequest to ControllerRequest for performance tracking
    val controllerReq = Wire(new ControllerRequest(params))
    controllerReq.rd_en      := io.in.bits.rd_en
    controllerReq.wr_en      := io.in.bits.wr_en
    controllerReq.addr       := io.in.bits.addr
    controllerReq.wdata      := io.in.bits.wdata
    controllerReq.request_id := readRequestId // Use the request being processed, not next one

    perfStats.io.in_fire  := io.in.fire
    perfStats.io.in_bits  := controllerReq
    perfStats.io.out_fire := io.out.fire
    perfStats.io.out_bits := io.out.bits.out
  }

  // Mock debug outputs to match MultiChannelSystem interface
  io.rankState         := VecInit(Seq.fill(params.numberOfRanks)(0.U(3.W)))
  io.reqQueueCount     := Mux(readPending, 1.U, 0.U)
  io.respQueueCount    := Mux(io.out.valid, 1.U, 0.U)
  io.fsmReqQueueCounts := VecInit(Seq.fill(params.numberOfRanks * params.numberOfBanks)(0.U(3.W)))
  io.activeRanks       := Mux(readPending, 1.U, 0.U)
}
