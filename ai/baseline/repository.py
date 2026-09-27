"""Supabase access for baseline_dev.readings / baseline_dev.baselines."""

import os
from datetime import datetime, timezone
from typing import Optional

from dotenv import load_dotenv
from supabase import Client, create_client

SCHEMA = "baseline_dev"
PAGE_SIZE = 1000  # PostgREST caps a response at 1000 rows by default


def parse_ts(value: str) -> datetime:
    """PostgREST timestamptz string -> tz-aware UTC datetime."""
    return datetime.fromisoformat(value).astimezone(timezone.utc)


def get_client() -> Client:
    load_dotenv()  # ai/.env, gitignored
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_SERVICE_KEY")
    if not url or not key:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_KEY must be set (see ai/.env.example)")
    return create_client(url, key)


class SupabaseBaselineRepo:
    def __init__(self, client: Client):
        self._db = client.schema(SCHEMA)

    def latest_valid_observed_at(self, region: str, signal_type: str) -> Optional[datetime]:
        rows = (
            self._db.table("readings")
            .select("observed_at")
            .eq("region", region)
            .eq("signal_type", signal_type)
            .or_("is_malformed.is.null,is_malformed.eq.false")
            .not_.is_("value", "null")
            .order("observed_at", desc=True)
            .limit(1)
            .execute()
            .data
        )
        return parse_ts(rows[0]["observed_at"]) if rows else None

    def fetch_readings(self, region: str, signal_type: str, since: datetime) -> list[dict]:
        out: list[dict] = []
        offset = 0
        while True:
            page = (
                self._db.table("readings")
                .select("value,observed_at,is_malformed")
                .eq("region", region)
                .eq("signal_type", signal_type)
                .gte("observed_at", since.isoformat())
                .order("observed_at")
                .order("id")  # deterministic paging when timestamps tie
                .range(offset, offset + PAGE_SIZE - 1)
                .execute()
                .data
            )
            out += [
                {
                    "value": r["value"],
                    "observed_at": parse_ts(r["observed_at"]),
                    "is_malformed": bool(r["is_malformed"]),
                }
                for r in page
            ]
            if len(page) < PAGE_SIZE:
                return out
            offset += PAGE_SIZE

    def upsert_baseline(self, row: dict) -> None:
        """Insert, or update in place on (region, signal_type, window_start, window_end)."""
        self._db.table("baselines").upsert(
            {
                **row,
                "window_start": row["window_start"].isoformat(),
                "window_end": row["window_end"].isoformat(),
                "computed_at": datetime.now(timezone.utc).isoformat(),
            },
            on_conflict="region,signal_type,window_start,window_end",
        ).execute()

    def list_baselines(self) -> list[dict]:
        return (
            self._db.table("baselines")
            .select("region,signal_type,median,mad,window_start,window_end,computed_at")
            .order("computed_at", desc=True)
            .execute()
            .data
        )
