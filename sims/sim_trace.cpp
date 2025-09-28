#include "VMultiChannelSystem.h"
#include "verilated.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <unordered_map>
#include <cassert>
using namespace std;

unsigned long long sim_cycle = 0;
static const unsigned long long TIMEOUT = 100000ULL;
unsigned long long max_request_cycles = 100; // Default timeout

struct TraceEntry {
    unsigned int addr;
    bool is_write;
    unsigned long long cycle;
    unsigned int wdata;
    int line_number;
};

struct PendingRequest {
    unsigned int addr;
    bool is_write;
    unsigned int wdata;
    unsigned long long enq_cycle;
    int line_number;
    // For reads: expected data at enqueue time
    unsigned int expected_rdata;
    bool has_expected;
};

// Track memory state and pending requests
unordered_map<unsigned, unsigned> memory_state;
unordered_map<unsigned int, PendingRequest> pending; // request_id -> request
unsigned int next_request_id = 1;

void tick(VMultiChannelSystem* top) {
    top->clock = 0; 
    top->eval();
    top->clock = 1; 
    top->eval();
    sim_cycle++;
}

vector<TraceEntry> load_trace(const string &filename) {
    vector<TraceEntry> trace;
    ifstream infile(filename);
    if (!infile) {
        cerr << "Failed to open trace file: " << filename << endl;
        exit(1);
    }
    
    string line;
    int line_number = 0;
    while (getline(infile, line)) {
        line_number++;
        if (line.empty()) continue;
        
        istringstream iss(line);
        string addr_str, op;
        if (!(iss >> addr_str >> op)) continue;
        
        vector<string> tokens;
        string tok;
        while (iss >> tok) tokens.push_back(tok);
        if (tokens.empty()) continue;
        
        TraceEntry e;
        e.addr = stoul(addr_str, nullptr, 0);
        e.is_write = (op == "WRITE");
        e.cycle = stoull(tokens.back(), nullptr, 0);
        e.line_number = line_number;
        e.wdata = 0;
        
        if (e.is_write && tokens.size() >= 2) {
            e.wdata = stoul(tokens[tokens.size() - 2], nullptr, 0);
        }
        
        trace.push_back(e);
    }
    return trace;
}

// Track current enqueue attempt
static bool enq_pending = false;
static TraceEntry current_enq_entry;

bool try_start_enqueue(VMultiChannelSystem* top, const TraceEntry& entry) {
    if (enq_pending) return false; // Already trying to enqueue something
    
    // Start handshake
    top->io_in_valid = 1;
    top->io_in_bits_addr = entry.addr;
    top->io_in_bits_wr_en = entry.is_write;
    top->io_in_bits_rd_en = !entry.is_write;
    top->io_in_bits_wdata = entry.wdata;
    
    enq_pending = true;
    current_enq_entry = entry;
    return false; // Not complete yet
}

bool check_enqueue_complete(VMultiChannelSystem* top) {
    if (!enq_pending) return false;
    
    // Use the fire attribute if available, otherwise compute manually
    bool fire;
    #ifdef USE_FIRE_ATTRIBUTE
    fire = top->io_in_fire;  // If your module exposes this
    #else
    fire = top->io_in_valid && top->io_in_ready;
    #endif
    
    if (fire) {
        // Handshake complete - record the enqueue
        PendingRequest req;
        req.addr = current_enq_entry.addr;
        req.is_write = current_enq_entry.is_write;
        req.wdata = current_enq_entry.wdata;
        req.enq_cycle = sim_cycle;
        req.line_number = current_enq_entry.line_number;
        
        // For reads, capture expected value NOW
        if (!current_enq_entry.is_write) {
            req.has_expected = memory_state.count(current_enq_entry.addr) > 0;
            req.expected_rdata = req.has_expected ? memory_state[current_enq_entry.addr] : 0;
        } else {
            req.has_expected = false;
        }
        
        unsigned int req_id = next_request_id++;
        pending[req_id] = req;
        
        // Update memory state for writes
        if (current_enq_entry.is_write) {
            memory_state[current_enq_entry.addr] = current_enq_entry.wdata;
        }
        
        cout << "[ENQ] cycle " << sim_cycle 
             << (current_enq_entry.is_write ? " WRITE " : " READ  ")
             << "addr=0x" << hex << current_enq_entry.addr << dec
             << " req_id=" << req_id;
        if (current_enq_entry.is_write) {
            cout << " data=0x" << hex << current_enq_entry.wdata << dec;
        }
        cout << " [line " << current_enq_entry.line_number << "]" << endl;
        
        enq_pending = false;
        top->io_in_valid = 0;
        return true;
    }
    
    return false; // Still waiting for ready
}

bool try_dequeue(VMultiChannelSystem* top) {
    // Check if response available this cycle
    if (!top->io_out_valid) {
        return false;
    }
    
    // Accept it immediately
    top->io_out_ready = 1;
    
    // Read response data
    unsigned int addr = top->io_out_bits_out_addr;
    unsigned int data = top->io_out_bits_out_data;
    unsigned int req_id = top->io_out_bits_out_request_id;
    bool is_write = top->io_out_bits_out_wr_en;
    
    cout << "[RESP] cycle " << sim_cycle 
         << (is_write ? " WRITE_RESP " : " READ_RESP  ")
         << "addr=0x" << hex << addr << dec
         << " data=0x" << hex << data << dec
         << " req_id=" << req_id;
    
    // Validate against pending request
    if (pending.count(req_id) == 0) {
        cout << " ERROR: Unknown request ID!" << endl;
        exit(1);
    }
    
    PendingRequest& req = pending[req_id];
    cout << " [line " << req.line_number << "]";
    
    // Validate response
    if (req.addr != addr || req.is_write != is_write) {
        cout << " ERROR: Response mismatch!" << endl;
        exit(1);
    }
    
    if (is_write) {
        if (data != req.wdata) {
            cout << " ERROR: Write data mismatch!" << endl;
            exit(1);
        }
        cout << " OK" << endl;
    } else {
        if (req.has_expected && data != req.expected_rdata) {
            cout << " ERROR: Read data mismatch! Expected=0x" << hex << req.expected_rdata << endl;
            exit(1);
        }
        cout << (req.has_expected ? " OK" : " (uninit)") << endl;
    }
    
    // Check timeout
    if (max_request_cycles > 0 && (sim_cycle - req.enq_cycle) > max_request_cycles) {
        cout << "ERROR: Request timeout! Took " << (sim_cycle - req.enq_cycle) << " cycles" << endl;
        exit(1);
    }
    
    pending.erase(req_id);
    return true;
}

int main(int argc, char **argv) {
    string trace_file = "test.trace";
    unsigned long long max_cycles = 100000ULL;
    
    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "-t" && i+1 < argc) trace_file = argv[++i];
        else if (arg == "-c" && i+1 < argc) max_cycles = stoull(argv[++i]);
        else if (arg == "-m" && i+1 < argc) max_request_cycles = stoull(argv[++i]);
    }
    
    VMultiChannelSystem *top = new VMultiChannelSystem;
    
    // Reset
    top->reset = 1;
    for (int i = 0; i < 5; ++i) tick(top);
    top->reset = 0;
    top->io_out_ready = 0; // Start with ready=0
    
    auto trace = load_trace(trace_file);
    size_t trace_idx = 0;
    
    cout << "Starting simulation with " << trace.size() << " requests..." << endl;
    
    while ((trace_idx < trace.size() || !pending.empty() || enq_pending) && sim_cycle < max_cycles) {
        // Check if previous enqueue completed
        bool enq_completed = check_enqueue_complete(top);
        if (enq_completed) {
            trace_idx++; // Move to next trace entry only after successful enqueue
        }
        
        // Try to dequeue 
        try_dequeue(top);
        
        // Try to start new enqueue if we have pending trace entries for this cycle and no enqueue in progress
        if (trace_idx < trace.size() && sim_cycle >= trace[trace_idx].cycle && !enq_pending) {
            try_start_enqueue(top, trace[trace_idx]);
        }
        
        // Always tick to advance time
        tick(top);
        
        // Reset ready after tick
        top->io_out_ready = 0;
    }
    
    if (sim_cycle >= max_cycles) {
        cerr << "ERROR: Max cycles reached" << endl;
        return 1;
    }
    
    if (!pending.empty()) {
        cerr << "ERROR: " << pending.size() << " requests still pending" << endl;
        return 1;
    }
    
    cout << "SUCCESS: All requests completed in " << sim_cycle << " cycles" << endl;
    cout << "Processed " << (next_request_id - 1) << " requests" << endl;
    
    delete top;
    return 0;
}