import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class KCoreExperiment {
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";
    
    // Scaled down: 50k nodes, 250k edges
    private static final int NODES = 1_000 * 1000;
    private static final int EDGES = 3_000 * 1000; 

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");
            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                setupRandomGraph(conn);
                
                System.out.println("Running K-Core (K=3) Decomposition...");
                long start = System.nanoTime();
                int iterations = runKCore(conn, 3);
                double duration = (System.nanoTime() - start) / 1e9;
                
                System.out.printf("K-Core Complete in %.2fs. Total Iterations: %d%n", duration, iterations);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static int runKCore(Connection conn, int k) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql = 
                "BEGIN QUERY DATAFLOW\n" +
                "  CREATE TABLE #Graph AS SELECT src, dest FROM random_graph;\n" +
                "  DECLARE @deleted_count INT = 1;\n" +
                "  DECLARE @iter INT = 0;\n" +
                
                "  WHILE (@deleted_count > 0) DO\n" +
                "     CREATE TABLE #Prune AS \n" +
                "       SELECT src as id FROM #Graph GROUP BY src HAVING count(*) < " + k + " \n" +
                "       UNION \n" +
                "       SELECT dest as id FROM #Graph GROUP BY dest HAVING count(*) < " + k + ";\n" +
                
                "     SET @deleted_count = (SELECT count(*) FROM #Prune);\n" +
                "     \n" +
                "     IF (@deleted_count > 0) THEN\n" +
                "       DELETE FROM #Graph WHERE src IN (SELECT id FROM #Prune) or dest in (SELECT id from #Prune);\n" +
                "     END IF;\n" +
                
                "     -- CRITICAL FIX: Drop table to allow recreation in next loop\n" +
                "     DROP TABLE #Prune;\n" +
                "     SET @iter = @iter + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT @iter;\n" +
                "END DATAFLOW;";
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                rs.next(); return rs.getInt(1);
            }
        }
    }

    private static void setupRandomGraph(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            System.out.println("Generating Random Graph (" + EDGES + " edges)...");
            stmt.execute("DROP TABLE IF EXISTS random_graph");
            String ddl = "CREATE TABLE random_graph AS SELECT " +
                         "ABS(CAST(RAND() * 1000000000 AS BIGINT) % " + NODES + ") as src, " +
                         "ABS(CAST(RAND() * 1000000000 AS BIGINT) % " + NODES + ") as dest " +
                         "FROM sys.dummy" + EDGES;
            stmt.execute(ddl);
        }
    }
}
