import com.ocient.jdbc.graph.OCGraph;
import com.ocient.jdbc.graph.OCGraph.ExecutionMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

public class BenchmarkConnectedComponents {

    // --- 1. SCALE CONFIGURATION ---
    // Currently set to 1 Trillion scale (assumes tables exist)
    private static final Scale CURRENT_SCALE = Scale.SCALE_1T;

    // --- 2. DATABASE CONFIGURATION ---
    private static final String DB_URL = System.getenv().getOrDefault("OCIENT_JDBC_URL", "jdbc:ocient://localhost:4050/tpc");
    private static final String USER = System.getenv().getOrDefault("OCIENT_USER", "admin@system");
    private static final String PWD = System.getenv().getOrDefault("OCIENT_PASSWORD", "");

    // --- 3. BENCHMARK SETTINGS ---
    // Result table location
    private static final String RESULT_SCHEMA = "benchmark_results";
    private static final String RESULT_TABLE = "wcc_output";

    // --- ENUMS ---
    enum Scale {
        SCALE_100M("wcc100m", 100_000_000L),
        SCALE_1B("wcc1b", 1_000_000_000L),
        SCALE_10B("wcc10b", 10_000_000_000L),
        SCALE_100B("wcc100b", 100_000_000_000L),
        SCALE_1T("wcc1t", 100_000_000_000L); // 1T Edges, approx 100B vertices range

        public final String schema;
        public final long vertexCount;

        Scale(String schema, long vertexCount) {
            this.schema = schema;
            this.vertexCount = vertexCount;
        }
    }

    public static void main(String[] args) {
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PWD);
             Statement stmt = conn.createStatement()) {

            System.out.println("===============================================================");
            System.out.println("      Ocient WCC Benchmark (Execution Only)");
            System.out.println("===============================================================");
            System.out.println("Target Scale:  " + CURRENT_SCALE.name());
            System.out.println("Target Schema: " + CURRENT_SCALE.schema);

            // Calculate max iterations
            int maxIterations = (CURRENT_SCALE.vertexCount > Integer.MAX_VALUE)
                                ? Integer.MAX_VALUE
                                : (int) CURRENT_SCALE.vertexCount;

            // Ensure result schema exists
            stmt.executeUpdate("CREATE SCHEMA IF NOT EXISTS " + RESULT_SCHEMA);

            // Define the task
            BenchmarkTask task = new WccTask(
                CURRENT_SCALE.schema,
                "bench_vertices",
                "bench_edges",
                RESULT_SCHEMA,
                RESULT_TABLE,
                maxIterations
            );

            // Run comparison
            runComparison(stmt, task);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runComparison(Statement stmt, BenchmarkTask task) throws SQLException {
        System.out.println("\n--- Starting Execution ---");

        // 1. Run Legacy
        System.out.print("Running Legacy... ");
        long tLegacy = runSingle(stmt, task, ExecutionMode.ALWAYS_LEGACY);
        System.out.println("Done. (" + tLegacy + " ms)");

        // 2. Run Dataflow
        System.out.print("Running Dataflow... ");
        long tDataflow = runSingle(stmt, task, ExecutionMode.ALWAYS_DATAFLOW);
        System.out.println("Done. (" + tDataflow + " ms)");

        printSummary(tLegacy, tDataflow);
    }

    private static long runSingle(Statement stmt, BenchmarkTask task, ExecutionMode mode) throws SQLException {
        OCGraph.setExecutionConfig(mode);
        task.cleanup(stmt); // Clean previous results

        long start = System.nanoTime();
        task.run(stmt);
        long end = System.nanoTime();

        return (end - start) / 1_000_000; // Convert to ms
    }

    private static void printSummary(long legacyTime, long dataflowTime) {
        double speedup = (double) legacyTime / dataflowTime;

        System.out.println("\n===============================================================");
        System.out.println("                       FINAL RESULTS");
        System.out.println("===============================================================");
        System.out.printf("Legacy Time:    %10d ms\n", legacyTime);
        System.out.printf("Dataflow Time:  %10d ms\n", dataflowTime);
        System.out.printf("Speedup Factor:     %10.2fx %s\n", speedup, (speedup > 1 ? "(Dataflow is faster)" : "(Legacy is faster)"));
        System.out.println("===============================================================");
    }

    // --- TASK INTERFACES ---

    interface BenchmarkTask {
        void run(Statement stmt) throws SQLException;
        void cleanup(Statement stmt) throws SQLException;
    }

    static class WccTask implements BenchmarkTask {
        private final String schema, vTable, eTable;
        private final String rSchema, rTable;
        private final int iterations;

        public WccTask(String schema, String vTable, String eTable, String rSchema, String rTable, int iterations) {
            this.schema = schema;
            this.vTable = vTable;
            this.eTable = eTable;
            this.rSchema = rSchema;
            this.rTable = rTable;
            this.iterations = iterations;
        }

        @Override
        public void run(Statement stmt) throws SQLException {
            OCGraph.connectedComponents(
                schema,
                vTable,
                eTable,
                rSchema,
                rTable,
                iterations,
                new ArrayList<>(), // No grouping columns
                stmt
            );
        }

        @Override
        public void cleanup(Statement stmt) throws SQLException {
            stmt.executeUpdate("DROP TABLE IF EXISTS " + rSchema + "." + rTable);
        }
    }
}
