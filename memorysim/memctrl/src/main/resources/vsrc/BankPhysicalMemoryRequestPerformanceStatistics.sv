module BankPhysicalMemoryRequestPerformanceStatistics #(
    parameter int RANK,
    parameter int BANK,
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input wire clk,
    input wire reset,
    input wire req_fire,
    input wire [ADDRESS_WIDTH-1:0] addr,
    input wire [DATA_WIDTH-1:0] data,
    input wire cs,
    input wire ras,
    input wire cas,
    input wire we,
    input wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input wire [REQUEST_ID_BITS-1:0] request_id,
    input wire [REQUEST_ID_BITS-1:0] internal_req_id,
    input wire [REQUEST_ID_BITS-1:0] channel_id,
    input wire [REQUEST_ID_BITS-1:0] rank_id, 
    input wire [REQUEST_ID_BITS-1:0] bank_id,
    input wire [REQUEST_ID_BITS-1:0] scheduler_id
);
    integer file;
    reg [1023:0] filename;
    
    initial begin
        $sformat(filename, "bank_req_queue_stats_rank%0d_bank%0d.csv", RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Type,Cycle\n");
    end
    
    always @(posedge clk) begin
        if (reset) begin
        end else if (req_fire) begin
            if(cs == 0 && ras == 0 && cas == 0 && we == 1) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "REFRESH", globalCycle);
            end
            else if(cs == 0 && ras == 0 && cas == 1 && we == 0) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "PRECHARGE", globalCycle);
            end 
            else if(cs == 0 && ras == 0 && cas == 1 && we == 1) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "ACTIVATE", globalCycle);
            end 
            else if(cs == 0 && ras == 1 && cas == 0 && we == 1) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "READ", globalCycle);
            end 
            else if(cs == 0 && ras == 1 && cas == 0 && we == 0) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "WRITE", globalCycle);
            end 
            else if(cs == 0 && ras == 0 && cas == 0 && we == 0) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "SELF REFRESH ENTER", globalCycle);
            end 
            else if(cs == 0 && ras == 1 && cas == 1 && we == 1) begin 
                $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%s,%d\n", 
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id, 
                    addr, "SELF REFRESH EXIT", globalCycle);
            end 
        end
    end
endmodule