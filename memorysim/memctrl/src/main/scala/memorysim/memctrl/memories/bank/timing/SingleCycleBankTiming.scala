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
    val waitCycles = Output(UInt(32.W))
  })

  // Prev/Cur ops
  val prevOp = RegInit(DRAMOp.N_OPS.U)
  // Decode incoming command into DRAMOp
  val cs_p   = Wire(Bool()); cs_p  := !io.cmd.bits.cs
  val ras_p  = Wire(Bool()); ras_p := !io.cmd.bits.ras
  val cas_p  = Wire(Bool()); cas_p := !io.cmd.bits.cas
  val we_p   = Wire(Bool()); we_p  := !io.cmd.bits.we

  val currOp = Wire(UInt(3.W))
  currOp := DRAMOp.N_OPS.U // default invalid op

  switch(Cat(cs_p, ras_p, cas_p, we_p)) {
    is("b1100".U) { currOp := DRAMOp.ACTIVATE } // cs=1 ras=1 cas=0 we=0
    is("b1010".U) { currOp := DRAMOp.READ } // cs=1 ras=0 cas=1 we=0
    is("b1011".U) { currOp := DRAMOp.WRITE } // cs=1 ras=0 cas=1 we=1
    is("b1101".U) { currOp := DRAMOp.PRECHARGE } // cs=1 ras=1 cas=0 we=1
    is("b1110".U) { currOp := DRAMOp.REFRESH } // cs=1 ras=1 cas=1 we=0
    is("b1111".U) { currOp := DRAMOp.SREF_ENTER } // cs=1 ras=1 cas=1 we=1
  }

  // When a new command fires, shift curr->prev and decode new opcode
  when(io.cmd.fire) {
    if (localConfig.verbose) {
      printf(
        "Received command - cs = %d ras = %d cas = %d we = %d\n",
        io.cmd.bits.cs,
        io.cmd.bits.ras,
        io.cmd.bits.cas,
        io.cmd.bits.we
      )
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
  // 2) same‑bank timing matrix
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
        case (DRAMOp.RP_INT, DRAMOp.SREF_INT)     => readp_to_activate

        case (DRAMOp.WP_INT, DRAMOp.ACTIVATE_INT) => writep_to_activate
        case (DRAMOp.WP_INT, DRAMOp.REF_INT)      => writep_to_activate
        case (DRAMOp.WP_INT, DRAMOp.SREF_INT)     => writep_to_activate

        case (DRAMOp.ACTIVATE_INT, DRAMOp.ACTIVATE_INT) => activate_to_act_l
        case (DRAMOp.ACTIVATE_INT, DRAMOp.READ_INT)     => activate_to_read
        case (DRAMOp.ACTIVATE_INT, DRAMOp.WRITE_INT)    => activate_to_write
        case (DRAMOp.ACTIVATE_INT, DRAMOp.PRE_INT)      => activate_to_precharge

        case (DRAMOp.PRE_INT, DRAMOp.ACTIVATE_INT) => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.REF_INT)      => precharge_to_activate
        case (DRAMOp.PRE_INT, DRAMOp.SREF_INT)     => precharge_to_activate

        case (DRAMOp.REF_INT, DRAMOp.ACTIVATE_INT) => refresh_to_activate
        case (DRAMOp.REF_INT, DRAMOp.SREF_INT)     => refresh_to_activate

        case (DRAMOp.SREF_INT, DRAMOp.SREF_INT) => tXS

        case _ => 1.U(32.W)
      }
    })
  })

  // ----------------------------------------------------------------
  // 3) Final lookup
  // ----------------------------------------------------------------
  when(prevOp === DRAMOp.N_OPS.U || currOp === DRAMOp.N_OPS.U) {
    io.waitCycles := 0.U
  }.otherwise {
    io.waitCycles := timing(prevOp)(currOp)
  }

  io.cmd.ready := true.B
}
