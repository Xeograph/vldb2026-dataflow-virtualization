-- k-Hop Reachability Dataflow
-- Companion to Listing 2 ("The transpiled imperative Dataflow") in
-- "Virtualizing Recursion: JIT Graph Analytics in a Hyperscale Relational
-- Warehouse," VLDB 2026.
--
-- The transpiler emits this from the standard recursive-CTE form:
--
--   WITH RECURSIVE Reachable(node_id, depth) AS (
--       SELECT @start_node, 0
--     UNION ALL
--       SELECT e.dest, r.depth + 1
--       FROM Reachable r JOIN Edges e ON r.node_id = e.src
--       WHERE r.depth < @max_depth
--   )
--   SELECT node_id, MIN(depth) AS depth FROM Reachable GROUP BY node_id;
--
-- @start_node and @max_depth are session-level parameters bound when
-- the recursive CTE is dispatched.

BEGIN DATAFLOW
  CREATE TABLE #R AS SELECT @start_node AS node_id, 0 AS depth;
  CREATE OR REPLACE TABLE #Q AS SELECT * FROM #R;
  WHILE ((SELECT count(*) FROM #Q) > 0) DO
    CREATE OR REPLACE TABLE #N AS
      SELECT e.dest AS node_id, q.depth + 1 AS depth
      FROM #Q q JOIN Edges e ON q.node_id = e.src
      WHERE q.depth < @max_depth;
    INSERT INTO #R SELECT * FROM #N;
    DROP TABLE #Q;
    ALTER TABLE #N RENAME TO #Q;
  END WHILE;
  RETURN SELECT node_id, MIN(depth) AS depth FROM #R GROUP BY node_id;
END DATAFLOW;
