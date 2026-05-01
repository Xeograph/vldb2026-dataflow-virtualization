import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CteVsTableCrossover {

    // --- CONFIGURATION ---
    private static final String URL = "jdbc:ocient://go-sql0:4050/test";
    private static final String USER = "admin@system";
    private static final String PASS = "admin";

    // Test range: 1,000 to 256,000 rows
    private static final int START_ITEMS = 1_000;
    private static final int MAX_ITEMS = 256_000;

    public static void main(String[] args) {
        try {
            Class.forName("com.ocient.jdbc.JDBCDriver");

            try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
                System.out.println("Connected to " + URL);
                System.out.println("Running Experiment: Virtual CTE vs. Physical Storage Crossover\n");

                System.out.printf("%-12s | %-15s | %-15s | %-10s%n",
                    "ITEM_COUNT", "CTE_TIME(s)", "TABLE_TIME(s)", "WINNER");
                System.out.println("---------------------------------------------------------------");

                checkTableExists(conn);

                for (int count = START_ITEMS; count <= MAX_ITEMS; count = (int)(count * 1.4142)) {

                    List<Integer> ids = generateRandomIds(count);

                    // 1. Test Virtual CTE (Parsing Bound)
                    double timeCte = Double.MAX_VALUE;
                    try {
                        long startCte = System.nanoTime();
                        runCteStrategy(conn, ids);
                        timeCte = (System.nanoTime() - startCte) / 1_000_000_000.0;
                    } catch (SQLException e) {
                        System.err.println("CTE FAILED: " + e.getMessage());
                    }

                    // 2. Test Physical Table (I/O Bound)
                    double timeTable = Double.MAX_VALUE;
                    try {
                        long startTable = System.nanoTime();
                        runTableStrategy(conn, count);
                        timeTable = (System.nanoTime() - startTable) / 1_000_000_000.0;
                    } catch (SQLException e) {
                        // --- FIX HERE: Print the error instead of hiding it ---
                        System.err.println("TABLE FAILED at count " + count + ": " + e.getMessage());
                        // e.printStackTrace(); // Uncomment if you need the stack trace
                    }

                    // Determine Winner
                    String winner = "N/A";
                    String marker = "";

                    if (timeCte < timeTable) {
                        winner = "CTE";
                    } else if (timeTable < timeCte) {
                        winner = "TABLE";
                        if (timeCte != Double.MAX_VALUE) {
                            marker = "<-- CROSSOVER";
                        }
                    }

                    // --- FIX HERE: Handle formatting for MAX_VALUE ---
                    String cteStr = (timeCte == Double.MAX_VALUE) ? "ERROR" : String.format("%.4f", timeCte);
                    String tableStr = (timeTable == Double.MAX_VALUE) ? "ERROR" : String.format("%.4f", timeTable);

                    System.out.printf("%-12d | %-15s | %-15s | %-10s %s%n",
                        count, cteStr, tableStr, winner, marker);

                    // Yield to let DB cleanup
                    Thread.sleep(500);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- STRATEGY 1: VIRTUAL CTE (Parsing Intensive) ---
    private static void runCteStrategy(Connection conn, List<Integer> ids) throws SQLException {
        StringBuilder sb = new StringBuilder(ids.size() * 12 + 500);

        sb.append("WITH input_ids AS (SELECT UNNEST(ARRAY[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("]) as id FROM sys.dummy1) ");
        sb.append("SELECT count(c.next_id) FROM input_ids i JOIN chain_link c ON i.id = c.curr_id");

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sb.toString())) {
            while (rs.next()) rs.getLong(1);
        }
    }

    // --- STRATEGY 2: PHYSICAL TABLE (Storage Intensive) ---
    private static void runTableStrategy(Connection conn, int count) throws SQLException {
        // Use a clean, specific name to avoid collisions
        String tableName = "temp_load_" + System.nanoTime();

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + tableName + " (id INT)");

            // Keeping original logic per your instruction regarding sys.dummyN
            String insertSql = "INSERT INTO " + tableName + " SELECT int(rand() * 100000) FROM sys.dummy" + count;
            stmt.execute(insertSql);

            String query = "SELECT count(c.next_id) FROM " + tableName + " i JOIN chain_link c ON i.id = c.curr_id";
            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) rs.getLong(1);
            }

        } finally {
            try (Statement cleanup = conn.createStatement()) {
                cleanup.execute("DROP TABLE IF EXISTS " + tableName);
            }
        }
    }

    private static List<Integer> generateRandomIds(int count) {
        List<Integer> list = new ArrayList<>(count);
        Random r = new Random();
        for (int i = 0; i < count; i++) {
            list.add(r.nextInt(100000));
        }
        return list;
    }

    private static void checkTableExists(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
             stmt.executeQuery("SELECT 1 FROM chain_link LIMIT 1");
        } catch (SQLException e) {
             System.out.println("WARNING: chain_link table missing! Unrolled tests might fail or return 0.");
        }
    }
}
