// File: src/main/scala/memctrl/BankModel.scala
package memorysim.memctrl

import chisel3._
import chisel3.util._

class RequestPacket(params: MemoryConfigurationParameters) extends Bundle {
  val request_id           = UInt(params.requestIDBits.W) // External request ID (0 for internal operations)
  val internal_req_id      = UInt(params.requestIDBits.W) // Internal operation ID (increments for refreshes, etc.)
  val channel_id           = UInt(params.requestIDBits.W)
  val rank_id              = UInt(params.requestIDBits.W)
  val bank_id              = UInt(params.requestIDBits.W) // bank_id = scheduler_id since 1:1 mapping
  val scheduler_identifier = UInt(params.requestIDBits.W) // Unique identifier for this scheduler instance
}

/** Memory Command interface (to external memory) * */
class PhysicalMemoryCommand(params: MemoryConfigurationParameters) extends Bundle {
  val addr       = UInt(params.addressWidth.W)
  val data       = UInt(params.dataWidth.W)
  val op         = UInt(params.dataWidth.W) // Encoded DRAM operation
  val request_id = new RequestPacket(params)
}

/** Physical Memory Response interface * */
class PhysicalMemoryResponse(params: MemoryConfigurationParameters) extends Bundle {
  val addr       = UInt(params.addressWidth.W)
  val data       = UInt(params.dataWidth.W)
  val request_id = new RequestPacket(params)
}

/** Generic Physical Memory I/O: decoupled command in, decoupled response out * */
class PhysicalMemoryIO(params: MemoryConfigurationParameters) extends Bundle {

  /** Input command from controller * */
  val memCmd = Flipped(Decoupled(new PhysicalMemoryCommand(params)))

  /** Output response back to controller * */
  val phyResp = Decoupled(new PhysicalMemoryResponse(params))

  /** Output active sub-memories count * */
  val activeSubMemories = Output(UInt(32.W)) // Track number of active sub-memories
}

/** Memory Command interface (to external memory) * */
class BankMemoryCommand(params: MemoryConfigurationParameters) extends Bundle {
  val addr             = UInt(params.addressWidth.W)
  val data             = UInt(params.dataWidth.W)
  val op               = UInt(params.dataWidth.W) // Encoded DRAM operation
  val request_id       = new RequestPacket(params)
  val lastColBankGroup = UInt(32.W)
  val lastColCycle     = UInt(32.W)
}

/** Physical Memory Response interface * */
class BankMemoryResponse(params: MemoryConfigurationParameters) extends Bundle {
  val addr       = UInt(params.addressWidth.W)
  val data       = UInt(params.dataWidth.W)
  val request_id = new RequestPacket(params)
}

/** Physical Memory I/O for DRAMBank: decoupled command in, decoupled response out * */
class PhysicalBankIO(params: MemoryConfigurationParameters) extends Bundle {

  /** Input command from controller * */
  val memCmd = Flipped(Decoupled(new BankMemoryCommand(params)))

  /** Output response back to controller * */
  val phyResp = Decoupled(new BankMemoryResponse(params))

  val waitCycles = Input(UInt(32.W)) // cycles to wait before processing

  /** Output active sub-memories count * */
  val activeSubMemories = Output(UInt(32.W)) // Track number of active sub-memories
}

/** HBM2 timing parameters + ACK constant - Minimized for fast simulation */
case class DRAMBankParameters(
  // Reduced memory size for faster simulation
  numRows:     Int = 1024, // Reduced from 32767 to 1024 (still plenty for testing)
  numCols:     Int = 256,  // Reduced from 2048 to 256
  deviceWidth: Int = 128,  // Keep same - affects data width, not timing

  // Basic timing - keep minimal
  tCK:         Int = 1, // Keep at 1 cycle
  burst_cycle: Int = 0, // Keep at 0
  RL:          Int = 0, // Keep at 0
  WL:          Int = 0, // Keep at 0
  AL:          Int = 0, // Keep at 0
  tRTP:        Int = 0, // Keep at 0

  // Core latencies - reduced but maintain relative relationships
  CL:  Int = 4, // Reduced from 14 to 4 (read latency)
  CWL: Int = 2, // Reduced from 4 to 2 (write latency)

  // RCD timing - critical path delays
  tRCDRD: Int = 4, // Reduced from 14 to 4
  tRCDWR: Int = 4, // Reduced from 14 to 4
  tRP:    Int = 4, // Reduced from 14 to 4 (precharge time)

  // Row timing
  tRAS: Int = 8, // Reduced from 34 to 8 (row active time)

  // Refresh timing - most critical for simulation speed
  tRFC:     Int = 8,  // Reduced from 268 via tXS, now set directly
  tSelfRFC: Int = 1000,
  tREFI:    Int = 32, // Reduced from 30 but made power of 2 for easier testing
  tREFIb:   Int = 16, // Reduced from 128 to 16

  // Precharge timing
  tRPRE: Int = 1, // Keep minimal
  tWPRE: Int = 1, // Keep minimal

  // Bank-to-bank timing
  tRRD_S: Int = 2, // Reduced from 4 to 2
  tRRD_L: Int = 3, // Reduced from 6 to 3
  tWTR_S: Int = 2, // Reduced from 6 to 2
  tWTR_L: Int = 4, // Reduced from 8 to 4
  tFAW:   Int = 8, // Reduced from 30 to 8

  // Write recovery
  tWR: Int = 4, // Reduced from 16 to 4

  // Column-to-column timing
  tCCD_S: Int = 1, // Keep at 1
  tCCD_L: Int = 2, // Keep at 2

  // Power-down and self-refresh timing
  tXS:    Int = 16, // Dramatically reduced from 268 to 16
  tCKE:   Int = 2,  // Reduced from 8 to 2
  tCKSRE: Int = 4,  // Reduced from 10 to 4
  tXP:    Int = 2,  // Reduced from 8 to 2

  // Additional RTP timing
  tRTP_L: Int = 3, // Reduced from 6 to 3
  tRTP_S: Int = 2, // Reduced from 4 to 2
  tRTRS:  Int = 0, // Keep at 0

  /** Constant to return as 'ACK' on non-data operations */
  ack: Int = 0) {
  require(numRows > 0 && numCols > 0, "numRows and numCols must be positive")
  val addressSpaceSize = numRows * numCols
  val ackData: UInt = ack.U(32.W)
}

case class MemoryConfigurationParameters(
  globalCycleCountBits: Int = 64,
  dataWidth:            Int = 32,
  addressWidth:         Int = 32,
  requestIDBits:        Int = 64,
  numberOfChannels:     Int = 1,
  numberOfRanks:        Int = 2,
  numberOfBanks:        Int = 8,
  memoryQueueSize:      Int = 8)

case class LocalConfigurationParameters(
  channelIndex: Int = 0,
  rankIndex:    Int = 0,
  bankIndex:    Int = 0,
  verbose:      Boolean = false)

/** Base class for any non-bank module exposing a PhysicalMemoryIO interface
  */
abstract class PhysicalMemoryModuleBase(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new PhysicalMemoryIO(params))
}

/** Base class for the bank module
  */
abstract class PhysicalBankModuleBase(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new PhysicalBankIO(params))
}
