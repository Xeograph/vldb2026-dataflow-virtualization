-- Stable Marriage Dataflow
-- Reference implementation of the Stable Marriage case study from
-- "Virtualizing Recursion: JIT Graph Analytics in a Hyperscale Relational
-- Warehouse," VLDB 2026.
--
-- Section 8.2 of the paper. The classical Gale-Shapley algorithm
-- expressed as a Dataflow loop: propose, evaluate, reject, repeat.
-- This is non-monotonic (DELETE inside the loop body) and therefore
-- not expressible in a single WITH RECURSIVE CTE.

BEGIN QUERY DATAFLOW
  DECLARE @changes INT = 1;

  -- 1. Setup: Copy preferences to temp table
  CREATE TABLE #AvailablePrefs AS
    SELECT * FROM Preferences;

  -- Initial State: All men are free, no proposals yet
  CREATE TABLE #FreeMen AS SELECT id FROM Men;
  CREATE TABLE #Proposals (
    man_id INT, woman_id INT, rank INT);

  WHILE (@changes > 0) DO
    -- 2. Propose: Pick best choice for each free man
    INSERT INTO #Proposals
    SELECT man_id, woman_id, rank
    FROM (
      SELECT man_id, woman_id, rank,
             ROW_NUMBER() OVER (PARTITION BY man_id
                                ORDER BY rank ASC) as rn
      FROM #AvailablePrefs
      WHERE man_id IN (SELECT id FROM #FreeMen)
    ) candidates
    WHERE rn = 1;

    -- Men are no longer free once they propose
    DELETE FROM #FreeMen;

    -- 3. Evaluate: Women reject all but the suitor with lowest ID
    CREATE OR REPLACE TABLE #BadProposals AS
      SELECT p.man_id, p.woman_id
      FROM #Proposals p
      WHERE p.man_id > (SELECT MIN(man_id)
                        FROM #Proposals p2
                        WHERE p2.woman_id = p.woman_id);
    SET @changes = (SELECT count(*) FROM #BadProposals);

    -- 4. Reject: Remove losers and delete that preference permanently
    IF (@changes > 0) THEN
      DELETE FROM #Proposals
      WHERE man_id IN (SELECT man_id FROM #BadProposals);

      DELETE FROM #AvailablePrefs
      WHERE man_id IN (SELECT man_id
                       FROM #BadProposals)
        AND woman_id IN (SELECT woman_id
                         FROM #BadProposals);

      INSERT INTO #FreeMen
        SELECT man_id FROM #BadProposals;
    END IF;
  END WHILE;

  -- Return final matches
  RETURN SELECT * FROM #Proposals;
END DATAFLOW;
