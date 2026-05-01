# Datasets

This directory is reserved for the datasets used by the paper's experiments.

## Status

- **Experiments 1--4** generate their own input tables in-process. Each Java
  driver in `experiments/java/` builds its working set with a `CREATE TABLE
  ... AS SELECT ... FROM sys.dummyN` against the engine's built-in
  `sys.dummy*` row-source views, so no separate dataset is needed before
  launching them.

- **Experiment 5 (Hyperscale WCC)** consumes pre-built `bench_vertices` /
  `bench_edges` tables (one schema per scale: `wcc100m`, `wcc1b`, `wcc10b`,
  `wcc100b`, `wcc1t`). The synthetic Zipfian (power-law) generator is
  bundled at `experiments/data_gen/generate_zipfian_graph.py`:

      cd experiments/data_gen
      python generate_zipfian_graph.py --scale wcc1b

  See `experiments/data_gen/README.md` for full usage.
