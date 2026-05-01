import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ThroughputExperiment {

    // --- CONFIGURATION ---
    private static final String URL = System.getenv().getOrDefault("OCIENT_JDBC_URL", "jdbc:ocient://localhost:4050/test");
    private static final String USER = System.getenv().getOrDefault("OCIENT_USER", "admin@system");
    private static final String PASS = System.getenv().getOrDefault("OCIENT_PASSWORD", "");
    
    // Scaling Row Counts: 1K, 10K, 100K, 1M, 5M, 10M
    private static final int[] SIZES = {1_000, 10_000, 100_000, 1_000_000, 5_000_000, 10_000_000};
    private static final int FIXED_LOOPS = 5;

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");
            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                System.out.println("Running Throughput Scaling Experiment (Fixed 5 Loops)...\n");

                System.out.printf("%-12s | %-15s | %-15s%n", "ROWS", "TOTAL_TIME(s)", "TIME_PER_ROW(ns)");
                System.out.println("-----------------------------------------------------");
                
                List<String> csvResults = new ArrayList<>();

                for (int targetSize : SIZES) {
                    // 1. Generate Data (Using Ocient virtual tables)
                    setupData(conn, targetSize);

                    // 2. Run Recursion
                    long start = System.nanoTime();
                    runRecursion(conn, FIXED_LOOPS);
                    double duration = (System.nanoTime() - start) / 1_000_000_000.0;
                    
                    // Metric: Nanoseconds per row processed
                    // Total rows processed = targetSize * loops
                    double nsPerRow = (duration * 1_000_000_000.0) / ((long)targetSize * FIXED_LOOPS);

                    System.out.printf("%-12d | %-15.4f | %-15.2f%n", targetSize, duration, nsPerRow);
                    csvResults.add(targetSize + "," + String.format("%.4f", duration) + "," + String.format("%.2f", nsPerRow));
                    
                    // Cleanup
                    dropData(conn);
                }
                
                System.out.println("\n--- CSV DATA ---");
                System.out.println("Rows,TotalTime,NsPerRow");
                for(String r : csvResults) System.out.println(r);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void setupData(Connection conn, int size) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS payload_source");
            
            // Efficient generation using sys.dummyN
            // e.g., CREATE TABLE payload_source AS SELECT 1 AS id FROM sys.dummy100000
            String sql = "CREATE TABLE payload_source AS SELECT 1 AS id FROM sys.dummy" + size;
            stmt.execute(sql);
        }
    }

    private static void dropData(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS payload_source");
        }
    }

    private static void runRecursion(Connection conn, int loops) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql = 
                "BEGIN QUERY DATAFLOW\n" +
                "  DECLARE @i INT = 0;\n" +
                // Initialize with full payload size
                "  CREATE TABLE #buffer AS SELECT * FROM payload_source;\n" + 
                "  WHILE (@i < " + loops + ") DO\n" +
                // Copy the buffer (Simulating a heavy step)
                "    CREATE TABLE #next AS SELECT * FROM #buffer;\n" + 
                "    DROP TABLE #buffer;\n" +
                "    ALTER TABLE #next RENAME TO #buffer;\n" +
                "    SET @i = @i + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT count(*) FROM #buffer;\n" +
                "END DATAFLOW;";
            
            try(ResultSet rs = stmt.executeQuery(sql)) {
                while(rs.next()) rs.getLong(1);
            }
        }
    }
}
