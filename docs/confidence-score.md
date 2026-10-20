# Confidence score (CG-16)

Every event gets a confidence score so an admin knows how much to trust it before acting. The score is
**computed in code** by `ConfidenceScorer` from measured inputs. It is never written, estimated or self-reported by
the AI. The same inputs always give the same score.

Shown to the admin as a band (**Low / Medium / High**) next to the raw value (0 to 1). Returned by `GET /events` and
`PATCH /events/{id}/status` as `confidence`.

## Inputs

| Input | Meaning | Stored in `events` |
| --- | --- | --- |
| Data staleness | Seconds from the newest reading behind the event to the moment of scoring (now for live events; the detection time for demo replays, so 0) | `confidence_staleness_seconds` |
| Reading count in window | Valid (non-malformed, finite) readings for the region and signal in the 24 hours up to the event | `confidence_reading_count` |
| Deviation size | The event's robust z-score, `(value - median) / scaledMAD` | `deviation` (existing column) |
| Source agreement | Share of sources that agree with the flag, 0 to 1. `NULL` while there is a single source. Fed in once CG-136 (second source) lands | `confidence_source_agreement` |

Also stored: `confidence_score`, `confidence_level` and `confidence_formula_version`.

Historical accuracy of similar past alerts is **out of scope** until alert outcomes exist (later sprint).

## Formula (version 1)

Each input is turned into a 0..1 sub-score, then combined as a weighted average.

| Sub-score | Calculation | Weight |
| --- | --- | --- |
| Deviation | `min(1, deviation / 10)` | 0.35 |
| Reading count | `min(1, count / 24)` (24 = a full day of hourly readings) | 0.25 |
| Freshness | `1 - min(1, staleness / 3h)` | 0.25 |
| Source agreement | the agreement value, clamped to 0..1 | 0.15 |

```
score = sum(weight x subscore) / sum(weights of the inputs that are available)
```

If source agreement is `NULL` it is left out and the other three weights are rescaled (divided by 0.85), so a
single-source event is neither rewarded nor penalised for the missing second source. The result is rounded to 4
decimal places.

**Bands:** `score < 0.40` is **LOW**, `0.40 <= score < 0.70` is **MEDIUM**, `score >= 0.70` is **HIGH**.

**Worked example:** staleness 5400 s (1.5 h), 20 readings, deviation 4.2, one source.
Deviation 0.42, count 0.8333, freshness 0.5.
`(0.35x0.42 + 0.25x0.8333 + 0.25x0.5) / 0.85 = 0.4803 / 0.85 = 0.5651` -> **MEDIUM**.

**Bad inputs never produce NaN or an out-of-range score.** NaN counts as 0, values are clamped to 0..1, and a negative
staleness (clock skew) counts as no staleness.

### Changing the formula

Weights, windows and bands are constants in `ConfidenceScorer`, not config, so a stored score can always be explained.
Changing any of them means bumping `FORMULA_VERSION` and updating this page. Old events keep the version they were
scored with (`confidence_formula_version`).

## MAD zero-guard (CG-137)

Detection divides by the baseline's scaled MAD, so a flat history (MAD = 0) would give an infinite or NaN deviation and
break this score. `BaselineSnapshot.hasUsableSpread()` is false when the median or scaled MAD is NaN or infinite, or the
MAD is 0 or negative. In that case `DeviationDetector.detect()` returns no event and never divides, so every deviation
that reaches the confidence score is finite. Details, the known trade-off and the tests are in the "Zero-spread guard
(CG-137)" section of the README.
