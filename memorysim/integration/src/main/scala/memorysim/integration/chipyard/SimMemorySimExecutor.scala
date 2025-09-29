package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import memorysim.memctrl._

/** Minimal port of SimMemorySimExecutorDefault using MemorySystemIO
  *
  * This is the smallest possible change from the reference implementation, just replacing direct SyncReadMem access
  * with the MemorySystemIO interface.
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
  })

  val addrBits  = params.addrBits
  val dataBits  = params.dataBits
  val wordBytes = (dataBits / 8) // bytes per beat in the mem model
  val strbBits  = wordBytes

  // sanity: only support standard beat size == bus width for now
  require(wordBytes > 0, "wordBytes must be > 0")

  val words = (memSize / wordBytes).toLong
  val depth = if (words <= Int.MaxValue) words.toInt else Int.MaxValue

  // Replace SyncReadMem with SyncReadMemWrapper
  val memParams = SingleChannelMemoryConfigurationParams(
    memConfiguration = MemoryConfigurationParameters(
      addressWidth = addrBits,
      dataWidth = dataBits,
      numberOfChannels = 1,
      numberOfRanks = 2,
      numberOfBanks = 8,
      memoryQueueSize = 8
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
    channelIndex = 0,
    rankIndex = 0,
    bankIndex = 0
  )

  val memWrapper = Module(new SyncReadMemWrapper(depth, memParams, localConfig))
  // val memWrapper = Module(new MultiChannelSystem(memParams, localConfig))

  def addrToIndex(addr: UInt): UInt = addr >> log2Ceil(wordBytes) // beat index (word address)

  // ---- write side state (AW + W -> B) ----
  val sWIdle :: sWCollect :: sWWrite :: sWResp :: Nil = Enum(4)
  val wState                                          = RegInit(sWIdle)

  val store_id    = RegInit(0.U(params.idBits.W))
  val store_addr  = RegInit(0.U(addrBits.W))
  val store_count = RegInit(0.U(8.W)) // counts remaining beats
  val store_size  = RegInit(0.U(3.W)) // size (log2 bytes per beat)

  // ---- read side state (AR -> R) ----
  val sRIdle :: sRRead :: sRWait :: Nil = Enum(3)
  val rState                            = RegInit(sRIdle)

  val read_id    = RegInit(0.U(params.idBits.W))
  val read_addr  = RegInit(0.U(addrBits.W))
  val read_count = RegInit(0.U(8.W)) // beats left
  val read_size  = RegInit(0.U(3.W))

  // Memory interface signals
  val mem_req_valid = RegInit(false.B)
  val mem_req_read  = RegInit(false.B)
  val mem_req_write = RegInit(false.B)
  val mem_req_addr  = RegInit(0.U(addrBits.W))
  val mem_req_data  = RegInit(0.U(dataBits.W))

  // Read pipeline
  val read_data_valid = RegInit(false.B)
  val read_data       = RegInit(0.U(dataBits.W))

  // Default write outputs
  io.axi.aw.ready    := (wState === sWIdle)
  io.axi.w.ready     := (wState === sWCollect) && !mem_req_valid
  io.axi.b.valid     := (wState === sWResp) && !mem_req_valid // Wait for pending writes to complete
  io.axi.b.bits.id   := store_id
  io.axi.b.bits.resp := 0.U

  // Default read outputs
  io.axi.ar.ready    := (rState === sRIdle)
  io.axi.r.valid     := (rState === sRWait) && read_data_valid
  io.axi.r.bits.id   := read_id
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.data := read_data
  io.axi.r.bits.last := (read_count === 1.U)

  // Default memory interface - always initialized
  memWrapper.io.in.valid      := mem_req_valid
  memWrapper.io.in.bits.rd_en := mem_req_read
  memWrapper.io.in.bits.wr_en := mem_req_write
  memWrapper.io.in.bits.addr  := mem_req_addr
  memWrapper.io.in.bits.wdata := mem_req_data
  memWrapper.io.out.ready     := true.B

  // Write state machine - unchanged logic, just set control signals
  switch(wState) {
    is(sWIdle) {
      // Clear all memory request signals when idle
      mem_req_valid := false.B
      mem_req_read  := false.B
      mem_req_write := false.B
      mem_req_addr  := 0.U
      mem_req_data  := 0.U

      when(io.axi.aw.fire) {
        store_id    := io.axi.aw.bits.id
        store_addr  := io.axi.aw.bits.addr
        store_count := io.axi.aw.bits.len + 1.U
        store_size  := io.axi.aw.bits.size
        wState      := sWCollect
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
      when(io.axi.w.fire && !mem_req_valid) {
        val fullMask = ((BigInt(1) << strbBits) - 1).U(strbBits.W)
        val newBeat  = Mux(
          io.axi.w.bits.strb === fullMask,
          io.axi.w.bits.data, {
            val parts = (0 until strbBits).map { i =>
              val byte    = (io.axi.w.bits.data >> (8 * i))(7, 0)
              val shifted = (byte.asUInt << (8 * i)).asUInt
              Mux(io.axi.w.bits.strb(i) === 1.U, shifted, 0.U)
            }
            parts.reduce(_ | _)
          }
        )

        // Prepare memory request
        mem_req_valid := true.B
        mem_req_write := true.B
        mem_req_read  := false.B
        mem_req_addr  := store_addr
        mem_req_data  := newBeat

        store_addr  := store_addr + (1.U << store_size)
        store_count := store_count - 1.U

        wState := sWWrite
      }
    }

    is(sWWrite) {
      // Wait for memory system to accept the write
      when(memWrapper.io.in.fire && mem_req_write) {
        mem_req_valid := false.B
        mem_req_write := false.B

        when(store_count > 0.U) {
          // Expect more beats, go back to collect
          wState := sWCollect
        }.otherwise {
          // All beats sent, move to response
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

  // AXI write channel signals updated accordingly
  io.axi.aw.ready    := (wState === sWIdle)
  io.axi.w.ready     := (wState === sWCollect) && !mem_req_valid
  io.axi.b.valid     := (wState === sWResp)
  io.axi.b.bits.id   := store_id
  io.axi.b.bits.resp := 0.U

  // Read state machine
  switch(rState) {
    is(sRIdle) {
      // Clear all memory request signals when idle
      mem_req_valid   := false.B
      mem_req_read    := false.B
      mem_req_write   := false.B
      mem_req_addr    := 0.U
      mem_req_data    := 0.U
      read_data_valid := false.B

      when(io.axi.ar.fire) {
        read_id    := io.axi.ar.bits.id
        read_addr  := io.axi.ar.bits.addr
        read_count := io.axi.ar.bits.len + 1.U
        read_size  := io.axi.ar.bits.size

        // Issue first memory read
        mem_req_valid := true.B
        mem_req_read  := true.B
        mem_req_write := false.B
        mem_req_addr  := io.axi.ar.bits.addr
        mem_req_data  := 0.U

        rState := sRRead
        printf(
          "[MemorySim] AR.fire id=%d addr=%x len=%d size=%d\n",
          io.axi.ar.bits.id,
          io.axi.ar.bits.addr,
          io.axi.ar.bits.len,
          io.axi.ar.bits.size
        )
      }
    }
    is(sRRead) {
      // Wait for memory system to accept the read request (input handshake)
      when(memWrapper.io.in.fire && mem_req_read) {
        mem_req_valid := false.B
        mem_req_read  := false.B
        rState        := sRWait
      }
    }
    is(sRWait) {
      // Wait for memory response (output handshake) AND valid data
      when(memWrapper.io.out.fire && memWrapper.io.out.bits.out.rd_en) {
        read_data       := memWrapper.io.out.bits.out.data
        read_data_valid := true.B
      }

      when(read_data_valid && io.axi.r.fire) {
        read_count      := read_count - 1.U
        read_addr       := read_addr + (1.U << read_size)
        read_data_valid := false.B // Clear after sending

        when(read_count > 1.U) {
          // Issue next memory read
          mem_req_valid := true.B
          mem_req_read  := true.B
          mem_req_write := false.B
          mem_req_addr  := read_addr + (1.U << read_size)
          mem_req_data  := 0.U

          rState := sRRead
        }.otherwise {
          // Last beat completed
          rState := sRIdle
        }
        printf(
          "[MemorySim] R.fire id=%d data=%x last=%d beats_left=%d\n",
          read_id,
          read_data,
          (read_count === 1.U).asUInt,
          read_count - 1.U
        )
      }
    }
  }

  // Handle memory responses - moved outside state machine
  when(memWrapper.io.out.fire && memWrapper.io.out.bits.out.rd_en) {
    read_data       := memWrapper.io.out.bits.out.data
    read_data_valid := true.B
  }

}
