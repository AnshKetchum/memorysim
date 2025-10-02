package memorysim.memctrl

import chisel3._
import chisel3.util._

class TimingEngine(
  params:      DRAMBankParameters,
  memConfig:   MemoryConfigurationParameters,
  localConfig: LocalConfigurationParameters)
    extends Module {
  val io = IO(new Bundle {
    val cmd        = Flipped(Decoupled(new BankMemoryCommand(memConfig)))
    val waitCycles = Output(UInt(memConfig.globalCycleCountBits.W))
  })

  // Prev/Cur ops
  val INVALID_OP = DRAMOp.INVALID_OP
  val prevOp     = RegInit(INVALID_OP)

  // Directly use op field from command
  val currOp = WireDefault(INVALID_OP)
  when(io.cmd.valid) {
    currOp := io.cmd.bits.op
  }

  when(io.cmd.fire) {
    if (localConfig.verbose) {
      printf("Received command - op = %d\n", io.cmd.bits.op)
      printf("Prev = %d, Cur = %d Wait = %d\n", prevOp, currOp, io.waitCycles)
    }
    prevOp := currOp
  }

  // ----------------------------------------------------------------
  // 1) Base DRAM timing params
  // ----------------------------------------------------------------
  val burst  = params.burst_cycle.U(32.W)
  val tCCD_L = params.tCCD_L.U(32.W)
  val tWTR_L = params.tWTR_L.U(32.W)
  val tRTRS  = params.tRTRS.U(32.W)
  val RL     = params.RL.U(32.W)
  val WL     = params.WL.U(32.W)
  val AL     = params.AL.U(32.W)
  val tRTP   = params.tRTP.U(32.W)
  val tWR    = params.tWR.U(32.W)
  val tRP    = params.tRP.U(32.W)
  val tRAS   = params.tRAS.U(32.W)
  val tRFC   = params.tRFC.U(32.W)
  val tRRD_L = params.tRRD_L.U(32.W)
  val tRCDRD = params.tRCDRD.U(32.W)
  val tRCDWR = params.tRCDWR.U(32.W)
  val CWL    = params.CWL.U(32.W)
  val tRTP_S = params.tRTP_S.U(32.W)
  val tXS    = params.tXS.U(32.W)

  // Derived delays
  val read_to_read_l    = Mux(burst > tCCD_L, burst, tCCD_L)
  val read_to_write     = RL + burst - WL + tRTRS
  val read_to_precharge = AL + tRTP
  val readp_to_activate = AL + burst + tRTP + tRP

  val write_to_read_l    = WL + burst + tRTRS - RL
  val write_to_write_l   = Mux(burst > tCCD_L, burst, tCCD_L)
  val write_to_precharge = WL + burst + tWR
  val writep_to_activate = write_to_precharge + tRP

  val precharge_to_activate = tRP

  val activate_to_act_l     = tRRD_L
  val activate_to_read      = tRCDRD
  val activate_to_write     = tRCDWR
  val activate_to_precharge = tRAS

  val refresh_to_activate = tRFC

  // ----------------------------------------------------------------
  // 2) same-bank timing matrix with new SREF ops
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

        case (DRAMOp.RP_INT, DRAMOp.ACTIVATE_INT)      => readp_to_activate
        case (DRAMOp.RP_INT, DRAMOp.REF_INT)           => readp_to_activate
        case (DRAMOp.RP_INT, DRAMOp.SREF_ENTER_INT)    => readp_to_activate

        case (DRAMOp.WP_INT, DRAMOp.ACTIVATE_INT)      => writep_to_activate
        case (DRAMOp.WP_INT, DRAMOp.REF_INT)           => writep_to_activate
        case (DRAMOp.WP_INT, DRAMOp.SREF_ENTER_INT)    => writep_to_activate

        case (DRAMOp.ACTIVATE_INT, DRAMOp.ACTIVATE_INT) => activate_to_act_l
        case (DRAMOp.ACTIVATE_INT, DRAMOp.READ_INT)     => activate_to_read
        case (DRAMOp.ACTIVATE_INT, DRAMOp.WRITE_INT)    => activate_to_write
        case (DRAMOp.ACTIVATE_INT, DRAMOp.PRE_INT)      => activate_to_precharge

        case (DRAMOp.PRE_INT, DRAMOp.ACTIVATE_INT)      => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.REF_INT)           => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.SREF_ENTER_INT)    => precharge_to_activate

        case (DRAMOp.REF_INT, DRAMOp.ACTIVATE_INT)      => refresh_to_activate
        case (DRAMOp.REF_INT, DRAMOp.SREF_ENTER_INT)    => refresh_to_activate

        case (DRAMOp.SREF_ENTER_INT, DRAMOp.SREF_ENTER_INT) => tXS
        case (DRAMOp.SREF_EXIT_INT, DRAMOp.ACTIVATE_INT)    => tXS
        case (DRAMOp.SREF_EXIT_INT, DRAMOp.READ_INT)        => tXS
        case (DRAMOp.SREF_EXIT_INT, DRAMOp.WRITE_INT)       => tXS

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
    io.waitCycles := timing(prevOp)(currOp)
  }

  io.cmd.ready := true.B
}
