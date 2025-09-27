#!/usr/bin/env python3
import csv
import argparse
import os

def convert_csvtrace(in_path, out_path):
    assert os.path.exists(in_path)
    with open(in_path, newline='') as fin, open(out_path, 'w') as fout:
        reader = csv.DictReader(fin, skipinitialspace=True)

        for row in reader:
            print(row)
            addr = int(row["Address"])
            cycle = int(row["Cycle"])
            is_read = int(row["Read"]) == 1
            is_write = int(row["Write"]) == 1
            data = int(row["Write Data"])

            if is_read:
                fout.write(f"0x{addr:08X} READ  {cycle}\n")
            if is_write:
                fout.write(f"0x{addr:08X} WRITE 0x{data:X} {cycle}\n")

def main():
    parser = argparse.ArgumentParser(
        description="Convert a CSV memory trace into a READ/WRITE trace format."
    )
    parser.add_argument("input", help="Input CSV file")
    parser.add_argument(
        "-o", "--output",
        default="trace.txt",
        help="Output trace file (default: trace.txt)"
    )

    args = parser.parse_args()
    convert_csvtrace(args.input, args.output)
    print(f"👉 Wrote {args.output}")

if __name__ == "__main__":
    main()
