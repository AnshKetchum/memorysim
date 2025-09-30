package memorysim.integration

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class AXIRequestArbiter(params: AXI4BundleParameters) extends Module {
  val io = IO(new Bundle {
    val ar  = Flipped(Decoupled(new Bundle {
      val id   = UInt(params.idBits.W)
      val addr = UInt(params.addrBits.W)
      val len  = UInt(8.W)
      val size = UInt(3.W)
    }))
    val aw  = Flipped(Decoupled(new Bundle {
      val id   = UInt(params.idBits.W)
      val addr = UInt(params.addrBits.W)
      val len  = UInt(8.W)
      val size = UInt(3.W)
    }))
    val out = Decoupled(new Bundle {
      val isRead = Bool()
      val id     = UInt(params.idBits.W)
      val addr   = UInt(params.addrBits.W)
      val len    = UInt(8.W)
      val size   = UInt(3.W)
    })
  })

  // Track which channel has a pending request
  val arPending = RegInit(false.B)
  val awPending = RegInit(false.B)

  // Latch AR request when it arrives
  val arReg = Reg(chiselTypeOf(io.ar.bits))
  when(io.ar.fire) {
    arPending := true.B
    arReg     := io.ar.bits
  }.elsewhen(io.out.fire && io.out.bits.isRead) {
    arPending := false.B
  }

  // Latch AW request when it arrives
  val awReg = Reg(chiselTypeOf(io.aw.bits))
  when(io.aw.fire) {
    awPending := true.B
    awReg     := io.aw.bits
  }.elsewhen(io.out.fire && !io.out.bits.isRead) {
    awPending := false.B
  }

  // Accept new requests only when not pending
  io.ar.ready := !arPending
  io.aw.ready := !awPending

  // Arbitration: grant to whichever arrived first
  val grantAR = arPending && (!awPending || io.ar.fire && !io.aw.fire)

  io.out.valid       := arPending || awPending
  io.out.bits.isRead := grantAR
  io.out.bits.id     := Mux(grantAR, arReg.id, awReg.id)
  io.out.bits.addr   := Mux(grantAR, arReg.addr, awReg.addr)
  io.out.bits.len    := Mux(grantAR, arReg.len, awReg.len)
  io.out.bits.size   := Mux(grantAR, arReg.size, awReg.size)
}
