# Java Microbenchmark Drivers

This directory contains the Java JDBC drivers used to run the experiments
in the VLDB 2026 paper. Each `*Experiment.java` file is an independent
`main`-class program that connects to an Ocient cluster, runs one
experiment scenario, and prints results to stdout (and in some cases CSV).

## Files

| File | Used in paper |
| ---- | ------------- |
| `RecursionExperiment.java`        | Experiment 1 (shallow latency: 10..100 iterations) |
| `DeepRecursionExperiment.java`    | Experiment 2 (deep recursion: up to 600 iterations) |
| `ThroughputExperiment.java`       | Experiment 3 (throughput across the 65,536-row threshold) |
| `DematerializationExperiment.java`| Experiment 4 (reduction dataflow / dynamic dematerialization) |
| `KCoreExperiment.java`            | Hyperscale graph workload (k-core variant; supports Experiment 5) |
| `BidirectionalExperiment.java`    | Bidirectional search dataflow (Section on optimized search) |
| `ConcurrencyExperiment.java`      | Concurrent dataflow execution stress test |
| `CteVsTableCrossover.java`        | CTE-vs-temp-table crossover measurement |
| `DisconnectedExperiment.java`     | WCC on disconnected components |
| `DisconnectedHopExperiment.java`  | WCC variant measuring hop counts on disconnected graphs |
| `HighFanoutExperiment.java`       | High-fanout traversal experiment |

> Note: a 12th experiment, `ViewOptimizationExperiment.java`, is referenced
> in the source tree but only the compiled `.class` was preserved on the
> author's machine. The Java source is missing; see the TODO in the
> top-level README.

## Build

```
cd experiments/java
mvn package
```

The compiled jar will be at `target/vldb2026-dataflow-experiments-1.0.0.jar`
and the resolved classpath will be in the standard Maven layout.

## JDBC driver

The Ocient JDBC driver (`com.ocient:ocient-jdbc4:3.6.4`) is declared as a
Maven dependency in `pom.xml`. **We have not verified that this artifact is
published to Maven Central.** If the dependency does not resolve from your
configured remote repositories, install the jar (obtained from Ocient)
into your local Maven repository:

```
mvn install:install-file \
    -Dfile=/path/to/ocient-jdbc4-3.6.4-jar-with-dependencies.jar \
    -DgroupId=com.ocient \
    -DartifactId=ocient-jdbc4 \
    -Dversion=3.6.4 \
    -Dpackaging=jar
```

The companion paper repo does **not** redistribute the JDBC jar.

## Running

The experiments read the JDBC URL, username, and password from
environment variables (with sensible defaults for a local-development
cluster). Set the following before launching each experiment:

```
export OCIENT_JDBC_URL="jdbc:ocient://<sql-node-host>:4050/<database>"
export OCIENT_USER="<your-user>"
export OCIENT_PASSWORD="<your-password>"
```

Defaults are `jdbc:ocient://localhost:4050/test`, `admin@system`, and
empty password (a connection failure if your cluster expects one).

Then run:

```
java -cp target/vldb2026-dataflow-experiments-1.0.0.jar:/path/to/ocient-jdbc4-3.6.4-jar-with-dependencies.jar      RecursionExperiment
```

Substitute the desired class name to run a different experiment. Most
experiments expect application tables (`graph`, `Edges`, `Nodes`,
`NetworkEdges`, `Preferences`, `Men`) to already exist; see
`docs/reproduction.md` and the per-file source comments.
