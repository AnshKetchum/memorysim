module BankPhysicalMemoryRequestPerformanceStatistics #(
    parameter int CHANNEL,
    parameter int RANK,
    parameter int BANK,
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input  wire clk,
    input  wire reset,
    input  wire req_fire,
    input  wire [ADDRESS_WIDTH-1:0] addr,
    input  wire [DATA_WIDTH-1:0] data,
    input  wire [DATA_WIDTH-1:0] op,  // DRAMOp value (width defined upstream)
    input  wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input  wire [REQUEST_ID_BITS-1:0] request_id,
    input  wire [REQUEST_ID_BITS-1:0] internal_req_id,
    input  wire [REQUEST_ID_BITS-1:0] channel_id,
    input  wire [REQUEST_ID_BITS-1:0] rank_id, 
    input  wire [REQUEST_ID_BITS-1:0] bank_id,
    input  wire [REQUEST_ID_BITS-1:0] scheduler_id
);
    integer file;
    reg [1023:0] filename;
    reg [8*24-1:0] opString; // longest string = "SELF REFRESH ENTER"

    initial begin
        $sformat(filename, "bank_req_queue_stats_channel%0d_rank%0d_bank%0d.csv", CHANNEL, RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Type,Cycle\n");
    end
    
    always @(posedge clk) begin
        if (!reset && req_fire) begin
            case (op)
                0: opString = "REFRESH";
                1: opString = "PRECHARGE";
                2: opString = "ACTIVATE";
                3: opString = "READ";
                4: opString = "WRITE";
                5: opString = "SELF REFRESH ENTER";
                6: opString = "SELF REFRESH EXIT";
                default: opString = "UNKNOWN";
            endcase

            $fwrite(file, "%0d,%0d,%0d,%0d,%0d,%0d,%0h,%s,%0d\n", 
                request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                addr, opString, globalCycle);
        end
    end
endmodule
