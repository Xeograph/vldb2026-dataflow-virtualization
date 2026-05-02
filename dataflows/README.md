# Dataflow SQL Listings

These files are the dataflow programs exhibited in the paper's listings.
Each file maps to a specific listing in the camera-ready PDF and is
runnable on an Ocient cluster of version `25.0` or later.

| File | Paper listing | Section |
| --- | --- | --- |
| `khop_reachability.sql` | Listing 2: "The transpiled imperative Dataflow" | 1.1 (Running Example) |
| `bidirectional_search.sql` | Listing 7: "Optimized Dataflow Bidirectional Search" | 11.1 |
| `stable_marriage.sql` | Listing 8: "Stable Marriage Dataflow" | 11.2 |
| `wcc.sql` | Listing 9: "Weakly Connected Components (WCC)" | 11.3 |
| `pagerank.sql` | Listing 10: "PageRank Dataflow (Append-Only)" | 11.4 |

## Schema assumptions

The `Edges` / `NetworkEdges` table is `(src BIGINT, dest BIGINT)` and
`Nodes` is `(id BIGINT)`. For Stable Marriage, `Preferences` is
`(man_id INT, woman_id INT, rank INT)` and `Men` is `(id INT)`. For
PageRank, `Edges` carries an `out_degree` column on the source side
(precomputed in a single GROUP BY pass before the loop body if absent).

## Running

Each listing is a single statement; submit the whole file via
`jdbc:ocient://<sqlnode>:4051/<db>` or via the Ocient `xgp` CLI:

```sh
xgp -h <sqlnode> -p 4051 -u <user> -d <db> -f wcc.sql
```

`@start_node`, `@max_depth`, `@startNode`, `@endNode` and the other
session parameters used in the listings are bound either via session
variables (`SET @start_node = 42;`) or via the `WITH RECURSIVE`
parameter mechanism described in Section 6.

## Comparison harnesses

The DuckDB and Umbra harnesses in `../data/` re-implement the WCC
listing (`wcc.sql`) in their respective dialects so that the
cross-engine comparison in Section 12.5 measures the same algorithm
on the same Zipfian inputs.
