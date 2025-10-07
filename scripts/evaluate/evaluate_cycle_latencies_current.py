#!/usr/bin/env python3
"""
Memory Request Latency Distribution Plotter

This script reads the aggregated JSON file and plots latency distributions
for read and write requests separately.
"""

import argparse
import json
import matplotlib.pyplot as plt
import seaborn as sns
import numpy as np
from pathlib import Path


def load_json(json_path):
    """Load the aggregated JSON file"""
    with open(json_path, 'r') as f:
        return json.load(f)


def extract_external_requests(data):
    """Extract external requests (request_id > 0) and separate by read/write"""
    reads = []
    writes = []
    
    for request in data['requests']:
        request_id = request.get('request_id', 0)
        
        # Skip internal requests (refreshes)
        if request_id == 0:
            continue
        
        # Get total_time (latency)
        latency = request.get('total_time')
        
        # Skip if no latency data
        if latency is None or latency <= 0:
            continue
        
        # Categorize by read or write
        if request.get('read', False):
            reads.append(latency)
        elif request.get('write', False):
            writes.append(latency)
    
    return reads, writes


def compute_statistics(latencies):
    """Compute mean and 99th percentile"""
    if not latencies:
        return None, None
    
    mean = np.mean(latencies)
    p99 = np.percentile(latencies, 99)
    return mean, p99


def plot_latency_distributions(reads, writes, output_path):
    """Plot latency distributions for reads and writes"""
    # Set seaborn style
    sns.set(style="whitegrid", context="notebook")
    
    # Create figure with two subplots
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    
    # Color palette
    read_color = sns.color_palette("flare", n_colors=3)[0]
    write_color = sns.color_palette("mako", n_colors=3)[0]
    
    # Plot Read latencies
    if reads:
        ax = axes[0]
        mean_read, p99_read = compute_statistics(reads)
        
        ax.hist(reads, bins=50, alpha=0.7, color=read_color, edgecolor='black')
        ax.axvline(mean_read, color='red', linestyle='--', linewidth=2, 
                   label=f'Mean: {mean_read:.1f} cycles')
        ax.axvline(p99_read, color='orange', linestyle='--', linewidth=2,
                   label=f'99th %ile: {p99_read:.1f} cycles')
        
        ax.set_xlabel('Latency (cycles)', fontsize=12)
        ax.set_ylabel('Frequency', fontsize=12)
        ax.set_title('Read Request Latency Distribution', fontsize=14, fontweight='bold')
        ax.legend(loc='upper right', fontsize=10)
        ax.grid(True, linestyle="--", linewidth=0.5, alpha=0.7)
    else:
        axes[0].text(0.5, 0.5, 'No Read Requests', 
                     ha='center', va='center', fontsize=14)
        axes[0].set_title('Read Request Latency Distribution', fontsize=14, fontweight='bold')
    
    # Plot Write latencies
    if writes:
        ax = axes[1]
        mean_write, p99_write = compute_statistics(writes)
        
        ax.hist(writes, bins=50, alpha=0.7, color=write_color, edgecolor='black')
        ax.axvline(mean_write, color='red', linestyle='--', linewidth=2,
                   label=f'Mean: {mean_write:.1f} cycles')
        ax.axvline(p99_write, color='orange', linestyle='--', linewidth=2,
                   label=f'99th %ile: {p99_write:.1f} cycles')
        
        ax.set_xlabel('Latency (cycles)', fontsize=12)
        ax.set_ylabel('Frequency', fontsize=12)
        ax.set_title('Write Request Latency Distribution', fontsize=14, fontweight='bold')
        ax.legend(loc='upper right', fontsize=10)
        ax.grid(True, linestyle="--", linewidth=0.5, alpha=0.7)
    else:
        axes[1].text(0.5, 0.5, 'No Write Requests',
                     ha='center', va='center', fontsize=14)
        axes[1].set_title('Write Request Latency Distribution', fontsize=14, fontweight='bold')
    
    plt.tight_layout()
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"✅ Saved latency distribution plot to {output_path}")


def print_statistics(reads, writes):
    """Print summary statistics"""
    print("\n" + "="*50)
    print("LATENCY STATISTICS")
    print("="*50)
    
    if reads:
        mean_read, p99_read = compute_statistics(reads)
        print(f"\n📖 READ REQUESTS (n={len(reads)}):")
        print(f"   Mean Latency:        {mean_read:.2f} cycles")
        print(f"   99th Percentile:     {p99_read:.2f} cycles")
        print(f"   Min Latency:         {min(reads):.2f} cycles")
        print(f"   Max Latency:         {max(reads):.2f} cycles")
    else:
        print("\n📖 READ REQUESTS: None found")
    
    if writes:
        mean_write, p99_write = compute_statistics(writes)
        print(f"\n✏️  WRITE REQUESTS (n={len(writes)}):")
        print(f"   Mean Latency:        {mean_write:.2f} cycles")
        print(f"   99th Percentile:     {p99_write:.2f} cycles")
        print(f"   Min Latency:         {min(writes):.2f} cycles")
        print(f"   Max Latency:         {max(writes):.2f} cycles")
    else:
        print("\n✏️  WRITE REQUESTS: None found")
    
    print("\n" + "="*50)


def main():
    parser = argparse.ArgumentParser(
        description="Plot latency distributions for read and write requests from aggregated JSON"
    )
    parser.add_argument('json_file', type=str, default='out.json', nargs='?',
                       help='Path to aggregated JSON file (default: out.json)')
    parser.add_argument('--output', '-o', type=str, default='latency_distributions.png',
                       help='Output plot filename (default: latency_distributions.png)')
    
    args = parser.parse_args()
    
    # Check if JSON file exists
    json_path = Path(args.json_file)
    if not json_path.exists():
        print(f"❌ Error: JSON file '{args.json_file}' not found")
        return 1
    
    # Load data
    print(f"📂 Loading data from {args.json_file}...")
    data = load_json(json_path)
    
    # Extract read and write latencies
    print("📊 Extracting external requests...")
    reads, writes = extract_external_requests(data)
    
    # Print statistics
    print_statistics(reads, writes)
    
    # Plot distributions
    if not reads and not writes:
        print("\n⚠️  Warning: No external requests found with valid latency data")
        return 1
    
    print(f"\n🎨 Generating plots...")
    plot_latency_distributions(reads, writes, args.output)
    
    return 0


if __name__ == "__main__":
    exit(main())