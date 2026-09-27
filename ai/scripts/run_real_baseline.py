"""Pull real baseline_dev.readings, compute the baseline, cross-check it, and persist it.

Run from ai/:  .venv/bin/python -m scripts.run_real_baseline
Needs SUPABASE_URL and SUPABASE_SERVICE_KEY in ai/.env.
"""

import math
from datetime import datetime, timedelta, timezone

from baseline import repository, service
from baseline.compute import compute_baseline

REGION, SIGNAL = "central", "pm25"
# Own copy of the scale constant (1 / norm.ppf(0.75)), deliberately not imported from compute.py.
SCALE = 1.482602218505602
# Known haze-spike window (UTC, end exclusive) - excluded so the baseline reflects "normal" readings.
SPIKE = (datetime(2026, 9, 3, tzinfo=timezone.utc), datetime(2026, 9, 5, tzinfo=timezone.utc))


def manual_median(xs):
    """Independent of statistics/compute.py: sort and pick the middle."""
    s = sorted(xs)
    n = len(s)
    return float(s[n // 2]) if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def main():
    repo = repository.SupabaseBaselineRepo(repository.get_client())

    anchor = repo.latest_valid_observed_at(REGION, SIGNAL)
    rows = repo.fetch_readings(REGION, SIGNAL, anchor - timedelta(days=service.WINDOW_DAYS))
    rows = [r for r in rows if not SPIKE[0] <= r["observed_at"] < SPIKE[1]]
    print(f"fetched {len(rows)} rows in window (spike excluded), anchor={anchor.isoformat()}")

    result = compute_baseline(rows)
    print("compute_baseline:", result)

    # Independent cross-check: raw fields, own filtering, own median/MAD, own scaling.
    vals = [r["value"] for r in rows if r["value"] is not None and r["is_malformed"] is not True]
    med = manual_median(vals)
    mad = manual_median([abs(v - med) for v in vals])
    print(f"manual: n={len(vals)} median={med!r} mad_raw={mad!r} mad_scaled={mad * SCALE!r}")
    # isclose, not ==: float subtraction (e.g. 18.9 - 14.9 = 3.9999999999999982) and Postgres's 15-digit float8 output differ by ~1e-15.
    got, want = (result["median"], result["mad_raw"], result["mad_scaled"]), (med, mad, mad * SCALE)
    assert all(math.isclose(g, w, rel_tol=1e-9) for g, w in zip(got, want)), f"MISMATCH: {got} vs {want}"
    print("cross-check: match (rel_tol=1e-9)")

    persisted = service.recompute(repo, REGION, SIGNAL, *SPIKE)
    print("recompute+persist:", persisted)
    assert math.isclose(persisted["median"], med, rel_tol=1e-9) and math.isclose(persisted["mad_scaled"], mad * SCALE, rel_tol=1e-9)


if __name__ == "__main__":
    main()
