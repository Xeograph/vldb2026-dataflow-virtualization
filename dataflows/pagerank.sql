BEGIN DATAFLOW
  DECLARE @damping DOUBLE = 0.85;
  DECLARE @epsilon DOUBLE = 0.000001;
  DECLARE @diff DOUBLE = 1.0;
  DECLARE @node_count INT = (SELECT count(*) FROM Nodes);

  -- Init: Uniform probability
  CREATE TABLE #PageRank AS
    SELECT id, 1.0 / @node_count as rank FROM Nodes;

  WHILE (@diff > @epsilon) DO
    -- 1. Calculate next iteration
    -- compute full new rank in a single streaming pass;
    -- no in-place update
    CREATE OR REPLACE TABLE #NewRank AS
      SELECT e.dest AS id,
             (1.0 - @damping) +
             (@damping * SUM(p.rank / p.out_degree))
               as rank
      FROM #PageRank p
      JOIN Edges e ON p.id = e.src
      GROUP BY e.dest;

    -- 2. Check Convergence (L1 Norm)
    SET @diff = (SELECT SUM(ABS(n.rank - o.rank))
                 FROM #NewRank n
                 JOIN #PageRank o ON n.id = o.id);

    -- 3. Swap
    CREATE OR REPLACE TABLE #PageRank AS
      SELECT * FROM #NewRank;
  END WHILE;
END DATAFLOW;
