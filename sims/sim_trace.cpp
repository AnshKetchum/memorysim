#include "VMultiChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <deque>
#include <unordered_map>
#include <algorithm>
#include <cassert>
using namespace std;

unsigned long long sim_cycle = 0;
static const unsigned long long TIMEOUT = 100000ULL;
unsigned long long max_request_cycles = 0; // 0 = unlimited

// Trace entry for input stimuli
struct TraceEntry {
    unsigned int addr;
    bool is_write;
    unsigned long long cycle;
    unsigned int wdata;
    unsigned long long enq_cycle = 0; // track when request was enqueued
};

// Log entry for enqueued requests
struct EnqueueLogEntry {
    unsigned int addr;
    bool is_write;
    int data; // data for write, -1 for read
};

// Log entry for dequeued responses
struct ResponseLogEntry {
    unsigned int addr;
    bool is_write;
    int data; // returned data
};

vector<EnqueueLogEntry> enqueue_log;
vector<ResponseLogEntry> response_log;

// Track last written data by address (only updated by explicit writes)
unordered_map<unsigned, unsigned> last_write_data;

// Pending now supports multiple outstanding requests per address
unordered_map<unsigned, deque<TraceEntry>> pending;

void write_enqueue_log(const string &filename) {
    ofstream log_file(filename);
    if (!log_file) {
        cerr << "ERROR: Unable to open enqueue log file: " << filename << endl;
        return;
    }
    for (const auto &e : enqueue_log) {
        log_file << hex << "0x" << e.addr << dec
                 << (e.is_write ? " WRITE " : " READ  ")
                 << e.data << endl;
    }
}

void write_response_log(const string &filename) {
    ofstream log_file(filename);
    if (!log_file) {
        cerr << "ERROR: Unable to open response log file: " << filename << endl;
        return;
    }
    for (const auto &r : response_log) {
        log_file << hex << "0x" << r.addr << dec
                 << (r.is_write ? " WRITE_RESP " : " READ_RESP  ")
                 << r.data << endl;
    }
}

void flush_and_exit(int code = 1) {
    write_enqueue_log("enqueue_log.txt");
    write_response_log("response_log.txt");
    exit(code);
}

void tick(VMultiChannelSystem* top) {
    top->clock = 0; top->eval();
    top->clock = 1; top->eval();
    sim_cycle++;

    // Check for max_request_cycles violation
    if (max_request_cycles > 0) {
        for (auto &kv : pending) {
            for (const auto &req : kv.second) {
                if ((sim_cycle - req.enq_cycle) > max_request_cycles) {
                    cerr << "ERROR: Request to addr 0x" << hex << req.addr
                         << dec << " exceeded max request cycles of " << max_request_cycles
                         << " (enqueued at cycle " << req.enq_cycle
                         << ", now " << sim_cycle << ")" << endl;
                    flush_and_exit(1);
                }
            }
        }
    }
}

vector<TraceEntry> load_trace(const string &filename) {
    vector<TraceEntry> trace;
    ifstream infile(filename);
    if (!infile) {
        cerr << "Failed to open trace file: " << filename << endl;
        write_enqueue_log("enqueue_log.txt");
        exit(1);
    }
    string line;

    auto strip = [](string s) -> string {
        size_t a = s.find_first_not_of(" \t\r\n");
        size_t b = s.find_last_not_of(" \t\r\n");
        if (a == string::npos) return "";
        s = s.substr(a, b - a + 1);
        while (!s.empty() && (s.back() == ',' || s.back() == ';')) s.pop_back();
        return s;
    };

    while (getline(infile, line)) {
        if (line.empty()) continue;

        istringstream iss(line);
        string addr_str, op;
        if (!(iss >> addr_str >> op)) continue;

        vector<string> rest;
        string tok;
        while (iss >> tok) rest.push_back(tok);

        if (rest.empty()) {
            cerr << "WARNING: Malformed trace line (no cycle): " << line << endl;
            continue;
        }

        string cycle_str = strip(rest.back());
        unsigned long long cycle = 0;
        try { cycle = stoull(cycle_str, nullptr, 0); }
        catch (...) { cerr << "WARNING: Couldn't parse cycle '" << cycle_str << "' in line: " << line << endl; continue; }

        TraceEntry e;
        string a = strip(addr_str);
        try { e.addr = static_cast<unsigned int>(stoul(a, nullptr, 0)); }
        catch (...) { cerr << "WARNING: Couldn't parse address '" << a << "' in line: " << line << endl; continue; }

        e.is_write = (op == "WRITE");
        e.cycle = cycle;
        e.wdata = 0;

        if (e.is_write) {
            if (rest.size() >= 2) {
                string data_str = strip(rest[rest.size() - 2]);
                try { e.wdata = static_cast<unsigned int>(stoul(data_str, nullptr, 0)); }
                catch (...) { e.wdata = static_cast<unsigned int>(rand()); }
            } else { e.wdata = static_cast<unsigned int>(rand()); }
        }

        trace.push_back(e);
    }
    return trace;
}

bool enqueue_request(VMultiChannelSystem* top, TraceEntry &e) {
    top->io_in_valid      = 1;
    top->io_in_bits_addr  = e.addr;
    top->io_in_bits_wr_en = e.is_write;
    top->io_in_bits_rd_en = !e.is_write;
    top->io_in_bits_wdata = e.wdata;

    unsigned long long wait = 0;
    while (!top->io_in_ready && wait++ < TIMEOUT) tick(top);

    if (!top->io_in_ready) {
        cerr << "ERROR: Timeout enqueuing " << (e.is_write ? "WRITE" : "READ")
             << " at cycle " << sim_cycle << " addr=0x" << hex << e.addr << dec << endl;
        top->io_in_valid = 0;
        return false;
    }

    // Log enqueue
    enqueue_log.push_back({e.addr, e.is_write, e.is_write ? static_cast<int>(e.wdata) : -1});

    // Record pending for response check
    e.enq_cycle = sim_cycle; // store when this request was enqueued
    pending[e.addr].push_back(e);

    if (e.is_write) last_write_data[e.addr] = e.wdata;

    tick(top);
    top->io_in_valid = 0;
    return true;
}

bool dequeue_response(VMultiChannelSystem* top) {
    if (!top->io_out_valid) return false;

    unsigned int addr = top->io_out_bits_addr;
    unsigned int data = top->io_out_bits_data;

    bool has_pending = (pending.count(addr) && !pending[addr].empty());
    bool is_write_resp = false;
    TraceEntry pending_entry;
    if (has_pending) {
        pending_entry = pending[addr].front();
        is_write_resp = pending_entry.is_write;
    } else {
        cerr << "WARNING: Received response for unknown addr 0x" << hex << addr << dec
             << " at cycle " << sim_cycle << ". Treating as unsolicited/random response." << endl;
    }

    cout << "[RESP] cycle " << sim_cycle << " "
         << (is_write_resp ? "WRITE_RESP" : "READ_RESP")
         << " addr=0x" << hex << addr << dec
         << " data=0x" << hex << data << dec << endl;

    response_log.push_back({addr, is_write_resp, static_cast<int>(data)});

    if (has_pending) {
        if (is_write_resp) {
            if (data != pending_entry.wdata) {
                cerr << "ERROR: Write mismatch at addr 0x" << hex << addr
                     << ". Sent=0x" << pending_entry.wdata
                     << ", Got=0x" << data << dec << endl;
                flush_and_exit(1);
            } else {
                cout << "[OK] Write confirmed for addr 0x" << hex << addr
                     << " value=0x" << data << dec << endl;
            }
        } else {
            if (last_write_data.count(addr)) {
                unsigned expected = last_write_data[addr];
                if (data != expected) {
                    cerr << "ERROR: Read mismatch at addr 0x" << hex << addr
                         << ". Expected=0x" << expected
                         << ", Got=0x" << data << dec << endl;
                    flush_and_exit(1);
                } else {
                    cout << "[OK] Read matched last write for addr 0x" << hex << addr
                         << " value=0x" << data << dec << endl;
                }
            } else {
                cout << "[INFO] Read to addr 0x" << hex << addr << dec
                     << " had no prior write; treating returned value 0x" << hex << data
                     << dec << " as random." << endl;
            }
        }
        pending[addr].pop_front();
        if (pending[addr].empty()) pending.erase(addr);
    }

    tick(top);
    return true;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    string trace_file = "test.trace";
    unsigned long long max_cycles = 100000ULL;

    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "-t" && i+1 < argc) trace_file = argv[++i];
        else if (arg == "-c" && i+1 < argc) max_cycles = stoull(argv[++i]);
        else if (arg == "-m" && i+1 < argc) max_request_cycles = stoull(argv[++i]);
        else {
            cerr << "Usage: " << argv[0] << " [-t <trace>] [-c <max_cycles>] [-m <max_request_cycles>]" << endl;
            write_enqueue_log("enqueue_log.txt");
            write_response_log("response_log.txt");
            return 1;
        }
    }

    VMultiChannelSystem *top = new VMultiChannelSystem;
    srand(static_cast<unsigned>(time(nullptr)));

    top->reset = 1;
    for (int i = 0; i < 5; ++i) tick(top);
    top->reset = 0;
    tick(top);

    top->io_out_ready = 1;

    auto trace = load_trace(trace_file);
    size_t idx = 0;

    while ((idx < trace.size() || !pending.empty()) && sim_cycle < max_cycles) {
        if (idx < trace.size() && sim_cycle >= trace[idx].cycle) {
            if (!enqueue_request(top, trace[idx])) {
                cerr << "ERROR: Failed to enqueue request from trace at index " << idx << endl;
                flush_and_exit(1);
            }
            idx++;
            continue;
        }
        if (!dequeue_response(top)) tick(top);
    }

    if (sim_cycle >= max_cycles) {
        cerr << "ERROR: Max cycles (" << max_cycles << ") reached." << endl;
        write_enqueue_log("enqueue_log.txt");
        write_response_log("response_log.txt");
        return 1;
    } else {
        cout << "Simulation completed in " << sim_cycle << " cycles." << endl;
    }

    write_enqueue_log("enqueue_log.txt");
    write_response_log("response_log.txt");

    top->final();
    delete top;
    return 0;
}
