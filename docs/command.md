make run-binary CONFIG=RocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log

make run-binary CONFIG=MemorySimRocketConfig BINARY=/workspace/chipyard/.conda-env/riscv-tools/riscv64-unknown-elf/share/riscv-tests/benchmarks/memcpy.riscv -B | tee run.log