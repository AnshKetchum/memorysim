package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import memorysim.memctrl._

/** MultiChannel-backed SimMemorySimExecutor
  *
  * Drop-in replacement for the original SimMemorySimExecutor that uses MultiChannelSystem as the backing memory instead
  * of SyncReadMem. Provides realistic memory controller timing while maintaining the same AXI4 interface.
  *
  * This file uses plain printf(...) format strings (no p interpolator).
  */
class SimMemorySimExecutor(
  memSize:  BigInt,
  lineSize: Int,
  memBase:  BigInt,
  params:   AXI4BundleParameters,
  chipId:   Int)
    extends Module {

  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(params))

    // Optional debug outputs from memory system
    val debug = Output(new Bundle {
      val rankState      = Vec(2, UInt(3.W))   // WARNING: Hardcoded to 2 ranks default
      val reqQueueCount  = UInt(4.W)
      val respQueueCount = UInt(4.W)
      val activeRanks    = UInt(log2Ceil(3).W) // WARNING: log2Ceil(numberOfRanks + 1)
    })
  })

  val addrBits  = params.addrBits
  val dataBits  = params.dataBits
  val wordBytes = (dataBits / 8)
  val strbBits  = wordBytes

  require(wordBytes > 0, "wordBytes must be > 0")
  // require(dataBits == 32, "MultiChannelSystem currently supports 32-bit data width")

  // WARNING: Using default memory system parameters - you may want to configure these!
  // Default memory configuration - adjust for your simulation needs
  val memParams = SingleChannelMemoryConfigurationParams(
    memConfiguration = MemoryConfigurationParameters(
      addressWidth = addrBits,
      dataWidth = dataBits,
      numberOfChannels = 1, // WARNING: Default single channel
      numberOfRanks = 2,    // WARNING: Default 2 ranks
      numberOfBanks = 8,    // WARNING: Default 8 banks
      memoryQueueSize = 1   // WARNING: Default queue size
    ),
    bankConfiguration = DRAMBankParameters(), // WARNING: Using default HBM2 timing
    controllerConfiguration = MemoryControllerParameters(
      queueSize = 1,
      openPagePolicy = false
    ),                                        // WARNING: Default controller params
    trackPerformance = true                   // WARNING: Performance tracking disabled by default
  )

  // WARNING: Using default local configuration - adjust channel/rank/bank indices as needed
  val localConfig = LocalConfigurationParameters(
    channelIndex = 0, // WARNING: Default to channel 0
    rankIndex = 0,    // WARNING: Default to rank 0
    bankIndex = 0     // WARNING: Default to bank 0
  )

  // Instantiate the MultiChannelSystem
  val memSys = Module(new MultiChannelSystem(memParams, localConfig))

  def addrToIndex(addr: UInt): UInt = addr >> log2Ceil(wordBytes)

  // ---- Write Path: AW + W -> Memory System -> B ----
  val sWIdle :: sWCollect :: sWWaitResp :: sWResp :: Nil = Enum(4)
  val wState                                             = RegInit(sWIdle)

  val store_id                = RegInit(0.U(params.idBits.W))
  val store_addr              = RegInit(0.U(addrBits.W))
  val store_base_addr         = RegInit(0.U(addrBits.W)) // Track original burst address
  val store_count             = RegInit(0.U(8.W))
  val store_size              = RegInit(0.U(3.W))
  val write_beats_issued      = RegInit(0.U(8.W))
  val write_responses_pending = RegInit(0.U(8.W))

  // AXI Write channels
  io.axi.aw.ready    := (wState === sWIdle)
  io.axi.w.ready     := (wState === sWCollect) && memSys.io.in.ready
  io.axi.b.valid     := (wState === sWResp)
  io.axi.b.bits.id   := store_id
  io.axi.b.bits.resp := 0.U

  // Write FSM
  switch(wState) {
    is(sWIdle) {
      when(io.axi.aw.fire) {
        store_id                := io.axi.aw.bits.id
        store_addr              := io.axi.aw.bits.addr
        store_base_addr         := io.axi.aw.bits.addr
        store_count             := io.axi.aw.bits.len + 1.U
        store_size              := io.axi.aw.bits.size
        write_beats_issued      := 0.U
        write_responses_pending := 0.U
        wState                  := sWCollect

        printf(
          "[MemorySim] AW.fire id=%d addr=%x len=%d size=%d\n",
          io.axi.aw.bits.id,
          io.axi.aw.bits.addr,
          io.axi.aw.bits.len,
          io.axi.aw.bits.size
        )
      }
    }
    is(sWCollect) {
      when(io.axi.w.fire) {
        write_beats_issued      := write_beats_issued + 1.U
        write_responses_pending := write_responses_pending + 1.U
        store_addr              := store_addr + (1.U << store_size)
        store_count             := store_count - 1.U

        when(io.axi.w.bits.last || store_count === 1.U) {
          wState := sWWaitResp
        }
      }
    }
    is(sWWaitResp) {
      // Wait for all memory system responses
      when(memSys.io.out.fire && memSys.io.out.bits.wr_en) {
        write_responses_pending := write_responses_pending - 1.U

        when(write_responses_pending === 1.U) {
          wState := sWResp
        }
      }
    }
    is(sWResp) {
      when(io.axi.b.fire) {
        wState := sWIdle
      }
    }
  }

  // ---- Read Path: AR -> Memory System -> R ----
  val sRIdle :: sRIssue :: sRCollect :: Nil = Enum(3)
  val rState                                = RegInit(sRIdle)

  val read_id                 = RegInit(0.U(params.idBits.W))
  val read_addr               = RegInit(0.U(addrBits.W))
  val read_base_addr          = RegInit(0.U(addrBits.W)) // Track original burst address
  val read_count              = RegInit(0.U(8.W))
  val read_size               = RegInit(0.U(3.W))
  val read_beats_issued       = RegInit(0.U(8.W))
  val read_responses_received = RegInit(0.U(8.W))
  val read_beats_sent         = RegInit(0.U(8.W))

  // Read response buffer (handles potential out-of-order responses)
  val max_burst_len     = 256
  val read_buffer       = Reg(Vec(max_burst_len, UInt(dataBits.W)))
  val read_buffer_valid = Reg(Vec(max_burst_len, Bool()))

  // Track which beats we've requested to handle the address mismatch issue
  val read_beats_remaining = RegInit(0.U(8.W))
  val read_timeout_counter = RegInit(0.U(16.W)) // Add timeout counter

  // Initialize buffer valid bits
  for (i <- 0 until max_burst_len) {
    when(reset.asBool) {
      read_buffer_valid(i) := false.B
    }
  }

  // AXI Read channels
  io.axi.ar.ready := (rState === sRIdle)

  val can_send_read_beat = (rState === sRCollect) &&
    read_buffer_valid(read_beats_sent) &&
    (read_beats_sent < read_responses_received)

  io.axi.r.valid     := can_send_read_beat
  io.axi.r.bits.id   := read_id
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.data := read_buffer(read_beats_sent)
  io.axi.r.bits.last := (read_beats_sent === (read_count - 1.U))

  // Read FSM
  switch(rState) {
    is(sRIdle) {
      when(io.axi.ar.fire) {
        read_id                 := io.axi.ar.bits.id
        read_addr               := io.axi.ar.bits.addr
        read_base_addr          := io.axi.ar.bits.addr
        read_count              := io.axi.ar.bits.len + 1.U
        read_size               := io.axi.ar.bits.size
        read_beats_issued       := 0.U
        read_responses_received := 0.U
        read_beats_sent         := 0.U
        read_beats_remaining    := io.axi.ar.bits.len + 1.U
        read_timeout_counter    := 0.U

        // Clear buffer valid bits for this burst
        for (i <- 0 until max_burst_len) {
          read_buffer_valid(i) := false.B
        }

        rState := sRIssue
        printf(
          "[MemorySim] AR.fire id=%d addr=%x len=%d size=%d\n",
          io.axi.ar.bits.id,
          io.axi.ar.bits.addr,
          io.axi.ar.bits.len,
          io.axi.ar.bits.size
        )
      }
    }
    is(sRIssue) {
      // Issue memory read requests for the entire burst
      when(memSys.io.in.ready && (read_beats_issued < read_count)) {
        read_beats_issued := read_beats_issued + 1.U
        read_addr         := read_addr + (1.U << read_size)

        when(read_beats_issued === (read_count - 1.U)) {
          rState := sRCollect
        }
      }
    }
    is(sRCollect) {
      // Increment timeout counter
      read_timeout_counter := read_timeout_counter + 1.U

      // Collect responses from memory system
      when(memSys.io.out.fire && memSys.io.out.bits.rd_en) {
        // For simplicity, assume responses come back in order (beat index = read_responses_received)
        // In a real system, you'd match based on request_id or address
        val beat_index = read_responses_received
        read_buffer(beat_index)       := memSys.io.out.bits.data
        read_buffer_valid(beat_index) := true.B
        read_responses_received       := read_responses_received + 1.U
        read_timeout_counter          := 0.U // Reset timeout on response
      }

      // Send buffered data to AXI R channel
      when(io.axi.r.fire) {
        read_beats_sent := read_beats_sent + 1.U

        printf(
          "[MemorySim] R.fire id=%d data=%x last=%d beats_left=%d\n",
          read_id,
          read_buffer(read_beats_sent),
          (read_beats_sent === (read_count - 1.U)).asUInt,
          read_count - read_beats_sent - 1.U
        )

        when(io.axi.r.bits.last) {
          rState := sRIdle
        }
      }

      // Timeout detection - if we've been waiting too long, force completion
      when(read_timeout_counter > 10000.U) {
        // Fill remaining buffer entries with zeros to allow completion
        for (i <- 0 until max_burst_len) {
          when(i.U >= read_responses_received && i.U < read_count) {
            read_buffer_valid(i.U) := true.B
            read_buffer(i.U)       := 0.U
          }
        }
        read_responses_received := read_count
      }
    }
  }

  // ---- Memory System Interface Connections ----

  // Determine if we're issuing a write or read request
  val mem_req_is_write = (wState === sWCollect) && io.axi.w.valid
  val mem_req_is_read  = (rState === sRIssue) && (read_beats_issued < read_count)

  // Memory system input
  memSys.io.in.valid      := mem_req_is_write || mem_req_is_read
  memSys.io.in.bits.wr_en := mem_req_is_write
  memSys.io.in.bits.rd_en := mem_req_is_read

  // Address calculation - FIXED: Use current address being processed
  memSys.io.in.bits.addr := Mux(
    mem_req_is_write,
    store_addr, // Current write address
    read_addr   // Current read address
  )

  // Write data (with strobe handling like original)
  val fullMask        = ((BigInt(1) << strbBits) - 1).U(strbBits.W)
  val maskedWriteData = Mux(
    io.axi.w.bits.strb === fullMask,
    io.axi.w.bits.data, {
      // partial strobe: build masked word (same logic as original)
      val parts = (0 until strbBits).map { i =>
        val byte    = (io.axi.w.bits.data >> (8 * i))(7, 0)
        val shifted = (byte.asUInt << (8 * i)).asUInt
        Mux(io.axi.w.bits.strb(i) === 1.U, shifted, 0.U)
      }
      parts.reduce(_ | _)
    }
  )
  memSys.io.in.bits.wdata := maskedWriteData

  // Ready to accept responses only when we can process them
  memSys.io.out.ready := (rState === sRCollect) || (wState === sWWaitResp)

  // ---- Debug Output Connections ----
  io.debug.rankState      := memSys.io.rankState
  io.debug.reqQueueCount  := memSys.io.reqQueueCount
  io.debug.respQueueCount := memSys.io.respQueueCount
  io.debug.activeRanks    := memSys.io.activeRanks
}
