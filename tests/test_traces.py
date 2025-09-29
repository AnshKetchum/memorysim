import subprocess
import pytest
from pathlib import Path

# Path to the compiled Verilator simulation binary
SIM_BIN = Path("./obj_dir/VMultiChannelSystem")

# Directory containing trace files
TRACE_DIR = Path("tests/resources")

# Time limit per test in seconds (5 minutes = 300s)
TIME_LIMIT = 300


def get_trace_files():
    """Collect all .txt trace files under TRACE_DIR."""
    return sorted(TRACE_DIR.rglob("*.txt"))


@pytest.mark.parametrize("trace_file", get_trace_files())
def test_trace_runs(trace_file):
    """
    Run the simulation for each trace file and ensure it exits cleanly
    within the TIME_LIMIT. Print simulator stdout.
    """
    cmd = [
        str(SIM_BIN),
        "-t", str(trace_file),
        "-c", str(500_000_000_000),  # large max cycle limit
        "-m", str(500),  # per request bound
    ]
    try:
        result = subprocess.run(
            cmd,
            check=True,
            timeout=TIME_LIMIT,
            capture_output=True,
            text=True,
        )
        # Print stdout after successful execution
        print(f"\n--- STDOUT for {trace_file} ---\n{result.stdout}\n--- END STDOUT ---\n")

    except subprocess.TimeoutExpired:
        pytest.fail(f"Simulation timed out for {trace_file} after {TIME_LIMIT}s")

    except subprocess.CalledProcessError as e:
        pytest.fail(
            f"Simulation failed for {trace_file}\n"
            f"stdout:\n{e.stdout}\n\nstderr:\n{e.stderr}"
        )
