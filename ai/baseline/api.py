"""HTTP API for baselines. Run from ai/:  uvicorn baseline.api:app --reload"""

import hmac
import os
from datetime import datetime
from typing import Literal, Optional

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, model_validator

from baseline import repository, service

load_dotenv()  # ai/.env, so ADMIN_TOKEN is set before the first request's auth check

app = FastAPI(title="HealthWatch Baseline API", version="0.1.0")


# --- dependencies -----------------------------------------------------------------

def get_repo() -> service.BaselineRepo:
    return repository.SupabaseBaselineRepo(repository.get_client())


def require_admin(x_admin_token: Optional[str] = Header(default=None, description="Admin stopgap token")) -> None:
    """AUTH STOPGAP: the repo has no real auth yet (Spring Security default login only).
    Compares X-Admin-Token to the ADMIN_TOKEN env var; fails closed if it is unset.
    Replace with the JWT admin role check once real auth lands."""
    expected = os.environ.get("ADMIN_TOKEN")
    if not expected:
        raise HTTPException(503, "ADMIN_TOKEN is not configured on the server")
    if not x_admin_token or not hmac.compare_digest(x_admin_token, expected):
        raise HTTPException(401, "Missing or invalid X-Admin-Token")


# --- models -----------------------------------------------------------------------

class RecomputeRequest(BaseModel):
    region: str = Field(min_length=1)
    signal_type: str = Field(min_length=1)
    exclude_start: Optional[datetime] = Field(None, description="Start of a known spike window to leave out (inclusive; naive = UTC)")
    exclude_end: Optional[datetime] = Field(None, description="End of the spike window (exclusive; naive = UTC)")

    @model_validator(mode="after")
    def _exclude_window_valid(self):
        if (self.exclude_start is None) != (self.exclude_end is None):
            raise ValueError("exclude_start and exclude_end must be given together")
        if self.exclude_start and self.exclude_start >= self.exclude_end:
            raise ValueError("exclude_start must be before exclude_end")
        return self

    model_config = {
        "json_schema_extra": {
            "example": {
                "region": "central",
                "signal_type": "pm25",
                "exclude_start": "2026-09-03T00:00:00Z",
                "exclude_end": "2026-09-05T00:00:00Z",
            }
        }
    }


class RecomputeResponse(BaseModel):
    status: Literal["ok", "insufficient_data"]
    region: str
    signal_type: str
    n_days: int
    persisted: bool = Field(description="True when a baselines row was inserted or updated")
    min_required: Optional[int] = Field(None, description="Only on insufficient_data")
    median: Optional[float] = None
    mad_raw: Optional[float] = None
    mad_scaled: Optional[float] = Field(None, description="mad_raw * 1.482602… (scipy's normal scale); this is what is stored as baselines.mad")
    window_start: Optional[datetime] = None
    window_end: Optional[datetime] = None

    model_config = {
        "json_schema_extra": {
            "example": {
                "status": "ok",
                "region": "central",
                "signal_type": "pm25",
                "n_days": 89,
                "persisted": True,
                "median": 18.9,
                "mad_raw": 4.0,
                "mad_scaled": 5.930408874022408,
                "window_start": "2026-06-26T07:06:21.067962Z",
                "window_end": "2026-09-24T07:06:21.067962Z",
            }
        }
    }


class BaselineOut(BaseModel):
    region: str
    signal_type: str
    median: float
    mad: float = Field(description="Scaled MAD (raw MAD * 1.482602…)")
    window_start: datetime
    window_end: datetime
    computed_at: datetime


class BaselineList(BaseModel):
    count: int
    baselines: list[BaselineOut]
    message: Optional[str] = None

    model_config = {
        "json_schema_extra": {
            "example": {
                "count": 1,
                "baselines": [
                    {
                        "region": "central",
                        "signal_type": "pm25",
                        "median": 18.9,
                        "mad": 5.930408874022408,
                        "window_start": "2026-06-26T07:06:21.067962Z",
                        "window_end": "2026-09-24T07:06:21.067962Z",
                        "computed_at": "2026-09-24T08:00:00Z",
                    }
                ],
                "message": None,
            }
        }
    }


# --- endpoints --------------------------------------------------------------------

@app.post(
    "/baselines/recompute",
    response_model=RecomputeResponse,
    summary="Recompute and persist the baseline for one region/signal",
    description=(
        "Rolling 90 days ending at the latest valid reading; needs >= 30 distinct days. Idempotent: repeating "
        "the call with unchanged data updates the same row rather than adding one. Always HTTP 200 with "
        "`status` = `ok` or `insufficient_data` (nothing is persisted in the latter case)."
    ),
    responses={401: {"description": "Missing or invalid X-Admin-Token"}},
    dependencies=[Depends(require_admin)],
)
def recompute_baseline(body: RecomputeRequest, repo: service.BaselineRepo = Depends(get_repo)):
    return service.recompute(repo, body.region, body.signal_type, body.exclude_start, body.exclude_end)


@app.get(
    "/baselines",
    response_model=BaselineList,
    summary="Current baseline per region/signal (admin only)",
    description="Latest computed baseline for each region + signal_type. Empty list (with a message) if none yet.",
    responses={401: {"description": "Missing or invalid X-Admin-Token"}},
    dependencies=[Depends(require_admin)],
)
def list_baselines(repo: service.BaselineRepo = Depends(get_repo)):
    latest: dict[tuple[str, str], dict] = {}
    for row in repo.list_baselines():  # newest computed_at first
        latest.setdefault((row["region"], row["signal_type"]), row)
    baselines = list(latest.values())
    if not baselines:
        return BaselineList(count=0, baselines=[], message="No baselines computed yet")
    return BaselineList(count=len(baselines), baselines=baselines)
