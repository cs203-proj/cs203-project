import statistics
from datetime import datetime, timedelta

from baseline import MAD_SCALE, compute_baseline
from tests.oracle import ORACLE_EXPECTED, ORACLE_READINGS

START = datetime(2024, 1, 1, 12, 0)


def daily(values, start=START, **extra):
    """One reading per consecutive day."""
    return [
        {"value": v, "observed_at": start + timedelta(days=i), **extra}
        for i, v in enumerate(values)
    ]


def test_empty_input_is_insufficient():
    assert compute_baseline([]) == {
        "status": "insufficient_data",
        "n_days": 0,
        "min_required": 30,
    }


def test_below_minimum_window_is_insufficient():
    result = compute_baseline(daily([50.0] * 20))
    assert result == {"status": "insufficient_data", "n_days": 20, "min_required": 30}


def test_at_minimum_window_is_ok():
    result = compute_baseline(daily([50.0 + i for i in range(30)]))
    assert result["status"] == "ok"
    assert result["n_days"] == 30


def test_oracle_match():
    # Canonical oracle (tests/oracle.py): 14 readings on 14 distinct dates with a gap and an outlier,
    # expected values derived independently via pandas / scipy.stats.median_abs_deviation(scale="normal").
    readings = [
        {
            "value": r["value"],
            "observed_at": datetime.fromisoformat(r["date"]),
            "is_malformed": r["is_malformed"],
        }
        for r in ORACLE_READINGS
    ]
    result = compute_baseline(readings, min_days=ORACLE_EXPECTED["n_valid_dates"])

    assert result["status"] == "ok"
    assert result["median"] == ORACLE_EXPECTED["median"]
    assert result["mad_raw"] == ORACLE_EXPECTED["mad_raw"]
    assert result["mad_scaled"] == ORACLE_EXPECTED["mad_scaled"]
    assert result["n_days"] == ORACLE_EXPECTED["n_valid_dates"]
    assert result["window_start"].date().isoformat() == ORACLE_EXPECTED["window_start"]
    assert result["window_end"].date().isoformat() == ORACLE_EXPECTED["window_end"]


def test_mad_scale_matches_oracle_constant():
    # oracle mad_raw is 1.0, so its mad_scaled *is* the scale constant scipy uses
    assert MAD_SCALE == ORACLE_EXPECTED["mad_scaled"] / ORACLE_EXPECTED["mad_raw"]


def test_constant_series_has_zero_mad():
    result = compute_baseline(daily([42.0] * 40))
    assert result["status"] == "ok"
    assert result["median"] == 42.0
    assert result["mad_raw"] == 0
    assert result["mad_scaled"] == 0


def test_outlier_robustness_median_vs_mean():
    normal = [50.0 + (i % 7) for i in range(39)]  # 50..56
    with_outlier = normal + [5000.0]

    base = compute_baseline(daily(normal))
    hit = compute_baseline(daily(with_outlier))

    median_shift = abs(hit["median"] - base["median"]) / base["median"]
    mean_shift = abs(statistics.mean(with_outlier) - statistics.mean(normal)) / statistics.mean(normal)

    assert median_shift < 0.05
    assert mean_shift > 0.05


def test_malformed_rows_excluded():
    good = daily([50.0 + (i % 5) for i in range(30)])
    bad = daily([9999.0] * 10, start=START + timedelta(days=30), is_malformed=True)

    result = compute_baseline(good + bad)
    clean = compute_baseline(good)

    assert result["status"] == "ok"
    assert result["n_days"] == 30  # malformed-only days do not count
    assert (result["median"], result["mad_raw"]) == (clean["median"], clean["mad_raw"])
    assert result["window_end"] == clean["window_end"]

    # 29 good days + malformed rows on the 30th must NOT reach the minimum
    short = compute_baseline(good[:29] + daily([9999.0], start=good[29]["observed_at"], is_malformed=True))
    assert short == {"status": "insufficient_data", "n_days": 29, "min_required": 30}


def test_null_values_excluded():
    good = daily([50.0 + (i % 5) for i in range(30)])
    nulls = daily([None] * 10, start=START + timedelta(days=30))

    result = compute_baseline(good + nulls)
    clean = compute_baseline(good)

    assert result["status"] == "ok"
    assert result["n_days"] == 30  # null-only days do not count
    assert (result["median"], result["mad_raw"]) == (clean["median"], clean["mad_raw"])

    short = compute_baseline(good[:29] + daily([None], start=good[29]["observed_at"]))
    assert short == {"status": "insufficient_data", "n_days": 29, "min_required": 30}


def test_day_is_distinct_dates_not_span_or_row_count():
    # 15 days, each with 3 readings, over a 60-day span -> n_days == 15
    rows = []
    for i in range(15):
        day = START + timedelta(days=i * 4)
        rows += [{"value": 50.0, "observed_at": day + timedelta(hours=h)} for h in range(3)]
    assert compute_baseline(rows) == {"status": "insufficient_data", "n_days": 15, "min_required": 30}
