package memorysim.memctrl

import chisel3._
import chisel3.util._

object DRAMOp {
  val ACTIVATE        = 0.U(3.W)
  val READ            = 1.U(3.W)
  val WRITE           = 2.U(3.W)
  val READ_PRECHARGE  = 3.U(3.W)
  val WRITE_PRECHARGE = 4.U(3.W)
  val PRECHARGE       = 5.U(3.W)
  val REFRESH         = 6.U(3.W)
  val SREF_ENTER      = 7.U(3.W)
  val N_OPS           = 8
  // Scala ints for matching
  val ACTIVATE_INT    = 0
  val READ_INT        = 1
  val WRITE_INT       = 2
  val RP_INT          = 3
  val WP_INT          = 4
  val PRE_INT         = 5
  val REF_INT         = 6
  val SREF_INT        = 7
}
