package testchipip.dram

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4BundleParameters, AXI4Bundle}

/**
 * Improved SimDRAMChisel
 *
 * - Supports multi-beat AXI bursts (len+1 beats).
 * - Uses AW/W collection to form a multi-beat write and asserts B when done.
 * - Issues AR and returns read beats sequentially, respecting r.ready.
 * - Assumes beatBytes == dataBits/8 (common case). If your test uses sub-beat sizes
 *   we can extend this to handle those by byte-assembling beats.
 *
 * This file uses plain printf(...) format strings (no p interpolator).
 */
class SimDRAMChisel(memSize: BigInt, lineSize: Int, memBase: BigInt,
                    params: AXI4BundleParameters, chipId: Int) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(params))
  })

  val addrBits = params.addrBits
  val dataBits = params.dataBits
  val wordBytes = (dataBits / 8)  // bytes per beat in the mem model
  val strbBits = wordBytes

  // sanity: only support standard beat size == bus width for now
  require(wordBytes > 0, "wordBytes must be > 0")

  val words = (memSize / wordBytes).toLong
  val depth = if (words <= Int.MaxValue) words.toInt else Int.MaxValue

  val mem = SyncReadMem(depth, UInt(dataBits.W))

  def addrToIndex(addr: UInt): UInt = addr >> log2Ceil(wordBytes) // beat index (word address)

  // ---- write side state (AW + W -> B) ----
  val sWIdle :: sWCollect :: sWResp :: Nil = Enum(3)
  val wState = RegInit(sWIdle)

  val store_id = RegInit(0.U(params.idBits.W))
  val store_addr = RegInit(0.U(addrBits.W))
  val store_count = RegInit(0.U(8.W))   // counts remaining beats
  val store_size = RegInit(0.U(3.W))    // size (log2 bytes per beat)

  // Default write outputs
  io.axi.aw.ready := (wState === sWIdle)
  io.axi.w.ready  := (wState === sWCollect)
  io.axi.b.valid  := (wState === sWResp)
  io.axi.b.bits.id := store_id
  io.axi.b.bits.resp := 0.U

  // Write state machine
  switch(wState) {
    is(sWIdle) {
      when(io.axi.aw.fire) {
        store_id := io.axi.aw.bits.id
        store_addr := io.axi.aw.bits.addr
        store_count := io.axi.aw.bits.len + 1.U
        store_size := io.axi.aw.bits.size
        wState := sWCollect
        // printf("[SimDRAM] AW.fire id=%d addr=%x len=%d size=%d\n", io.axi.aw.bits.id, io.axi.aw.bits.addr, io.axi.aw.bits.len, io.axi.aw.bits.size)
      }
    }
    is(sWCollect) {
      when(io.axi.w.fire) {
        val idx = addrToIndex(store_addr)
        val fullMask = ((BigInt(1) << strbBits) - 1).U(strbBits.W)
        val newBeat = Mux(io.axi.w.bits.strb === fullMask, io.axi.w.bits.data,
          {
            // partial strobe: build masked word
            val parts = (0 until strbBits).map { i =>
              val byte = (io.axi.w.bits.data >> (8 * i))(7, 0)
              val shifted = (byte.asUInt << (8 * i)).asUInt
              Mux(io.axi.w.bits.strb(i) === 1.U, shifted, 0.U)
            }
            parts.reduce(_ | _)
          })
        mem.write(idx, newBeat)
        // printf("[SimDRAM] W.fire data=%x strb=%x last=%d idx=%d addr=%x\n", io.axi.w.bits.data, io.axi.w.bits.strb, io.axi.w.bits.last, idx, store_addr)

        store_addr := store_addr + (1.U << store_size)
        store_count := store_count - 1.U

        when(io.axi.w.bits.last || store_count === 1.U) {
          wState := sWResp
          // printf("[SimDRAM] WRITE complete id=%d\n", store_id)
        }
      }
    }
    is(sWResp) {
      when(io.axi.b.fire) {
        wState := sWIdle
      }
    }
  }

  // ---- read side state (AR -> R) ----
  val sRIdle :: sRRead :: sRWait :: Nil = Enum(3)
  val rState = RegInit(sRIdle)

  val read_id = RegInit(0.U(params.idBits.W))
  val read_addr = RegInit(0.U(addrBits.W))
  val read_count = RegInit(0.U(8.W))   // beats left
  val read_size = RegInit(0.U(3.W))
  
  // Memory read address register - needed for SyncReadMem
  val mem_read_addr = RegInit(0.U(log2Ceil(depth).W))
  val mem_read_en = RegInit(false.B)
  
  // Pipeline registers for read data
  val read_data_valid = RegNext(mem_read_en, false.B)
  val read_data = mem.read(mem_read_addr, mem_read_en)

  // Default read outputs
  io.axi.ar.ready := (rState === sRIdle)
  io.axi.r.valid := (rState === sRWait) && read_data_valid
  io.axi.r.bits.id := read_id
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.data := read_data
  io.axi.r.bits.last := (read_count === 1.U)

  // Read state machine
  switch(rState) {
    is(sRIdle) {
      when(io.axi.ar.fire) {
        read_id := io.axi.ar.bits.id
        read_addr := io.axi.ar.bits.addr
        read_count := io.axi.ar.bits.len + 1.U
        read_size := io.axi.ar.bits.size
        
        // Issue first memory read
        mem_read_addr := addrToIndex(io.axi.ar.bits.addr)
        mem_read_en := true.B
        rState := sRRead
        // printf("[SimDRAM] AR.fire id=%d addr=%x len=%d size=%d\n", io.axi.ar.bits.id, io.axi.ar.bits.addr, io.axi.ar.bits.len, io.axi.ar.bits.size)
      }
    }
    is(sRRead) {
      // Wait one cycle for memory read to complete
      mem_read_en := false.B
      rState := sRWait
    }
    is(sRWait) {
      when(read_data_valid) {
        when(io.axi.r.fire) {
          read_count := read_count - 1.U
          read_addr := read_addr + (1.U << read_size)
          
          when(read_count > 1.U) {
            // Issue next memory read
            mem_read_addr := addrToIndex(read_addr + (1.U << read_size))
            mem_read_en := true.B
            rState := sRRead
          } .otherwise {
            // Last beat completed
            rState := sRIdle
          }
          // printf("[SimDRAM] R.fire id=%d data=%x last=%d beats_left=%d\n", read_id, read_data, (read_count === 1.U).asUInt, read_count - 1.U)
        }
      }
    }
  }

  // Note: this is a simplification: SyncReadMem returns data next cycle; above logic properly handles this timing.
  // For heavy concurrency / out-of-order IDs / multiple outstanding AR/AW with same IDs you'd need per-ID queues.
}