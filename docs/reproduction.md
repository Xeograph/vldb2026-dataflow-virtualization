# Reproducing the Paper's Experiments

This document maps each experiment in the paper's evaluation section to
the corresponding driver in `experiments/java/`, and records the exact
cluster shape used in the original measurements.

## Cluster shape (Experiments 1 through 4)

Per Section 12 ("Experimental Setup") of the paper, Experiments 1-4 ran
on an Ocient cluster:

- **2 SQL Nodes** (Coordinators)
- **10 Foundation Nodes** (Compute)
- **1 Loader Node**
- **Processors:** Dual-socket Intel Xeon Gold 6140 (Coordinators);
  Gold 6148 (Compute / Loader)
- **Memory:** 768 GB RAM per node
- **Roles:** Foundation nodes execute query plans; Loader nodes handle
  writes (`INSERT`, `CTAS`). All physical DDL passes through Raft
  consensus, so metadata overhead is realistic.

## Cluster shape (Experiment 5)

Experiment 5 used a distinct, higher-capacity cluster:

- **11 nodes total:** 8 Compute, 2 Loader, 1 SQL
- **Processors:** Dual-socket AMD EPYC 9654 96-Core
- **Memory:** 2.3 TB RAM per node (24 x 96 GB)

For the Spark comparison run in Experiment 5, a separate 5-node Apache
Spark cluster was used (see paper Section 12.5 for details).

## Experiment-to-driver mapping

| Paper experiment | Driver | Notes |
| ---------------- | ------ | ----- |
| **Experiment 1** - Latency & JIT Virtualization (shallow chain traversal, 10..100 iterations; JIT Dataflow vs. Client Baseline vs. Unrolled SQL) | `RecursionExperiment.java` | Default `STEPS_TO_TEST = {10..100}`. |
| **Experiment 2** - Deep Recursion Scalability (up to 600 iterations; JIT Dataflow vs. Unrolled SQL) | `DeepRecursionExperiment.java` | |
| **Experiment 3** - Throughput Scaling & Thresholds (5-iteration loop, 1k..10M rows, virtualization-to-physical crossover) | `ThroughputExperiment.java` | Targets the 65,536-row threshold. |
| **Experiment 4** - Dynamic Dematerialization (reduction dataflow; 200k rows down to 0) | `DematerializationExperiment.java` | |
| **Experiment 5** - Hyperscale WCC (synthetic Zipfian graphs; Spark vs. Ocient Legacy vs. Ocient Dataflow) | `KCoreExperiment.java` (and the `wcc.sql` dataflow) plus the missing data-generation scripts (TODO) | Requires the EPYC cluster shape above. Synthetic graph generator not yet bundled. |

## Supporting experiments referenced in the paper but not pinned to a numbered experiment

| Driver | Purpose |
| ------ | ------- |
| `BidirectionalExperiment.java`   | Validates the Bidirectional Search optimization. |
| `ConcurrencyExperiment.java`     | Concurrent dataflow execution stress test. |
| `CteVsTableCrossover.java`       | CTE-vs-temp-table crossover behavior. |
| `DisconnectedExperiment.java`    | WCC on graphs with many small components. |
| `DisconnectedHopExperiment.java` | Hop-count behavior on disconnected graphs. |
| `HighFanoutExperiment.java`      | High-fanout traversal stress test. |

## TODOs

- **Experiment 5 input graphs.** The Zipfian synthetic graph generator is
  not yet bundled. See `data/README.md`.
- **`ViewOptimizationExperiment.java`** is referenced by the source tree
  but only the compiled `.class` survives. The source must be recovered
  before its experiment can be reproduced.
