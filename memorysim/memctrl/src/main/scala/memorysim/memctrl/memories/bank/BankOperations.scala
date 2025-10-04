package memorysim.memctrl

import chisel3._
import chisel3.util._

object DRAMOp {
  val ACTIVATE        = 0.U(4.W)
  val READ            = 1.U(4.W)
  val WRITE           = 2.U(4.W)
  val READ_PRECHARGE  = 3.U(4.W)
  val WRITE_PRECHARGE = 4.U(4.W)
  val PRECHARGE       = 5.U(4.W)
  val REFRESH         = 6.U(4.W)
  val SREF_ENTER      = 7.U(4.W)
  val SREF_EXIT       = 8.U(4.W)
  val INVALID_OP      = 15.U(4.W)
  val N_OPS           =
    16 // For now, we'll just set this to a convenient power of two. This isn't REALLY the number of operations.
  // Scala ints for matching
  val ACTIVATE_INT   = 0
  val READ_INT       = 1
  val WRITE_INT      = 2
  val RP_INT         = 3
  val WP_INT         = 4
  val PRE_INT        = 5
  val REF_INT        = 6
  val SREF_ENTER_INT = 7
  val SREF_EXIT_INT  = 8
}
