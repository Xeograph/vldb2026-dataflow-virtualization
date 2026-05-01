BEGIN DATAFLOW
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
