package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import memorysim.memctrl._

/** SimMemorySimExecutor — wire-driven mem requests, with split read wait/respond states
  *
  *   - Single transactionActive + currentIsRead toggle
  *   - memWrapper.io.in.* are wires computed from FSM state and currentIsRead
  *   - Read accept (mem -> capture) and read respond (present to AXI) are separated
  *   - Write now includes an explicit accept state (sWAccept) that waits for memWrapper out
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
  val wordBytes = (dataBits / 8)
  val strbBits  = wordBytes

  require(wordBytes > 0, "wordBytes must be > 0")

  val words = (memSize / wordBytes).toLong
  val depth = if (words <= Int.MaxValue) words.toInt else Int.MaxValue

  val memParams = SingleChannelMemoryConfigurationParams(
    memConfiguration = MemoryConfigurationParameters(
      addressWidth = addrBits,
      dataWidth = dataBits,
      numberOfChannels = 1,
      numberOfRanks = 2,
      numberOfBanks = 8,
      memoryQueueSize = 8
    ),
    bankConfiguration = DRAMBankParameters(),
    controllerConfiguration = MemoryControllerParameters(
      queueSize = 8,
      openPagePolicy = true
    ),
    trackPerformance = true
  )

  val localConfig = LocalConfigurationParameters(
    channelIndex = 0,
    rankIndex = 0,
    bankIndex = 0,
    verbose = true
  )

  val memWrapper = Module(new SyncReadMemWrapper(depth, memParams, localConfig))

  // Arbiter instance
  val arb = Module(new AXIRequestArbiter(params))

  // FSM states
  val sWIdle :: sWCollect :: sWIssue :: sWAccept :: sWResp :: Nil = Enum(5)
  val wState                                                      = RegInit(sWIdle)

  // NOTE: rState now has separate Accept and Respond states
  val sRIdle :: sRIssue :: sRAccept :: sRRespond :: Nil = Enum(4)
  val rState                                            = RegInit(sRIdle)

  // Transaction-level register: indicates an arbified transaction is active and whether it's a read
  val transactionActive = RegInit(false.B)
  val currentIsRead     = RegInit(false.B)

  // Write metadata
  val store_id    = RegInit(0.U(params.idBits.W))
  val store_addr  = RegInit(0.U(addrBits.W))
  val store_count = RegInit(0.U(8.W))
  val store_size  = RegInit(0.U(3.W))

  // Read metadata
  val read_id    = RegInit(0.U(params.idBits.W))
  val read_addr  = RegInit(0.U(addrBits.W))
  val read_count = RegInit(0.U(8.W))
  val read_size  = RegInit(0.U(3.W))

  // Read pipeline
  val read_data_valid = RegInit(false.B)
  val read_data       = RegInit(0.U(dataBits.W))

  // Beat staging for writes (must be registers because W channel is streaming)
  val beat_addr_reg = RegInit(0.U(addrBits.W))
  val beat_data_reg = RegInit(0.U(dataBits.W))

  // -------------------------
  // Arbiter wiring
  // -------------------------
  arb.io.ar.valid     := io.axi.ar.valid
  arb.io.ar.bits.id   := io.axi.ar.bits.id
  arb.io.ar.bits.addr := io.axi.ar.bits.addr
  arb.io.ar.bits.len  := io.axi.ar.bits.len
  arb.io.ar.bits.size := io.axi.ar.bits.size
  io.axi.ar.ready     := arb.io.ar.ready

  arb.io.aw.valid     := io.axi.aw.valid
  arb.io.aw.bits.id   := io.axi.aw.bits.id
  arb.io.aw.bits.addr := io.axi.aw.bits.addr
  arb.io.aw.bits.len  := io.axi.aw.bits.len
  arb.io.aw.bits.size := io.axi.aw.bits.size
  io.axi.aw.ready     := arb.io.aw.ready

  // Arbiter only hands off when no transactionActive (strict serialization).
  arb.io.out.ready := !transactionActive

  // -------------------------
  // When arbiter hands off, capture metadata and set transactionActive/currentIsRead.
  // -------------------------
  when(arb.io.out.fire) {
    transactionActive := true.B
    currentIsRead     := arb.io.out.bits.isRead

    when(arb.io.out.bits.isRead) {
      read_id    := arb.io.out.bits.id
      read_addr  := arb.io.out.bits.addr
      read_count := arb.io.out.bits.len + 1.U
      read_size  := arb.io.out.bits.size
      rState     := sRIssue // start read ISSUE (mem request will be a wire)
      printf(
        "[MemorySim] AR(arb) id=%d addr=%x len=%d size=%d\n",
        arb.io.out.bits.id,
        arb.io.out.bits.addr,
        arb.io.out.bits.len,
        arb.io.out.bits.size
      )
    }.otherwise {
      store_id    := arb.io.out.bits.id
      store_addr  := arb.io.out.bits.addr
      store_count := arb.io.out.bits.len + 1.U
      store_size  := arb.io.out.bits.size
      wState      := sWCollect // start write collect to gather W beats
      printf(
        "[MemorySim] AW(arb) id=%d addr=%x len=%d size=%d\n",
        arb.io.out.bits.id,
        arb.io.out.bits.addr,
        arb.io.out.bits.len,
        arb.io.out.bits.size
      )
    }
  }

  // -------------------------
  // Drive AXI W/B and R channels (unchanged semantics except read valid)
  // -------------------------
  io.axi.w.ready     := (wState === sWCollect)
  io.axi.b.valid     := (wState === sWResp)
  io.axi.b.bits.id   := store_id
  io.axi.b.bits.resp := 0.U

  // Now R.valid only in the Respond state and when we have captured data
  io.axi.r.valid     := (rState === sRRespond) && read_data_valid
  io.axi.r.bits.id   := read_id
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.data := read_data
  io.axi.r.bits.last := (read_count === 1.U)

  // -------------------------
  // Compose memWrapper.io.in as wires (combinational)
  // -------------------------
  val mem_in_valid = WireDefault(false.B)
  val mem_in_rd    = WireDefault(false.B)
  val mem_in_wr    = WireDefault(false.B)
  val mem_in_addr  = WireDefault(0.U(addrBits.W))
  val mem_in_data  = WireDefault(0.U(dataBits.W))

  when(transactionActive && currentIsRead && (rState === sRIssue)) {
    mem_in_valid := true.B
    mem_in_rd    := true.B
    mem_in_wr    := false.B
    mem_in_addr  := read_addr
    mem_in_data  := 0.U
  }

  when(transactionActive && !currentIsRead && (wState === sWIssue)) {
    mem_in_valid := true.B
    mem_in_rd    := false.B
    mem_in_wr    := true.B
    mem_in_addr  := beat_addr_reg
    mem_in_data  := beat_data_reg
  }

  memWrapper.io.in.valid      := mem_in_valid
  memWrapper.io.in.bits.rd_en := mem_in_rd
  memWrapper.io.in.bits.wr_en := mem_in_wr
  memWrapper.io.in.bits.addr  := mem_in_addr
  memWrapper.io.in.bits.wdata := mem_in_data

  // memWrapper responses accepted only when we're explicitly waiting to accept them:
  // - reads: rRAccept
  // - writes: sWAccept (we now wait for mem out ack before moving on)
  memWrapper.io.out.ready := (rState === sRAccept) || (wState === sWAccept)

  // -------------------------
  // WRITE FSM (now with sWAccept)
  // -------------------------
  switch(wState) {
    is(sWIdle) { /* idle */ }

    is(sWCollect) {
      when(io.axi.w.fire) {
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
        beat_addr_reg := store_addr
        beat_data_reg := newBeat

        store_addr  := store_addr + (1.U << store_size)
        store_count := store_count - 1.U

        wState := sWIssue
      }
    }

    is(sWIssue) {
      // Wait for the mem wrapper to accept the write request
      when(memWrapper.io.in.fire && mem_in_wr) {
        // After the write is accepted into the memory subsystem, wait for its response on out
        wState := sWAccept
      }
    }

    is(sWAccept) {
      // Wait for memWrapper to produce the write completion (out.fire with wr_en)
      when(memWrapper.io.out.fire && memWrapper.io.out.bits.out.wr_en) {
        when(store_count > 0.U) {
          wState := sWCollect
        }.otherwise {
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

  // -------------------------
  // READ FSM (split Accept and Respond)
  // -------------------------
  switch(rState) {
    is(sRIdle) {
      // idle
    }

    is(sRIssue) {
      // Assert mem_in via wires; wait for memWrapper to accept the request
      when(memWrapper.io.in.fire && mem_in_rd) {
        // move to accept state where we capture mem.out
        rState := sRAccept
      }
    }

    is(sRAccept) {
      // FIX: Only capture read data here, in this state
      when(memWrapper.io.out.fire && memWrapper.io.out.bits.out.rd_en) {
        read_data       := memWrapper.io.out.bits.out.data
        read_data_valid := true.B
        // move to respond state to present to AXI R
        rState          := sRRespond
      }
    }

    is(sRRespond) {
      // Present read_data to AXI and wait for AXI R handshake
      when(read_data_valid && io.axi.r.fire) {
        read_count      := read_count - 1.U
        read_addr       := read_addr + (1.U << read_size)
        read_data_valid := false.B

        when(read_count > 1.U) {
          // request next beat
          rState := sRIssue
        }.otherwise {
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

  // -------------------------
  // Clear transactionActive when both FSMs idle (transaction completed)
  // -------------------------
  when(transactionActive && (wState === sWIdle) && (rState === sRIdle)) {
    transactionActive := false.B
    currentIsRead     := false.B
  }

  // Debug prints
  printf(
    "Bridge rState=%d wState=%d transActive=%d isRead=%d mem.in.vld=%d mem.in.rdy=%d mem.out.vld=%d mem.out.rdy=%d\n",
    rState,
    wState,
    transactionActive,
    currentIsRead,
    memWrapper.io.in.valid,
    memWrapper.io.in.ready,
    memWrapper.io.out.valid,
    memWrapper.io.out.ready
  )

}
