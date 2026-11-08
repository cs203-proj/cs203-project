/*
 * Events page.
 *   List:    GET /events?status=...  (sorted by deviation by the backend)
 *   Detail:  GET /events/{id}/explanation, /events/{id}/warnings, /events/{id}/audit
 *   Action:  PATCH /events/{id}/status
 *
 * There is no GET /events/{id}, so the detail panel uses the event object from the list.
 * Opening events.html?id=... (from the review queue) selects that event once the list has loaded.
 */

let events = [];          // events currently in the table
let currentStatus = "";
let selectedId = new URLSearchParams(window.location.search).get("id");

/*
 * Which buttons to show for each status. This only hides buttons the backend would reject anyway;
 * the backend (EventStatus.allowedNext) is still what enforces the rule and returns 400 otherwise.
 */
const NEXT_STATUSES = {
  NEW: ["UNDER_REVIEW", "DISMISSED"],
  UNDER_REVIEW: ["CONFIRMED", "DISMISSED"],
  DISMISSED: ["UNDER_REVIEW"],
  CONFIRMED: [],
};
const ACTION_TEXT = {
  UNDER_REVIEW: "Start review",
  CONFIRMED: "Confirm (drafts a warning)",
  DISMISSED: "Dismiss",
};

if (requireLogin()) {
  renderNav();
  setUpControls();
  loadEvents();
}

function setUpControls() {
  document.querySelectorAll("#status-tabs button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("#status-tabs button").forEach((b) => b.classList.remove("active"));
      button.classList.add("active");
      currentStatus = button.dataset.status;
      loadEvents();
    });
  });
  document.getElementById("refresh").addEventListener("click", loadEvents);
}

async function loadEvents() {
  clearMessage();
  const rows = document.getElementById("event-rows");
  rows.innerHTML = '<tr><td colspan="6" class="empty">Loading…</td></tr>';

  try {
    const page = await api("/events?limit=200" + (currentStatus ? "&status=" + currentStatus : ""));
    events = page.items;
    document.getElementById("count").textContent =
      page.total + (page.total === 1 ? " event" : " events") + (page.total > page.items.length ? " (showing top 200)" : "");

    if (events.length === 0) {
      rows.innerHTML = '<tr><td colspan="6" class="empty">No events with this status.</td></tr>';
      return;
    }
    rows.innerHTML = events.map(eventRow).join("");
    rows.querySelectorAll("tr[data-id]").forEach((row) => {
      row.addEventListener("click", () => selectEvent(row.dataset.id));
    });

    if (selectedId) {
      if (events.some((e) => e.id === selectedId)) {
        selectEvent(selectedId);
      } else {
        document.getElementById("detail").hidden = true;
      }
    }
  } catch (error) {
    rows.innerHTML = "";
    showMessage(error.message);
  }
}

function eventRow(event) {
  const confidence = event.confidence
    ? badge(event.confidence.level) + ' <span class="muted small">' + formatNumber(event.confidence.score) + "</span>"
    : '<span class="muted">–</span>';
  return (
    '<tr class="clickable' + (event.id === selectedId ? " selected" : "") + '" data-id="' + esc(event.id) + '">' +
      "<td>" + esc(formatTime(event.timestamp)) + '<br><span class="muted small">' + esc(timeAgo(event.timestamp)) + "</span></td>" +
      "<td>" + esc(label(event.region)) + "</td>" +
      "<td>" + esc(event.signalType) + "</td>" +
      "<td>" + formatNumber(event.deviationSize, 1) + " σ</td>" +
      "<td>" + confidence + "</td>" +
      "<td>" + badge(event.status) + "</td>" +
    "</tr>"
  );
}

/* ---------- Detail panel ---------- */

async function selectEvent(id) {
  selectedId = id;
  history.replaceState(null, "", "events.html?id=" + encodeURIComponent(id)); // so the URL can be shared/refreshed
  document.querySelectorAll("tr[data-id]").forEach((row) => row.classList.toggle("selected", row.dataset.id === id));

  const event = events.find((e) => e.id === id);
  const detail = document.getElementById("detail");
  detail.hidden = false;
  detail.innerHTML =
    '<div class="card">' +
      '<div class="toolbar">' +
        "<h2 style=\"margin: 0\">" + esc(label(event.region)) + " · " + esc(event.signalType) + "</h2>" +
        badge(event.status) +
        '<span class="spacer"></span>' +
        statusButtons(event) +
      "</div>" +
      '<div class="grid-2">' +
        "<div>" + eventFacts(event) + "</div>" +
        "<div>" + confidenceBlock(event.confidence) + "</div>" +
      "</div>" +
    "</div>" +
    '<div class="card" id="explanation"><h2>AI explanation</h2><p class="muted">Loading…</p></div>' +
    '<div class="grid-2">' +
      '<div class="card" id="event-warnings"><h2>Drafted warnings</h2><p class="muted">Loading…</p></div>' +
      '<div class="card" id="event-audit"><h2>Status history</h2><p class="muted">Loading…</p></div>' +
    "</div>";

  detail.querySelectorAll("[data-next]").forEach((button) => {
    button.addEventListener("click", () => changeStatus(event, button.dataset.next, button));
  });
  detail.scrollIntoView({ behavior: "smooth", block: "start" });

  // Each section loads on its own, so one failing (e.g. no explanation yet) doesn't block the others.
  loadExplanation(id);
  loadEventWarnings(id);
  loadEventAudit(id);
}

function statusButtons(event) {
  return NEXT_STATUSES[event.status]
    .map((next) => {
      const style = next === "DISMISSED" ? "danger" : next === "CONFIRMED" ? "primary" : "";
      return '<button type="button" class="' + style + '" data-next="' + next + '">' + esc(ACTION_TEXT[next]) + "</button>";
    })
    .join(" ");
}

function eventFacts(event) {
  return (
    '<dl class="facts">' +
      "<dt>Detected</dt><dd>" + esc(formatTime(event.timestamp)) + "</dd>" +
      "<dt>Region</dt><dd>" + esc(label(event.region)) + "</dd>" +
      "<dt>Signal</dt><dd>" + esc(event.signalType) + "</dd>" +
      "<dt>Deviation</dt><dd>" + formatNumber(event.deviationSize, 2) + " (robust z-score vs baseline)</dd>" +
      '<dt>Event id</dt><dd class="mono">' + esc(event.id) + "</dd>" +
    "</dl>"
  );
}

/** Confidence is computed by the backend from measured inputs (never by the AI); this just shows them. */
function confidenceBlock(confidence) {
  if (!confidence) {
    return "<h3 style=\"margin-top: 0\">Confidence</h3><p class=\"muted\">Not scored (event created before confidence scoring existed).</p>";
  }
  const inputs = confidence.inputs || {};
  const agreement = inputs.sourceAgreement === null || inputs.sourceAgreement === undefined
    ? "no comparable region"
    : Math.round(inputs.sourceAgreement * 100) + "% of other regions also spiked";
  return (
    '<h3 style="margin-top: 0">Confidence ' + badge(confidence.level) + "</h3>" +
    '<div class="score-bar"><div style="width: ' + Math.round(confidence.score * 100) + '%"></div></div>' +
    '<p class="small muted">Score ' + formatNumber(confidence.score) + " / 1 · formula v" + esc(confidence.formulaVersion) + "</p>" +
    '<dl class="facts small">' +
      "<dt>Data staleness</dt><dd>" + Math.round(inputs.stalenessSeconds / 60) + " min since newest reading</dd>" +
      "<dt>Readings (24 h)</dt><dd>" + esc(inputs.readingCount) + "</dd>" +
      "<dt>Deviation size</dt><dd>" + formatNumber(inputs.deviationSize) + "</dd>" +
      "<dt>Source agreement</dt><dd>" + esc(agreement) + "</dd>" +
    "</dl>"
  );
}

async function loadExplanation(id) {
  const box = document.getElementById("explanation");
  try {
    const e = await api("/events/" + id + "/explanation");
    if (e.status === "FAILED") {
      box.innerHTML =
        "<h2>AI explanation " + badge("FAILED") + "</h2>" +
        "<p>The AI call failed, so there is no explanation. The event itself is unaffected.</p>" +
        '<p class="muted small">Reason: ' + esc(e.error) + "</p>" +
        dataQuality(e);
      return;
    }
    box.innerHTML =
      '<div class="toolbar" style="margin-bottom: 8px"><h2 style="margin: 0">AI explanation</h2>' +
        badge(e.assessment) + '<span class="spacer"></span><span class="muted small">' + esc(e.model) + "</span></div>" +
      dataQuality(e) +
      "<h3>Likely cause</h3><p>" + esc(e.likelyCause) + "</p>" +
      "<h3>Explanation</h3><p>" + esc(e.explanation) + "</p>" +
      '<div class="grid-2">' +
        "<div><h3>Key evidence</h3>" + list(e.evidence) + "</div>" +
        "<div><h3>Assumptions</h3>" + list(e.assumptions) + "</div>" +
      "</div>";
  } catch (error) {
    box.innerHTML = "<h2>AI explanation</h2><p class=\"muted\">" +
      (error.status === 404 ? "No explanation yet for this event." : esc(error.message)) + "</p>";
  }
}

/** The stale/partial-data note is computed by the backend, not the AI, so it is always shown. */
function dataQuality(e) {
  const type = e.dataComplete ? "info" : "error";
  return '<div class="message message-' + type + ' small">' + esc(e.dataQualityNote) + "</div>";
}

function list(items) {
  if (!items || items.length === 0) return '<p class="muted">None given.</p>';
  return '<ul class="plain">' + items.map((item) => "<li>" + esc(item) + "</li>").join("") + "</ul>";
}

async function loadEventWarnings(id) {
  const box = document.getElementById("event-warnings");
  try {
    const warnings = await api("/events/" + id + "/warnings");
    box.innerHTML = "<h2>Drafted warnings</h2>" + (warnings.length === 0
      ? '<p class="muted">None yet. A warning is drafted when the event is confirmed.</p>'
      : warnings.map((w) =>
          '<div style="margin-bottom: 12px">' + badge(w.severity) + " " + badge(w.status) +
          ' <span class="muted small">' + esc(label(w.audience)) + " · " + esc(formatTime(w.createdAt)) + "</span>" +
          '<div class="warning-text">' + esc(w.warningText) + "</div></div>").join("") +
        '<a href="queue.html">Go to review queue →</a>');
  } catch (error) {
    box.innerHTML = "<h2>Drafted warnings</h2><p class=\"muted\">" + esc(error.message) + "</p>";
  }
}

async function loadEventAudit(id) {
  const box = document.getElementById("event-audit");
  try {
    const entries = await api("/events/" + id + "/audit");
    box.innerHTML = "<h2>Status history</h2>" + (entries.length === 0
      ? '<p class="muted">No status changes yet.</p>'
      : '<ul class="audit-list">' + entries.map((a) =>
          "<li>" + badge(a.oldStatus) + " → " + badge(a.newStatus) + "<br>" +
          '<span class="muted small">by ' + esc(a.actorUsername || a.actorId) + " · " + esc(formatTime(a.changedAt)) + "</span></li>"
        ).join("") + "</ul>");
  } catch (error) {
    box.innerHTML = "<h2>Status history</h2><p class=\"muted\">" + esc(error.message) + "</p>";
  }
}

/* ---------- Status change ---------- */

async function changeStatus(event, newStatus, button) {
  if (newStatus === "DISMISSED" && !confirm("Dismiss this event? You can reopen it later.")) return;

  await withButton(button, async () => {
    try {
      await api("/events/" + event.id + "/status", { method: "PATCH", body: { status: newStatus } });
      // Switch to "All" so the event doesn't vanish from a filtered list after its status changes.
      currentStatus = "";
      document.querySelectorAll("#status-tabs button").forEach((b) => b.classList.toggle("active", b.dataset.status === ""));
      await loadEvents(); // reload so the table, badge, buttons, audit and warnings are all current
      showMessage("Event moved to " + label(newStatus) + ".", "success"); // after the reload, which clears messages
    } catch (error) {
      showMessage(error.message);
    }
  });
}
