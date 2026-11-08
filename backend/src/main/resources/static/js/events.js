/*
 * Events page: a list view (events.html) and a detail view (events.html?id=...).
 *   List:    GET /events?status=...  (sorted by deviation by the backend)
 *   Detail:  GET /events/{id}/explanation, /events/{id}/warnings, /events/{id}/audit
 *   Action:  PATCH /events/{id}/status
 *
 * There is no GET /events/{id}, so the detail view finds the event in GET /events (top 200).
 */

const EVENT_STATUSES = ["", "NEW", "UNDER_REVIEW", "CONFIRMED", "DISMISSED"]; // "" = all
let currentStatus = "";
let listedEvents = []; // events loaded for the current status tab; the region filter works on these

/*
 * Which buttons to show for each status. This only hides buttons the backend would reject anyway;
 * the backend (EventStatus.allowedNext) still enforces the rule and returns 400 otherwise.
 */
const NEXT_STATUSES = {
  NEW: ["UNDER_REVIEW", "DISMISSED"],
  UNDER_REVIEW: ["DISMISSED", "CONFIRMED"],
  DISMISSED: ["UNDER_REVIEW"],
  CONFIRMED: [],
};
const ACTIONS = {
  UNDER_REVIEW: { text: "Start review", style: "btn-secondary", icon: "eye" },
  CONFIRMED: { text: "Confirm", style: "btn-primary", icon: "check" },
  DISMISSED: { text: "Dismiss", style: "btn-danger", icon: "x" },
};

if (requireLogin()) {
  renderSidebar();
  const id = new URLSearchParams(window.location.search).get("id");
  if (id) {
    showDetail(id);
  } else {
    showList();
  }
}

/* ================= List view ================= */

function showList() {
  document.getElementById("list-view").hidden = false;
  document.querySelectorAll("#status-tabs button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("#status-tabs button").forEach((b) => b.classList.toggle("active", b === button));
      currentStatus = button.dataset.status;
      loadEvents();
    });
  });
  document.getElementById("region-filter").addEventListener("change", renderEventRows);
  loadTabCounts();
  loadEvents();
}

async function loadTabCounts() {
  const buttons = document.querySelectorAll("#status-tabs button");
  EVENT_STATUSES.forEach(async (status, i) => {
    try {
      const page = await api("/events?limit=1" + (status ? "&status=" + status : ""));
      buttons[i].querySelector("span").textContent = page.total;
    } catch (e) {
      buttons[i].querySelector("span").textContent = "";
    }
  });
}

async function loadEvents() {
  clearMessage();
  const rows = document.getElementById("event-rows");
  rows.innerHTML = '<tr><td colspan="7" class="empty">Loading…</td></tr>';
  try {
    const page = await api("/events?limit=200" + (currentStatus ? "&status=" + currentStatus : ""));
    listedEvents = page.items;
    fillRegionOptions();
    renderEventRows();
  } catch (error) {
    rows.innerHTML = "";
    showMessage(error.message);
  }
}

/** Region dropdown: the regions that appear in the loaded events (the API has no region filter, so this is done here). */
function fillRegionOptions() {
  const select = document.getElementById("region-filter");
  const chosen = select.value;
  const regions = [...new Set(listedEvents.map((e) => e.region))].sort();
  if (chosen && !regions.includes(chosen)) regions.push(chosen); // keep the current choice even if this tab has none
  select.innerHTML = '<option value="">All regions</option>' +
    regions.map((r) => '<option value="' + esc(r) + '"' + (r === chosen ? " selected" : "") + ">" + esc(label(r)) + "</option>").join("");
}

function renderEventRows() {
  const rows = document.getElementById("event-rows");
  const region = document.getElementById("region-filter").value;
  const shown = listedEvents.filter((e) => !region || e.region === region);
  if (shown.length === 0) {
    rows.innerHTML = '<tr><td colspan="7" class="empty">No events ' +
      (region ? "in " + esc(label(region)) + " " : "") + "with this status.</td></tr>";
    return;
  }
  rows.innerHTML = shown.map(eventRow).join("");
  rows.querySelectorAll("tr[data-id]").forEach((row) => {
    row.addEventListener("click", () => (window.location.href = "events.html?id=" + encodeURIComponent(row.dataset.id)));
  });
}

function eventRow(event) {
  const confidence = event.confidence
    ? '<div class="confidence-cell">' + scoreBar(event.confidence.score) + formatNumber(event.confidence.score) + "</div>"
    : '<span class="muted">–</span>';
  return (
    '<tr class="clickable" data-id="' + esc(event.id) + '">' +
      "<td><strong>" + esc(formatTime(event.timestamp)) + "</strong></td>" +
      "<td>" + esc(label(event.region)) + "</td>" +
      "<td>" + esc(signalName(event.signalType)) + "</td>" +
      "<td><strong>" + formatNumber(event.deviationSize, 1) + "σ</strong></td>" +
      "<td>" + confidence + "</td>" +
      "<td>" + badge(event.status) + "</td>" +
      '<td class="chev">' + icon("chevron", 16) + "</td>" +
    "</tr>"
  );
}

/* ================= Detail view ================= */

async function showDetail(id) {
  // The list view isn't used on a detail page; remove it so its #message box can't catch this page's messages.
  const listView = document.getElementById("list-view");
  if (listView) listView.remove();
  const view = document.getElementById("detail-view");
  view.hidden = false;
  view.innerHTML = detailHeader(null) + '<div class="page"><div id="message" class="message" hidden></div><div class="panel empty">Loading…</div></div>';

  let event;
  try {
    const page = await api("/events?limit=200");
    event = page.items.find((e) => e.id === id);
  } catch (error) {
    showMessage(error.message);
    return;
  }
  if (!event) {
    view.querySelector(".page").innerHTML =
      '<div class="note note-warn">' + icon("warning", 17) + "<div>This event isn't among the 200 largest events. " +
      '<a href="events.html">Back to the list</a>.</div></div>';
    return;
  }

  view.innerHTML =
    detailHeader(event) +
    '<div class="page">' +
      '<div id="message" class="message" hidden></div>' +
      summaryBar(event) +
      '<div class="detail-grid">' +
        "<div>" +
          confidencePanel(event.confidence) +
          '<section class="panel" id="explanation"><h2>AI explanation</h2><p class="muted">Loading…</p></section>' +
          '<section class="panel" id="event-warnings"><h2>Drafted warnings</h2><p class="muted">Loading…</p></section>' +
        "</div>" +
        "<div>" +
          '<section class="panel" id="event-audit"><h2>Status history</h2><p class="muted">Loading…</p></section>' +
        "</div>" +
      "</div>" +
    "</div>";

  view.querySelectorAll("[data-next]").forEach((button) => {
    button.addEventListener("click", () => changeStatus(event, button.dataset.next, button));
  });

  // Each section loads on its own, so one failing (e.g. no explanation yet) doesn't block the others.
  loadExplanation(id);
  loadEventWarnings(id);
  loadEventAudit(id);
}

function detailHeader(event) {
  const actions = event
    ? NEXT_STATUSES[event.status].map((next) => {
        const a = ACTIONS[next];
        return '<button type="button" class="btn ' + a.style + '" data-next="' + next + '">' + icon(a.icon, 16) + " " + a.text + "</button>";
      }).join("")
    : "";
  return (
    '<header class="topbar">' +
      '<a class="back" href="events.html" aria-label="Back to events">' + icon("back") + "</a>" +
      "<div>" +
        '<div class="eyebrow">Events' + (event ? " / " + esc(event.id.slice(0, 8)) : "") + "</div>" +
        "<h1>" + (event ? esc(signalName(event.signalType)) + " · " + esc(label(event.region)) : "Event") + "</h1>" +
      "</div>" +
      (event ? badge(event.status) : "") +
      '<div class="actions">' + actions + "</div>" +
    "</header>"
  );
}

function summaryBar(event) {
  const c = event.confidence;
  return (
    '<section class="detail-summary">' +
      "<div><span>Detected</span><strong>" + esc(formatTime(event.timestamp)) + "</strong></div>" +
      "<div><span>Region</span><strong>" + esc(label(event.region)) + "</strong></div>" +
      "<div><span>Deviation</span><strong>" + formatNumber(event.deviationSize, 1) + "σ above baseline</strong></div>" +
      '<div><span>Confidence</span><strong class="accent">' +
        (c ? formatNumber(c.score) + " · " + esc(label(c.level)) : "Not scored") + "</strong></div>" +
    "</section>"
  );
}

/** Confidence is computed by the backend from measured inputs (never by the AI); this only shows them. */
function confidencePanel(c) {
  if (!c) {
    return '<section class="panel"><h2>Confidence breakdown</h2><p class="muted" style="margin-top: 8px">' +
      "Not scored: this event was created before confidence scoring existed.</p></section>";
  }
  const inputs = c.inputs || {};
  const minutes = Math.round((inputs.stalenessSeconds || 0) / 60);
  const agreement = inputs.sourceAgreement === null || inputs.sourceAgreement === undefined
    ? { value: "–", note: "No comparable region" }
    : { value: Math.round(inputs.sourceAgreement * 100) + "%", note: "of other regions also spiked" };
  return (
    '<section class="panel">' +
      '<div class="panel-title"><div><h2>Confidence breakdown</h2><p>Computed from measured inputs, not by the AI (formula v' +
        esc(c.formulaVersion) + ")</p></div>" +
        '<div style="text-align: right"><div class="big-score">' + formatNumber(c.score) + "</div>" + confidenceBadge(c.level) + "</div></div>" +
      '<div class="factor-grid">' +
        factor("Data staleness", minutes + " min", "since the newest reading") +
        factor("Reading count", esc(inputs.readingCount), "valid readings in 24 h") +
        factor("Deviation", formatNumber(inputs.deviationSize, 1) + "σ", "robust z-score") +
        factor("Source agreement", agreement.value, agreement.note) +
      "</div>" +
    "</section>"
  );
}

function factor(name, value, note) {
  return '<div class="factor"><span>' + esc(name) + "</span><strong>" + value + "</strong><small>" + esc(note) + "</small></div>";
}

async function loadExplanation(id) {
  const box = document.getElementById("explanation");
  try {
    const e = await api("/events/" + id + "/explanation");
    if (e.status === "FAILED") {
      box.innerHTML =
        '<div class="panel-title"><div><div class="eyebrow">AI assessment</div><h2>AI explanation</h2></div>' + badge("FAILED") + "</div>" +
        '<p class="explanation-text">The AI call failed, so there is no explanation. The event itself is unaffected.</p>' +
        '<p class="small muted" style="margin: 6px 0 14px">Reason: ' + esc(e.error) + "</p>" +
        dataQualityNote(e);
      return;
    }
    box.innerHTML =
      '<div class="panel-title"><div><div class="eyebrow">AI assessment · ' + esc(e.model) + "</div><h2>Event explanation</h2></div>" +
        badge(e.assessment) + "</div>" +
      '<div class="cause"><span class="label-sm">Likely cause</span><strong>' + esc(e.likelyCause) + "</strong></div>" +
      '<p class="explanation-text">' + esc(e.explanation) + "</p>" +
      '<div class="evidence-cols">' +
        "<div><h3>Key evidence</h3>" + list(e.evidence, "check-list") + "</div>" +
        "<div><h3>Assumptions</h3>" + list(e.assumptions, "dot-list") + "</div>" +
      "</div>" +
      '<div style="margin-top: 16px">' + dataQualityNote(e) + "</div>";
  } catch (error) {
    box.innerHTML = '<h2>AI explanation</h2><p class="muted" style="margin-top: 8px">' +
      (error.status === 404 ? "No explanation yet for this event." : esc(error.message)) + "</p>";
  }
}

/** The stale/partial-data note is computed by the backend, not the AI, so it is always shown. */
function dataQualityNote(e) {
  return '<div class="note ' + (e.dataComplete ? "note-info" : "note-warn") + '">' + icon(e.dataComplete ? "info" : "warning", 17) +
    "<div><strong class=\"note-title\">Data quality</strong>" + esc(e.dataQualityNote) + "</div></div>";
}

function list(items, style) {
  if (!items || items.length === 0) return '<p class="muted small">None given.</p>';
  return '<ul class="' + style + '">' + items.map((item) => "<li>" + esc(item) + "</li>").join("") + "</ul>";
}

async function loadEventWarnings(id) {
  const box = document.getElementById("event-warnings");
  try {
    const warnings = await api("/events/" + id + "/warnings");
    const pending = warnings.filter((w) => w.status === "PENDING_REVIEW").length;
    box.innerHTML =
      '<div class="panel-title"><div><h2>Drafted warnings</h2><p>Drafted automatically when the event is confirmed.</p></div>' +
        (pending ? badge("PENDING_REVIEW").replace("Pending review", pending + " pending") : "") + "</div>" +
      (warnings.length === 0
        ? '<p class="muted">None yet. Confirm the event to draft a warning.</p>'
        : warnings.map((w) =>
            '<div class="mini-warning"><div class="badges">' + badge(w.severity) + badge(w.status) +
            '<span class="small muted">' + esc(label(w.audience)) + "</span></div>" +
            "<p>" + esc(w.warningText) + "</p></div>").join("") +
          '<a class="btn btn-secondary" href="queue.html">Go to Review warnings ' + icon("arrow", 16) + "</a>");
  } catch (error) {
    box.innerHTML = '<h2>Drafted warnings</h2><p class="muted" style="margin-top: 8px">' + esc(error.message) + "</p>";
  }
}

async function loadEventAudit(id) {
  const box = document.getElementById("event-audit");
  try {
    const entries = await api("/events/" + id + "/audit");
    box.innerHTML = "<h2>Status history</h2>" + (entries.length === 0
      ? '<p class="muted" style="margin-top: 8px">No status changes yet.</p>'
      : '<div class="timeline" style="margin-top: 16px">' + entries.slice().reverse().map((a) =>
          "<div><i></i><span>" + esc(formatTime(a.changedAt)) + "</span>" +
          "<strong>" + esc(label(a.oldStatus)) + " → " + esc(label(a.newStatus)) + "</strong>" +
          "<p>By " + esc(a.actorUsername || a.actorId) + "</p></div>").join("") + "</div>");
  } catch (error) {
    box.innerHTML = '<h2>Status history</h2><p class="muted" style="margin-top: 8px">' + esc(error.message) + "</p>";
  }
}

/* ================= Status change ================= */

async function changeStatus(event, newStatus, button) {
  if (newStatus === "DISMISSED" && !(await confirmDialog({
    title: "Dismiss this event?",
    body: "<p>It will be moved to Dismissed. You can reopen it later.</p>",
    confirmText: "Dismiss", danger: true,
  }))) return;
  if (newStatus === "CONFIRMED" && !(await confirmDialog({
    title: "Confirm this event?",
    body: "<p>A warning will be drafted from it and added to Review warnings. Confirmed events can't be reopened.</p>",
    confirmText: "Confirm event",
  }))) return;

  await withButton(button, async () => {
    try {
      await api("/events/" + event.id + "/status", { method: "PATCH", body: { status: newStatus } });
      await showDetail(event.id); // reload so badge, buttons, audit and warnings are all current
      showMessage("Event moved to " + label(newStatus) + ".", "success");
      renderSidebar(); // confirming drafts a warning, so the pending count may change
    } catch (error) {
      showMessage(error.message);
    }
  });
}
