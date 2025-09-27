#!/usr/bin/env python3
"""
Memory System CSV Aggregator

This script processes CSV files from a memory system simulation and aggregates
the data into a comprehensive JSON file tracking both internal and external
requests through their entire journey.
"""

import argparse
import csv
import json
import os
import glob
import traceback
from collections import defaultdict
from typing import Dict, List, Any, Optional

# Command type lookup
COMMAND_TYPES = {
    0: "REFRESH",
    1: "PRECHARGE", 
    2: "ACTIVATE",
    3: "READ",
    4: "WRITE",
    5: "SELF_REFRESH_ENTER",
    6: "SELF_REFRESH_EXIT",
    7: "UNKNOWN"
}

class MemorySystemAggregator:
    def __init__(self, log_directory: str, num_ranks: int, num_banks: int):
        self.log_directory = log_directory
        self.num_ranks = num_ranks
        self.num_banks = num_banks
        self.requests = defaultdict(lambda: {
            "request_id": None,
            "internal_request_id": None,
            "channel_id": None,
            "rank_id": None,
            "bank_id": None,
            "address": None,
            "read": False,
            "write": False,
            "times": []
        })
        
    def safe_int(self, value: str) -> Optional[int]:
        """Safely convert string to int, return None if invalid"""
        try:
            return int(value.strip())
        except (ValueError, AttributeError):
            return None
    
    def safe_bool(self, value: str) -> bool:
        """Safely convert string to bool"""
        try:
            return bool(int(value.strip()))
        except (ValueError, AttributeError):
            return False
    
    def get_request_key(self, request_id: int, internal_request_id: int, 
                       rank_id: int, bank_id: int) -> str:
        """Generate a unique key for each request"""
        return f"req_{request_id}_int_{internal_request_id}_r{rank_id}_b{bank_id}"
    
    def process_input_request_stats(self):
        """Process the main input request stats file"""
        filepath = os.path.join(self.log_directory, "input_request_stats.csv")
        if not os.path.exists(filepath):
            print(f"Warning: {filepath} not found")
            return
            
        with open(filepath, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                request_id = self.safe_int(row.get('RequestID'))
                if request_id is None:
                    continue
                    
                address = self.safe_int(row.get('Address'))
                read = self.safe_bool(row.get('Read'))
                write = self.safe_bool(row.get('Write'))
                cycle = self.safe_int(row.get('Cycle'))
                
                # We don't know rank/bank yet, so we'll store this separately
                # and match it later when we see the request in scheduler files
                self.initial_requests = getattr(self, 'initial_requests', {})
                self.initial_requests[request_id] = {
                    'address': address,
                    'read': read,
                    'write': write,
                    'enter_queue_cycle': cycle
                }
    
    def process_scheduler_files(self):
        """Process all scheduler request stats files"""
        pattern = os.path.join(self.log_directory, "input_request_stats_scheduler_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            # Extract rank and bank from filename
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                print(f"Warning: Could not extract rank/bank from {filename}")
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    if request_id is None:
                        continue
                        
                    cycle = self.safe_int(row.get('Cycle'))
                    
                    # Create request key
                    key = self.get_request_key(request_id, 0, rank_id, bank_id)
                    
                    # Initialize request data
                    req = self.requests[key]
                    req["request_id"] = request_id
                    req["internal_request_id"] = 0
                    req["rank_id"] = rank_id
                    req["bank_id"] = bank_id
                    req["channel_id"] = 0  # Assuming single channel
                    
                    # Add initial request data if available
                    initial_data = getattr(self, 'initial_requests', {}).get(request_id, {})
                    if initial_data:
                        req["address"] = initial_data.get('address')
                        req["read"] = initial_data.get('read', False)
                        req["write"] = initial_data.get('write', False)
                        
                        # Add enter_queue event if we have it
                        enter_cycle = initial_data.get('enter_queue_cycle')
                        if enter_cycle is not None:
                            req["times"].append({
                                "event": "enter_queue",
                                "value": enter_cycle
                            })
                    
                    # Add scheduler start event
                    if cycle is not None:
                        req["times"].append({
                            "event": "scheduler_start",
                            "value": cycle
                        })
    
    def process_memory_request_queue_files(self):
        """Process memory request queue stats files"""
        pattern = os.path.join(self.log_directory, "memory_request_queue_stats_scheduler_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    internal_request_id = self.safe_int(row.get('InternalReqID'))
                    cycle = self.safe_int(row.get('Cycle'))
                    type_id = self.safe_int(row.get('TypeID'))
                    address = self.safe_int(row.get('Address'))
                    
                    if None in [request_id, internal_request_id, cycle, type_id]:
                        continue
                    
                    key = self.get_request_key(request_id, internal_request_id, rank_id, bank_id)
                    
                    req = self.requests[key]
                    req["request_id"] = request_id
                    req["internal_request_id"] = internal_request_id
                    req["rank_id"] = rank_id
                    req["bank_id"] = bank_id
                    req["channel_id"] = self.safe_int(row.get('ChannelID', 0))
                    req["address"] = address
                    
                    # Determine if this is internal or external request
                    is_internal = (request_id == 0 and internal_request_id != 0)
                    
                    command_type = COMMAND_TYPES.get(type_id, "UNKNOWN")
                    
                    req["times"].append({
                        "event": f"memory_request_queue_{command_type.lower()}",
                        "value": cycle
                    })
    
    def process_bank_request_queue_files(self):
        """Process bank request queue stats files"""
        pattern = os.path.join(self.log_directory, "bank_req_queue_stats_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    internal_request_id = self.safe_int(row.get('InternalReqID'))
                    cycle = self.safe_int(row.get('Cycle'))
                    command_type = (row.get('Type', '') or " ").strip()
                    
                    if None in [request_id, internal_request_id, cycle] or not command_type:
                        continue
                    
                    key = self.get_request_key(request_id, internal_request_id, rank_id, bank_id)
                    
                    if key in self.requests:
                        self.requests[key]["times"].append({
                            "event": f"bank_execute_{command_type.lower()}",
                            "value": cycle
                        })
    
    def process_bank_response_queue_files(self):
        """Process bank response queue stats files"""
        pattern = os.path.join(self.log_directory, "bank_resp_queue_stats_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    internal_request_id = self.safe_int(row.get('InternalReqID'))
                    cycle = self.safe_int(row.get('Cycle'))
                    
                    if None in [request_id, internal_request_id, cycle]:
                        continue
                    
                    key = self.get_request_key(request_id, internal_request_id, rank_id, bank_id)
                    
                    if key in self.requests:
                        self.requests[key]["times"].append({
                            "event": "bank_response_queue",
                            "value": cycle
                        })
    
    def process_memory_response_queue_files(self):
        """Process memory response queue stats files"""
        pattern = os.path.join(self.log_directory, "memory_response_queue_stats_scheduler_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    internal_request_id = self.safe_int(row.get('InternalReqID'))
                    cycle = self.safe_int(row.get('Cycle'))
                    
                    if None in [request_id, internal_request_id, cycle]:
                        continue
                    
                    key = self.get_request_key(request_id, internal_request_id, rank_id, bank_id)
                    
                    if key in self.requests:
                        self.requests[key]["times"].append({
                            "event": "memory_response_queue",
                            "value": cycle
                        })
    
    def process_output_response_files(self):
        """Process output response stats files"""
        pattern = os.path.join(self.log_directory, "output_response_stats_scheduler_rank*_bank*.csv")
        files = glob.glob(pattern)
        
        for filepath in files:
            filename = os.path.basename(filepath)
            parts = filename.replace('.csv', '').split('_')
            rank_id = None
            bank_id = None
            
            for part in parts:
                if part.startswith('rank'):
                    rank_id = self.safe_int(part[4:])
                elif part.startswith('bank'):
                    bank_id = self.safe_int(part[4:])
            
            if rank_id is None or bank_id is None:
                continue
                
            with open(filepath, 'r') as f:
                reader = csv.DictReader(f)
                for row in reader:
                    request_id = self.safe_int(row.get('RequestID'))
                    cycle = self.safe_int(row.get('Cycle'))
                    type_id = self.safe_int(row.get('TypeID'))
                    
                    if None in [request_id, cycle, type_id]:
                        continue
                    
                    # For output responses, we need to find the matching request
                    # Since we don't have internal_request_id, we'll match by request_id and rank/bank
                    # Only match external requests (internal_request_id == 0)
                    matching_keys = [k for k in self.requests.keys() 
                                   if (self.requests[k]["request_id"] == request_id and 
                                       self.requests[k]["rank_id"] == rank_id and
                                       self.requests[k]["bank_id"] == bank_id and
                                       self.requests[k]["internal_request_id"] == 0)]
                    
                    command_type = COMMAND_TYPES.get(type_id, "UNKNOWN")
                    
                    for key in matching_keys:
                        self.requests[key]["times"].append({
                            "event": f"output_response_{command_type.lower()}",
                            "value": cycle
                        })
    
    def process_output_request_stats(self):
        """Process the final output request stats file"""
        filepath = os.path.join(self.log_directory, "output_request_stats.csv")
        if not os.path.exists(filepath):
            print(f"Warning: {filepath} not found")
            return
            
        with open(filepath, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                request_id = self.safe_int(row.get('RequestID'))
                cycle = self.safe_int(row.get('Cycle'))
                
                if None in [request_id, cycle]:
                    continue
                
                # Find all matching requests - only external requests (internal_request_id == 0)
                matching_keys = [k for k in self.requests.keys() 
                               if (self.requests[k]["request_id"] == request_id and 
                                   self.requests[k]["internal_request_id"] == 0)]
                
                for key in matching_keys:
                    self.requests[key]["times"].append({
                        "event": "final_response",
                        "value": cycle
                    })
    
    def sort_request_times(self):
        """Sort the times for each request by cycle value"""
        for req in self.requests.values():
            req["times"].sort(key=lambda x: x["value"])
    
    def generate_output(self) -> Dict[str, Any]:
        """Generate the final JSON output"""
        self.sort_request_times()
        
        # Convert defaultdict to regular list
        requests_list = []
        for req_data in self.requests.values():
            # Skip requests that have no timing data
            if req_data["times"]:
                requests_list.append(req_data)
        
        # Sort requests by request_id, then by internal_request_id
        requests_list.sort(key=lambda x: (x["request_id"] or 0, x["internal_request_id"] or 0))
        
        return {
            "metadata": {
                "num_ranks": self.num_ranks,
                "num_banks": self.num_banks,
                "total_requests": len(requests_list)
            },
            "requests": requests_list
        }
    
    def process_all_files(self):
        """Process all CSV files in the correct order"""
        print("Processing input request stats...")
        self.process_input_request_stats()
        
        print("Processing scheduler files...")
        self.process_scheduler_files()
        
        print("Processing memory request queue files...")
        self.process_memory_request_queue_files()
        
        print("Processing bank request queue files...")
        self.process_bank_request_queue_files()
        
        print("Processing bank response queue files...")
        self.process_bank_response_queue_files()
        
        print("Processing memory response queue files...")
        self.process_memory_response_queue_files()
        
        print("Processing output response files...")
        self.process_output_response_files()
        
        print("Processing output request stats...")
        self.process_output_request_stats()
        
        print("Finalizing data...")
        return self.generate_output()

def main():
    parser = argparse.ArgumentParser(description='Aggregate memory system CSV files into JSON')
    parser.add_argument('--num-ranks', type=int, default=2, 
                       help='Number of memory ranks (default: 2)')
    parser.add_argument('--num-banks', type=int, default=8,
                       help='Number of banks per rank (default: 8)')
    parser.add_argument('--log-directory', type=str, default='logs',
                       help='Directory containing CSV log files (default: logs)')
    
    args = parser.parse_args()
    
    # Check if log directory exists
    if not os.path.exists(args.log_directory):
        print(f"Error: Log directory '{args.log_directory}' does not exist")
        return 1
    
    # Create aggregator and process files
    aggregator = MemorySystemAggregator(args.log_directory, args.num_ranks, args.num_banks)
    
    try:
        result = aggregator.process_all_files()
        
        # Write output JSON
        output_file = "out.json"
        with open(output_file, 'w') as f:
            json.dump(result, f, indent=2)
        
        print(f"Successfully generated {output_file}")
        print(f"Processed {result['metadata']['total_requests']} total requests")
        
    except Exception as e:
        print(f"Error processing files: {e}")
        traceback.print_exc()
        return 1
    
    return 0

if __name__ == "__main__":
    exit(main())