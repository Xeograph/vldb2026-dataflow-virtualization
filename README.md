# VLDB 2026 - Virtualizing Recursion: Companion Artifacts

This repository holds the Java microbenchmark drivers and the Dataflow
SQL listings used in the evaluation section of our VLDB 2026 paper on
virtualizing iterative and recursive computation inside an MPP SQL
engine. It is intended as a companion to the paper PDF: the code here
is what produced the numbers in the figures and tables, and the SQL
files are the dataflow programs shown in the paper's listings.

## Citation

```bibtex
@inproceedings{arnold2026virtualizing,
  title     = {Virtualizing Recursion: Dataflow-Based Iterative SQL in an MPP Database},
  author    = {Arnold, Jason and Stolze, Knut},
  booktitle = {Proceedings of the VLDB Endowment},
  year      = {2026},
  publisher = {VLDB Endowment},
  doi       = {TODO: assign DOI on publication},
  note      = {Ocient Inc.}
}
```

## Repository layout

```
.
├── README.md
├── LICENSE
├── experiments/
│   └── java/
│       ├── README.md
│       ├── pom.xml
│       ├── BidirectionalExperiment.java
│       ├── ConcurrencyExperiment.java
│       ├── CteVsTableCrossover.java
│       ├── DeepRecursionExperiment.java
│       ├── DematerializationExperiment.java
│       ├── DisconnectedExperiment.java
│       ├── DisconnectedHopExperiment.java
│       ├── HighFanoutExperiment.java
│       ├── KCoreExperiment.java
│       ├── RecursionExperiment.java
│       └── ThroughputExperiment.java
├── dataflows/
│   ├── README.md
│   ├── simple.sql
│   ├── bidirectional_search.sql
│   ├── stable_marriage.sql
│   ├── wcc.sql
│   └── pagerank.sql
├── docs/
│   └── reproduction.md
└── data/
    └── README.md
```

## Quick start

Build the experiment drivers:

```
cd experiments/java
mvn package
```

The Ocient JDBC driver (`com.ocient:ocient-jdbc4:3.6.4`) may need to be
installed manually if it is not available on your configured Maven
remotes; see `experiments/java/README.md`.

Run an experiment (after editing the JDBC URL at the top of the
relevant `.java` file to point at your cluster):

```
java -cp target/vldb2026-dataflow-experiments-1.0.0.jar:/path/to/ocient-jdbc4-3.6.4-jar-with-dependencies.jar \
     RecursionExperiment
```

Run a dataflow listing by piping it through the Ocient SQL CLI of your
choice; each `.sql` file in `dataflows/` is the body of a dataflow
script.

## Status

Some artifacts are still being prepared. Synthetic graph generation
scripts for Experiment 5 are not yet bundled (TODO; see
`data/README.md`). A 12th experiment driver
(`ViewOptimizationExperiment.java`) is referenced in the source tree
but only the compiled `.class` survives in the author's working
directory; the Java source needs to be recovered before that
experiment can be reproduced.

## License

Apache License 2.0. See `LICENSE`.
