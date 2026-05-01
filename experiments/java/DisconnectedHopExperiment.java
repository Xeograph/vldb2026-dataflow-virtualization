import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DisconnectedHopExperiment {
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // We use Linear Chains to strictly control the "Hop Count".
    // Disconnected: Start is at top of Huge Chain, End is at bottom of Tiny Chain.
    
    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");
            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                
                // -------------------------------------------------------
                // CASE 1: The Break Even Point (4x Hop Difference)
                // Uni must walk 400 steps. Bi walks 100 steps (x4 overhead).
                // They should finish in roughly the same time.
                // -------------------------------------------------------
                System.out.println("Running Case 1: The Break Even Point (4x Hop Diff)...");
                runExperiment(conn, 400, 100);

                System.out.println("\n-------------------------------------------------------------");

                // -------------------------------------------------------
                // CASE 2: The Blowout (40x Hop Difference)
                // Uni must walk 400 steps. Bi walks 10 steps.
                // Bi should be significantly faster despite the overhead.
                // -------------------------------------------------------
                System.out.println("Running Case 2: The Blowout (40x Hop Diff)...");
                runExperiment(conn, 400, 10);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void runExperiment(Connection conn, int hugeDepth, int tinyDepth) throws Exception {
        setupTrapGraph(conn, hugeDepth, tinyDepth);

        // Nodes: 1 is top of Huge. 
        // End Node is bottom of Tiny.
        // For Tiny size 100, nodes are 2000...2100. End is 2100.
        int startNode = 1;
        int endNode = 2000 + tinyDepth; 

        System.out.printf("Scenario: Huge Depth %d vs Tiny Depth %d%n", hugeDepth, tinyDepth);
        System.out.printf("%-15s | %-15s | %-15s%n", "STRATEGY", "TIME(s)", "STEPS");
        System.out.println("-------------------------------------------------------------");

        // 1. Unidirectional
        long startUni = System.nanoTime();
        int stepsUni = runUnidirectional(conn, startNode, endNode);
        double timeUni = (System.nanoTime() - startUni) / 1_000_000_000.0;
        System.out.printf("%-15s | %-15.4f | %-15s%n", "Unidirectional", timeUni, stepsUni);

        // 2. Bidirectional
        long startBi = System.nanoTime();
        int stepsBi = runBidirectional(conn, startNode, endNode);
        double timeBi = (System.nanoTime() - startBi) / 1_000_000_000.0;
        System.out.printf("%-15s | %-15.4f | %-15s%n", "Bidirectional", timeBi, stepsBi);
    }

    // ---------------------------------------------------------
    // GRAPH SETUP
    // ---------------------------------------------------------
    private static void setupTrapGraph(Connection conn, int hugeDepth, int tinyDepth) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS chain_link");
            
            // 1. Huge Chain: 1 -> 2 -> ... -> hugeDepth
            // Unidirectional starts at 1 and must walk all the way down.
            String sqlHuge = "CREATE TABLE chain_link AS " +
                             "SELECT c1 as curr_id, c1+1 as next_id FROM sys.dummy" + hugeDepth;
            stmt.execute(sqlHuge);
            
            // 2. Tiny Chain: 2001 -> 2002 -> ... -> (2000+tinyDepth)
            // Bidirectional Back-Search starts at Bottom (2000+tinyDepth) and walks UP.
            // It will hit the top (2001) and run out of edges after 'tinyDepth' steps.
            String sqlTiny = "INSERT INTO chain_link " +
                             "SELECT c1+2000 as curr_id, c1+2001 as next_id FROM sys.dummy" + tinyDepth;
            stmt.execute(sqlTiny);
        }
    }

    // ---------------------------------------------------------
    // ALGORITHMS (Standard)
    // ---------------------------------------------------------
    private static int runUnidirectional(Connection conn, int start, int end) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0; DECLARE @steps INT = 0; DECLARE @q_size INT = 1;\n" +
                "  CREATE TABLE #queue AS SELECT INT(" + start + ") as id;\n" +
                "  WHILE (@found = 0 AND @q_size > 0 AND @steps < 1000) DO\n" +
                "    CREATE TABLE #next AS SELECT c.next_id as id FROM #queue q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    SET @found = (SELECT count(*) FROM #next WHERE id = " + end + ");\n" +
                "    DROP TABLE #queue; ALTER TABLE #next RENAME TO #queue;\n" +
                "    SET @q_size = (SELECT count(*) FROM #queue);\n" +
                "    SET @steps = @steps + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @steps;\n" +
                "END DATAFLOW;";
            try (ResultSet rs = stmt.executeQuery(sql)) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int runBidirectional(Connection conn, int start, int end) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0; DECLARE @steps INT = 0;\n" +
                "  DECLARE @fwd_sz INT = 1; DECLARE @bwd_sz INT = 1;\n" +
                "  CREATE TABLE #fwd AS SELECT INT(" + start + ") as id;\n" +
                "  CREATE TABLE #bwd AS SELECT INT(" + end + ") as id;\n" +

                "  WHILE (@found = 0 AND @fwd_sz > 0 AND @bwd_sz > 0 AND @steps < 1000) DO\n" +
                "    -- FWD Step\n" +
                "    CREATE TABLE #nf AS SELECT c.next_id as id FROM #fwd q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    DROP TABLE #fwd; ALTER TABLE #nf RENAME TO #fwd;\n" +
                "    SET @fwd_sz = (SELECT count(*) FROM #fwd);\n" +
                
                "    -- Check Intersection\n" +
                "    SET @found = (SELECT count(*) FROM #fwd f JOIN #bwd b ON f.id = b.id);\n" +
                
                "    -- BWD Step\n" +
                "    IF (@found = 0 AND @fwd_sz > 0) THEN\n" +
                "       -- Note: Traversing chain UPWARDS (curr_id <- next_id)\n" +
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
