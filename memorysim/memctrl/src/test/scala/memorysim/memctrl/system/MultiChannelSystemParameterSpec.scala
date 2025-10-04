package memorysim.memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Verification spec for MultiChannelSystem focusing on parameter configurations.
  *
  * Tests various combinations of channels, ranks, banks, and queue sizes, starting with minimal configuration (all
  * parameters = 1) and scaling up.
  */
class MultiChannelSystemParameterSpec extends AnyFreeSpec with Matchers {

  "MultiChannelSystem with minimal configuration (all parameters = 1)" - {
    "should handle write and read with 1 channel, 1 rank, 1 bank, queue size 1" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 1 // Minimal queue
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 1, // Minimal queue
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

        dut.io.out.ready.poke(true.B)

        // Write
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h1000".U)
        dut.io.in.bits.wdata.poke("hABCD1234".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with minimal config")
        dut.clock.step()

        // Read
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
        assert(cycles < 1000, "Read timeout with minimal config")
        dut.io.out.bits.out.data.expect("hABCD1234".U)
      }
    }
  }

  "MultiChannelSystem scaling channel count" - {
    "should work with 2 channels (1 rank, 1 bank, queue 1)" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 2, // Scale channels to 2
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 1
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 1,
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

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h5000".U)
        dut.io.in.bits.wdata.poke("h22222222".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with 2 channels")
        dut.clock.step()

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h5000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with 2 channels")
        dut.io.out.bits.out.data.expect("h22222222".U)
      }
    }
  }

  "MultiChannelSystem scaling rank count" - {
    "should work with 2 ranks (1 channel, 1 bank, queue 1)" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 1,
          numberOfRanks = 2, // Scale ranks to 2
          numberOfBanks = 1,
          memoryQueueSize = 1
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 1,
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

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h6000".U)
        dut.io.in.bits.wdata.poke("h33333333".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with 2 ranks")
        dut.clock.step()

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h6000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with 2 ranks")
        dut.io.out.bits.out.data.expect("h33333333".U)
      }
    }
  }

  "MultiChannelSystem scaling bank count" - {
    "should work with 2 banks (1 channel, 1 rank, queue 1)" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 2, // Scale banks to 2
          memoryQueueSize = 1
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 1,
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

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h7000".U)
        dut.io.in.bits.wdata.poke("h44444444".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with 2 banks")
        dut.clock.step()

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h7000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with 2 banks")
        dut.io.out.bits.out.data.expect("h44444444".U)
      }
    }
  }

  "MultiChannelSystem scaling queue size" - {
    "should work with queue size of 2 (1 channel, 1 rank, 1 bank)" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 1,
          numberOfRanks = 1,
          numberOfBanks = 1,
          memoryQueueSize = 2 // Scale queue to 2
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 2, // Scale queue to 2
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

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h8000".U)
        dut.io.in.bits.wdata.poke("h55555555".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with queue size 2")
        dut.clock.step()

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h8000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with queue size 2")
        dut.io.out.bits.out.data.expect("h55555555".U)
      }
    }
  }

  "MultiChannelSystem with combined scaling" - {
    "should work with 2 channels, 2 ranks, 2 banks, queue size 2" in {
      val params = SingleChannelMemoryConfigurationParams(
        memConfiguration = MemoryConfigurationParameters(
          addressWidth = 32,
          dataWidth = 32,
          numberOfChannels = 2,
          numberOfRanks = 2,
          numberOfBanks = 2,
          memoryQueueSize = 2
        ),
        bankConfiguration = DRAMBankParameters(),
        controllerConfiguration = MemoryControllerParameters(
          queueSize = 2,
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

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.wr_en.poke(true.B)
        dut.io.in.bits.rd_en.poke(false.B)
        dut.io.in.bits.addr.poke("h9000".U)
        dut.io.in.bits.wdata.poke("h99999999".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Write timeout with combined scaling")
        dut.clock.step()

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.rd_en.poke(true.B)
        dut.io.in.bits.wr_en.poke(false.B)
        dut.io.in.bits.addr.poke("h9000".U)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 1000, "Read timeout with combined scaling")
        dut.io.out.bits.out.data.expect("h99999999".U)
      }
    }
  }
}
