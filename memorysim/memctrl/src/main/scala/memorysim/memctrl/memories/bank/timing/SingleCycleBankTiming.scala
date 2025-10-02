package memorysim.memctrl

import chisel3._
import chisel3.util._

class SingleCycleTimingEngine(
  params:      DRAMBankParameters,
  memConfig:   MemoryConfigurationParameters,
  localConfig: LocalConfigurationParameters)
    extends Module {
  val io = IO(new Bundle {
    val cmd        = Flipped(Decoupled(new BankMemoryCommand(memConfig)))
    val waitCycles = Output(UInt(memConfig.globalCycleCountBits.W))
  })

  // Prev/Cur ops (4-bit to match DRAMOp.* widths)
  val INVALID_OP = DRAMOp.INVALID_OP
  val prevOp = RegInit(INVALID_OP)

  // Use op field directly (assume lower 4 bits encode DRAMOp)
  val currOp = Wire(UInt(memConfig.dataWidth.W))
  currOp := INVALID_OP // default invalid op
  when(io.cmd.valid) {
    // safely grab low 4 bits of op (in case width differs)
    currOp := io.cmd.bits.op

  }

  // When a new command fires, shift curr->prev
  when(io.cmd.fire) {
    if (localConfig.verbose) {
      printf("Received command - op = %d\n", io.cmd.bits.op(3,0))
      printf("Prev = %d, Cur = %d Wait = %d\n", prevOp, currOp, io.waitCycles)
    }
    prevOp := currOp
  }

  val tXS = 1.U(32.W) // params.tXS.U(32.W)

  // Derived delays:
  val read_to_read_l    = 1.U(32.W)
  val read_to_write     = 1.U(32.W)
  val read_to_precharge = 1.U(32.W)
  val readp_to_activate = 1.U(32.W)

  val write_to_read_l    = 1.U(32.W)
  val write_to_write_l   = 1.U(32.W)
  val write_to_precharge = 1.U(32.W)
  val writep_to_activate = 1.U(32.W)

  val precharge_to_activate = 1.U(32.W)

  val activate_to_act_l     = 1.U(32.W)
  val activate_to_read      = 1.U(32.W)
  val activate_to_write     = 1.U(32.W)
  val activate_to_precharge = 1.U(32.W)

  val refresh_to_activate = 1.U(32.W)

  // ----------------------------------------------------------------
  // 2) same-bank timing matrix
  // ----------------------------------------------------------------
  val timing = VecInit(Seq.tabulate(DRAMOp.N_OPS) { i =>
    VecInit(Seq.tabulate(DRAMOp.N_OPS) { j =>
      (i, j) match {
        case (DRAMOp.READ_INT, DRAMOp.READ_INT)  => read_to_read_l
        case (DRAMOp.READ_INT, DRAMOp.WRITE_INT) => read_to_write
        case (DRAMOp.READ_INT, DRAMOp.PRE_INT)   => read_to_precharge
        case (DRAMOp.READ_INT, DRAMOp.RP_INT)    => read_to_read_l
        case (DRAMOp.READ_INT, DRAMOp.WP_INT)    => read_to_write

        case (DRAMOp.WRITE_INT, DRAMOp.READ_INT)  => write_to_read_l
        case (DRAMOp.WRITE_INT, DRAMOp.WRITE_INT) => write_to_write_l
        case (DRAMOp.WRITE_INT, DRAMOp.PRE_INT)   => write_to_precharge
        case (DRAMOp.WRITE_INT, DRAMOp.RP_INT)    => write_to_read_l
        case (DRAMOp.WRITE_INT, DRAMOp.WP_INT)    => write_to_write_l

        case (DRAMOp.RP_INT, DRAMOp.ACTIVATE_INT) => readp_to_activate
        case (DRAMOp.RP_INT, DRAMOp.REF_INT)      => readp_to_activate
        // RP -> SREF_ENTER (kept behavior similar to before)
        case (DRAMOp.RP_INT, DRAMOp.SREF_ENTER_INT)     => readp_to_activate

        case (DRAMOp.WP_INT, DRAMOp.ACTIVATE_INT) => writep_to_activate
        case (DRAMOp.WP_INT, DRAMOp.REF_INT)      => writep_to_activate
        // WP -> SREF_ENTER
        case (DRAMOp.WP_INT, DRAMOp.SREF_ENTER_INT)     => writep_to_activate

        case (DRAMOp.ACTIVATE_INT, DRAMOp.ACTIVATE_INT) => activate_to_act_l
        case (DRAMOp.ACTIVATE_INT, DRAMOp.READ_INT)     => activate_to_read
        case (DRAMOp.ACTIVATE_INT, DRAMOp.WRITE_INT)    => activate_to_write
        case (DRAMOp.ACTIVATE_INT, DRAMOp.PRE_INT)      => activate_to_precharge

        case (DRAMOp.PRE_INT, DRAMOp.ACTIVATE_INT) => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.REF_INT)      => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.SREF_ENTER_INT) => precharge_to_activate

        case (DRAMOp.REF_INT, DRAMOp.ACTIVATE_INT) => refresh_to_activate
        case (DRAMOp.REF_INT, DRAMOp.SREF_ENTER_INT)     => refresh_to_activate

        // SREF_ENTER staying in SREF_ENTER -> tXS
        case (DRAMOp.SREF_ENTER_INT, DRAMOp.SREF_ENTER_INT) => tXS

        case _ => 1.U(32.W)
      }
    })
  })

  // ----------------------------------------------------------------
  // 3) Final lookup
  // ----------------------------------------------------------------
  when(prevOp === INVALID_OP || currOp === INVALID_OP) {
    io.waitCycles := 0.U
  }.otherwise {
    // prevOp and currOp are 4-bit UInts; timing expects Int indices
    // use .asUInt for indexing (Vec index will accept UInt that fits)
    io.waitCycles := timing(prevOp)(currOp)
  }

  io.cmd.ready := true.B
}
