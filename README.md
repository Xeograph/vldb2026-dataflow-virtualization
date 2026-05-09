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
│       ├── BenchmarkConnectedComponents.java
│       ├── BidirectionalExperiment.java
│       ├── DeepRecursionExperiment.java
│       ├── DematerializationExperiment.java
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

Run an experiment (set environment variables to point at your cluster):

```
export OCIENT_JDBC_URL="jdbc:ocient://<sql-node-host>:4050/<database>"
export OCIENT_USER="<your-user>"
export OCIENT_PASSWORD="<your-password>"

java -cp target/vldb2026-dataflow-experiments-1.0.0.jar:/path/to/ocient-jdbc4-3.6.4-jar-with-dependencies.jar      RecursionExperiment
```

Run a dataflow listing by piping it through the Ocient SQL CLI of your
choice; each `.sql` file in `dataflows/` is the body of a dataflow
script.

## Status

All scripts referenced in the paper's evaluation section are bundled.
The synthetic Zipfian (power-law) graph generator for Experiment 5 is
at `experiments/data_gen/generate_zipfian_graph.py` (see
`data/README.md` for usage).

## License

Apache License 2.0. See `LICENSE`.
