"""Hand-calculated test oracle for baseline median/MAD computation (CG-62).

Ground truth values in ORACLE_EXPECTED were computed from ORACLE_READINGS using:
    pandas.Series(values).median()
    scipy.stats.median_abs_deviation(values, scale="normal")
"""

ORACLE_READINGS = [
    {"date": "2024-01-01", "value": 45, "is_malformed": False},
    {"date": "2024-01-02", "value": 46, "is_malformed": False},
    {"date": "2024-01-03", "value": 44, "is_malformed": False},
    {"date": "2024-01-04", "value": 47, "is_malformed": False},
    {"date": "2024-01-05", "value": 45, "is_malformed": False},
    {"date": "2024-01-06", "value": 48, "is_malformed": False},
    {"date": "2024-01-07", "value": 46, "is_malformed": False},
    # 2024-01-08 intentionally skipped to simulate a gap in the data
    {"date": "2024-01-09", "value": 45, "is_malformed": False},
    {"date": "2024-01-10", "value": 120, "is_malformed": False},  # outlier
    {"date": "2024-01-11", "value": 47, "is_malformed": False},
    {"date": "2024-01-12", "value": 44, "is_malformed": False},
    {"date": "2024-01-13", "value": 46, "is_malformed": False},
    {"date": "2024-01-14", "value": 45, "is_malformed": False},
    {"date": "2024-01-15", "value": 48, "is_malformed": False},
]

ORACLE_EXPECTED = {
    "median": 46.0,
    "mad_raw": 1.0,
    "mad_scaled": 1.482602218505602,
    "n_valid_dates": 14,
    "window_start": "2024-01-01",
    "window_end": "2024-01-15",
}
