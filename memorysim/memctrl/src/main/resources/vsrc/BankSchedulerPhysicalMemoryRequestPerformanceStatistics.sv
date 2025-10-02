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
    input  wire                          cs,
    input  wire                          ras,
    input  wire                          cas,
    input  wire                          we,
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

    // Request type encoding (small integer IDs)
    // +-----------------------+---------+
    // | String                | ID      |
    // +-----------------------+---------+
    // | REFRESH               | 0       |
    // | PRECHARGE             | 1       |
    // | ACTIVATE              | 2       |
    // | READ                  | 3       |
    // | WRITE                 | 4       |
    // | SELF REFRESH ENTER    | 5       |
    // | SELF REFRESH EXIT     | 6       |
    // | UNKNOWN               | 7       |
    // +-----------------------+---------+
    //
    // Use a small packed reg to avoid wide string literal assignments
    // and the associated WIDTHEXPAND warnings.
    reg [2:0] reqType; // holds 0..7 per table above

    initial begin
        $sformat(filename, "memory_request_queue_stats_scheduler_channel%0d_rank%0d_bank%0d.csv", CHANNEL, RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Data,TypeID,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && req_fire) begin
            // Encode DRAM command type as a small integer ID
            if (cs == 0 && ras == 0 && cas == 0 && we == 1)
                reqType = 0; // REFRESH
            else if (cs == 0 && ras == 0 && cas == 1 && we == 0)
                reqType = 1; // PRECHARGE
            else if (cs == 0 && ras == 0 && cas == 1 && we == 1)
                reqType = 2; // ACTIVATE
            else if (cs == 0 && ras == 1 && cas == 0 && we == 1)
                reqType = 3; // READ
            else if (cs == 0 && ras == 1 && cas == 0 && we == 0)
                reqType = 4; // WRITE
            else if (cs == 0 && ras == 0 && cas == 0 && we == 0)
                reqType = 5; // SELF REFRESH ENTER
            else if (cs == 0 && ras == 1 && cas == 1 && we == 1)
                reqType = 6; // SELF REFRESH EXIT
            else
                reqType = 7; // UNKNOWN

            // Log CSV with numeric type ID (avoids wide string assignments)
            // Fields: RequestID,InternalReqID,ChannelID,RankID,BankID,SchedulerID,Address,Data,TypeID,Cycle
            $fwrite(file, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                request_id, internal_req_id, channel_id, rank_id, bank_id, scheduler_id,
                addr, data, reqType, globalCycle);
        end
    end
endmodule
