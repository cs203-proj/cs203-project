/*
 * Review warnings.
 *   Warnings:     GET /warnings?status=...  (already sorted by severity, then age, by the backend)
 *   Per card:     region and confidence from GET /events, AI verdict from GET /events/{id}/explanation
 *   Audit trail:  GET /warnings/{id}/audit  (shown in a pop-up)
 *   Review (CG-28): POST /warnings/{id}/approve | edit | downgrade | suppress  (PENDING_REVIEW only)
 */

const PAGE_SIZE = 20;
const STATUSES = ["PENDING_REVIEW", "APPROVED", "SUPPRESSED", ""]; // "" = all
let currentStatus = "PENDING_REVIEW";
let offset = 0;

/**
 * Least to most severe, same order as the backend's WarningSeverity enum. Only used to offer the lower severities
 * in the downgrade form; the backend still rejects anything that is not strictly lower.
 */
const SEVERITY_ORDER = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];

/**
 * The three review actions that need a form. Which fields show matches the request bodies
 * (EditWarningRequest, DowngradeWarningRequest, SuppressWarningRequest); the backend validates them again.
 */
const REVIEW_FORMS = {
  edit: {
    title: "Edit wording",
    intro: "End users will see your wording instead of the original draft. The warning stays pending until it is approved.",
    submit: "Save edit", done: "Wording saved. The warning still needs approval.",
    text: true, severity: false, reason: false, danger: false,
  },
  downgrade: {
    title: "Lower severity",
    intro: "Pick a lower severity and restate the advice to match it. The warning stays pending until it is approved.",
    submit: "Downgrade", done: "Severity lowered. The warning still needs approval.",
    text: true, severity: true, reason: true, danger: false,
  },
  suppress: {
    title: "Suppress warning",
    intro: "This warning will never be sent. This is final: it can't be approved afterwards.",
    submit: "Suppress", done: "Warning suppressed.",
    text: false, severity: false, reason: true, danger: true,
  },
};

let warningsOnPage = {}; // { warningId: warning } for the cards currently shown, so buttons can find their warning
let eventsById = {};     // { eventId: event } from the last load, for the region in pop-up headers
let reviewing = null;    // { mode, warning } while the review pop-up is open

if (requireAdmin()) {
  renderSidebar();
  setUpControls();
  setUpRefresh(document.getElementById("refresh"), async () => {
    updatePendingCount(); // sidebar badge
    await loadQueue();    // list and tab counts
  });
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

  // One listener for every card button (cards are re-rendered on each load).
  document.getElementById("warnings").addEventListener("click", onCardClick);
  setUpReviewModal();
}

function onCardClick(e) {
  const button = e.target.closest("button[data-action]");
  if (!button) return;
  const warning = warningsOnPage[button.dataset.id];
  if (!warning) return;

  if (button.dataset.action === "audit") openAudit(warning.id);
  else if (button.dataset.action === "approve") approveWarning(button, warning);
  else openReview(button.dataset.action, warning);
}

async function loadQueue() {
  clearMessage();
  const container = document.getElementById("warnings");
  container.innerHTML = '<div class="panel empty">Loading\u2026</div>';
  loadTabCounts();

  try {
    const query = "?limit=" + PAGE_SIZE + "&offset=" + offset + (currentStatus ? "&status=" + currentStatus : "");
    const [page, events] = await Promise.all([api("/warnings" + query), loadEventsById()]);
    eventsById = events;
    const verdicts = await loadVerdicts(page.items.map((w) => w.eventId));
    renderPager(page);

    if (page.items.length === 0) {
      container.innerHTML = '<div class="panel empty">No warnings here.</div>';
      return;
    }
    warningsOnPage = {};
    page.items.forEach((w) => (warningsOnPage[w.id] = w));
    container.innerHTML = page.items.map((w) => warningCard(w, events[w.eventId], verdicts[w.eventId])).join("");
  } catch (error) {
    container.innerHTML = "";
    showMessage(error.message);
  }
}

/** The small number on each tab: one tiny request per status (only the "total" is needed). */
async function loadTabCounts() {
  const buttons = document.querySelectorAll("#status-tabs button");
  STATUSES.forEach(async (status, i) => {
    try {
      const page = await api("/warnings?limit=" + COUNT_ONLY + (status ? "&status=" + status : ""));
      buttons[i].querySelector("span").textContent = page.total;
    } catch (e) {
      buttons[i].querySelector("span").textContent = "";
    }
  });
}

/** GET /events (up to MAX_PAGE_SIZE) as { eventId: event }, for each card's region and confidence. */
async function loadEventsById() {
  const page = await api("/events?limit=" + MAX_PAGE_SIZE);
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
        waitLabel(warning) +
      "</div>" +
      '<div class="warning-meta">' +
        '<div><span class="label-sm">Region</span><strong>' + esc(event ? label(event.region) : "Unknown") + "</strong></div>" +
        '<div><span class="label-sm">Confidence</span>' + confidence + "</div>" +
        (event && event.confidence ? "<div>" + scoreBar(event.confidence.score) + "</div>" : "") +
        '<div><span class="label-sm">AI assessment</span>' + verdict + "</div>" +
        '<div><span class="label-sm">Audience</span><strong>' + esc(label(warning.audience)) + "</strong></div>" +
      "</div>" +
      warningTextBlock(warning) +
      '<div class="card-actions">' +
        reviewButtons(warning) +
        '<span class="actions-spacer"></span>' +
        '<a class="btn btn-secondary" href="events.html?id=' + encodeURIComponent(warning.eventId) + '">' +
          icon("eye", 17) + " View event</a>" +
        '<button type="button" class="btn btn-ghost" data-action="audit" data-id="' + esc(warning.id) + '">' +
          icon("history", 17) + " Show history</button>" +
      "</div>" +
    "</article>"
  );
}

/**
 * Pending: how long it has waited for review. Once approved or suppressed it is no longer waiting, so show when it
 * was drafted instead (the API has no decision time for suppressed warnings, and createdAt is all we have).
 */
function waitLabel(warning) {
  const drafted = formatTime(warning.createdAt);
  const text = warning.status === "PENDING_REVIEW"
    ? "Waiting " + duration(warning.createdAt)
    : "Drafted " + drafted;
  return '<span class="wait" title="Drafted ' + esc(drafted) + '">' + icon("clock", 15) + " " + esc(text) + "</span>";
}

/**
 * The wording end users get. If an admin edited it, the original system draft stays one click away (CG-28 / CG-37). The draft is written by code
 * from a fixed template and advice table (WarningDrafter), not by the AI.
 * The AI "why this matters" line (CG-154) is shown underneath, labelled, since end users see it too.
 */
function warningTextBlock(warning) {
  return mainText(warning) + aiLineBlock(warning.aiLine);
}

function aiLineBlock(line) {
  if (!line) return "";
  return (
    '<div class="ai-line">' + icon("sparkle", 16) +
      '<div><span class="label-sm">AI-generated line, shown to users with this warning</span><p>' + esc(line) + "</p></div>" +
    "</div>"
  );
}

function mainText(warning) {
  if (!warning.editedText) return '<p class="warning-text">' + esc(warning.warningText) + "</p>";
  return (
    '<span class="label-sm">Edited by an admin</span>' +
    '<p class="warning-text">' + esc(warning.editedText) + "</p>" +
    '<details class="ai-original"><summary>Show original draft</summary>' +
      '<p class="warning-text">' + esc(warning.warningText) + "</p></details>"
  );
}

/**
 * Approve / Edit / Downgrade / Suppress, only on pending warnings (the backend rejects the rest anyway).
 * Downgrade is hidden on LOW, since there is nothing lower.
 */
function reviewButtons(warning) {
  if (warning.status !== "PENDING_REVIEW") return "";
  const id = esc(warning.id);
  const canDowngrade = SEVERITY_ORDER.indexOf(warning.severity) > 0;
  return (
    '<button type="button" class="btn btn-primary" data-action="approve" data-id="' + id + '">' + icon("check", 17) + " Approve</button>" +
    '<button type="button" class="btn btn-secondary" data-action="edit" data-id="' + id + '">' + icon("edit", 17) + " Edit</button>" +
    (canDowngrade
      ? '<button type="button" class="btn btn-secondary" data-action="downgrade" data-id="' + id + '">Downgrade</button>'
      : "") +
    '<button type="button" class="btn btn-danger" data-action="suppress" data-id="' + id + '">' + icon("x", 17) + " Suppress</button>"
  );
}

/* ---------- Review actions (CG-28) ---------- */

async function approveWarning(button, warning) {
  const confirmed = await confirmDialog({
    title: "Approve this warning?",
    body: "<p>It becomes active for users in the matching region and audience. An approved warning can't be edited " +
      "or suppressed afterwards.</p>" +
      '<p class="warning-text">' + esc(warning.editedText || warning.warningText) + "</p>",
    confirmText: "Approve",
  });
  if (!confirmed) return;

  await withButton(button, async () => {
    try {
      await api("/warnings/" + warning.id + "/approve", { method: "POST" });
      showMessage("Warning approved.", "success");
      afterReview();
    } catch (error) {
      showMessage(error.message);
    }
  });
}

function setUpReviewModal() {
  const modal = document.getElementById("review-modal");
  document.getElementById("review-close").innerHTML = icon("x");
  document.getElementById("review-close").addEventListener("click", closeReview);
  document.getElementById("review-cancel").addEventListener("click", closeReview);
  modal.addEventListener("mousedown", (e) => { if (e.target === modal) closeReview(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !modal.hidden) closeReview(); });
  document.getElementById("review-form").addEventListener("submit", submitReview);
}

function openReview(mode, warning) {
  const form = REVIEW_FORMS[mode];
  reviewing = { mode: mode, warning: warning };

  document.getElementById("review-eyebrow").textContent =
    warningTitle(warning);
  document.getElementById("review-title").textContent = form.title;
  document.getElementById("review-intro").textContent = form.intro;
  document.getElementById("review-original").textContent = warning.warningText;

  document.getElementById("severity-field").hidden = !form.severity;
  document.getElementById("text-field").hidden = !form.text;
  document.getElementById("reason-field").hidden = !form.reason;

  // Only severities below the current one are offered.
  const lower = SEVERITY_ORDER.slice(0, SEVERITY_ORDER.indexOf(warning.severity)).reverse();
  document.getElementById("review-severity").innerHTML =
    lower.map((s) => '<option value="' + s + '">' + esc(label(s)) + "</option>").join("");

  document.getElementById("review-text").value = warning.editedText || warning.warningText;
  document.getElementById("review-reason").value = "";
  document.getElementById("review-error").hidden = true;

  const submit = document.getElementById("review-submit");
  submit.textContent = form.submit;
  submit.className = "btn " + (form.danger ? "btn-danger-solid" : "btn-primary");

  document.getElementById("review-modal").hidden = false;
  (form.text ? document.getElementById("review-text") : document.getElementById("review-reason")).focus();
}

function closeReview() {
  document.getElementById("review-modal").hidden = true;
  reviewing = null;
}

async function submitReview(event) {
  event.preventDefault();
  if (!reviewing) return;
  const { mode, warning } = reviewing;
  const form = REVIEW_FORMS[mode];
  const errorBox = document.getElementById("review-error");

  const body = {};
  if (form.severity) body.severity = document.getElementById("review-severity").value;
  if (form.text) body.warningText = document.getElementById("review-text").value.trim();
  if (form.reason) body.reason = document.getElementById("review-reason").value.trim();

  // Quick checks so the admin isn't sent a round trip for an empty box; the backend checks the same things.
  if (form.text && !body.warningText) return showReviewError(errorBox, "Write the wording end users will see.");
  if (form.reason && !body.reason) return showReviewError(errorBox, "A reason is required.");
  if (form.text && body.warningText === (warning.editedText || warning.warningText)) {
    return showReviewError(errorBox, mode === "downgrade"
      ? "Restate the wording for the new severity (the current text still describes the old one)."
      : "The wording hasn't changed.");
  }

  await withButton(document.getElementById("review-submit"), async () => {
    try {
      await api("/warnings/" + warning.id + "/" + mode, { method: "POST", body: body });
      closeReview();
      showMessage(form.done, "success");
      afterReview();
    } catch (error) {
      showReviewError(errorBox, error.message);
    }
  });
}

function showReviewError(box, text) {
  box.innerHTML = icon("warning", 16) + "<span>" + esc(text) + "</span>";
  box.hidden = false;
}

/** Reload the list, the tab counts and the sidebar badge after any review action. */
function afterReview() {
  loadQueue();
  updatePendingCount();
}

async function openAudit(warningId) {
  const modal = document.getElementById("audit-modal");
  const body = document.getElementById("audit-body");
  document.getElementById("audit-eyebrow").textContent = warningTitle(warningsOnPage[warningId]);
  body.innerHTML = '<p class="muted">Loading\u2026</p>';
  modal.hidden = false;
  try {
    const entries = await api("/warnings/" + warningId + "/audit");
    body.innerHTML = entries.length === 0
      ? '<p class="muted">No entries yet.</p>'
      : '<div class="timeline">' + entries.slice().reverse().map((entry) =>
          "<div><i></i><span>" + esc(formatTime(entry.createdAt)) + "</span>" +
          "<strong>" + esc(label(entry.action)) + "</strong>" +
          "<p>By " + esc(actorName(entry)) +
            (entry.after && entry.after.status ? ", " + esc(label(entry.after.status)) : "") + "</p>" +
          auditDetail(entry) + "</div>"
        ).join("") + "</div>";
  } catch (error) {
    body.innerHTML = '<div class="note note-error">' + esc(error.message) + "</div>";
  }
}

/** Pop-up header that says which warning this is: "West \u00B7 General public \u00B7 Critical". */
function warningTitle(warning) {
  if (!warning) return "Warning";
  const event = eventsById[warning.eventId];
  return [event ? label(event.region) : null, label(warning.audience), label(warning.severity)]
    .filter(Boolean).join(" \u00B7 ");
}

/** Extra line for review actions: the severity change for a downgrade, and the reason the admin gave (CG-28). */
function auditDetail(entry) {
  const before = entry.before || {};
  const after = entry.after || {};
  let html = "";
  if (before.severity && after.severity && before.severity !== after.severity) {
    html += '<p class="small">' + esc(label(before.severity)) + " \u2192 " + esc(label(after.severity)) + "</p>";
  }
  if (after.reason) html += '<p class="small">Reason: ' + esc(after.reason) + "</p>";
  return html;
}

/**
 * Who did it. Drafts are made by the system. For admin actions the warning audit log only stores the admin's
 * user id (GET /warnings/{id}/audit has no username), so a short form of it is shown.
 */
function actorName(entry) {
  if (entry.actor === "system") return "System (automatic draft)";
  return "Admin " + String(entry.actor).slice(0, 8);
}

function renderPager(page) {
  document.getElementById("pager").hidden = page.total <= page.limit;
  document.getElementById("prev").disabled = page.offset === 0;
  document.getElementById("next").disabled = page.offset + page.items.length >= page.total;
}
