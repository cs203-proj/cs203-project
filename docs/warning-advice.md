# Warning advice per audience (CG-21, CG-154)

When an event is confirmed, three warning drafts are created: one each for `GENERAL_PUBLIC`, `VULNERABLE` and
`HEALTHCARE_WORKER`. The "What you should do" line in each draft comes from the fixed tables below, defined in
`WarningDrafter.java`. The same event and audience always give the same advice. The AI never writes or changes it.

## Source

MOH health advisory for the general public, as published by NEA:
<https://www.nea.gov.sg/our-services/pollution-control/air-pollution/faqs> (FAQ Q8, the 24-hour PSI health advisory
table; Q16, Q17 and Q19 for seeing a doctor and N95 masks). Checked November 2026.

| 24-hr PSI | Healthy persons | Elderly, pregnant women, children | Chronic lung or heart disease |
| --- | --- | --- | --- |
| Good (0-50) | Normal activities | Normal activities | Normal activities |
| Moderate (51-100) | Normal activities | Normal activities | Normal activities |
| Unhealthy (101-200) | Reduce prolonged or strenuous outdoor physical exertion | Minimise prolonged or strenuous outdoor physical exertion | Avoid prolonged or strenuous outdoor physical exertion |
| Very Unhealthy (201-300) | Avoid prolonged or strenuous outdoor physical exertion | Minimise outdoor activity | Avoid outdoor activity |
| Hazardous (>300) | Minimise outdoor activity | Avoid outdoor activity | Avoid outdoor activity |

The same page also says to consult a doctor if you develop breathing difficulties (Q16) and to seek medical advice
if you feel unwell (Q17). It says elderly people, pregnant women and people with severe lung or heart problems should
ask their doctor before using an N95 mask (Q19, Q21).

## Mapping our severity to the advisory

Our severity is not a PSI value. It comes from how far readings are above the region's normal level (robust z-score),
so the mapping below is our own judgement:

| Severity | z-score | Advisory band used |
| --- | --- | --- |
| `LOW` | below 5 | Moderate |
| `MEDIUM` | 5 to below 8 | Unhealthy |
| `HIGH` | 8 to below 12 | Very Unhealthy |
| `CRITICAL` | 12 and above | Hazardous |

## Advice tables

### `GENERAL_PUBLIC` (CG-21, unchanged): "healthy persons" column

| Severity | What you should do |
| --- | --- |
| `LOW` | Check air quality updates before planning long outdoor activities today. |
| `MEDIUM` | Cut down on prolonged or strenuous outdoor activity, especially if you have heart or lung conditions. |
| `HIGH` | Avoid strenuous outdoor activity and keep windows closed while levels stay high. |
| `CRITICAL` | Stay indoors where possible, keep windows closed, and wear an N95 mask if you must go outside. |

### `VULNERABLE`: elderly, pregnant women, children, and chronic lung or heart disease columns

One draft covers both columns, so each line gives the general rule and the stricter one for chronic lung or heart
disease where they differ.

| Severity | What you should do |
| --- | --- |
| `LOW` | Normal activities are fine, but check air quality updates and see a doctor if you develop breathing difficulties. |
| `MEDIUM` | Minimise prolonged or strenuous outdoor activity, and avoid it if you have chronic lung or heart disease. See a doctor if you develop breathing difficulties. |
| `HIGH` | Minimise outdoor activity, and avoid it if you have chronic lung or heart disease. Seek medical advice if you feel unwell. |
| `CRITICAL` | Avoid outdoor activity. Seek medical advice if you feel unwell, and ask your doctor before using an N95 mask. |

### `HEALTHCARE_WORKER`: derived from the advisory

NEA does not publish advice for healthcare workers. These lines tell staff what to expect and what to tell at-risk
patients, using the vulnerable-group rules above.

| Severity | What you should do |
| --- | --- |
| `LOW` | Watch for patients with chronic lung or heart disease reporting breathing difficulties. |
| `MEDIUM` | Expect more respiratory and cardiac presentations, and advise elderly, pregnant and young patients and those with chronic lung or heart disease to minimise prolonged or strenuous outdoor activity. |
| `HIGH` | Expect more respiratory and cardiac presentations, and advise at-risk patients to minimise outdoor activity, or avoid it if they have chronic lung or heart disease. |
| `CRITICAL` | Prepare for a surge in respiratory and cardiac presentations, advise at-risk patients to avoid outdoor activity, and follow your facility's haze response plan. |

## Changing the advice

Change `WarningDrafter.java` and this file together, and keep the source citation current. Drafts already created
keep their original text: `warning_text` cannot be changed after it is written.
