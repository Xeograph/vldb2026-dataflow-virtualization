import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DeepRecursionExperiment {

    // --- CONFIGURATION ---
    private static final String URL = System.getenv().getOrDefault("OCIENT_JDBC_URL", "jdbc:ocient://localhost:4050/test");
    private static final String USER = System.getenv().getOrDefault("OCIENT_USER", "admin@system");
    private static final String PASS = System.getenv().getOrDefault("OCIENT_PASSWORD", "");

    // Deep Recursion Steps: 100 to 1000
    private static final int[] STEPS_TO_TEST = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                System.out.println("Running Experiment 2: Deep Recursion Scalability (Dataflow vs Unrolled)...\n");

                System.out.printf("%-10s | %-15s | %-15s%n", "STEPS", "DATAFLOW(s)", "UNROLLED(s)");
                System.out.println("-------------------------------------------------");

                List<String> csvResults = new ArrayList<>();

                for (int steps : STEPS_TO_TEST) {
                    // 1. Dataflow (Actual C++ Runtime)
                    long startDF = System.nanoTime();
                    runServerSide(conn, steps);
                    double timeDF = (System.nanoTime() - startDF) / 1_000_000_000.0;

                    // 2. Unrolled SQL (Baseline)
                    long startUnrolled = System.nanoTime();
                    runUnrolledSQL(conn, steps);
                    double timeUnrolled = (System.nanoTime() - startUnrolled) / 1_000_000_000.0;

                    // Output
                    System.out.printf("%-10d | %-15.4f | %-15.4f%n", steps, timeDF, timeUnrolled);
                    csvResults.add(steps + "," +
                                   String.format("%.4f", timeDF) + "," +
                                   String.format("%.4f", timeUnrolled));

                    // Pause to allow system to quiesce / WLM to settle
                    Thread.sleep(1000);
                }

                System.out.println("\n--- CSV DATA FOR PLOTTING ---");
                System.out.println("Iterations,Dataflow,Unrolled");
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

    private static void runUnrolledSQL(Connection conn, int steps) throws SQLException {
        if (steps <= 0) return;

        StringBuilder sb = new StringBuilder();
        // Correct loop logic: t(steps+1) ensures exactly 'steps' joins
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

