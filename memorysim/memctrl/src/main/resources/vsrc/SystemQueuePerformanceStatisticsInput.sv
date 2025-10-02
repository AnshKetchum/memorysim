module SystemQueuePerformanceStatisticsInput #(
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
    input wire [DATA_WIDTH-1:0] wdata,
    input wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input wire [REQUEST_ID_BITS-1:0] request_id
);
    integer file;
    initial begin
        file = $fopen("input_request_stats.csv", "w");
        $fwrite(file, "RequestID,Address,Read,Write,Cycle,Write Data\n");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (req_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d,%d\n", request_id, addr, rd_en, wr_en, globalCycle, wdata);
        end
    end
endmodule
