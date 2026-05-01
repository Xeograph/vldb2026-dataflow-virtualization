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
  distributions; we provide a generator that reproduces inputs of the same
  shape and structure at any of those scales:

      cd experiments/data_gen
      python generate_zipfian_graph.py --scale wcc1b

  See `experiments/data_gen/README.md` for full usage. The original
  generator script the paper used was not preserved as a clean reusable
  artifact, so this replacement uses the same `<schema>.bench_vertices` /
  `<schema>.bench_edges` table structure with a power-law endpoint sampler;
  the random seed is not preserved, so numbers will be qualitatively
  similar to but not bit-identical with the paper's reported results.
