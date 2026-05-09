# Spark GraphFrames WCC -- Methodology Notes

This document describes the methodology used for the Spark GraphFrames
WCC measurements that appear in:

* Section 9.6.1 / Figure (`Spark`) -- absolute runtime at 100M, 1B, 10B
  edges (DNF at 100B, DNS at 1T).
* Section 9.6.1 / Table 3 (`tab:wcc_per_node`) -- normalized per-node
  throughput at the same scales.

## Cluster shape

* 5 nodes, each:
  * Dual-socket AMD EPYC 9654 96-Core (192 physical cores per node)
  * 2.3 TB RAM (24 x 96 GB)
* Same hardware family as the Ocient cluster used for the same
  experiment. The size disparity (5 vs. 11 nodes) is captured in the
  per-node throughput normalization in Table 3.

## Software stack

* Apache Spark 3.5.x in standalone mode.
* GraphFrames 0.8.x against Spark's bundled Scala/Python stack.
* JVM heap sized to fill ~80% of node RAM (~1.8 TB heap per executor).
* Spark configuration: `spark.executor.memoryOverhead` set to allow
  GraphFrames' shuffle materialization to land on local NVMe; spill
  directories pointed at the node's local SSD.

## Input

The same Zipfian (alpha = 1.5, seed = 42) synthetic graph used for the
Ocient cluster runs (see `experiments/data_gen/generate_zipfian_graph.py`
for the generator). The Spark cluster materialized the graph from CSV
sourced off shared storage.

## Algorithm

Standard GraphFrames `connectedComponents()`, which executes a
label-propagation BFS. Setting matches the WCC dataflow in
`dataflows/wcc.sql` modulo MPP-vs-Spark execution-engine differences:

* Vertex/edge tables loaded into a `GraphFrame`.
* `g.connectedComponents().run()` invoked once.
* Wall-clock measured from start of `run()` to materialization of the
  result DataFrame's first row count.

## What is and isn't bundled

The Spark cluster used for the published numbers belongs to a separate
production Spark deployment that is not packaged into this repository
for reproducibility reasons (cluster-management tooling, security
posture, dataset distribution); the methodology above is sufficient for
an independent operator to reconstruct the run on any 5-node Spark
cluster of similar resource shape. The Ocient-side WCC dataflow that
the Spark numbers are compared against is at `dataflows/wcc.sql`, and
the Ocient driver is at `experiments/java/BenchmarkConnectedComponents.java`.

## DNF / DNS

* `100B` edges: the Spark job hit shuffle-spill local-disk exhaustion
  during the second iteration of label-propagation and was killed
  after >13 hours; reported as `DNF` (Did Not Finish).
* `1T` edges: not attempted; Spark's resource shape and the 100B DNF
  signal made the run unviable. Reported as `DNS` (Did Not Start).
