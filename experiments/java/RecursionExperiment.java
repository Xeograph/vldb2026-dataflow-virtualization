import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class RecursionExperiment {

    // --- CONFIGURATION ---
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // Steps to test
    private static final int[] STEPS_TO_TEST = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                System.out.println("Running Overhead & Latency Experiment (Dataflow vs Client vs Unrolled)...\n");

                System.out.printf("%-10s | %-15s | %-15s | %-15s%n", "STEPS", "DATAFLOW(s)", "CLIENT(s)", "UNROLLED(s)");
                System.out.println("---------------------------------------------------------------");

                List<String> csvResults = new ArrayList<>();

                for (int steps : STEPS_TO_TEST) {
                    // 1. Dataflow (Server-Side)
                    long startDF = System.nanoTime();
                    runServerSide(conn, steps);
                    double timeDF = (System.nanoTime() - startDF) / 1_000_000_000.0;

                    // 2. Client-Side ELT
                    long startClient = System.nanoTime();
                    runClientSide(conn, steps);
                    double timeClient = (System.nanoTime() - startClient) / 1_000_000_000.0;

                    // 3. Unrolled SQL (The "Speed of Light" Baseline)
                    long startUnrolled = System.nanoTime();
                    runUnrolledSQL(conn, steps);
                    double timeUnrolled = (System.nanoTime() - startUnrolled) / 1_000_000_000.0;

                    // Output
                    System.out.printf("%-10d | %-15.4f | %-15.4f | %-15.4f%n", steps, timeDF, timeClient, timeUnrolled);
                    csvResults.add(steps + "," +
                                   String.format("%.4f", timeDF) + "," +
                                   String.format("%.4f", timeClient) + "," +
                                   String.format("%.4f", timeUnrolled));

                    Thread.sleep(500);
                }

                System.out.println("\n--- CSV DATA FOR PLOTTING ---");
                System.out.println("Iterations,Dataflow,Client,Unrolled");
                for (String row : csvResults) {
                    System.out.println(row);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runServerSide(Connection conn, int steps) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql =
                "BEGIN QUERY DATAFLOW\n" +
                "    DECLARE @steps INT = 0;\n" +
                "    CREATE TABLE #walker AS SELECT INT(0) as id;\n" +
                "    WHILE (@steps < " + steps + ") DO\n" +
                "        CREATE TABLE #next_step AS\n" +
                "        SELECT c.next_id as id\n" +
                "        FROM #walker w\n" +
                "        JOIN chain_link c ON w.id = c.curr_id;\n" +
                "        DROP TABLE #walker;\n" +
                "        ALTER TABLE #next_step RENAME TO #walker;\n" +
                "        SET @steps = @steps + 1;\n" +
                "    END WHILE;\n" +
                "    RETURN SELECT count(*) FROM #walker;\n" +
                "END DATAFLOW;";

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while(rs.next()) rs.getLong(1);
            }
        }
    }

    private static void runClientSide(Connection conn, int steps) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("DROP TABLE IF EXISTS bench_client_walker");
                stmt.execute("DROP TABLE IF EXISTS bench_client_next");
                stmt.execute("CREATE TABLE bench_client_walker AS SELECT INT(0) AS id");

                for (int i = 0; i < steps; i++) {
                    stmt.execute(
                        "CREATE TABLE bench_client_next AS " +
                        "SELECT c.next_id as id " +
                        "FROM bench_client_walker w " +
                        "JOIN chain_link c ON w.id = c.curr_id"
                    );
                    stmt.execute("DROP TABLE bench_client_walker");
                    stmt.execute("ALTER TABLE bench_client_next RENAME TO bench_client_walker");
                }
            } finally {
                try {
                    stmt.execute("DROP TABLE IF EXISTS bench_client_walker");
                    stmt.execute("DROP TABLE IF EXISTS bench_client_next");
                } catch (SQLException e) { }
            }
        }
    }

    private static void runUnrolledSQL(Connection conn, int steps) throws SQLException {
        if (steps <= 0) return;

        StringBuilder sb = new StringBuilder();
        // Uses t(steps+1) to ensure we perform exactly 'steps' joins
        // Example: steps=1 -> Join t1, t2. (1 join).
        sb.append("SELECT t").append(steps + 1).append(".curr_id ");
        sb.append("FROM chain_link t1 ");

        for (int i = 2; i <= steps + 1; i++) {
            sb.append("JOIN chain_link t").append(i)
              .append(" ON t").append(i - 1).append(".next_id = t").append(i).append(".curr_id ");
        }

        sb.append("WHERE t1.curr_id = 0");

        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(sb.toString())) {
                while (rs.next()) rs.getLong(1);
            }
        }
    }
}
