"""Robust baseline (median + MAD) for one region/signal series (S1-5)."""

import math
import statistics
from datetime import datetime

# Makes MAD comparable to a standard deviation for normally distributed data.
# Precise value, 1 / norm.ppf(0.75): identical to scipy.stats.median_abs_deviation(scale="normal"),
# which is what tests/oracle.py is derived from.
MAD_SCALE = 1.482602218505602


def compute_baseline(readings: list[dict], min_days: int = 30) -> dict:
    """Compute median and MAD over one region/signal's readings.

    readings: list of dicts, each with:
        - "value": float or None
        - "observed_at": datetime
        - "is_malformed": bool (optional, default False)

    Grouping (region + signal_type) and date-windowing (e.g. the rolling 90 days,
    excluding known spike windows) are the CALLER's responsibility; this function
    only drops malformed / null rows and computes over what it is given.

    A "day" is a distinct calendar date (observed_at.date(), as given, no timezone
    conversion) with at least one valid reading. It is NOT the calendar-date span,
    so gaps do not count towards min_days. (Locked assumption; flag, don't change.)

    Returns {"status": "ok", "median", "mad_raw", "mad_scaled", "window_start",
    "window_end", "n_days"} or {"status": "insufficient_data", "n_days",
    "min_required"}. window_start / window_end are the earliest / latest valid
    observed_at.
    """
    valid = [
        r for r in readings
        if not r.get("is_malformed", False) and _is_usable(r.get("value"))
    ]

    n_days = len({r["observed_at"].date() for r in valid})
    if n_days < min_days:
        return {"status": "insufficient_data", "n_days": n_days, "min_required": min_days}

    values = [float(r["value"]) for r in valid]
    median = statistics.median(values)
    mad_raw = statistics.median([abs(v - median) for v in values])

    observed: list[datetime] = [r["observed_at"] for r in valid]
    return {
        "status": "ok",
        "median": float(median),
        "mad_raw": float(mad_raw),
        "mad_scaled": mad_raw * MAD_SCALE,
        "window_start": min(observed),
        "window_end": max(observed),
        "n_days": n_days,
    }


def _is_usable(value) -> bool:
    # None is the spec'd exclusion; NaN is treated the same since it would poison the median.
    return value is not None and not (isinstance(value, float) and math.isnan(value))
