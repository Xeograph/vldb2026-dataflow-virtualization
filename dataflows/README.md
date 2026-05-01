# Dataflow SQL Listings

These are the Dataflow SQL fragments shown in the paper. They use Ocient
control-flow extensions (`BEGIN DATAFLOW` / `BEGIN QUERY DATAFLOW`, `WHILE`,
`IF`, `DECLARE @var`, `#temp` tables). Each file is the body of a dataflow
script. To run any of them, wrap the contents in a connection script for
the SQL CLI of your choice (e.g., the Ocient JDBC CLI, a `psql`-style
client, or the Java drivers in `experiments/java/`).

| File | Paper listing |
| ---- | ------------- |
| `simple.sql`               | A Simple Dataflow |
| `bidirectional_search.sql` | Optimized Dataflow Bidirectional Search |
| `stable_marriage.sql`      | Stable Marriage Dataflow |
| `wcc.sql`                  | Weakly Connected Components (WCC) |
| `pagerank.sql`             | PageRank Dataflow (Append-Only) |

The listings reference application tables (`graph`, `Preferences`, `Men`,
`Nodes`, `NetworkEdges`, `Edges`) that must be created and populated
before execution. See `docs/reproduction.md` for the cluster shape used
in the paper. Synthetic graph generation scripts are not yet bundled
(see `data/README.md`).
