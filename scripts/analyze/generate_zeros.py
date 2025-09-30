#!/usr/bin/env python3
"""
Generate a hex file filled with zeroes for Chisel/Verilog memory initialization.

Each line will contain a zero word, sized according to the given bit width.

Arguments:
  --bit-width   Width of each word in bits (default 32)
  --num-lines   Number of lines/words to generate (default 100000)
  --out         Output file name (default zero_init.hex)
"""

import argparse

def main():
    parser = argparse.ArgumentParser(description="Generate zero-initialized hex file")
    parser.add_argument("--bit-width", type=int, default=32, help="Word width in bits")
    parser.add_argument("--num-lines", type=int, default=100000, help="Number of lines")
    parser.add_argument("--out", type=str, default="zero_init.hex", help="Output filename")
    args = parser.parse_args()

    # Calculate hex digits needed for given bit width
    hex_digits = (args.bit_width + 3) // 4  # round up to nearest nibble

    zero_word = "0".zfill(hex_digits)

    with open(args.out, "w") as f:
        for _ in range(args.num_lines):
            f.write(f"{zero_word}\n")

    print(f"Wrote {args.num_lines} lines of {args.bit_width}-bit zero words to {args.out}")

if __name__ == "__main__":
    main()
