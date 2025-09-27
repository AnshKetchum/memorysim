module CommandQueuePerformanceStatisticsOutput #(
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input  wire clk,
    input  wire reset,
    input  wire resp_fire,
    input  wire [ADDRESS_WIDTH-1:0] addr,
    input  wire [DATA_WIDTH-1:0] data,
    input  wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input  wire [REQUEST_ID_BITS-1:0] request_id,
    input  wire [REQUEST_ID_BITS-1:0] internal_req_id,
    input  wire [REQUEST_ID_BITS-1:0] channel_id,
    input  wire [REQUEST_ID_BITS-1:0] rank_id,
    input  wire [REQUEST_ID_BITS-1:0] bank_id,
    input  wire [REQUEST_ID_BITS-1:0] scheduler_id
);
    integer file;
    initial begin
        file = $fopen("memory_response_queue_stats.csv", "w");
        $fwrite(file, "RequestID,InternalReqID,Channel,Rank,Bank,Scheduler,Address,Data,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && resp_fire) begin
            $fwrite(file, "%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d\n",
                    request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id,
                    addr, data, globalCycle);
        end
    end
endmodule
