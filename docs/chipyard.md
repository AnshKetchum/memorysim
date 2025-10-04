# Running Chipyard Benchmarks

## DRAMSim Comparison 

To compare output with DRAMSim, go ahead and run the following benchmark to establish a baseline - 

```bash
make run-binary CONFIG=RocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log
```

## Running MemorySim 

To run MemorySim, go ahead and run the following benchmark

```bash
make run-binary CONFIG=MemorySimRocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log
```