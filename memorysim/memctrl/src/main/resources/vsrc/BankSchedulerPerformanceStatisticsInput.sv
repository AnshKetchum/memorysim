module BankSchedulerPerformanceStatisticsInput #(
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
    input wire req_fire,
    input wire rd_en,
    input wire wr_en,
    input wire [ADDRESS_WIDTH-1:0] addr,
    input wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input wire [REQUEST_ID_BITS-1:0] request_id
);
    integer file;
    reg [1023:0] filename;

    initial begin
        $sformat(filename, "input_request_stats_scheduler_channel%0d_rank%0d_bank%0d.csv", CHANNEL, RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,Address,TypeRd,TypeWr,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && req_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d\n",
                request_id, addr, rd_en, wr_en, globalCycle);
        end
    end
endmodule
