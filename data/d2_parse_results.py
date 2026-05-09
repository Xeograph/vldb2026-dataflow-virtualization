#!/usr/bin/env python3
"""Parse D2 v6 results to extract per-iteration timing and per-node skew."""
import json
import sys
import re
from collections import defaultdict
import datetime as dt

with open(sys.argv[1]) as f:
    r = json.load(f)

print(f"Scale: {r['scale']}")
print(f"Wall: {r['dataflow_wall_seconds']:.1f}s = {r['dataflow_wall_seconds']/60:.1f}min")
print(f"Completed queries: {len(r.get('completed_queries', []))}")
print()

queries = r.get("completed_queries", [])

# Classify each child statement by what it is.
# In our v6 dataflow, each iteration has:
#   1. DROP TABLE IF EXISTS #LabelsNew
#   2. CREATE TABLE #Updates AS SELECT ...
#   3. SET @changes = (SELECT count(*) FROM #Updates ...)
#   4. CREATE TABLE #LabelsNew AS SELECT ...
#   5. DROP TABLE #Labels
#   6. ALTER TABLE #LabelsNew RENAME TO #Labels
#   7. DROP TABLE #Updates
# The init creates #Labels.
def classify(sql):
    s = sql.lower()
    if "create table temp_updates_" in s or "create table temp_updates" in s:
        return "iter_create_updates"
    if "create table temp_labelsnew_" in s or "create table temp_labelsnew" in s:
        return "iter_create_labelsnew"
    if "create table temp_labels_" in s and "labelsnew" not in s:
        return "init_labels"
    if "select count(*)" in s and "where u.new_label < l.label" in s:
        return "iter_count_changes"
    if "drop table" in s and "labels" in s:
        return "iter_drop_labels"
    if "alter table" in s and "rename" in s:
        return "iter_rename"
    if "drop table" in s and "updates" in s:
        return "iter_drop_updates"
    if "drop table if exists" in s:
        return "iter_drop_labelsnew_pre"
    if "begin dataflow" in s:
        return "outer_dataflow"
    if "with \"cnt_wrapper\"" in s:
        # These are the Coordinator-side count-checks before each big CTAS.
        # The runtime materializes the input first to decide on virtualization.
        return "cnt_wrapper"
    return f"other:{s[:60]}"

# Group by classification
buckets = defaultdict(list)
for q in queries:
    sql = q.get("sql", "")
    bucket = classify(sql)
    q["_kind"] = bucket
    buckets[bucket].append(q)

print("=== Query kind breakdown ===")
for k in sorted(buckets.keys()):
    bucket = buckets[k]
    total = sum((q.get("total_time") or 0) for q in bucket)
    print(f"  {k}: count={len(bucket)} total_time={total/1000:.1f}s")
print()

# Identify iteration boundaries: each iteration produces one
# iter_create_updates, one iter_create_labelsnew. Pair by adjacency.
iter_create_updates = [q for q in queries if q["_kind"] == "iter_create_updates"]
iter_create_labelsnew = [q for q in queries if q["_kind"] == "iter_create_labelsnew"]
iter_count_changes = [q for q in queries if q["_kind"] == "iter_count_changes"]

n_iters = max(len(iter_create_updates), len(iter_create_labelsnew), len(iter_count_changes))
print(f"=== Iteration count: {n_iters} ===")
print()

# Per-iteration wall time = sum of all queries between two iter_create_updates
# events. This binning is approximate; precise is hard without explicit
# iteration markers (which the dataflowEmitIterationMarkers flag would provide).
ts_starts = []
for q in iter_create_updates:
    ts_starts.append(dt.datetime.fromisoformat(q["timestamp_start"]))

print("=== Per-iteration timing (approximate, by Updates-CTAS boundaries) ===")
print(f"{'iter':>4} {'start':>20} {'iter_wall_s':>10} {'updates_s':>10} {'labelsnew_s':>11} {'count_s':>8} {'changes':>10}")
for i in range(n_iters):
    iter_start = ts_starts[i] if i < len(ts_starts) else None
    iter_end = ts_starts[i+1] if i+1 < len(ts_starts) else None
    if iter_end and iter_start:
        wall = (iter_end - iter_start).total_seconds()
    else:
        wall = None
    upd_t = (iter_create_updates[i].get("total_time") or 0) / 1000 if i < len(iter_create_updates) else None
    lab_t = (iter_create_labelsnew[i].get("total_time") or 0) / 1000 if i < len(iter_create_labelsnew) else None
    cnt_t = (iter_count_changes[i].get("total_time") or 0) / 1000 if i < len(iter_count_changes) else None
    chg = iter_count_changes[i].get("rows_returned") if i < len(iter_count_changes) else None

    wall_str = f"{wall:>10.1f}" if wall is not None else (" " * 10)
    upd_str = f"{upd_t:>10.1f}" if upd_t is not None else "-"
    lab_str = f"{lab_t:>11.1f}" if lab_t is not None else "-"
    cnt_str = f"{cnt_t:>8.1f}" if cnt_t is not None else "-"
    print(f"{i+1:>4} {str(iter_start)[:19]:>20} {wall_str} {upd_str} {lab_str} {cnt_str} {str(chg):>10}")

print()

# Per-node skew within each Updates CTAS (the big JOIN). Look at participating_nodes.
print("=== Per-iteration participating nodes (Updates CTAS = the big JOIN) ===")
for i, q in enumerate(iter_create_updates[:5] + iter_create_updates[-5:]):
    nodes = q.get("participating_nodes")
    routed = q.get("routed_through_node")
    total = (q.get("total_time") or 0) / 1000
    iter_label = i if i < 5 else f"({len(iter_create_updates)-(10-i)})"
    print(f"  iter{iter_label}: routed={routed} total={total:.1f}s nodes_count={len(nodes) if nodes else 'NA'}")
