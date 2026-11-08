/* Demo tools: POST /demo/replay and POST /backfill/pm25?from=&to= */

const REPLAY_OUTCOME_TEXT = {
  CREATED: "A new event was created.",
  ALREADY_OPEN: "A replay event is already open. Dismiss it on the Events page to replay again.",
  NO_ANOMALY: "No sustained breach found in the replay window.",
  NO_READINGS: "No readings in the replay window. Backfill that period first.",
  NO_BASELINE: "No baseline found. Recompute baselines first.",
};

if (requireLogin()) {
  renderNav();
  document.getElementById("replay").addEventListener("click", runReplay);
  document.getElementById("backfill-form").addEventListener("submit", runBackfill);
}

async function runReplay(event) {
  clearMessage();
  const box = document.getElementById("replay-result");
  await withButton(event.target, async () => {
    try {
      const r = await api("/demo/replay", { method: "POST" });
      box.innerHTML =
        "<h3>" + esc(label(r.outcome)) + "</h3>" +
        "<p>" + esc(REPLAY_OUTCOME_TEXT[r.outcome] || r.message) + "</p>" +
        '<dl class="facts small">' +
          "<dt>Readings scanned</dt><dd>" + esc(r.readingsScanned) + "</dd>" +
          (r.baselineMedian !== undefined ? "<dt>Baseline median</dt><dd>" + formatNumber(r.baselineMedian) + "</dd>" : "") +
          (r.deviation !== undefined ? "<dt>Deviation</dt><dd>" + formatNumber(r.deviation) + "</dd>" : "") +
          (r.detectedAt ? "<dt>Detected at</dt><dd>" + esc(formatTime(r.detectedAt)) + "</dd>" : "") +
        "</dl>" +
        (r.eventId ? '<p style="margin-top: 8px"><a class="button" href="events.html?id=' + encodeURIComponent(r.eventId) + '">Open the event →</a></p>' : "");
    } catch (error) {
      box.innerHTML = "";
      showMessage(error.message);
    }
  });
}

async function runBackfill(event) {
  event.preventDefault();
  clearMessage();
  const from = document.getElementById("from").value;
  const to = document.getElementById("to").value;
  const box = document.getElementById("backfill-result");
  box.innerHTML = '<p class="muted">Fetching… this can take a while for a full month.</p>';

  await withButton(event.target.querySelector("button"), async () => {
    try {
      const r = await api("/backfill/pm25?from=" + from + "&to=" + to, { method: "POST" });
      box.innerHTML =
        "<h3>Backfill finished</h3>" +
        '<dl class="facts small">' +
          "<dt>Days</dt><dd>" + esc(r.days) + "</dd>" +
          "<dt>New rows saved</dt><dd>" + esc(r.saved) + " (" + esc(r.malformed) + " flagged malformed)</dd>" +
          "<dt>Already stored</dt><dd>" + esc(r.duplicates) + "</dd>" +
          "<dt>Failed rows</dt><dd>" + esc(r.failed) + "</dd>" +
        "</dl>" +
        (r.failedDays && r.failedDays.length
          ? '<div class="message message-error small" style="margin-top: 8px">Rerun these days: ' + r.failedDays.map(esc).join(", ") + "</div>"
          : '<div class="message message-success small" style="margin-top: 8px">All days stored completely.</div>');
    } catch (error) {
      box.innerHTML = "";
      // 409 = the PM2.5 source is disabled on the Data sources page.
      showMessage(error.status === 409 ? error.message + " Enable it on the Data sources page." : error.message);
    }
  });
}
