module BankSchedulerPhysicalMemoryRequestPerformanceStatistics #(
    parameter int CHANNEL,
    parameter int RANK,
    parameter int BANK,
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input  wire                          clk,
    input  wire                          reset,
    input  wire                          req_fire,
    input  wire [ADDRESS_WIDTH-1:0]      addr,
    input  wire [DATA_WIDTH-1:0]         data,
    input  wire [DATA_WIDTH-1:0]           op,              // DRAMOp enum encoding
    input  wire [GLOBAL_CYCLE_BITS-1:0]  globalCycle,
    input  wire [REQUEST_ID_BITS-1:0]    request_id,
    input  wire [REQUEST_ID_BITS-1:0]    internal_req_id,
    input  wire [REQUEST_ID_BITS-1:0]    channel_id,
    input  wire [REQUEST_ID_BITS-1:0]    rank_id,
    input  wire [REQUEST_ID_BITS-1:0]    bank_id,
    input  wire [REQUEST_ID_BITS-1:0]    scheduler_id
);
    integer file;
    reg [1023:0] filename;

    // String register (max 16 chars wide here)
    reg [8*50-1:0] opString;

    initial begin
        $sformat(filename,
            "memory_request_queue_stats_scheduler_channel%0d_rank%0d_bank%0d.csv",
            CHANNEL, RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file,
          "RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Data,Op,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && req_fire) begin
            // Map op code to string name
            case (op)
                0:  opString = "ACTIVATE";
                1:  opString = "READ";
                2:  opString = "WRITE";
                3:  opString = "READ_PRECHARGE";
                4:  opString = "WRITE_PRECHARGE";
                5:  opString = "PRECHARGE";
                6:  opString = "REFRESH";
                7:  opString = "SELFREF_ENTER";
                8:  opString = "SELFREF_EXIT";
                default: opString = "UNKNOWN";
            endcase

            // Log CSV with string field
            $fwrite(file, "%0d,%0d,%0d,%0d,%0d,%0d,%0h,%0h,%s,%0d\n",
                request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id,
                addr, data, opString, globalCycle);
        end
    end
endmodule
