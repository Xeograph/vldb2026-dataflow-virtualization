# Synthetic graph generator

`generate_zipfian_graph.py` produces the `bench_vertices` / `bench_edges`
table pair used by `BenchmarkConnectedComponents.java`. For each named
scale the generator builds a power-law-skewed directed graph by inverse-CDF
sampling of endpoint ids:

    rank = floor(N * U^alpha) + 1,   U ~ Uniform(0,1)

The sampler concentrates probability mass on small-numbered vertex ids,
producing a heavy tail of high-degree "supernodes" with a Zipfian-like
shape parameterized by `alpha` (default 1.5).

## Usage

```
export OCIENT_JDBC_URL="jdbc:ocient://<sql-node>:4050/<db>"
export OCIENT_USER="<user>"
export OCIENT_PASSWORD="<pw>"

python generate_zipfian_graph.py --scale wcc1b
```

`--scale` is one of `wcc100m`, `wcc1b`, `wcc10b`, `wcc100b`, `wcc1t`. Use
`--dry-run` to print the dataflow SQL without executing it.

The wrapper relies on `sys.dummy*` virtual row sources to build sufficiently
large iterators; trillion-edge generation requires `sys.dummy100000000000`
(10^11) on the cluster. Smaller deployments can compose two smaller
`sys.dummy*` views via `CROSS JOIN` --- adjust `cross_dummy()` in the
script to match what the local cluster has.
