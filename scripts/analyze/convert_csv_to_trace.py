#!/usr/bin/env python3
import csv
import argparse
import os

def convert_csvtrace(in_path, out_path, show_request_ids=False):
    assert os.path.exists(in_path)
    with open(in_path, newline='') as fin, open(out_path, 'w') as fout:
        reader = csv.DictReader(fin, skipinitialspace=True)

        for row in reader:
            req_id = int(row["RequestID"].strip())
            addr = int(row["Address"].strip())
            cycle = int(row["Cycle"].strip())
            is_read = int(row["Read"].strip()) == 1
            is_write = int(row["Write"].strip()) == 1
            data = int(row["Write Data"].strip())

            prefix = f"{req_id}: " if show_request_ids else ""
            if is_read:
                fout.write(f"{prefix}0x{addr:08X} READ  {cycle}\n")
            if is_write:
                fout.write(f"{prefix}0x{addr:08X} WRITE 0x{data:X} {cycle}\n")


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
    parser.add_argument(
        "--show-request-ids",
        action="store_true",
        help="Prepend RequestID to each trace line"
    )

    args = parser.parse_args()
    convert_csvtrace(args.input, args.output, args.show_request_ids)
    print(f"👉 Wrote {args.output}")


if __name__ == "__main__":
    main()
