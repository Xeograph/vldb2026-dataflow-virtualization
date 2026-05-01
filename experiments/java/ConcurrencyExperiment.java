import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcurrencyExperiment {
    private static final String URL = System.getenv().getOrDefault("OCIENT_JDBC_URL", "jdbc:ocient://localhost:4050/test");
    private static final String USER = System.getenv().getOrDefault("OCIENT_USER", "admin@system");
    private static final String PASS = System.getenv().getOrDefault("OCIENT_PASSWORD", "");

    public static void main(String[] args) throws Exception {
        Class.forName("com.ocient.jdbc.JDBCDriver");
        
        // 1. Establish Baseline (No Load)
        System.out.println("Phase 1: Baseline Ping Latency (No Load)...");
        double baselineP99 = runPingTest(false);
        System.out.printf("Baseline P99: %.2f ms%n", baselineP99);

        // 2. Establish Load (Recursive Query)
        System.out.println("\nPhase 2: Ping Latency Under Recursive Load...");
        AtomicBoolean stopLoad = new AtomicBoolean(false);
        Thread loadThread = new Thread(() -> runBackgroundLoad(stopLoad));
        loadThread.start();

        // Give it a second to warm up
        Thread.sleep(2000);

        // 3. Measure P99 under Load
        double loadP99 = runPingTest(true);
        System.out.printf("Under Load P99: %.2f ms%n", loadP99);
        
        // Stop load
        stopLoad.set(true);
        loadThread.join();
    }

    private static double runPingTest(boolean isUnderLoad) throws Exception {
        List<Long> latencies = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             Statement stmt = conn.createStatement()) {
            
            long endTime = System.currentTimeMillis() + 10000; // Run for 10 seconds
            while (System.currentTimeMillis() < endTime) {
                long start = System.nanoTime();
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    rs.next();
                }
                long duration = (System.nanoTime() - start) / 1000; // microseconds
                latencies.add(duration);
                
                // Sleep to simulate interactive user (10 requests/sec)
                Thread.sleep(100);
            }
        }
        
        Collections.sort(latencies);
        int p99Index = (int)(latencies.size() * 0.99);
        return latencies.get(p99Index) / 1000.0; // convert back to ms
    }

    private static void runBackgroundLoad(AtomicBoolean stop) {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             Statement stmt = conn.createStatement()) {
            
            // Re-use the "High Fanout" graph setup from previous exp
            // Just run a heavy query repeatedly
            String heavySql = 
                "BEGIN QUERY DATAFLOW\n" +
                "  CREATE TABLE #q AS SELECT c1 as id FROM sys.dummy100;\n" +
                "  DECLARE @i INT = 0;\n" +
                "  WHILE (@i < 10) DO\n" +
                // Force a heavy shuffle join
                "     CREATE TABLE #n AS SELECT c.next_id as id FROM #q q JOIN chain_link c ON q.id = c.curr_id;\n" +
                "     DROP TABLE #q; ALTER TABLE #n RENAME TO #q;\n" +
                "     SET @i = @i + 1;\n" +
                "  END WHILE;\n" +
                "  RETURN SELECT count(*) FROM #q;\n" +
                "END DATAFLOW;";

            while (!stop.get()) {
                try {
                    stmt.executeQuery(heavySql);
                } catch (Exception e) {
                    // Ignore interrupts
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
