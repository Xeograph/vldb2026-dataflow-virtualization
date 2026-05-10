-- Bidirectional Search Dataflow
-- Reference implementation of the Bidirectional Search case study from
-- "Virtualizing Recursion: JIT Graph Analytics in a Hyperscale Relational
-- Warehouse," VLDB 2026.
--
-- Section 8.1 of the paper. Two coupled BFS frontiers expanding from
-- @startNode and @endNode; terminates as soon as the frontiers meet.
-- Mutually recursive over @found, which a single WITH RECURSIVE CTE
-- cannot express directly.

BEGIN DATAFLOW
  -- @startNode and @endNode are illustrative literal values for a
  -- self-contained run; in practice the user wires them through the
  -- enclosing application or substitutes their own ids before
  -- dispatching the dataflow. Dataflows do not have session-bound
  -- parameter binding (only stored procedures do), so the values
  -- are declared inline here.
  DECLARE @startNode BIGINT = 1;
  DECLARE @endNode BIGINT = 1000000;
  DECLARE @found INT = 0;

  -- Initialize frontiers
  CREATE TABLE #FrontierFwd AS
    SELECT * FROM graph WHERE id = @startNode;
  CREATE TABLE #FrontierBwd AS
    SELECT * FROM graph WHERE id = @endNode;

  WHILE (@found = 0) DO
    -- Expand forward & check intersection
    CREATE OR REPLACE TABLE #FrontierFwd AS
      SELECT g.* FROM graph g
      JOIN #FrontierFwd f ON g.src = f.dest;
    SET @found = (
      SELECT count(*) FROM #FrontierFwd f
      JOIN #FrontierBwd b ON f.node_id = b.node_id);

    -- Expand backward ONLY if not found
    IF (@found = 0) THEN
      CREATE OR REPLACE TABLE #FrontierBwd AS
        SELECT g.* FROM graph g
          JOIN #FrontierBwd b ON g.dest = b.src;
      SET @found = (
        SELECT count(*) FROM #FrontierFwd f
          JOIN #FrontierBwd b ON f.node_id = b.node_id);
    END IF;
  END WHILE;
END DATAFLOW;
