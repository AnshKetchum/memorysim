package memorysim.memctrl

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PhysicalMemoryModuleSpec extends AnyFreeSpec with Matchers {

  // -----------------------
  // DRAM Flow Test Helpers
  // -----------------------

  private def sendCmd(
    dut:  PhysicalMemoryModuleBase,
    addr: UInt,
    data: UInt,
    op:   UInt
  ): Unit = {
    dut.io.memCmd.bits.addr.poke(addr)
    dut.io.memCmd.bits.data.poke(data)
    dut.io.memCmd.bits.op.poke(op)
    dut.io.memCmd.valid.poke(true.B)
    while (!dut.io.memCmd.ready.peek().litToBoolean) { dut.clock.step() }
    dut.clock.step()
    dut.io.memCmd.valid.poke(false.B)
  }

  private def expectResp(
    dut: PhysicalMemoryModuleBase,
    expAddr: UInt,
    expData: UInt,
    maxCycles: Int = 500
  ): Unit = {
    var cycles = 0
    while (!dut.io.phyResp.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < maxCycles, s"Timed out after $maxCycles cycles waiting for response")
    dut.io.phyResp.valid.expect(true.B)
    dut.io.phyResp.bits.addr.expect(expAddr)
    dut.io.phyResp.bits.data.expect(expData)
    dut.clock.step()
  }

  private def dramFlowSpec(name: String, instantiate: => PhysicalMemoryModuleBase): Unit = {
    s"$name DRAM Flow" - {
      "should support activate → write / read → refresh" in {
        simulate(instantiate) { dut =>
          dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.clock.step()
          dut.io.phyResp.ready.poke(true.B)

          val base = 0x10.U
          val pat  = "hABCD".U

          println("IN DRAM FLOW SPEC")

          // init ACTIVATE
          sendCmd(dut, base, 0.U, DRAMOp.ACTIVATE)
          expectResp(dut, base, 0.U)

          // READ (expect default 0)
          sendCmd(dut, base, 0.U, DRAMOp.READ)
          expectResp(dut, base, 0.U)

          // PRECHARGE
          sendCmd(dut, base, 0.U, DRAMOp.PRECHARGE)
          expectResp(dut, base, 0.U)

          // ACTIVATE before WRITE
          sendCmd(dut, base, 0.U, DRAMOp.ACTIVATE)
          expectResp(dut, base, 0.U)

          // WRITE pat
          sendCmd(dut, base, pat, DRAMOp.WRITE)
          expectResp(dut, base, 0.U)

          // PRECHARGE again
          sendCmd(dut, base, 0.U, DRAMOp.PRECHARGE)
          expectResp(dut, base, 0.U)

          // REFRESH
          sendCmd(dut, base, 0.U, DRAMOp.REFRESH)
          expectResp(dut, base, 0.U)
        }
      }
    }
  }

  // ----------------
  // Test Invocation
  // DRAM flow tests
  val memParams   = MemoryConfigurationParameters()
  val bankParams  = DRAMBankParameters()
  val localConfig = LocalConfigurationParameters(
    channelIndex = 0,
    rankIndex = 0,
    bankIndex = 0
  )

  println("[PhysicalMemorySpec] In here.")
  dramFlowSpec("Channel", new Channel(memParams, bankParams, localConfig))
  dramFlowSpec("Rank", new Rank(memParams, bankParams, localConfig))
}
