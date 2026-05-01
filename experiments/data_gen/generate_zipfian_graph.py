#!/usr/bin/env python3
"""Synthesize a Zipfian-skewed graph for the paper's WCC benchmarks.

For each named scale, creates two tables in the target schema:
    <schema>.bench_vertices(id BIGINT)             -- N vertices, ids 1..N
    <schema>.bench_edges(src BIGINT, dest BIGINT)  -- E directed edges with
                                                   -- power-law-skewed endpoints

The edge endpoint sampler uses inverse-CDF style:
    rank = floor(N * U^alpha) + 1, U ~ Uniform(0,1)
which produces a heavy-tailed distribution similar in shape to a Zipfian
draw with skew parameter s ~ alpha. This is not a strict-Zipf RNG, but it
reproduces the power-law degree distribution the paper's Section 12.5
inputs were generated with.

Usage:
    OCIENT_JDBC_URL=jdbc:ocient://<sql-node>:4050/<db>  \
    OCIENT_USER=<user>  \
    OCIENT_PASSWORD=<pw>  \
    python generate_zipfian_graph.py --scale wcc1b [--alpha 1.5]

Notes for hyperscale (wcc100b / wcc1t):
- The generator uses CROSS JOIN of `sys.dummy*` virtual row-sources to
  build sufficiently large iterators. Verify that the chosen
  combinations exist on your cluster (queries take a moment to plan
  but cost nothing extra; the engine recognizes these views).
- Generation time is dominated by the CTAS write phase. Trillion-edge
  tables require Loader nodes with significant NVMe headroom; expect
  several hours.
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Tuple

try:
    import pyocient
except ImportError:
    sys.stderr.write("pyocient is required: pip install pyocient\n")
    raise


# Mapping from logical scale name to (num_vertices, num_edges).
# Keep in sync with the paper's Section 12.5 schemas.
SCALES: dict[str, Tuple[int, int]] = {
    "wcc100m": (10_000_000, 100_000_000),
    "wcc1b": (100_000_000, 1_000_000_000),
    "wcc10b": (1_000_000_000, 10_000_000_000),
    "wcc100b": (10_000_000_000, 100_000_000_000),
    "wcc1t": (100_000_000_000, 1_000_000_000_000),
}


def cross_dummy(target_rows: int) -> str:
    """Return a SQL fragment that yields exactly `target_rows` rows.

    Uses the largest single sys.dummyN that fits, then CROSS JOINs with
    a smaller sys.dummyM if needed. Caps each factor at 10**11 to stay
    within reasonable single-CTAS bounds.
    """
    if target_rows <= 0:
        raise ValueError(f"target_rows must be positive, got {target_rows}")

    # Available sys.dummy* sizes (powers of 10). Includes only those we
    # have observed on real Ocient deployments; if a smaller cluster
    # lacks one of these, fall back to a CROSS JOIN of two smaller ones.
    available = [10 ** k for k in range(1, 12)]  # 10..10^11

    # Find the largest factor that divides target_rows evenly.
    for factor in reversed(available):
        if target_rows % factor == 0:
            other = target_rows // factor
            if other == 1:
                return f"SELECT c1 FROM sys.dummy{factor}"
            elif other in available:
                return (
                    f"SELECT a.c1 FROM sys.dummy{factor} a "
                    f"CROSS JOIN sys.dummy{other} b"
                )
    raise ValueError(
        f"Cannot factor {target_rows} into available sys.dummy* sizes"
    )


def render_dataflow(schema: str, num_vertices: int, num_edges: int, alpha: float) -> str:
    vsrc = cross_dummy(num_vertices)
    esrc = cross_dummy(num_edges)
    return f"""\
BEGIN DATAFLOW
  CREATE SCHEMA IF NOT EXISTS {schema};

  CREATE OR REPLACE TABLE {schema}.bench_vertices AS
  SELECT CAST(ROW_NUMBER() OVER () AS BIGINT) AS id
  FROM ({vsrc}) AS vsrc;

  CREATE OR REPLACE TABLE {schema}.bench_edges AS
  SELECT
    CAST(FLOOR({num_vertices} * POWER(rand(), {alpha})) + 1 AS BIGINT) AS src,
    CAST(FLOOR({num_vertices} * POWER(rand(), {alpha})) + 1 AS BIGINT) AS dest
  FROM ({esrc}) AS esrc;
END DATAFLOW;
"""


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scale", required=True, choices=sorted(SCALES.keys()),
                   help="paper-named scale; vertex / edge counts come from SCALES")
    p.add_argument("--alpha", type=float, default=1.5,
                   help="power-law skew exponent; >1 produces a heavy tail (default 1.5)")
    p.add_argument("--dry-run", action="store_true",
                   help="print the dataflow SQL but do not execute")
    args = p.parse_args()

    schema = args.scale
    nv, ne = SCALES[schema]
    sql = render_dataflow(schema, nv, ne, args.alpha)

    if args.dry_run:
        print(sql)
        return 0

    dsn = os.environ.get("OCIENT_JDBC_URL")
    user = os.environ.get("OCIENT_USER", "admin@system")
    pw = os.environ.get("OCIENT_PASSWORD", "")
    if not dsn:
        sys.stderr.write("OCIENT_JDBC_URL is required in the environment\n")
        return 2
    parsed = dsn.replace("jdbc:ocient://", "ocient://")
    if "@" not in parsed:
        # ocient://host:port/db  -> ocient://user:pw@host:port/db
        proto, rest = parsed.split("://", 1)
        parsed = f"{proto}://{user}:{pw}@{rest}"

    print(f"Submitting Zipfian generator for {schema} (NV={nv}, NE={ne}, alpha={args.alpha})...")
    conn = pyocient.connect(parsed)
    cur = conn.cursor()
    try:
        cur.execute(sql)
    finally:
        cur.close()
        conn.close()
    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
