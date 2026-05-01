import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

public class DematerializationExperiment {
    private static final String URL = System.getenv().getOrDefault("OCIENT_JDBC_URL", "jdbc:ocient://localhost:4050/test");
    private static final String USER = System.getenv().getOrDefault("OCIENT_USER", "admin@system");
    private static final String PASS = System.getenv().getOrDefault("OCIENT_PASSWORD", "");
    
    // The new build threshold: 64 * 1024
    private static final int THRESHOLD = 65536;

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");
            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected. Setting up Dematerialization Experiment...");
                
                setupLogTable(conn);

                System.out.println("Running Reduction Dataflow (Server-Side)...");
                runReduction(conn);

                System.out.println("\nAnalyzing Server-Side Performance Logs (Threshold: " + THRESHOLD + " rows):");
                System.out.println("--------------------------------------------------------------------------------------");
                System.out.printf("%-15s | %-15s | %-15s | %-20s%n", "ROW_COUNT", "STEP_TIME(ms)", "MODE (Inferred)", "NOTES");
                System.out.println("--------------------------------------------------------------------------------------");
                analyzeLogs(conn);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void runReduction(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            String sql = 
                "BEGIN QUERY DATAFLOW\n" +
                "  -- Cleanup log from previous runs\n" +
                "  DELETE FROM benchmark_log WHERE 1=1;\n" +
                
                "  -- Start Physical (200k rows > 64k limit)\n" +
                "  CREATE TABLE #reduce_me AS SELECT c1 as id FROM sys.dummy200000;\n" +
                "  DECLARE @count INT = 200000;\n" +
                
                "  INSERT INTO benchmark_log VALUES (CURRENT_TIMESTAMP(), @count, 'Start');\n" +
                
                "  WHILE (@count > 1000) DO\n" +
                "      -- Delete top half of IDs\n" +
                "      DELETE FROM #reduce_me WHERE id >= (@count / 2);\n" +
                
                "      -- Update count\n" +
                "      SET @count = (SELECT count(*) FROM #reduce_me);\n" +
                
                "      -- Log timestamp\n" +
                "      INSERT INTO benchmark_log VALUES (CURRENT_TIMESTAMP(), @count, 'Step Complete');\n" +
                "  END WHILE;\n" +
                
                "  RETURN SELECT 1;\n" +
                "END DATAFLOW;";
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) { }
            }
        }
    }

    private static void analyzeLogs(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM benchmark_log ORDER BY event_time ASC");
            
            Timestamp prevTime = null;
            int prevCount = -1;
            
            while (rs.next()) {
                Timestamp currTime = rs.getTimestamp("event_time");
                int currCount = rs.getInt("row_count");
                
                if (prevTime != null) {
                    long diffMs = (currTime.getTime() - prevTime.getTime());
                    
                    // Logic updated for 64k threshold
                    String mode = (prevCount > THRESHOLD) ? "PHYSICAL (Disk)" : "VIRTUAL (Ram)";
                    String note = "";
                    
                    if (prevCount > THRESHOLD && currCount <= THRESHOLD) {
                        note = "<-- DEMATERIALIZATION (Hits " + THRESHOLD + " limit)";
                    }
                    
                    System.out.printf("%-15d | %-15d | %-15s | %s%n", 
                        prevCount, diffMs, mode, note);
                }
                
                prevTime = currTime;
                prevCount = currCount;
            }
        }
    }

    private static void setupLogTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS benchmark_log");
            stmt.execute("CREATE TABLE benchmark_log (event_time TIMESTAMP, row_count INT, msg VARCHAR(64))");
        }
    }
}
