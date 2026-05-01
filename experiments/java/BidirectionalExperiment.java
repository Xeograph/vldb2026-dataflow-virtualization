import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class BidirectionalExperiment {
    // ---------------------------------------------------------
    // CONFIGURATION
    // ---------------------------------------------------------
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // We only need enough rows to reach depth 14 (2^14 = 16,384)
    // A graph size of 20,000 is plenty and very fast to build.
    private static final int GRAPH_SIZE = 20_000; 
    
    // Search from Root (1) to Node 16384 (Depth 14)
    private static final int START_NODE = 1;
    private static final int END_NODE = 16384; 

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                
                // 1. Setup the Small Binary Tree
                setupBinaryTree(conn, GRAPH_SIZE);

                System.out.println("\nRunning Experiment: Small Binary Tree");
                System.out.println("Path: " + START_NODE + " -> " + END_NODE + " (14 hops)");
                System.out.println("Unidirectional Frontier: ~16,384 rows");
                System.out.println("Bidirectional Frontier:  ~256 rows (128 + 128)");
                
                System.out.println("\n-------------------------------------------------------------");
                System.out.printf("%-15s | %-15s | %-15s%n", "STRATEGY", "TIME(s)", "TOTAL_STEPS");
                System.out.println("-------------------------------------------------------------");

                // 2. Run Unidirectional
                long startUni = System.nanoTime();
                int stepsUni = runUnidirectional(conn, START_NODE, END_NODE);
                double timeUni = (System.nanoTime() - startUni) / 1_000_000_000.0;
                System.out.printf("%-15s | %-15.4f | %-15d%n", "Unidirectional", timeUni, stepsUni);

                // 3. Run Bidirectional
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
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @found INT = 0;\n" +
                "  DECLARE @steps INT = 0;\n" +
                "  CREATE TABLE #queue AS SELECT INT(" + startNode + ") as id;\n" +
                
                "  WHILE (@found = 0 AND @steps < 100) DO\n" +
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

                "  WHILE (@found = 0 AND @steps < 100) DO\n" +
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
    // GRAPH SETUP (BINARY TREE)
    // ---------------------------------------------------------
    private static void setupBinaryTree(Connection conn, int size) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            System.out.println("Dropping old table...");
            stmt.execute("DROP TABLE IF EXISTS chain_link");
            
            System.out.println("Creating Binary Tree with " + size + " rows...");
            String buildSql = 
                "CREATE TABLE chain_link AS " +
                "SELECT c1+1 as curr_id, (c1+1)*2 as next_id FROM sys.dummy" + size + 
                " UNION ALL " +
                "SELECT c1+1 as curr_id, (c1+1)*2+1 as next_id FROM sys.dummy" + size;
            
            stmt.execute(buildSql);
            System.out.println("Tree created.");
        }
    }
}
