-- Weakly Connected Components (WCC) Dataflow
-- Companion to Listing 9 ("Weakly Connected Components (WCC)") in
-- "Virtualizing Recursion: JIT Graph Analytics in a Hyperscale Relational
-- Warehouse," VLDB 2026.
--
-- Section 11.3 of the paper. Label-propagation: every node initially
-- holds its own ID as its label, then in each iteration takes the
-- minimum label of its neighbors. Terminates when no labels change.
-- This is the algorithm measured at 100M, 1B, 10B, 100B, and 1T edges
-- in Section 12.5; the comparison harnesses in data/ implement the
-- same algorithm against DuckDB and Umbra.

BEGIN DATAFLOW
  DECLARE @changes INT = 1;

  -- Init: Every node's label is its own ID
  CREATE TABLE #Labels AS
    SELECT id as node_id, id as label FROM Nodes;

  WHILE (@changes > 0) DO
    -- 1. Propagate: Join Edges to find min neighbor
    CREATE TABLE #Updates AS
      SELECT
        e.src AS node_id,
        MIN(l.label) AS new_label
      FROM NetworkEdges e
      JOIN #Labels l ON e.dest = l.node_id
      GROUP BY e.src;

    -- 2. Update: Apply strict improvement
    -- (Only update if neighbor's label is smaller)
    CREATE OR REPLACE TABLE #Labels AS
      SELECT
        l.node_id,
        MIN(u.new_label, l.label) AS label
      FROM #Labels l
      LEFT JOIN #Updates u ON l.node_id = u.node_id;

    -- 3. Check Convergence
    SET @changes = (
      SELECT count(*) FROM #Updates u
      JOIN #Labels l ON u.node_id = l.node_id
      WHERE u.new_label < l.label);

    -- 4. Lifecycle Management
    DROP TABLE #Updates;
  END WHILE;
END DATAFLOW;
