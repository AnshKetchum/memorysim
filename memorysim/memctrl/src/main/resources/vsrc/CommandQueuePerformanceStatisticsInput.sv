module CommandQueuePerformanceStatisticsInput #(
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
    input  wire [DATA_WIDTH-1:0] op,  // DRAMOp enum encoding
    input  wire [GLOBAL_CYCLE_BITS-1:0] globalCycle,
    input  wire [REQUEST_ID_BITS-1:0] request_id,
    input  wire [REQUEST_ID_BITS-1:0] internal_req_id,
    input  wire [REQUEST_ID_BITS-1:0] channel_id,
    input  wire [REQUEST_ID_BITS-1:0] rank_id,
    input  wire [REQUEST_ID_BITS-1:0] bank_id,
    input  wire [REQUEST_ID_BITS-1:0] scheduler_id
);
    integer file;
    reg [8*24-1:0] opString; // wide enough to hold longest name

    initial begin
        file = $fopen("memory_request_queue_stats.csv", "w");
        $fwrite(file, "RequestID,InternalReqID,Channel,Rank,Bank,Scheduler,Address,Type,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && req_fire) begin
            // Map op enum to human-readable string
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

            // Log CSV with string op
            $fwrite(file, "%0d,%0d,%0d,%0d,%0d,%0d,%0h,%s,%0d\n",
                request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id,
                addr, opString, globalCycle);
        end
    end
endmodule
