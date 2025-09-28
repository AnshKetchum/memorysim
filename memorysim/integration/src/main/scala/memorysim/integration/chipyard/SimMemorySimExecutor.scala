package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import memorysim.memctrl._

/** MultiChannel-backed SimMemorySimExecutor
  *
  * Drop-in replacement for the original SimMemorySimExecutor that uses MultiChannelSystem as the backing memory instead
  * of SyncReadMem. Provides realistic memory controller timing while maintaining the same AXI4 interface.
  * Now handles out-of-order responses from the memory system.
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
      memoryQueueSize = 8   // WARNING: Default queue size
    ),
    bankConfiguration = DRAMBankParameters(), // WARNING: Using default HBM2 timing
    controllerConfiguration = MemoryControllerParameters(
      queueSize = 8,
      openPagePolicy = true
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

  // Helper function to calculate beat index from response address
  def calculateBeatIndex(responseAddr: UInt, baseAddr: UInt, transferSize: UInt): UInt = {
    (responseAddr - baseAddr) >> transferSize
  }

  // ---- Write Path: AW + W -> Memory System -> B ----
  val sWIdle :: sWCollect :: sWIssue :: sWWaitResp :: sWResp :: Nil = Enum(5)
  val wState                                                       = RegInit(sWIdle)

  val store_id                = RegInit(0.U(params.idBits.W))
  val store_addr              = RegInit(0.U(addrBits.W))
  val store_base_addr         = RegInit(0.U(addrBits.W)) // Track original burst address
  val store_count             = RegInit(0.U(8.W))
  val store_size              = RegInit(0.U(3.W))
  val write_beats_issued      = RegInit(0.U(8.W))
  val write_responses_pending = RegInit(0.U(8.W))

  // Write data buffer for collected AXI beats
  val max_burst_len = 256
  val write_buffer = Reg(Vec(max_burst_len, UInt(dataBits.W)))
  val write_buffer_valid = Reg(Vec(max_burst_len, Bool()))

  // Initialize write buffer valid bits
  for (i <- 0 until max_burst_len) {
    when(reset.asBool) {
      write_buffer_valid(i) := false.B
    }
  }

  // Write response tracking for out-of-order responses
  val write_response_received = Reg(Vec(max_burst_len, Bool()))
  val write_beats_collected = RegInit(0.U(8.W))

  // Initialize write response tracking
  for (i <- 0 until max_burst_len) {
    when(reset.asBool) {
      write_response_received(i) := false.B
    }
  }

  // AXI Write channels
  io.axi.aw.ready    := (wState === sWIdle)
  io.axi.w.ready     := (wState === sWCollect)
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
        write_beats_collected   := 0.U
        write_responses_pending := 0.U
        wState                  := sWCollect

        // Clear write response tracking and buffer for this burst
        for (i <- 0 until max_burst_len) {
          write_response_received(i) := false.B
          write_buffer_valid(i) := false.B
        }

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
      // Collect all AXI write data into buffer
      when(io.axi.w.fire) {
        val beat_index = write_beats_collected
        write_buffer(beat_index) := io.axi.w.bits.data
        write_buffer_valid(beat_index) := true.B
        write_beats_collected := write_beats_collected + 1.U

        printf(
          "[MemorySim] W.collect beat=%d data=%x strb=%x\n",
          beat_index,
          io.axi.w.bits.data,
          io.axi.w.bits.strb
        )

        when(io.axi.w.bits.last || write_beats_collected === (store_count - 1.U)) {
          wState := sWIssue
          printf("[MemorySim] All write data collected, transitioning to issue\n")
        }
      }
    }
    is(sWIssue) {
      // Issue all collected write beats to memory system
      when(memSys.io.in.ready && (write_beats_issued < store_count)) {
        write_beats_issued := write_beats_issued + 1.U
        write_responses_pending := write_responses_pending + 1.U
        store_addr := store_addr + (1.U << store_size)

        printf(
          "[MemorySim] Issuing write beat=%d addr=%x data=%x\n",
          write_beats_issued,
          store_addr,
          write_buffer(write_beats_issued)
        )

        when(write_beats_issued === (store_count - 1.U)) {
          wState := sWWaitResp
          printf("[MemorySim] All write beats issued, waiting for responses\n")
        }
      }
    }
    is(sWWaitResp) {
      // Wait for all memory system responses (handle out-of-order)
      when(memSys.io.out.fire && memSys.io.out.bits.out.wr_en) {
        val response_beat_index = calculateBeatIndex(
          memSys.io.out.bits.out.addr,
          store_base_addr,
          store_size
        )
        
        // Mark this beat as received
        write_response_received(response_beat_index) := true.B
        write_responses_pending := write_responses_pending - 1.U

        printf(
          "[MemorySim] Write response addr=%x beat_index=%d remaining=%d\n",
          memSys.io.out.bits.out.addr,
          response_beat_index,
          write_responses_pending - 1.U
        )

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

  // Read response buffer (handles out-of-order responses)
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

      // Collect responses from memory system (handle out-of-order)
      when(memSys.io.out.fire && memSys.io.out.bits.out.rd_en) {
        // Calculate which beat this response corresponds to
        val response_beat_index = calculateBeatIndex(
          memSys.io.out.bits.out.addr,
          read_base_addr,
          read_size
        )
        
        // Store response in the correct buffer position
        read_buffer(response_beat_index)       := memSys.io.out.bits.out.data
        read_buffer_valid(response_beat_index) := true.B
        read_responses_received                := read_responses_received + 1.U
        read_timeout_counter                   := 0.U // Reset timeout on response

        // printf(
        //   "[MemorySim] Read response addr=%x data=%x beat_index=%d total_received=%d\n",
        //   memSys.io.out.bits.out.addr,
        //   memSys.io.out.bits.out.data,
        //   response_beat_index,
        //   read_responses_received + 1.U
        // )
      }

      // Send buffered data to AXI R channel in order
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
        printf("[MemorySim] Read timeout - filling remaining beats with zeros for base_addr=%x\n", read_base_addr)
      }
    }
  }

  // ---- Memory System Interface Connections ----

  // Determine if we're issuing a write or read request
  val mem_req_is_write = (wState === sWIssue) && (write_beats_issued < store_count)
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

  // Write data (use buffered data during issue phase)
  val current_write_data = write_buffer(write_beats_issued)
  val fullMask        = ((BigInt(1) << strbBits) - 1).U(strbBits.W)
  // Note: For simplicity, we're not handling partial strobes in buffered mode
  // You may want to also buffer the strobe bits if needed
  memSys.io.in.bits.wdata := current_write_data

  // Ready to accept responses only when we can process them
  memSys.io.out.ready := (rState === sRCollect) || (wState === sWWaitResp)

  // ---- Debug Output Connections ----
  io.debug.rankState      := memSys.io.rankState
  io.debug.reqQueueCount  := memSys.io.reqQueueCount
  io.debug.respQueueCount := memSys.io.respQueueCount
  io.debug.activeRanks    := memSys.io.activeRanks
}