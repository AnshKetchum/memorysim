#include "VMultiChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <unordered_map>
#include <cassert>
using namespace std;

// Number of write-read sanity checks
static const int NUM_TESTS = 25;
// Max cycles to wait for a response before timing out
static const unsigned long long TIMEOUT = 10000ULL;

unsigned long long sim_cycle = 0;

// Advance one clock cycle
void tick(VMultiChannelSystem* top) {
    top->clock = 0;
    top->eval();
    top->clock = 1;
    top->eval();
    sim_cycle++;
}

// Issue a request (read or write) using Decoupled handshake
// Returns the next available request ID from the system response
unsigned int issue_request(VMultiChannelSystem* top, bool wr, unsigned int addr, unsigned int wdata) {
    // Drive valid and bits
    top->io_in_valid      = 1;
    top->io_in_bits_wr_en = wr;
    top->io_in_bits_rd_en = !wr;
    top->io_in_bits_addr  = addr;
    top->io_in_bits_wdata = wdata;
    
    // Wait for ready
    unsigned long long wait = 0;
    while (!top->io_in_ready && wait < TIMEOUT) {
        tick(top);
        wait++;
    }
    if (!top->io_in_ready) {
        cerr << "ERROR: Request enqueue timeout on " << (wr ? "WRITE" : "READ")
             << " @ cycle " << sim_cycle << endl;
        assert(false && "Request enqueue timeout");
        exit(0);
    }
    
    // Handshake complete: advance one cycle and deassert valid
    tick(top);
    top->io_in_valid = 0;
    
    // The request ID that was assigned to this request would be 
    // (next_available_request_id - 1), but we'll get it from the response
    return 0; // Placeholder - actual request ID will come from response
}

// Wait for and consume a response
// Returns the response data and fills in the request_id parameter
unsigned int get_response(VMultiChannelSystem* top, bool expect_wr, unsigned int expected_addr, unsigned int* request_id) {
    unsigned long long wait = 0;
    while (!top->io_out_valid && wait < TIMEOUT) {
        tick(top);
        wait++;
    }
    if (!top->io_out_valid) {
        cerr << "ERROR: Response timeout @ cycle " << sim_cycle << endl;
        assert(false && "Response timeout");
    }
    
    // Access response fields through the new SystemResponse structure
    unsigned int raddr = top->io_out_bits_out_addr;
    unsigned int rdata = top->io_out_bits_out_data;
    bool rwr = top->io_out_bits_out_wr_en;
    bool rrd = top->io_out_bits_out_rd_en;
    unsigned int rid = top->io_out_bits_out_request_id;
    unsigned int next_id = top->io_out_bits_next_available_request_id;
    
    // Validate response metadata
    assert(raddr == expected_addr && "Response address mismatch");
    assert(rwr == expect_wr && "Response type mismatch");
    
    // Return the request ID
    *request_id = rid;
    
    cout << "Response: addr=0x" << hex << raddr 
         << ", data=0x" << rdata 
         << ", req_id=" << dec << rid
         << ", next_avail_id=" << next_id << endl;
    
    // Consume response
    tick(top);
    return rdata;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    
    // Instantiate DUT
    VMultiChannelSystem* top = new VMultiChannelSystem;
    
    // Reset sequence
    top->reset = 1;
    for (int i = 0; i < 5; ++i) tick(top);
    top->reset = 0;
    tick(top);
    
    // Always ready to accept responses
    top->io_out_ready = 1;
    
    // Seed RNG
    srand(static_cast<unsigned>(time(nullptr)));
    
    // Track expected data and request IDs
    unordered_map<unsigned int, unsigned int> golden; // addr -> data
    unordered_map<unsigned int, unsigned int> request_ids; // addr -> request_id
    
    for (int i = 0; i < NUM_TESTS; ++i) {
        unsigned int addr  = rand() % 0x10000; // 16-bit space
        unsigned int wdata = rand();
        
        cout << "\n=== Test " << i << " ===" << endl;
        
        // WRITE
        cout << "Issuing WRITE: addr=0x" << hex << addr << ", data=0x" << wdata << dec << endl;
        issue_request(top, true, addr, wdata);
        golden[addr] = wdata;
        
        unsigned int write_req_id;
        unsigned int dummy = get_response(top, true, addr, &write_req_id);
        (void)dummy;
        request_ids[addr] = write_req_id;
        
        // READ
        cout << "Issuing READ: addr=0x" << hex << addr << dec << endl;
        issue_request(top, false, addr, 0);
        
        unsigned int read_req_id;
        unsigned int rdata = get_response(top, false, addr, &read_req_id);
        
        // Verify data
        unsigned int expected = golden[addr];
        if (rdata != expected) {
            cerr << "ERROR: Data mismatch at addr=0x" << hex << addr
                 << ". Expected=0x" << expected
                 << ", got=0x" << rdata << dec << endl;
            exit(0);
        }
        
        // Verify request IDs are incrementing (read ID should be write ID + 1)
        if (read_req_id != write_req_id + 1) {
            cerr << "ERROR: Request ID mismatch at addr=0x" << hex << addr
                 << ". Write req_id=" << dec << write_req_id
                 << ", Read req_id=" << read_req_id
                 << " (expected " << (write_req_id + 1) << ")" << endl;
            exit(0);
        }
        
        cout << "Test " << i << ": PASS addr=0x" << hex << addr
             << ", data=0x" << rdata 
             << ", write_req_id=" << dec << write_req_id
             << ", read_req_id=" << read_req_id << endl;
    }
    
    cout << "\nAll " << NUM_TESTS << " sanity tests PASSED!" << endl;
    cout << "Request ID tracking working correctly!" << endl;
    
    top->final();
    delete top;
    return 0;
}