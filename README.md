# MemorySim: An RTL-Level DRAM Controller and Memory Simulator Model for High Performance Memory Subsystems

Looking for contributions!

Edit 8/19/2025 - Check out our paper on MemorySim that's on [arXiV](https://arxiv.org/abs/2508.12636)! 

**MemorySim** is an RTL-native, high level memory simulator designed for the Chisel/Chipyard ecosystem and trace based simulations. It strives to provide cycle-accurate profiling of memory subsystems, enabling hardware designers to evaluate bandwidth, latency, and power-performance trade-offs in next-generation AI accelerators.

##  Chipyard API 

### Quickstart

  ```scala
    new chipyard.harness.WithMemorySimMem ++ 
  ```

### Grab and go config

Add this into `RocketConfigs.scala`.

  ```scala
  class MemorySimRocketConfig extends Config(
    new chipyard.harness.WithMemorySimMem(nChannels = 2, nRanks = 2, nBanks = 8) ++ /** add MemorySim DRAM model for axi4 backing memory, if axi4 mem is enabled */
    new freechips.rocketchip.rocket.WithNHugeCores(1) ++         // single rocket-core
    new chipyard.config.AbstractConfig)
  ```

## Standalone, Trace Based Simulations 

### Installation 

1. Elaborate Chisel into verilog

```bash 
  make verilog
```

2. Build the trace based simulator - 

```bash
  make verilator-trace
```

3. Run the traces 

See [tests/test_traces.py](tests/test_traces.py) as an example.

The general command is as follows 

```bash
./obj_dir/VMultiChannelSystem -t (PATH TO TRACE FILE) -c (MAX CYCLES TO RUN FOR) -m (UPPER BOUND ON TIME PER REQUEST)
```

## Features
- **High Fidelity Chipyard Integration** 
  Leverage MemorySim with other high quality Chipyard components to obtain accurate, cycle level hardware simulations.

- **RTL-Level Fidelity**  
  Implements bank-level finite-state machines (FSMs) and a comprehensive DRAM timing model entirely in hardware for bit-true data correctness.  
- **Cycle-Accurate DRAM Model**  
  Supports key JEDEC timing parameters (e.g., tRCD, tRP, tRFC) with closed-page policy and self-refresh modes.  
- **Backpressure Analysis**  
  Centralized request queue with multi-dequeue support to study the impact of queue depth on latency and throughput.  
- **Trace-Driven and Standalone Modes**  
  Run isolated trace-based experiments or co-simulate with full-system benchmarks.

## Architecture Overview

1. **Top-Level Interface**  
   - Frontend accepts memory trace requests (`addr`, `cycle`) and enqueues into `reqQueue`.  
2. **Memory Controller**  
   - Splits requests by rank and bank, dispatches to bank schedulers, and aggregates responses in `respQueue`.  
3. **Bank Scheduler**  
   - Enforces closed-page policy, manages ACTIVATE–READ/WRITE–PRECHARGE handshakes, and handles refresh/self-refresh states.  
4. **DRAM Timing Model**  
   - Tracks timing constraints (e.g., tRCD, tRP, tRFC) and issues acknowledgments after parameterized delays.  
5. **Physical Channel Hierarchy**  
   - Models channels, ranks, bank groups, and banks with round-robin arbitration for responses.

![MemorySim Architecture](docs/architecture_diagram.png)

## Experimentation & Results

We evaluated MemorySim against DRAMSim3 using four microbenchmarks: `conv2d.c`, `multihead_attention.c`, `trace_example.c`, and `vector_similarity.c`. Key findings:

- **Read/Write Overhead**  
  - Average read penalty: ~111 cycles  
  - Average write penalty: ~125 cycles  
- **Latency vs. Queue Depth**  
  - Exponential latency growth with larger `reqQueue` sizes; sub-80 cycles at queue size 2, >250 cycles at size 1024.  
- **Throughput–Latency Trade-off**  
  - Smaller queues reduce latency but can starve bank schedulers, lowering overall requests served.

For detailed metrics, refer to the results section of the [paper](docs/paper.pdf).

## Getting Started

### Prerequisites

- [Chisel](https://www.chisel-lang.org/) (Scala 2.13+, SBT)
- [Chipyard](https://github.com/chipsalliance/chipyard)

## Running Chipyard Benchmarks

See [Running Chipyard Benchmarks](docs/chipyard.md) for further details.

Trace generators and scripts are located in `benchmarks/`.

## Citation

If you use MemorySim in your research, please cite:

```
@misc{chaurasia2025memorysimrtlleveltimingaccurate,
      title={MemorySim: An RTL-level, timing accurate simulator model for the Chisel ecosystem}, 
      author={Ansh Chaurasia},
      year={2025},
      eprint={2508.12636},
      archivePrefix={arXiv},
      primaryClass={cs.AR},
      url={https://arxiv.org/abs/2508.12636}, 
}
```

## Resources
- If interested in development, consider using the batteries included development [devcontainer](https://hub.docker.com/repository/docker/eyeamansh/memorysim-dev/general) associated with the repo!

## Acknowledgements

Thanks to Professor Christopher Fletcher, Professor Sagar Karandikar, and Ph.D Tianrui Wei for their invaluable guidance through the process.
