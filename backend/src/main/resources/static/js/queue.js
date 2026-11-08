/*
 * Review queue: GET /warnings?status=... (already sorted by severity, then age, by the backend).
 * Each warning card also shows its event's region and confidence, looked up from GET /events.
 */

const PAGE_SIZE = 20;
let currentStatus = "PENDING_REVIEW";
let offset = 0;

if (requireLogin()) {
  renderNav();
  setUpControls();
  loadQueue();
}

function setUpControls() {
  document.querySelectorAll("#status-tabs button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("#status-tabs button").forEach((b) => b.classList.remove("active"));
      button.classList.add("active");
      currentStatus = button.dataset.status;
      offset = 0;
      loadQueue();
    });
  });
  document.getElementById("refresh").addEventListener("click", loadQueue);
  document.getElementById("prev").addEventListener("click", () => {
    offset = Math.max(0, offset - PAGE_SIZE);
    loadQueue();
  });
  document.getElementById("next").addEventListener("click", () => {
    offset += PAGE_SIZE;
    loadQueue();
  });
}

async function loadQueue() {
  clearMessage();
  const container = document.getElementById("warnings");
  container.innerHTML = '<div class="card empty">Loading…</div>';

  try {
    const query = "?limit=" + PAGE_SIZE + "&offset=" + offset + (currentStatus ? "&status=" + currentStatus : "");
    // Both calls run at the same time. Events give each card its region and confidence.
    const [page, events] = await Promise.all([api("/warnings" + query), loadEventsById()]);

    document.getElementById("count").textContent = page.total + (page.total === 1 ? " warning" : " warnings");
    renderPager(page);

    if (page.items.length === 0) {
      container.innerHTML = '<div class="card empty">No warnings here. 🎉</div>';
      return;
    }
    container.innerHTML = page.items.map((warning) => warningCard(warning, events[warning.eventId])).join("");
    container.querySelectorAll("[data-audit]").forEach((button) => {
      button.addEventListener("click", () => toggleAudit(button));
    });
  } catch (error) {
    container.innerHTML = "";
    showMessage(error.message);
  }
}

/** GET /events (largest 200) turned into { eventId: event } for quick lookup. */
async function loadEventsById() {
  const page = await api("/events?limit=200");
  const byId = {};
  page.items.forEach((event) => (byId[event.id] = event));
  return byId;
}

function warningCard(warning, event) {
  const confidence = event && event.confidence
    ? badge(event.confidence.level) + " confidence (" + formatNumber(event.confidence.score) + ")"
    : '<span class="muted">confidence n/a</span>';
  const region = event ? esc(label(event.region)) : "unknown region";

  return (
    '<div class="card">' +
      '<div class="toolbar" style="margin-bottom: 4px">' +
        badge(warning.severity) + " " + badge(warning.status) +
        "<strong>" + region + "</strong>" +
        "<span>" + confidence + "</span>" +
        '<span class="spacer"></span>' +
        '<span class="muted small" title="' + esc(formatTime(warning.createdAt)) + '">waiting ' +
          esc(timeAgo(warning.createdAt).replace(" ago", "")) + "</span>" +
      "</div>" +
      '<div class="warning-text">' + esc(warning.warningText) + "</div>" +
      '<div class="meta">' +
        "<span>Audience: " + esc(label(warning.audience)) + "</span>" +
        "<span>Drafted " + esc(formatTime(warning.createdAt)) + "</span>" +
      "</div>" +
      '<div class="toolbar" style="margin: 12px 0 0">' +
        '<a class="button" href="events.html?id=' + encodeURIComponent(warning.eventId) + '">View event and AI explanation</a>' +
        '<button type="button" data-audit="' + esc(warning.id) + '">Show audit trail</button>' +
      "</div>" +
      '<div class="audit" hidden></div>' +
    "</div>"
  );
}

/** Loads GET /warnings/{id}/audit the first time, then just shows/hides it. */
async function toggleAudit(button) {
  const box = button.closest(".card").querySelector(".audit");
  if (!box.hidden) {
    box.hidden = true;
    button.textContent = "Show audit trail";
    return;
  }
  if (!box.dataset.loaded) {
    try {
      const entries = await api("/warnings/" + button.dataset.audit + "/audit");
      box.innerHTML = "<h3>Audit trail</h3>" + warningAuditList(entries);
      box.dataset.loaded = "yes";
    } catch (error) {
      showMessage(error.message);
      return;
    }
  }
  box.hidden = false;
  button.textContent = "Hide audit trail";
}

function warningAuditList(entries) {
  if (entries.length === 0) return '<p class="muted">No entries yet.</p>';
  return (
    '<ul class="audit-list">' +
    entries
      .map(
        (entry) =>
          "<li><strong>" + esc(label(entry.action)) + "</strong> by " + esc(entry.actor) +
          ' <span class="muted">· ' + esc(formatTime(entry.createdAt)) + "</span>" +
          (entry.after && entry.after.status ? " → " + badge(entry.after.status) : "") +
          "</li>"
      )
      .join("") +
    "</ul>"
  );
}

function renderPager(page) {
  document.getElementById("pager").hidden = page.total <= page.limit;
  document.getElementById("prev").disabled = page.offset === 0;
  document.getElementById("next").disabled = page.offset + page.items.length >= page.total;
}
