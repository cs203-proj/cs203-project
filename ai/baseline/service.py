"""Recompute orchestration: pick the caller-side window, run compute_baseline, persist."""

from datetime import datetime, timedelta, timezone
from typing import Optional, Protocol

from baseline.compute import compute_baseline

WINDOW_DAYS = 90
MIN_DAYS = 30


class BaselineRepo(Protocol):
    def latest_valid_observed_at(self, region: str, signal_type: str) -> Optional[datetime]: ...
    def fetch_readings(self, region: str, signal_type: str, since: datetime) -> list[dict]: ...
    def upsert_baseline(self, row: dict) -> None: ...
    def list_baselines(self) -> list[dict]: ...


def _utc(dt: datetime) -> datetime:
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)


def recompute(
    repo: BaselineRepo,
    region: str,
    signal_type: str,
    exclude_start: Optional[datetime] = None,
    exclude_end: Optional[datetime] = None,
) -> dict:
    """Compute and persist the baseline for one region/signal.

    Windowing lives here (the caller), not in compute_baseline: the rolling 90 days
    is anchored on the latest valid reading (not now()) so the same data always yields
    the same window and therefore the same upsert key. [exclude_start, exclude_end)
    drops a known spike window so the baseline reflects "normal" readings only.
    Naive datetimes are taken as UTC, and "day" is the UTC calendar date.
    """
    anchor = repo.latest_valid_observed_at(region, signal_type)
    if anchor is None:
        return {**compute_baseline([], MIN_DAYS), "region": region, "signal_type": signal_type, "persisted": False}

    readings = repo.fetch_readings(region, signal_type, anchor - timedelta(days=WINDOW_DAYS))
    if exclude_start and exclude_end:
        lo, hi = _utc(exclude_start), _utc(exclude_end)
        readings = [r for r in readings if not lo <= r["observed_at"] < hi]

    result = compute_baseline(readings, min_days=MIN_DAYS)
    result = {**result, "region": region, "signal_type": signal_type, "persisted": False}
    if result["status"] == "ok":
        repo.upsert_baseline(
            {
                "region": region,
                "signal_type": signal_type,
                "median": result["median"],
                # DECISION (open for team discussion): baselines.mad stores mad_SCALED (MAD * 1.482602…),
                # i.e. a sigma-equivalent, so detection can compute robust z = (x - median) / mad
                # directly. mad_raw is returned by the API but not persisted (single `mad` column).
                "mad": result["mad_scaled"],
                "window_start": result["window_start"],
                "window_end": result["window_end"],
            }
        )
        result["persisted"] = True
    return result
