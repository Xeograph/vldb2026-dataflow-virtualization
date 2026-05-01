# Datasets

This directory is reserved for the datasets used by the paper's experiments.

## Status

- **Experiments 1--4** generate their own input tables in-process. Each Java driver
  in `experiments/java/` builds its working set with a `CREATE TABLE ... AS SELECT
  ... FROM sys.dummyN` against the engine's built-in `sys.dummy*` row-source views,
  so no separate dataset is needed before launching them.

- **Experiment 5 (Hyperscale WCC)** consumes pre-built `bench_vertices` /
  `bench_edges` tables (one schema per scale: `wcc100m`, `wcc1b`, `wcc10b`,
  `wcc100b`, `wcc1t`). The original input graphs were Zipfian (power-law)
  distributions generated via a separate Ocient dataflow against the EPYC
  cluster used in Section 12.5. The generator script is **not bundled here**
  yet --- the source we used has not been preserved as a clean reusable
  artifact. Reproducing Experiment 5 against a fresh cluster therefore
  requires producing equivalent input tables yourself; the
  `BenchmarkConnectedComponents.java` driver assumes those tables already
  exist.
