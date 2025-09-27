module BankSchedulerPerformanceStatisticsOutput #(
    parameter int RANK,
    parameter int BANK,
    parameter int ADDRESS_WIDTH,
    parameter int DATA_WIDTH,
    parameter int GLOBAL_CYCLE_BITS,
    parameter int REQUEST_ID_BITS
)(
    input  wire                          clk,
    input  wire                          reset,
    input  wire                          resp_fire,
    input  wire                          rd_en,
    input  wire                          wr_en,
    input  wire [ADDRESS_WIDTH-1:0]      addr,
    input  wire [DATA_WIDTH-1:0]         data,
    input  wire [GLOBAL_CYCLE_BITS-1:0]  globalCycle,
    input  wire [REQUEST_ID_BITS-1:0]    request_id
);
    integer file;
    reg [1023:0] filename;

    // Request/Response type encoding (keep consistent with request-side)
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
    // | UNKNOWN / OTHER       | 7       |
    // +-----------------------+---------+
    //
    // Use a small packed reg to avoid wide string literal assignments
    reg [2:0] reqType; // holds 0..7 per table above

    initial begin
        $sformat(filename, "output_response_stats_scheduler_rank%0d_bank%0d.csv", RANK, BANK);
        file = $fopen(filename, "w");
        // Note header includes TypeID (numeric) to match encoded values above
        $fwrite(file, "RequestID,Address,Data,TypeID,Cycle\n");
    end

    always @(posedge clk) begin
        if (!reset && resp_fire) begin
            // Encode READ/WRITE/OTHER into small integer ID
            if (rd_en && !wr_en)
                reqType = 3; // READ
            else if (wr_en && !rd_en)
                reqType = 4; // WRITE
            else
                reqType = 7; // OTHER / UNKNOWN

            // Log CSV with numeric type ID (avoids wide string assignments)
            $fwrite(file, "%d,%d,%d,%d,%d\n",
                request_id, addr, data, reqType, globalCycle);
        end
    end
endmodule
