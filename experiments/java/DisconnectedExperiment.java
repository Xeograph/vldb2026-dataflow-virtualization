import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DisconnectedExperiment {
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // 1. The "Continent" (Huge Component)
    private static final int HUGE_SIZE = 5_000_000; // 5 Million edges
    // 2. The "Island" (Tiny Component)
    private static final int TINY_SIZE = 100;
    
    // We will search from HUGE -> TINY.
    // Unidirectional must scan 5M nodes to prove no path exists.
    // Bidirectional will scan ~100 nodes on the Island side, realize it's trapped, and stop.
    
    private static final int START_NODE_HUGE = 1;      // In the 5M blob
    private static final int END_NODE_TINY = 6000000;  // In the 100 node blob

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");
            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                
                System.out.println("Setting up Disconnected Graph...");
                setupDisconnectedGraph(conn);

                System.out.println("\nExperiment: Path Existence Check (Disconnected)");
                System.out.println("Start Node: In Huge Component (5M nodes)");
                System.out.println("End Node:   In Tiny Component (100 nodes)");
                System.out.println("Result: No Path Exists.");
                System.out.println("\n-------------------------------------------------------------");
                System.out.printf("%-15s | %-15s | %-15s%n", "STRATEGY", "TIME(s)", "STEPS");
                System.out.println("-------------------------------------------------------------");

                // 1. Unidirectional (The Trap)
                // This has to explore all 5,000,000 nodes before giving up.
                long startUni = System.nanoTime();
                int stepsUni = runUnidirectional(conn, START_NODE_HUGE, END_NODE_TINY);
                double timeUni = (System.nanoTime() - startUni) / 1_000_000_000.0;
                System.out.printf("%-15s | %-15.4f | %-15s%n", "Uni (Huge->Tiny)", timeUni, (stepsUni == -1 ? "Gave Up" : stepsUni));

                // 2. Bidirectional (The Escape)
                // This explores the Tiny side, finishes it in ~5 steps, sees the queue is empty, and quits.
                long startBi = System.nanoTime();
                int stepsBi = runBidirectional(conn, START_NODE_HUGE, END_NODE_TINY);
                double timeBi = (System.nanoTime() - startBi) / 1_000_000_000.0;
                System.out.printf("%-15s | %-15.4f | %-15s%n", "Bidirectional", timeBi, (stepsBi == -1 ? "Empty Queue" : stepsBi));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ---------------------------------------------------------
    // SETUP
    // ---------------------------------------------------------
    private static void setupDisconnectedGraph(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS chain_link");
            
            // 1. Build Huge Component (0 to 5,000,000)
            // Linear chain is enough to waste time.
            String sqlHuge = "CREATE TABLE chain_link AS " +
                             "SELECT c1 as curr_id, c1+1 as next_id FROM sys.dummy" + HUGE_SIZE;
            stmt.execute(sqlHuge);
            
            // 2. Build Tiny Component (6,000,000 to 6,000,100)
            // Disconnected from the first set.
            String sqlTiny = "INSERT INTO chain_link " +
                             "SELECT c1+6000000 as curr_id, c1+6000001 as next_id FROM sys.dummy" + TINY_SIZE;
            stmt.execute(sqlTiny);
        }
    }

    // ---------------------------------------------------------
    // LOGIC
    // ---------------------------------------------------------
    private static int runUnidirectional(Connection conn, int start, int end) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0; DECLARE @steps INT = 0; DECLARE @q_size INT = 1;\n" +
                "  CREATE TABLE #queue AS SELECT INT(" + start + ") as id;\n" +
                // Run until queue is empty (@q_size = 0)
                "  WHILE (@found = 0 AND @q_size > 0 AND @steps < 500) DO\n" +
                "    CREATE TABLE #next AS SELECT c.next_id as id FROM #queue q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    SET @found = (SELECT count(*) FROM #next WHERE id = " + end + ");\n" +
                "    DROP TABLE #queue; ALTER TABLE #next RENAME TO #queue;\n" +
                "    SET @q_size = (SELECT count(*) FROM #queue);\n" + // Check if we ran out of road
                "    SET @steps = @steps + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @steps;\n" +
                "END DATAFLOW;";
            try (ResultSet rs = stmt.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int runBidirectional(Connection conn, int start, int end) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Note the logic change: We stop if EITHER queue becomes empty.
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0; DECLARE @steps INT = 0;\n" +
                "  DECLARE @fwd_sz INT = 1; DECLARE @bwd_sz INT = 1;\n" +
                "  CREATE TABLE #fwd AS SELECT INT(" + start + ") as id;\n" +
                "  CREATE TABLE #bwd AS SELECT INT(" + end + ") as id;\n" +

                "  WHILE (@found = 0 AND @fwd_sz > 0 AND @bwd_sz > 0 AND @steps < 500) DO\n" +
                
                "    -- FWD Step\n" +
                "    CREATE TABLE #nf AS SELECT c.next_id as id FROM #fwd q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    DROP TABLE #fwd; ALTER TABLE #nf RENAME TO #fwd;\n" +
                "    SET @fwd_sz = (SELECT count(*) FROM #fwd);\n" +
                
                "    -- Check Intersection\n" +
                "    SET @found = (SELECT count(*) FROM #fwd f JOIN #bwd b ON f.id = b.id);\n" +
                
                "    -- BWD Step (Only if needed and FWD didn't die)\n" +
                "    IF (@found = 0 AND @fwd_sz > 0) THEN\n" +
                "       CREATE TABLE #nb AS SELECT c.curr_id as id FROM #bwd q JOIN chain_link c ON q.id = c.next_id;\n" +
                "       DROP TABLE #bwd; ALTER TABLE #nb RENAME TO #bwd;\n" +
                "       SET @bwd_sz = (SELECT count(*) FROM #bwd);\n" +
                "       SET @found = (SELECT count(*) FROM #fwd f JOIN #bwd b ON f.id = b.id);\n" +
                "    END IF;\n" +
                "    SET @steps = @steps + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @steps;\n" +
                "END DATAFLOW;";
            try (ResultSet rs = stmt.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
        }
    }
}
