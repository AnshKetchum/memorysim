package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import memorysim.memctrl._

/** Bridge that converts AXI4 protocol to MultiChannelSystem interface
  * 
  * This module sits between AXI4 masters and the MultiChannelSystem, handling:
  * - AXI4 burst transactions (converting to individual memory requests)
  * - Write data collection and response generation
  * - Read data buffering and streaming
  * - Request ID tracking for proper response routing
  */
class AXIToMultiChannelBridge(
  memSize: BigInt, 
  lineSize: Int, 
  memBase: BigInt, 
  params: AXI4BundleParameters,
  memParams: SingleChannelMemoryConfigurationParams,
  localConfig: LocalConfigurationParameters,
  chipId: Int
) extends Module {
  
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(params))
  })

  val addrBits = params.addrBits
  val dataBits = params.dataBits
  val wordBytes = (dataBits / 8)
  
  require(wordBytes > 0, "wordBytes must be > 0")
  require(dataBits == 32, "Currently only supports 32-bit data width to match MemorySystem")

  // Instantiate the MultiChannelSystem
  val memSys = Module(new MultiChannelSystem(memParams, localConfig))

  // ---- Write Path State Machine ----
  val sWIdle :: sWCollect :: sWResp :: Nil = Enum(3)
  val wState = RegInit(sWIdle)

  val store_id = RegInit(0.U(params.idBits.W))
  val store_addr = RegInit(0.U(addrBits.W))
  val store_count = RegInit(0.U(8.W))
  val store_size = RegInit(0.U(3.W))
  
  // Write transaction tracking
  val write_responses_pending = RegInit(0.U(8.W))

  // AXI Write Address Channel
  io.axi.aw.ready := (wState === sWIdle)

  // AXI Write Data Channel  
  io.axi.w.ready := (wState === sWCollect) && memSys.io.in.ready

  // AXI Write Response Channel
  io.axi.b.valid := (wState === sWResp) && (write_responses_pending === 0.U)
  io.axi.b.bits.id := store_id
  io.axi.b.bits.resp := 0.U

  // Memory System Input (for writes)
  val write_req_valid = (wState === sWCollect) && io.axi.w.valid
  
  // Write FSM
  switch(wState) {
    is(sWIdle) {
      when(io.axi.aw.fire) {
        store_id := io.axi.aw.bits.id
        store_addr := io.axi.aw.bits.addr
        store_count := io.axi.aw.bits.len + 1.U
        store_size := io.axi.aw.bits.size
        wState := sWCollect
        
        printf("[AXI-Bridge] AW.fire id=%d addr=%x len=%d size=%d\n",
          io.axi.aw.bits.id, io.axi.aw.bits.addr, 
          io.axi.aw.bits.len, io.axi.aw.bits.size)
      }
    }
    is(sWCollect) {
      when(io.axi.w.fire && memSys.io.in.ready) {
        store_addr := store_addr + (1.U << store_size)
        store_count := store_count - 1.U
        write_responses_pending := write_responses_pending + 1.U
        
        when(io.axi.w.bits.last || store_count === 1.U) {
          wState := sWResp
          printf("[AXI-Bridge] Write burst complete, waiting for %d responses\n", 
                 write_responses_pending + 1.U)
        }
      }
    }
    is(sWResp) {
      // Count down responses from memory system
      when(memSys.io.out.fire && memSys.io.out.bits.wr_en) {
        write_responses_pending := write_responses_pending - 1.U
      }
      
      // Send AXI response when all memory responses received
      when(io.axi.b.fire) {
        wState := sWIdle
        printf("[AXI-Bridge] B.fire id=%d\n", store_id)
      }
    }
  }

  // ---- Read Path State Machine ----
  val sRIdle :: sRIssue :: sRCollect :: Nil = Enum(3)
  val rState = RegInit(sRIdle)

  val read_id = RegInit(0.U(params.idBits.W))
  val read_addr = RegInit(0.U(addrBits.W))
  val read_count = RegInit(0.U(8.W))
  val read_size = RegInit(0.U(3.W))
  val read_issued = RegInit(0.U(8.W))
  val read_responses_received = RegInit(0.U(8.W))

  // Read data buffer to handle out-of-order responses
  val read_buffer = Reg(Vec(256, UInt(32.W))) // Max 256 beat burst
  val read_buffer_valid = Reg(Vec(256, Bool()))
  val read_buffer_ptr = RegInit(0.U(8.W))

  // AXI Read Address Channel
  io.axi.ar.ready := (rState === sRIdle)

  // AXI Read Data Channel
  val can_send_read_data = (rState === sRCollect) && 
                          read_buffer_valid(read_buffer_ptr) &&
                          (read_responses_received > read_buffer_ptr)

  io.axi.r.valid := can_send_read_data
  io.axi.r.bits.id := read_id
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.data := read_buffer(read_buffer_ptr)
  io.axi.r.bits.last := (read_buffer_ptr === (read_count - 1.U))

  // Read FSM
  switch(rState) {
    is(sRIdle) {
      when(io.axi.ar.fire) {
        read_id := io.axi.ar.bits.id
        read_addr := io.axi.ar.bits.addr
        read_count := io.axi.ar.bits.len + 1.U
        read_size := io.axi.ar.bits.size
        read_issued := 0.U
        read_responses_received := 0.U
        read_buffer_ptr := 0.U
        
        // Clear buffer valid bits
        for (i <- 0 until 256) {
          read_buffer_valid(i) := false.B
        }
        
        rState := sRIssue
        printf("[AXI-Bridge] AR.fire id=%d addr=%x len=%d size=%d\n",
          io.axi.ar.bits.id, io.axi.ar.bits.addr, 
          io.axi.ar.bits.len, io.axi.ar.bits.size)
      }
    }
    is(sRIssue) {
      // Issue memory requests for the burst
      when(memSys.io.in.ready && (read_issued < read_count)) {
        read_issued := read_issued + 1.U
        
        when(read_issued === (read_count - 1.U)) {
          rState := sRCollect
        }
      }
    }
    is(sRCollect) {
      // Collect responses and buffer them
      when(memSys.io.out.fire && memSys.io.out.bits.rd_en) {
        val beat_index = read_responses_received // Assuming in-order for simplicity
        read_buffer(beat_index) := memSys.io.out.bits.data
        read_buffer_valid(beat_index) := true.B
        read_responses_received := read_responses_received + 1.U
      }
      
      // Send buffered data to AXI
      when(io.axi.r.fire) {
        read_buffer_ptr := read_buffer_ptr + 1.U
        
        when(io.axi.r.bits.last) {
          rState := sRIdle
          printf("[AXI-Bridge] Read burst complete id=%d\n", read_id)
        }
      }
    }
  }

  // ---- Memory System Interface Connections ----
  
  // Input to memory system (combines write and read requests)
  val mem_req_is_write = write_req_valid
  val mem_req_is_read = (rState === sRIssue) && (read_issued < read_count)
  
  memSys.io.in.valid := mem_req_is_write || mem_req_is_read
  memSys.io.in.bits.wr_en := mem_req_is_write
  memSys.io.in.bits.rd_en := mem_req_is_read
  
  memSys.io.in.bits.addr := Mux(mem_req_is_write, 
    store_addr,
    read_addr + (read_issued << read_size))
    
  memSys.io.in.bits.wdata := io.axi.w.bits.data

  // Always ready to accept responses
  memSys.io.out.ready := true.B

  // Debug outputs (optional)
  when(memSys.io.in.fire) {
    printf("[AXI-Bridge] MemSys req: wr=%d rd=%d addr=%x data=%x\n",
      memSys.io.in.bits.wr_en, memSys.io.in.bits.rd_en,
      memSys.io.in.bits.addr, memSys.io.in.bits.wdata)
  }
  
  when(memSys.io.out.fire) {
    printf("[AXI-Bridge] MemSys resp: wr=%d rd=%d addr=%x data=%x id=%d\n",
      memSys.io.out.bits.wr_en, memSys.io.out.bits.rd_en,
      memSys.io.out.bits.addr, memSys.io.out.bits.data,
      memSys.io.out.bits.request_id)
  }
}

/** Drop-in replacement for SimMemorySimExecutor that uses MultiChannelSystem as backing store
  * 
  * This provides the same AXI4 interface but with realistic memory controller timing
  */
class RealisticMemorySimExecutor(
  memSize: BigInt, 
  lineSize: Int, 
  memBase: BigInt, 
  params: AXI4BundleParameters,
  memParams: SingleChannelMemoryConfigurationParams,
  localConfig: LocalConfigurationParameters,
  chipId: Int
) extends Module {
  
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(params))
    
    // Expose memory system debug signals
    val rankState = Output(Vec(memParams.memConfiguration.numberOfRanks, UInt(3.W)))
    val reqQueueCount = Output(UInt(4.W))
    val respQueueCount = Output(UInt(4.W))
    val activeRanks = Output(UInt(log2Ceil(memParams.memConfiguration.numberOfRanks + 1).W))
  })

  val bridge = Module(new AXIToMultiChannelBridge(
    memSize, lineSize, memBase, params, memParams, localConfig, chipId))
  
  // Connect AXI interface
  io.axi <> bridge.io.axi
  
  // Expose debug signals
  io.rankState := bridge.memSys.io.rankState
  io.reqQueueCount := bridge.memSys.io.reqQueueCount  
  io.respQueueCount := bridge.memSys.io.respQueueCount
  io.activeRanks := bridge.memSys.io.activeRanks

  printf("[RealisticMemSim] Active ranks: %d, Req queue: %d, Resp queue: %d\n",
    io.activeRanks, io.reqQueueCount, io.respQueueCount)
}