#!/usr/bin/env python3
"""Release-acquire WLM scheduling ablation harness.

Drives a controlled experiment that quantifies the value of the dataflow
runtime's release-acquire WLM behavior:

  - Creates a temporary service class with max_concurrent_queries=1.
  - Runs a long-running BFS dataflow against ``wcc100m`` ("blocker"
    connection) using SET serviceclass to force it into the 1-slot SC.
  - Concurrently issues a stream of short SELECT queries on a separate
    "victim" connection with the same SC.
  - Records victim p50/p95/p99 latency.
  - Repeats the run with the dataflowHoldsSlotForFullLifetime mutable
    parameter flipped on (the ablation baseline that simulates a
    kernel-level recursive operator holding its slot for the full
    multi-iteration lifetime).

Compares the two regimes; the gap is the size of the contribution of the
release-acquire scheduling pattern.

Required custom build: branch ``jarnold/db-vldb-ablation`` (or any tip
that includes the ``dataflowHoldsSlotForFullLifetime`` mutable parameter).

Usage:
    OCIENT_JDBC_URL=jdbc:ocient://<sql-node>:4050/tpc \\
    OCIENT_USER=admin@system OCIENT_PASSWORD=<password> \\
    python harness_release_acquire.py
"""
from __future__ import annotations

import argparse
import os
import statistics
import sys
import threading
import time
from typing import Optional

try:
    import pyocient
except ImportError:
    sys.stderr.write("pyocient required: pip install pyocient\n")
    raise


SC_NAME = "vldb_ablation_one_slot"

# A BFS-style dataflow heavy enough to last several minutes against the
# 100M-edge graph; the SC concurrency of 1 means each child statement
# takes the slot in turn under the default release-acquire model.
BLOCKER_DATAFLOW = """
BEGIN DATAFLOW
  DECLARE @changes INT = 1;

  CREATE OR REPLACE TABLE #Labels AS
    SELECT id AS node_id, id AS label FROM wcc100m.bench_vertices;

  WHILE (@changes > 0) DO
    CREATE OR REPLACE TABLE #Updates AS
      SELECT e.src AS node_id, MIN(l.label) AS new_label
      FROM wcc100m.bench_edges e
      JOIN #Labels l ON e.dest = l.node_id
      GROUP BY e.src;

    CREATE OR REPLACE TABLE #Labels AS
      SELECT l.node_id, LEAST(COALESCE(u.new_label, l.label), l.label) AS label
      FROM #Labels l
      LEFT JOIN #Updates u ON l.node_id = u.node_id;

    SET @changes = (
      SELECT count(*) FROM #Updates u
      JOIN #Labels l ON u.node_id = l.node_id
      WHERE u.new_label < l.label
    );
    DROP TABLE #Updates;
  END WHILE;
END DATAFLOW;
"""

# Cheap, deterministic, doesn't read user data --- isolates the WLM slot wait.
VICTIM_QUERY = "SELECT 1 + 1"


def make_dsn(user_override: Optional[str] = None) -> str:
    dsn = os.environ.get("OCIENT_JDBC_URL")
    if not dsn:
        sys.stderr.write("OCIENT_JDBC_URL required in env\n")
        sys.exit(2)
    parsed = dsn.replace("jdbc:ocient://", "ocient://")
    user = user_override or os.environ.get("OCIENT_USER", "admin@system")
    pw = os.environ.get("OCIENT_PASSWORD", "")
    if "@" not in parsed:
        proto, rest = parsed.split("://", 1)
        parsed = f"{proto}://{user}:{pw}@{rest}"
    return parsed


def setup_service_class(admin_dsn: str) -> None:
    """Idempotently CREATE OR REPLACE the 1-slot service class."""
    conn = pyocient.connect(admin_dsn)
    cur = conn.cursor()
    try:
        try:
            cur.execute(f'DROP SERVICE CLASS IF EXISTS "{SC_NAME}" FORCE')
        except Exception:
            pass
        cur.execute(f'CREATE SERVICE CLASS "{SC_NAME}" max_concurrent_queries=1')
        print(f"Created service class {SC_NAME} (max_concurrent_queries=1)")
    finally:
        cur.close()
        conn.close()


def teardown_service_class(admin_dsn: str) -> None:
    conn = pyocient.connect(admin_dsn)
    cur = conn.cursor()
    try:
        cur.execute(f'DROP SERVICE CLASS IF EXISTS "{SC_NAME}" FORCE')
    finally:
        cur.close()
        conn.close()


def set_session_param(dsn: str, key: str, value: str) -> None:
    """Flip a mutable parameter at the session level."""
    conn = pyocient.connect(dsn)
    cur = conn.cursor()
    try:
        # ALTER SESSION SET <key> = <value>; pyocient supports SET ... directly.
        cur.execute(f"SET {key} = {value}")
    finally:
        cur.close()
        conn.close()


def run_blocker(stop_event: threading.Event, dsn: str, error_box: list) -> float:
    """Run the BFS dataflow under SC. Returns wall time (s)."""
    conn = pyocient.connect(dsn)
    cur = conn.cursor()
    t0 = time.monotonic()
    try:
        cur.execute(f'SET serviceclass "{SC_NAME}"')
        cur.execute(BLOCKER_DATAFLOW)
        try:
            cur.fetchall()
        except Exception:
            pass
    except Exception as e:
        error_box.append(repr(e))
    finally:
        cur.close()
        conn.close()
        stop_event.set()
    return time.monotonic() - t0


def run_victims(stop_event: threading.Event, dsn: str, latencies: list[float]) -> None:
    """Hammer 1s SELECTs on a separate connection. Records latency in seconds."""
    conn = pyocient.connect(dsn)
    cur = conn.cursor()
    try:
        cur.execute(f'SET serviceclass "{SC_NAME}"')
        while not stop_event.is_set():
            t0 = time.monotonic()
            try:
                cur.execute(VICTIM_QUERY)
                cur.fetchall()
                latencies.append(time.monotonic() - t0)
            except Exception:
                latencies.append(time.monotonic() - t0)
            time.sleep(0.5)  # 2 victims/s; slot contention is the dominant signal
    finally:
        cur.close()
        conn.close()


def percentile(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    idx = max(0, min(len(s) - 1, int(len(s) * p) - 1))
    return s[idx]


def run_one_regime(label: str, admin_dsn: str, hold_slot: bool) -> dict:
    print(f"\n=== Regime: {label} (dataflowHoldsSlotForFullLifetime={hold_slot}) ===")
    set_session_param(admin_dsn, "dataflowHoldsSlotForFullLifetime", "true" if hold_slot else "false")

    stop = threading.Event()
    err: list[str] = []
    latencies: list[float] = []

    blocker_thread = threading.Thread(target=run_blocker, args=(stop, admin_dsn, err), name="blocker")
    victim_thread = threading.Thread(target=run_victims, args=(stop, admin_dsn, latencies), name="victims")

    t0 = time.monotonic()
    blocker_thread.start()
    time.sleep(1)  # let blocker grab the slot first
    victim_thread.start()
    blocker_thread.join()
    victim_thread.join(timeout=10)
    duration = time.monotonic() - t0

    return {
        "label": label,
        "duration_s": duration,
        "victim_count": len(latencies),
        "victim_p50_s": percentile(latencies, 0.50),
        "victim_p95_s": percentile(latencies, 0.95),
        "victim_p99_s": percentile(latencies, 0.99),
        "victim_max_s": max(latencies) if latencies else float("nan"),
        "blocker_error": err[0] if err else "",
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--skip-cleanup", action="store_true",
                   help="leave the service class in place after the run (useful for debugging)")
    args = p.parse_args()

    admin_dsn = make_dsn()
    setup_service_class(admin_dsn)
    try:
        normal = run_one_regime("release-acquire (default)", admin_dsn, hold_slot=False)
        ablated = run_one_regime("hold-slot (ablation)", admin_dsn, hold_slot=True)
    finally:
        if not args.skip_cleanup:
            teardown_service_class(admin_dsn)

    print("\n=== Summary ===")
    cols = ("label", "duration_s", "victim_count", "victim_p50_s", "victim_p95_s",
            "victim_p99_s", "victim_max_s", "blocker_error")
    print(" | ".join(f"{c:24}" for c in cols))
    for row in (normal, ablated):
        print(" | ".join(f"{str(row.get(c, '')):24}" for c in cols))
    print(
        "\nInterpretation: under the default release-acquire model, victim p99/max\n"
        "is bounded by the longest single child statement of the dataflow. Under the\n"
        "hold-slot ablation, victim p99/max approaches the entire dataflow runtime."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
