# Running Chipyard Benchmarks

## DRAMSim 2 Comparison 

To compare output with DRAMSim, go ahead and run the following benchmark to establish a baseline - 

```bash
make run-binary CONFIG=RocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log
```

## Running MemorySim 

To run MemorySim, go ahead and run the following benchmark

```bash
make run-binary CONFIG=MemorySimRocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log
```


### Running the dev container
```bash
docker run -d --privileged --network=host -it --rm --user root \
  --memory=32g --memory-swap=64g \
  --name chipyard-development-environment \
  -v /tmp/.X11-unix:/tmp/.X11-unix \
  eyeamansh/chipyard-dev:latest
```