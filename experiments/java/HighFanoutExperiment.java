import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class HighFanoutExperiment {
    // ---------------------------------------------------------
    // CONFIGURATION
    // ---------------------------------------------------------
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // Branching Factor 100.
    // We will generate 10 Million edges.
    // This allows Unidirectional to expand: 1 -> 100 -> 10k -> 1M -> (Boom)
    private static final int GRAPH_EDGES = 10_000_000; 
    private static final int FANOUT = 100;

    // Search from Root (0) to a specific node at Depth 3.
    // Path: 0 -> 1 -> 101 -> 10101 (Indices are examples)
    // We pick a target that guarantees a path exists.
    private static final int START_NODE = 0;
    // Target a node deep in the tree.
    // With BF=100:
    // Depth 1: ~100
    // Depth 2: ~10,000
    // Depth 3: ~1,000,000
    private static final int END_NODE = 880000; 

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                
                // 1. Setup the "Exploding" Graph
                setupHighFanoutGraph(conn, GRAPH_EDGES, FANOUT);

                System.out.println("\nRunning Experiment: High Branching Factor (" + FANOUT + ")");
                System.out.println("Path: " + START_NODE + " -> " + END_NODE + " (Approx 3-4 hops)");
                System.out.println("Constraint: Short path, but massive data explosion.");
                
                System.out.println("\n-------------------------------------------------------------");
                System.out.printf("%-15s | %-15s | %-15s%n", "STRATEGY", "TIME(s)", "TOTAL_STEPS");
                System.out.println("-------------------------------------------------------------");

                // 2. Run Unidirectional (Will hit the 1M -> 100M expansion wall)
                long startUni = System.nanoTime();
                int stepsUni = runUnidirectional(conn, START_NODE, END_NODE);
                double timeUni = (System.nanoTime() - startUni) / 1_000_000_000.0;
                System.out.printf("%-15s | %-15.4f | %-15d%n", "Unidirectional", timeUni, stepsUni);

                // 3. Run Bidirectional (Should meet at ~10k rows)
                long startBi = System.nanoTime();
                int stepsBi = runBidirectional(conn, START_NODE, END_NODE);
                double timeBi = (System.nanoTime() - startBi) / 1_000_000_000.0;
                System.out.printf("%-15s | %-15.4f | %-15d%n", "Bidirectional", timeBi, stepsBi);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------
    // ALGORITHMS
    // ---------------------------------------------------------

    private static int runUnidirectional(Connection conn, int startNode, int endNode) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Note: We increased step limit slightly, but this should finish in <10 steps
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0;\n" +
                "  DECLARE @steps INT = 0;\n" +
                "  CREATE TABLE #queue AS SELECT INT(" + startNode + ") as id;\n" +
                
                "  WHILE (@found = 0 AND @steps < 20) DO\n" +
                "    CREATE TABLE #next AS SELECT c.next_id as id FROM #queue q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    SET @found = (SELECT count(*) FROM #next WHERE id = " + endNode + ");\n" +
                "    DROP TABLE #queue; ALTER TABLE #next RENAME TO #queue;\n" +
                "    SET @steps = @steps + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @steps;\n" +
                "END DATAFLOW;";
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) return rs.getInt(1);
                return -1;
            }
        }
    }

    private static int runBidirectional(Connection conn, int startNode, int endNode) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0;\n" +
                "  DECLARE @steps INT = 0;\n" +
                "  CREATE TABLE #fwd AS SELECT INT(" + startNode + ") as id;\n" +
                "  CREATE TABLE #bwd AS SELECT INT(" + endNode + ") as id;\n" +

                "  WHILE (@found = 0 AND @steps < 20) DO\n" +
                "    -- Expand Fwd\n" +
                "    CREATE TABLE #next_fwd AS SELECT c.next_id as id FROM #fwd q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "    DROP TABLE #fwd; ALTER TABLE #next_fwd RENAME TO #fwd;\n" +

                "    -- Check\n" +
                "    SET @found = (SELECT count(*) FROM #fwd f JOIN #bwd b ON f.id = b.id);\n" +

                "    -- Expand Bwd (Only if needed)\n" +
                "    IF (@found = 0) THEN\n" +
                "       CREATE TABLE #next_bwd AS SELECT c.curr_id as id FROM #bwd q JOIN chain_link c ON q.id = c.next_id;\n" +
                "       DROP TABLE #bwd; ALTER TABLE #next_bwd RENAME TO #bwd;\n" +
                "       SET @found = (SELECT count(*) FROM #fwd f JOIN #bwd b ON f.id = b.id);\n" +
                "    END IF;\n" +
                "    SET @steps = @steps + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @steps;\n" +
                "END DATAFLOW;";
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) return rs.getInt(1);
                return -1;
            }
        }
    }

    // ---------------------------------------------------------
    // GRAPH SETUP (High Fanout Tree)
    // ---------------------------------------------------------
    private static void setupHighFanoutGraph(Connection conn, int totalEdges, int fanout) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            System.out.println("Dropping old table...");
            stmt.execute("DROP TABLE IF EXISTS chain_link");
            
            System.out.println("Creating Graph with " + totalEdges + " edges (Branching Factor " + fanout + ")...");
            
            // MATH EXPLANATION:
            // We want Node 0 to connect to 1..100
            // Node 1 connects to 101..200
            // This maps Parent P -> Children (P*Fanout + 1) to (P*Fanout + Fanout)
            
            // To generate this efficiently in SQL without a massive stored proc:
            // We generate edges sequentially: Edge ID 1 connects Parent 0 to Child 1.
            // Edge ID X connects Parent (X-1)/Fanout to Child X.
            
            // We use sys.dummy to generate 'totalEdges' rows.
            // If totalEdges > dummy limit, we use cross join.
            // Assuming Ocient supports sys.dummy10000000 or similar.
            // If not, we use a cross join of 4k * 4k (16M)
            
            String buildSql = 
                "CREATE TABLE chain_link AS " +
                "SELECT " +
                "   CAST((seq - 1) / " + fanout + " AS INT) as curr_id, " +
                "   CAST(seq AS INT) as next_id " +
                "FROM ( " +
                "   SELECT (a.c1 * 5000 + b.c1) + 1 as seq " +
                "   FROM sys.dummy5000 a, sys.dummy5000 b " + 
                ") t " + 
                "WHERE seq <= " + totalEdges;
            
            // Note: 5000 * 5000 = 25,000,000 potential rows.
            // We limit to totalEdges.
            
            long start = System.currentTimeMillis();
            stmt.execute(buildSql);
            System.out.println("Graph created in " + (System.currentTimeMillis() - start) + "ms");
            
            // OPTIONAL: Create index to make the lookups fast?
            // Unidirectional relies on scans, but indices might help the Bwd search.
            // stmt.execute("CREATE INDEX idx_curr ON chain_link(curr_id)");
            // stmt.execute("CREATE INDEX idx_next ON chain_link(next_id)");
        }
    }
}
