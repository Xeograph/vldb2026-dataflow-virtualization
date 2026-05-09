# Experiment harnesses

Driver scripts that reproduce the paper's reservation-sensitive experiments
end-to-end. Both expect the standard `OCIENT_JDBC_URL` / `OCIENT_USER` /
`OCIENT_PASSWORD` environment variables for connecting to the cluster.

## `harness_1t_skew.py`

1-trillion-edge WCC run with per-Foundation-node resource sampling. Submits
the WCC dataflow against `wcc1t.bench_edges` while collecting per-second
`pidstat` and `iostat` samples from each Foundation node via SSH, then
reports a coarse skew summary.

Outputs raw CSVs under `./skew_run_<timestamp>/` for offline analysis.

```
python harness_1t_skew.py \
    --foundation-nodes <foundation-node-1>..<foundation-node-N>
```

Set `--ssh-user` if SSH login is not the current user. `--skip-dataflow`
stands up the samplers and idles, useful when the dataflow is being
submitted from a separate driver.

## `harness_release_acquire.py`

Quantifies the value of the dataflow runtime's release-acquire WLM
scheduling pattern. Creates a temporary service class with
`max_concurrent_queries=1`, runs a long BFS dataflow under it on one
connection, and concurrently issues short SELECTs on a second connection.
Reports the victim p50/p95/p99 latency.

Runs the experiment twice:

1. **Default release-acquire** (`dataflowHoldsSlotForFullLifetime=false`):
   each child statement of the dataflow takes and releases the slot, so
   victims interleave between iterations. Victim p99 is bounded by the
   longest single child statement.

2. **Hold-slot ablation** (`dataflowHoldsSlotForFullLifetime=true`): the
   parent dataflow holds the slot for its entire lifetime, simulating a
   kernel-level recursive operator. Victims wait for the whole dataflow.
   Victim p99 approaches the dataflow's total runtime.

The size of the gap between the two regimes is the contribution of
release-acquire scheduling.

```
python harness_release_acquire.py
```

Requires a custom build of the database engine that exposes the `dataflowHoldsSlotForFullLifetime` mutable parameter as a session-level toggle. The mutable-parameter PR (Xeograph internal #47580) has been closed without merge for unrelated reasons, so this harness does not run against any shipped Ocient build today. It is included as the harness we would re-run once the parameter lands; the paper itself does not depend on its results, and the gap is acknowledged explicitly in Section 9.1.
