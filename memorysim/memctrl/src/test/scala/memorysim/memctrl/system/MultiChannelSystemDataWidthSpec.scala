package memorysim.memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Verification spec for MultiChannelSystem focusing on data width variations.
  *
  * Tests both 32-bit and 64-bit data widths to catch mismatches between configured data width and actual operation.
  */
class MultiChannelSystemDataWidthSpec extends AnyFreeSpec with Matchers {

  "MultiChannelSystem with 32-bit data width" - {
    "should correctly handle write followed by read" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32, // 32-bit data
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 8
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 8,
          openPagePolicy = true
        ),
        trackPerformance = false
      )

      val localConfig = LocalConfigurationParameters(
        channelIndex = 0,
        rankIndex = 0,
        bankIndex = 0,
        verbose = false
      )

      simulate(new MultiChannelSystem(params, localConfig)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        // Write 32-bit data
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h1000".U)
        dut.io.in.bits.wdata.poke("hDEADBEEF".U) // 32-bit value
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        dut.io.out.ready.poke(true.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with 32-bit data")
        dut.io.out.valid.expect(true.B)
        dut.clock.step()

        // Read back 32-bit data
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h1000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with 32-bit data")
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.out.data.expect("hDEADBEEF".U)
      }
    }

    "should handle multiple different 32-bit values" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 8
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 8,
          openPagePolicy = true
        ),
        trackPerformance = false
      )

      val localConfig = LocalConfigurationParameters()

      simulate(new MultiChannelSystem(params, localConfig)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.out.ready.poke(true.B)

        val testData = Seq(
          ("h0000".U, "h00000000".U),
          ("h0004".U, "hFFFFFFFF".U),
          ("h0008".U, "h12345678".U),
          ("h000C".U, "hA5A5A5A5".U)
        )

        // Write all values
        for ((addr, data) <- testData) {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.wr_en.poke(true.B)
          dut.io.in.bits.rd_en.poke(false.B)
          dut.io.in.bits.addr.poke(addr)
          dut.io.in.bits.wdata.poke(data)
          dut.clock.step()
          dut.io.in.valid.poke(false.B)

          var cycles = 0
          while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
            dut.clock.step()
            cycles += 1
          }
          assert(cycles < 1000, s"Write timeout for addr=$addr")
          dut.clock.step()
        }

        // Read back and verify
        for ((addr, expectedData) <- testData) {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.rd_en.poke(true.B)
          dut.io.in.bits.wr_en.poke(false.B)
          dut.io.in.bits.addr.poke(addr)
          dut.clock.step()
          dut.io.in.valid.poke(false.B)

          var cycles = 0
          while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
            dut.clock.step()
            cycles += 1
          }
          assert(cycles < 1000, s"Read timeout for addr=$addr")
          dut.io.out.bits.out.data.expect(expectedData)
          dut.clock.step()
        }
      }
    }
  }

  "MultiChannelSystem with 64-bit data width" - {
    "should correctly handle write followed by read" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 64, // 64-bit data
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 8
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 8,
          openPagePolicy = true
        ),
        trackPerformance = false
      )

      val localConfig = LocalConfigurationParameters(
        channelIndex = 0,
        rankIndex = 0,
        bankIndex = 0,
        verbose = false
      )

      simulate(new MultiChannelSystem(params, localConfig)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        // Write 64-bit data
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h2000".U)
        dut.io.in.bits.wdata.poke("hCAFEBABEDEADBEEF".U) // 64-bit value
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        dut.io.out.ready.poke(true.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with 64-bit data")
        dut.io.out.valid.expect(true.B)
        dut.clock.step()

        // Read back 64-bit data
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h2000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with 64-bit data")
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.out.data.expect("hCAFEBABEDEADBEEF".U)
      }
    }

    "should handle multiple different 64-bit values" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 64,
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 8
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 8,
          openPagePolicy = true
        ),
        trackPerformance = false
      )

      val localConfig = LocalConfigurationParameters()

      simulate(new MultiChannelSystem(params, localConfig)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.out.ready.poke(true.B)

        val testData = Seq(
          ("h0000".U, "h0000000000000000".U),
          ("h0008".U, "hFFFFFFFFFFFFFFFF".U),
          ("h0010".U, "h123456789ABCDEF0".U),
          ("h0018".U, "hFEDCBA9876543210".U),
          ("h0020".U, "h5555555555555555".U)
        )

        // Write all values
        for ((addr, data) <- testData) {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.wr_en.poke(true.B)
          dut.io.in.bits.rd_en.poke(false.B)
          dut.io.in.bits.addr.poke(addr)
          dut.io.in.bits.wdata.poke(data)
          dut.clock.step()
          dut.io.in.valid.poke(false.B)

          var cycles = 0
          while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
            dut.clock.step()
            cycles += 1
          }
          assert(cycles < 1000, s"Write timeout for addr=$addr")
          dut.clock.step()
        }

        // Read back and verify
        for ((addr, expectedData) <- testData) {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.rd_en.poke(true.B)
          dut.io.in.bits.wr_en.poke(false.B)
          dut.io.in.bits.addr.poke(addr)
          dut.clock.step()
          dut.io.in.valid.poke(false.B)

          var cycles = 0
          while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
            dut.clock.step()
            cycles += 1
          }
          assert(cycles < 1000, s"Read timeout for addr=$addr")
          dut.io.out.bits.out.data.expect(expectedData)
          dut.clock.step()
        }
      }
    }
  }
}
