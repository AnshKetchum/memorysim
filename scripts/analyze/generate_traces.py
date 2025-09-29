#!/usr/bin/env python3
"""
Generate synthetic memory-access traces.

Output format per line:
  0x{ADDRESS:08X} {READ|WRITE}  {CYCLE}

Options:
  --num-entries   Number of lines to generate (required)
  --address-bits  Address width in bits (default 32)
  --alignment     Address alignment in bytes (default 8)
  --seed          RNG seed (optional)
  --start-cycle   Starting cycle number (default random)
  --out           Output file path (default stdout)
"""
import argparse
import random
import sys

OPS = ["READ", "WRITE"]

def rnd_addr_in_range(bits, alignment):
    max_addr = (1 << bits) - 1
    # pick an address that's a multiple of alignment and fits in bits
    # ensure it's at least alignment-1 less than max so alignment rounding stays within range
    rand = random.randrange(0, (1 << bits))
    addr = (rand // alignment) * alignment
    return addr & max_addr

def format_line(addr, op, cycle, address_bits):
    # hex width depend on bits (32 bits -> 8 hex digits, etc)
    hex_width = max(8, (address_bits + 3) // 4)  # at least 8 for aesthetics
    return f"0x{addr:0{hex_width}X} {op}  {cycle}"

def generate_trace(num_entries, address_bits=32, alignment=8, seed=None, start_cycle=None):
    if seed is not None:
        random.seed(seed)

    # initial cycle
    if start_cycle is None:
        cycle = random.randint(0, 1000)
    else:
        cycle = start_cycle

    entries = []

    # pattern probabilities/parameters
    # We'll mix:
    # - sequential runs (back-to-back addresses)
    # - bursts of alternating read/write
    # - interleaving streams (two addresses alternating)
    # - random jumps (spatial randomness)
    # - local clustered accesses
    i = 0
    # keep a small working set to support interleaving patterns
    working_set = [rnd_addr_in_range(address_bits, alignment) for _ in range(8)]

    while i < num_entries:
        # choose pattern
        p = random.random()
        if p < 0.28 and i + 1 < num_entries:
            # sequential run
            run_len = random.randint(2, min(12, num_entries - i))
            base = rnd_addr_in_range(address_bits, alignment)
            stride = random.choice([alignment, alignment * 2, alignment * 4])
            op = random.choice(OPS)
            for k in range(run_len):
                addr = (base + k * stride) & ((1 << address_bits) - 1)
                entries.append((addr, op, cycle))
                cycle += random.choice([1,2])  # tight cycles for back-to-back
                i += 1
                if i >= num_entries: break

        elif p < 0.50 and i + 2 < num_entries:
            # alternating read/write burst on same or successive addresses
            burst_len = random.randint(2, min(16, num_entries - i))
            base = rnd_addr_in_range(address_bits, alignment)
            stride = random.choice([0, alignment, alignment*2])
            for k in range(burst_len):
                addr = (base + (k % 2) * stride) & ((1 << address_bits) - 1)
                op = OPS[k % 2]
                entries.append((addr, op, cycle))
                cycle += random.choice([1,2,3])
                i += 1
                if i >= num_entries: break

        elif p < 0.68 and i + 3 < num_entries:
            # interleaving two-streams (use working_set)
            a = random.choice(working_set)
            b = rnd_addr_in_range(address_bits, alignment)
            working_set[random.randrange(len(working_set))] = b
            stream_len = random.randint(4, min(32, num_entries - i))
            for k in range(stream_len):
                addr = a if (k % 2 == 0) else b
                op = random.choice(OPS) if k % 3 != 0 else ("READ" if random.random() < 0.7 else "WRITE")
                entries.append((addr, op, cycle))
                cycle += random.choice([2,3,4])
                i += 1
                if i >= num_entries: break

        elif p < 0.86:
            # small cluster of locality then jump
            cluster_base = rnd_addr_in_range(address_bits, alignment)
            remaining = num_entries - i
            if remaining <= 1:
                cluster_size = 1
            else:
                cluster_size = random.randint(2, min(10, remaining))
            for k in range(cluster_size):
                offset = random.choice([0, alignment, alignment*2, alignment*4, alignment*8])
                addr = (cluster_base + offset) & ((1 << address_bits) - 1)
                op = random.choices(OPS, weights=[0.7,0.3])[0]  # more reads
                entries.append((addr, op, cycle))
                cycle += random.choice([1,2,3,5])
                i += 1
                if i >= num_entries: break

        else:
            # random single-access jump
            addr = rnd_addr_in_range(address_bits, alignment)
            op = random.choices(OPS, weights=[0.6,0.4])[0]
            entries.append((addr, op, cycle))
            cycle += random.randint(1, 20)
            i += 1

    return entries

def main():
    parser = argparse.ArgumentParser(description="Generate memory-access trace.")
    parser.add_argument("--num-entries", "-n", type=int, required=True, help="Number of trace lines to generate")
    parser.add_argument("--address-bits", "-b", type=int, default=32, help="Address space width in bits (default 32)")
    parser.add_argument("--alignment", "-a", type=int, default=8, help="Alignment in bytes (default 8)")
    parser.add_argument("--seed", type=int, default=None, help="RNG seed (optional)")
    parser.add_argument("--start-cycle", type=int, default=None, help="Start cycle number (optional)")
    parser.add_argument("--out", "-o", type=str, default=None, help="Output file (default stdout)")
    args = parser.parse_args()

    entries = generate_trace(num_entries=args.num_entries,
                             address_bits=args.address_bits,
                             alignment=args.alignment,
                             seed=args.seed,
                             start_cycle=args.start_cycle)

    # write
    out_f = open(args.out, "w") if args.out else sys.stdout
    for addr, op, cycle in entries:
        out_f.write(format_line(addr, op, cycle, args.address_bits) + "\n")
    if args.out:
        out_f.close()

if __name__ == "__main__":
    main()
