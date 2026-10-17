"""Supabase access for baseline_dev.readings / baseline_dev.baselines."""

import os
from datetime import datetime, timezone
from typing import Optional

from dotenv import load_dotenv
from supabase import Client, create_client

SCHEMA = "public" # SCHEMA = "baseline_dev"
PAGE_SIZE = 1000  # PostgREST caps a response at 1000 rows by default

# NEW: API signal_type -> data_sources.name. Add entries as new signals appear.
SIGNAL_SOURCES = {"pm25": "datagovsg_pm25"}
SOURCE_SIGNALS = {v: k for k, v in SIGNAL_SOURCES.items()}

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
        self._source_ids: dict[str, str] = {}        # NEW: cache
        
    def _source_id(self, signal_type: str) -> Optional[str]:
        """NEW: resolve signal_type to a data_sources uuid, or None if unknown."""
        name = SIGNAL_SOURCES.get(signal_type)
        if name is None:
            return None
        if name not in self._source_ids:
            rows = self._db.table("data_sources").select("id").eq("name", name).limit(1).execute().data
            if not rows:
                return None
            self._source_ids[name] = rows[0]["id"]
        return self._source_ids[name]

    def latest_valid_observed_at(self, region: str, signal_type: str) -> Optional[datetime]:
        source_id = self._source_id(signal_type)
        if source_id is None:
            return None
        rows = (
            self._db.table("readings")
            .select("observed_at")
            .eq("region", region)
            .eq("source_id", source_id)
            .or_("is_malformed.is.null,is_malformed.eq.false")
            .not_.is_("value", "null")
            .order("observed_at", desc=True)
            .limit(1)
            .execute()
            .data
        )
        return parse_ts(rows[0]["observed_at"]) if rows else None

    def fetch_readings(self, region: str, signal_type: str, since: datetime) -> list[dict]:
        source_id = self._source_id(signal_type)
        if source_id is None:
            return []
        out: list[dict] = []
        offset = 0
        while True:
            page = (
                self._db.table("readings")
                .select("value,observed_at,is_malformed")
                .eq("region", region)
                .eq("source_id", source_id)
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
            """Insert, or update in place on (source_id, region, window_start, window_end)."""
            source_id = self._source_id(row["signal_type"])
            if source_id is None:
                raise LookupError(f"No data_sources row for signal_type {row['signal_type']!r}")
            self._db.table("baselines").upsert(
                {
                    "source_id": source_id,              # CHANGED
                    "region": row["region"],
                    "baseline_value": row["median"],     # CHANGED: median -> baseline_value
                    "mad": row["mad"],
                    "window_start": row["window_start"].isoformat(),
                    "window_end": row["window_end"].isoformat(),
                    "computed_at": datetime.now(timezone.utc).isoformat(),
                },
                on_conflict="source_id,region,window_start,window_end",   # CHANGED
            ).execute()

    def list_baselines(self) -> list[dict]:
        rows = (
            self._db.table("baselines")
            .select("region,baseline_value,mad,window_start,window_end,computed_at,data_sources(name)")
            .order("computed_at", desc=True)
            .execute()
            .data
        )
        return [
            {
                "region": r["region"],
                "signal_type": SOURCE_SIGNALS.get(r["data_sources"]["name"], r["data_sources"]["name"]),
                "median": r["baseline_value"],
                "mad": r["mad"],
                "window_start": r["window_start"],
                "window_end": r["window_end"],
                "computed_at": r["computed_at"],
            }
            for r in rows
        ]