/*
 * Review warnings.
 *   Warnings:     GET /warnings?status=...  (already sorted by severity, then age, by the backend)
 *   Per card:     region and confidence from GET /events, AI verdict from GET /events/{id}/explanation
 *   Audit trail:  GET /warnings/{id}/audit  (shown in a pop-up)
 */

const PAGE_SIZE = 20;
const STATUSES = ["PENDING_REVIEW", "APPROVED", "SUPPRESSED", ""]; // "" = all
let currentStatus = "PENDING_REVIEW";
let offset = 0;

if (requireLogin()) {
  renderSidebar();
  setUpControls();
  loadQueue();
}

function setUpControls() {
  document.querySelectorAll("#status-tabs button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("#status-tabs button").forEach((b) => b.classList.toggle("active", b === button));
      currentStatus = button.dataset.status;
      offset = 0;
      loadQueue();
    });
  });
  document.getElementById("prev").addEventListener("click", () => { offset = Math.max(0, offset - PAGE_SIZE); loadQueue(); });
  document.getElementById("next").addEventListener("click", () => { offset += PAGE_SIZE; loadQueue(); });

  // Pop-up: close with the X, a click outside it, or the Escape key.
  const modal = document.getElementById("audit-modal");
  document.getElementById("audit-close").innerHTML = icon("x");
  document.getElementById("audit-close").addEventListener("click", () => (modal.hidden = true));
  modal.addEventListener("mousedown", (e) => { if (e.target === modal) modal.hidden = true; });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") modal.hidden = true; });
}

async function loadQueue() {
  clearMessage();
  const container = document.getElementById("warnings");
  container.innerHTML = '<div class="panel empty">Loading…</div>';
  loadTabCounts();

  try {
    const query = "?limit=" + PAGE_SIZE + "&offset=" + offset + (currentStatus ? "&status=" + currentStatus : "");
    const [page, events] = await Promise.all([api("/warnings" + query), loadEventsById()]);
    const verdicts = await loadVerdicts(page.items.map((w) => w.eventId));
    renderPager(page);

    if (page.items.length === 0) {
      container.innerHTML = '<div class="panel empty">No warnings here.</div>';
      return;
    }
    container.innerHTML = page.items.map((w) => warningCard(w, events[w.eventId], verdicts[w.eventId])).join("");
    container.querySelectorAll("[data-audit]").forEach((b) => b.addEventListener("click", () => openAudit(b.dataset.audit)));
  } catch (error) {
    container.innerHTML = "";
    showMessage(error.message);
  }
}

/** The small number on each tab: one tiny request per status (limit=1, we only need "total"). */
async function loadTabCounts() {
  const buttons = document.querySelectorAll("#status-tabs button");
  STATUSES.forEach(async (status, i) => {
    try {
      const page = await api("/warnings?limit=1" + (status ? "&status=" + status : ""));
      buttons[i].querySelector("span").textContent = page.total;
    } catch (e) {
      buttons[i].querySelector("span").textContent = "";
    }
  });
}

/** GET /events (top 200) as { eventId: event }, for each card's region and confidence. */
async function loadEventsById() {
  const page = await api("/events?limit=200");
  const byId = {};
  page.items.forEach((event) => (byId[event.id] = event));
  return byId;
}

/** AI explanation per event, as { eventId: explanation or null }. A missing explanation is not an error here. */
async function loadVerdicts(eventIds) {
  const unique = [...new Set(eventIds)];
  const results = await Promise.all(unique.map((id) => api("/events/" + id + "/explanation").catch(() => null)));
  const byId = {};
  unique.forEach((id, i) => (byId[id] = results[i]));
  return byId;
}

function warningCard(warning, event, explanation) {
  const confidence = event && event.confidence
    ? "<strong>" + esc(label(event.confidence.level)) + ' <span class="score-pill">' + formatNumber(event.confidence.score) +
      "</span></strong>"
    : '<strong class="muted">Not scored</strong>';
  let verdict = '<span class="muted">No explanation yet</span>';
  if (explanation && explanation.status === "OK") verdict = "<strong>" + esc(label(explanation.assessment)) + "</strong>";
  if (explanation && explanation.status === "FAILED") verdict = '<span class="muted">AI call failed</span>';

  return (
    '<article class="warning-card">' +
      '<div class="severity-line severity-' + esc(String(warning.severity).toLowerCase()) + '"></div>' +
      '<div class="warning-head">' +
        '<div class="badges">' + badge(warning.severity) + badge(warning.status) + "</div>" +
        '<span class="wait" title="Drafted ' + esc(formatTime(warning.createdAt)) + '">' + icon("clock", 15) +
          " Waiting " + esc(duration(warning.createdAt)) + "</span>" +
      "</div>" +
      '<div class="warning-meta">' +
        '<div><span class="label-sm">Region</span><strong>' + esc(event ? label(event.region) : "Unknown") + "</strong></div>" +
        '<div><span class="label-sm">Confidence</span>' + confidence + "</div>" +
        (event && event.confidence ? "<div>" + scoreBar(event.confidence.score) + "</div>" : "") +
        '<div><span class="label-sm">AI assessment</span>' + verdict + "</div>" +
        '<div><span class="label-sm">Audience</span><strong>' + esc(label(warning.audience)) + "</strong></div>" +
      "</div>" +
      '<p class="warning-text">' + esc(warning.warningText) + "</p>" +
      '<div class="card-actions">' +
        '<a class="btn btn-secondary" href="events.html?id=' + encodeURIComponent(warning.eventId) + '">' +
          icon("eye", 17) + " View event</a>" +
        '<button type="button" class="btn btn-ghost" data-audit="' + esc(warning.id) + '">' +
          icon("history", 17) + " Show history</button>" +
      "</div>" +
    "</article>"
  );
}

async function openAudit(warningId) {
  const modal = document.getElementById("audit-modal");
  const body = document.getElementById("audit-body");
  document.getElementById("audit-eyebrow").textContent = "Warning " + warningId.slice(0, 8);
  body.innerHTML = '<p class="muted">Loading…</p>';
  modal.hidden = false;
  try {
    const entries = await api("/warnings/" + warningId + "/audit");
    body.innerHTML = entries.length === 0
      ? '<p class="muted">No entries yet.</p>'
      : '<div class="timeline">' + entries.slice().reverse().map((entry) =>
          "<div><i></i><span>" + esc(formatTime(entry.createdAt)) + "</span>" +
          "<strong>" + esc(label(entry.action)) + "</strong>" +
          "<p>By " + esc(actorName(entry)) +
            (entry.after && entry.after.status ? ", " + esc(label(entry.after.status)) : "") + "</p></div>"
        ).join("") + "</div>";
  } catch (error) {
    body.innerHTML = '<div class="note note-error">' + esc(error.message) + "</div>";
  }
}

/**
 * Who did it. Drafts are made by the system. For admin actions the warning audit log stores the admin's user id;
 * if the backend also sends actorUsername (like the event audit does), that name is shown instead.
 */
function actorName(entry) {
  if (entry.actorUsername) return entry.actorUsername;
  if (entry.actor === "system") return "System (automatic draft)";
  return "Admin " + String(entry.actor).slice(0, 8);
}

function renderPager(page) {
  document.getElementById("pager").hidden = page.total <= page.limit;
  document.getElementById("prev").disabled = page.offset === 0;
  document.getElementById("next").disabled = page.offset + page.items.length >= page.total;
}
