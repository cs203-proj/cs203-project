from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from baseline import service
from baseline.api import app, get_repo

T0 = datetime(2026, 6, 1, 12, tzinfo=timezone.utc)
HEADERS = {"X-Admin-Token": "secret"}


class FakeRepo:
    """In-memory stand-in; upsert keyed like the DB unique index."""

    def __init__(self, readings):
        self.readings = readings
        self.rows: dict[tuple, dict] = {}

    def latest_valid_observed_at(self, region, signal_type):
        valid = [r["observed_at"] for r in self.readings if r["value"] is not None and not r["is_malformed"]]
        return max(valid) if valid else None

    def fetch_readings(self, region, signal_type, since):
        return [r for r in self.readings if r["observed_at"] >= since]

    def upsert_baseline(self, row):
        self.rows[(row["region"], row["signal_type"], row["window_start"], row["window_end"])] = row

    def list_baselines(self):
        return [
            {**r, "computed_at": "2026-09-24T00:00:00Z", "window_start": r["window_start"].isoformat(),
             "window_end": r["window_end"].isoformat()}
            for r in self.rows.values()
        ]


def series(n_days, base=20.0):
    return [
        {"value": base + (i % 5), "observed_at": T0 + timedelta(days=i), "is_malformed": False}
        for i in range(n_days)
    ]


@pytest.fixture(autouse=True)
def admin_token(monkeypatch):
    monkeypatch.setenv("ADMIN_TOKEN", "secret")


@pytest.fixture
def client():
    repo = FakeRepo(series(100))
    app.dependency_overrides[get_repo] = lambda: repo
    yield TestClient(app), repo
    app.dependency_overrides.clear()


BODY = {"region": "central", "signal_type": "pm25"}


def test_recompute_ok_and_idempotent(client):
    c, repo = client
    first = c.post("/baselines/recompute", json=BODY, headers=HEADERS)
    second = c.post("/baselines/recompute", json=BODY, headers=HEADERS)
    assert first.status_code == second.status_code == 200
    assert first.json()["status"] == "ok" and first.json()["persisted"] is True
    assert first.json()["n_days"] == 91  # 90 days back from the anchor, inclusive
    assert len(repo.rows) == 1
    assert next(iter(repo.rows.values()))["mad"] == first.json()["mad_scaled"]


def test_recompute_insufficient_data_reports_status_and_persists_nothing():
    repo = FakeRepo(series(10))
    app.dependency_overrides[get_repo] = lambda: repo
    try:
        r = TestClient(app).post("/baselines/recompute", json=BODY, headers=HEADERS)
    finally:
        app.dependency_overrides.clear()
    assert r.status_code == 200
    assert r.json()["status"] == "insufficient_data"
    assert r.json()["n_days"] == 10 and r.json()["min_required"] == 30
    assert r.json()["persisted"] is False and repo.rows == {}


def test_recompute_no_data_at_all():
    repo = FakeRepo([])
    app.dependency_overrides[get_repo] = lambda: repo
    try:
        r = TestClient(app).post("/baselines/recompute", json=BODY, headers=HEADERS)
    finally:
        app.dependency_overrides.clear()
    assert r.json()["status"] == "insufficient_data" and r.json()["n_days"] == 0


def test_exclude_window_removes_spike(client):
    c, repo = client
    last_normal = repo.readings[-1]["observed_at"]
    spike = last_normal + timedelta(hours=6)  # latest reading overall -> would set window_end if kept
    repo.readings.append({"value": 900.0, "observed_at": spike, "is_malformed": False})

    kept = c.post("/baselines/recompute", json=BODY, headers=HEADERS).json()
    assert kept["window_end"].startswith(spike.strftime("%Y-%m-%dT%H:%M"))

    body = {**BODY, "exclude_start": (spike - timedelta(hours=1)).isoformat(),
            "exclude_end": (spike + timedelta(hours=1)).isoformat()}
    excluded = c.post("/baselines/recompute", json=body, headers=HEADERS).json()
    assert excluded["window_end"].startswith(last_normal.strftime("%Y-%m-%dT%H:%M"))


def test_exclude_requires_both_bounds(client):
    c, _ = client
    r = c.post("/baselines/recompute", json={**BODY, "exclude_start": "2026-09-03T00:00:00Z"}, headers=HEADERS)
    assert r.status_code == 422


def test_get_baselines_empty_is_clear_not_500(client):
    c, _ = client
    r = c.get("/baselines", headers=HEADERS)
    assert r.status_code == 200
    assert r.json() == {"count": 0, "baselines": [], "message": "No baselines computed yet"}


def test_get_baselines_returns_latest_per_region_signal(client):
    c, _ = client
    c.post("/baselines/recompute", json=BODY, headers=HEADERS)
    r = c.get("/baselines", headers=HEADERS).json()
    assert r["count"] == 1
    assert (r["baselines"][0]["region"], r["baselines"][0]["signal_type"]) == ("central", "pm25")


@pytest.mark.parametrize("headers", [{}, {"X-Admin-Token": "wrong"}])
def test_admin_required(client, headers):
    c, _ = client
    assert c.get("/baselines", headers=headers).status_code == 401
    assert c.post("/baselines/recompute", json=BODY, headers=headers).status_code == 401


@pytest.mark.parametrize("configured", [None, ""])  # env var missing vs. present-but-empty (`ADMIN_TOKEN=`)
@pytest.mark.parametrize("headers", [{}, {"X-Admin-Token": ""}, {"X-Admin-Token": "None"}, {"X-Admin-Token": "secret"}])
def test_fails_closed_when_token_unset_or_empty(client, monkeypatch, configured, headers):
    # Never open: with no configured token every request is rejected (503) before any comparison,
    # including an empty header (which would match an empty expected value if this failed open).
    c, _ = client
    if configured is None:
        monkeypatch.delenv("ADMIN_TOKEN")
    else:
        monkeypatch.setenv("ADMIN_TOKEN", configured)
    assert c.get("/baselines", headers=headers).status_code == 503
    assert c.post("/baselines/recompute", json=BODY, headers=headers).status_code == 503


def test_openapi_has_examples():
    schema = app.openapi()
    assert "example" in schema["components"]["schemas"]["BaselineList"]
    assert "example" in schema["components"]["schemas"]["RecomputeRequest"]
