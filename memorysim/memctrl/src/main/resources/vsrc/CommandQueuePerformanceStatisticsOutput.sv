module CommandQueuePerformanceStatisticsOutput #(
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
    input wire [REQUEST_ID_BITS-1:0] request_id
);
    integer file;
    initial begin
        file = $fopen("memory_response_queue_stats.csv", "w");
        $fwrite(file, "RequestID,Address,Type,Cycle\n");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d\n", request_id, addr, data, globalCycle);
        end
    end
endmodule
