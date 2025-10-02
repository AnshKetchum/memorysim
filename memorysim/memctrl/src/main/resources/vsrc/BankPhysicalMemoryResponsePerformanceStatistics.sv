module BankPhysicalMemoryResponsePerformanceStatistics #(
    parameter int CHANNEL,
    parameter int RANK,
    parameter int BANK,
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire [ADDRESS_WIDTH-1:0] addr,
    input wire [DATA_WIDTH-1:0] data,
    input wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input wire [REQUEST_ID_BITS-1:0] request_id,
    input wire [REQUEST_ID_BITS-1:0] internal_req_id,
    input wire [REQUEST_ID_BITS-1:0] channel_id,
    input wire [REQUEST_ID_BITS-1:0] rank_id,
    input wire [REQUEST_ID_BITS-1:0] bank_id,
    input wire [REQUEST_ID_BITS-1:0] scheduler_id,
    input wire [31:0] active_row,
    input wire [31:0] active_col
);
    integer file;
    reg [1023:0] filename;
    
    initial begin
        $sformat(filename, "bank_resp_queue_stats_channel%0d_rank%0d_bank%0d.csv", CHANNEL, RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Data,Cycle,ActiveRow,ActiveCol\n");
    end
    
    always @(posedge clk) begin
        if (!reset && resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n", 
                request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id,
                addr, data, globalCycle, active_row, active_col);
        end
    end
endmodule