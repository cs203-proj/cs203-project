/*
 * Data sources page (CG-58).
 *   List:           GET /data-sources   (all of them; the search box filters in the browser)
 *   Register:       POST /data-sources {name, type, url}   (created disabled)
 *   Edit URL:       PUT /data-sources/{id} {url}           (name and type are immutable)
 *   Enable/disable: PATCH /data-sources/{id}/enabled {enabled}
 *   Backfill:       POST /backfill/pm25?from=&to=          (409 if the source is disabled)
 *   Allowed hosts:  GET /data-sources/allowed-hosts        (for the "?" help; see loadAllowedHosts)
 * Ids come from the list response; the user only ever sees names.
 *
 * "Last data" column: shown only when the backend sends lastSuccessfulPullAt (newest valid reading of the source,
 * CG-58's "last successful pull" criterion). It is how an admin notices a wrong or broken feed URL.
 */

const MAX_BACKFILL_DAYS = 31;

/** An enabled source with no new valid reading for this long is flagged (the feed is pulled hourly). */
const QUIET_AFTER_HOURS = 3;
let showLastData = false;
let allowedHosts = null;     // from GET /data-sources/allowed-hosts, or null if the backend doesn't offer it

/** Sources that have a backfill endpoint in the backend. Only PM2.5 has one so far. */
const BACKFILL_ENDPOINTS = {
  datagovsg_pm25: "/backfill/pm25",
};

let sources = [];
let backfillSource = null;   // the source whose backfill pop-up is open
let rangePicker = null;      // the calendar in the backfill pop-up

if (requireLogin()) {
  renderSidebar();
  setUpControls();
  loadSources();
}

function setUpControls() {
  document.getElementById("show-add").innerHTML = icon("plus", 16) + " Register source";
  document.getElementById("close-add").innerHTML = icon("x", 18);
  document.getElementById("search-icon").innerHTML = icon("search", 16);
  document.getElementById("backfill-close").innerHTML = icon("x");
  document.getElementById("run-backfill").innerHTML = icon("history", 17) + " Start backfill";
  document.querySelector(".date-input").insertAdjacentHTML("beforeend", icon("calendar", 17));

  // Search: filters as you type, matching any part of the name (not case-sensitive).
  document.getElementById("search-name").addEventListener("input", renderRows);

  const addForm = document.getElementById("add-form");
  const closeAdd = () => {
    addForm.reset();
    addForm.hidden = true;
    document.getElementById("add-error").hidden = true;
  };
  document.getElementById("show-add").addEventListener("click", () => {
    addForm.hidden = false;
    document.getElementById("add-name").focus();
  });
  document.getElementById("close-add").addEventListener("click", closeAdd);
  document.getElementById("cancel-add").addEventListener("click", closeAdd);
  addForm.addEventListener("submit", registerSource);

  setUpBackfillModal();
  loadAllowedHosts();
}

/**
 * The "?" next to Feed URL lists the hosts a feed URL may use. The list lives in the server config
 * (ingestion.sources.allowed-hosts), so the page asks the backend for it instead of keeping a copy here.
 * If the backend doesn't have that endpoint (yet), the pop-over says where the list is set instead.
 */
async function loadAllowedHosts() {
  let body;
  try {
    const hosts = await api("/data-sources/allowed-hosts");
    allowedHosts = hosts;
    body = "<strong>Allowed hosts</strong>A feed URL must use https on one of these:" +
      "<ul>" + hosts.map((h) => "<li><code>" + esc(h) + "</code></li>").join("") + "</ul>";
  } catch (e) {
    body = "<strong>Allowed hosts</strong>A feed URL must use https on a host in the server's allowlist " +
      "(<code>ingestion.sources.allowed-hosts</code>). By default that is <code>api-open.data.gov.sg</code>.";
  }
  document.getElementById("hosts-help-form").innerHTML = helpButton("hosts-pop-form", body);
  document.getElementById("hosts-help-table").innerHTML = helpButton("hosts-pop-table", body);
  setUpHelp();
}

/* ---------- Table ---------- */

async function loadSources() {
  try {
    sources = await api("/data-sources");
    showLastData = sources.some((s) => "lastSuccessfulPullAt" in s);
    document.querySelectorAll(".col-last-data").forEach((th) => (th.hidden = !showLastData));
    document.getElementById("count-enabled").textContent = sources.filter((s) => s.enabled).length;
    document.getElementById("count-disabled").textContent = sources.filter((s) => !s.enabled).length;
    renderRows();
  } catch (error) {
    document.getElementById("source-rows").innerHTML = "";
    showMessage(error.message);
  }
}

function renderRows() {
  const rows = document.getElementById("source-rows");
  const term = document.getElementById("search-name").value.trim().toLowerCase();
  const shown = sources.filter((s) => s.name.toLowerCase().includes(term));

  if (shown.length === 0) {
    rows.innerHTML = '<tr><td colspan="7" class="empty">' +
      (term ? 'No source name contains "' + esc(term) + '".' : "No data sources registered.") + "</td></tr>";
    return;
  }
  rows.innerHTML = shown.map(sourceRow).join("");
  rows.querySelectorAll("[data-toggle]").forEach((b) => b.addEventListener("click", () => toggleEnabled(b)));
  rows.querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => startEdit(b.dataset.edit)));
  rows.querySelectorAll("[data-backfill]").forEach((b) => b.addEventListener("click", () => openBackfill(b.dataset.backfill)));
}

function sourceRow(source) {
  return (
    '<tr data-row="' + esc(source.id) + '">' +
      '<td><div class="source-name"><div class="source-icon">' + icon("database", 17) + "</div><strong>" + esc(source.name) + "</strong></div></td>" +
      "<td>" + esc(source.type) + "</td>" +
      '<td class="url-cell">' + urlView(source) + "</td>" +
      "<td>" + statusSwitch(source) + "</td>" +
      "<td>" + (source.ingestionAvailable ? "Available" : '<span class="muted">No parser yet</span>') + "</td>" +
      (showLastData ? "<td>" + lastDataCell(source) + "</td>" : "") +
      "<td>" + backfillCell(source) + "</td>" +
    "</tr>"
  );
}

/** The URL with a pencil button next to it. */
function urlView(source) {
  return '<div class="url-view">' +
    (source.url ? '<code class="url">' + esc(source.url) + "</code>" : '<span class="muted">Default from config</span>') +
    '<button type="button" class="icon-btn" data-edit="' + esc(source.id) + '" title="Edit URL" aria-label="Edit URL of ' + esc(source.name) + '">' +
      icon("edit", 15) + "</button></div>";
}

/**
 * On/off switch. Enabling is blocked when the backend has no parser for the source (ingestionAvailable = false),
 * because an enabled source with no parser would never be ingested. An enabled one can always be switched off.
 */
function statusSwitch(source) {
  const blocked = !source.enabled && !source.ingestionAvailable;
  const title = blocked
    ? "Can't enable: the backend has no parser for this source yet"
    : source.enabled ? "Click to disable" : "Click to enable";
  return '<button type="button" class="switch' + (source.enabled ? " on" : "") + '" role="switch" aria-checked="' + source.enabled + '"' +
    ' data-toggle="' + esc(source.id) + '" title="' + title + '"' + (blocked ? " disabled" : "") + ">" +
    '<span class="switch-track"><span class="switch-thumb"></span></span>' +
    '<span class="switch-label">' + (source.enabled ? "Enabled" : "Disabled") + "</span></button>";
}

/** How recent the newest valid reading is; flags an enabled feed that has gone quiet (e.g. a wrong URL). */
function lastDataCell(source) {
  const at = source.lastSuccessfulPullAt;
  if (!at) {
    return source.enabled
      ? '<span class="quiet" title="Enabled, but no valid reading has been stored. Check the feed URL.">' + icon("warning", 14) + " No data yet</span>"
      : '<span class="muted">–</span>';
  }
  const hours = (Date.now() - new Date(at).getTime()) / 3600000;
  const text = esc(duration(at)) + " ago";
  if (source.enabled && hours > QUIET_AFTER_HOURS) {
    return '<span class="quiet" title="No new valid reading for over ' + QUIET_AFTER_HOURS + ' hours. Check the feed URL. Last: ' +
      esc(formatTime(at)) + '">' + icon("warning", 14) + " " + text + "</span>";
  }
  return '<span title="' + esc(formatTime(at)) + '">' + text + "</span>";
}

function backfillCell(source) {
  if (!BACKFILL_ENDPOINTS[source.name]) return '<span class="muted small">Not supported</span>';
  const title = source.enabled ? "Load past readings" : "Enable the source to backfill it";
  return '<button type="button" class="btn btn-secondary btn-sm" data-backfill="' + esc(source.id) + '" title="' + title + '"' +
    (source.enabled ? "" : " disabled") + ">" + icon("history", 14) + " Backfill</button>";
}

/* ---------- Enable / disable ---------- */

/** PATCH sends the value we want (not "toggle"), so a double-click or retry can't undo itself. */
async function toggleEnabled(button) {
  const source = sources.find((s) => String(s.id) === button.dataset.toggle);
  const enable = !source.enabled;
  if (!enable && !(await confirmDialog({
    title: "Disable " + source.name + "?",
    body: "<p>The hourly pull and backfill will skip it until you enable it again. Readings already stored are kept.</p>",
    confirmText: "Disable", danger: true,
  }))) return;

  button.disabled = true;
  try {
    await api("/data-sources/" + source.id + "/enabled", { method: "PATCH", body: { enabled: enable } });
    await loadSources();
    showMessage('"' + source.name + '" ' + (enable ? "enabled" : "disabled") + ".", "success");
  } catch (error) {
    button.disabled = false;
    if (error.status === 404) {
      await loadSources();
      showMessage("This source no longer exists. The list has been refreshed.");
    } else {
      showMessage(error.message);
    }
  }
}

/* ---------- Edit URL ---------- */

/** Turns the URL cell into an input with Save / Cancel. Errors (e.g. host not allowed) show right under it. */
function startEdit(id) {
  const source = sources.find((s) => String(s.id) === id);
  const cell = document.querySelector('tr[data-row="' + CSS.escape(id) + '"] .url-cell');
  cell.innerHTML =
    '<div class="inline-edit">' +
      '<input type="text" class="input" placeholder="Empty = default from config" aria-label="Feed URL">' +
      '<button type="button" class="btn btn-primary btn-sm" aria-label="Save">' + icon("check", 15) + "</button>" +
      '<button type="button" class="btn btn-ghost btn-sm" aria-label="Cancel">' + icon("x", 15) + "</button>" +
    "</div>" +
    '<div class="form-error small" hidden></div>';
  const input = cell.querySelector("input");
  const error = cell.querySelector(".form-error");
  const [save, cancel] = cell.querySelectorAll("button");
  input.value = source.url || "";
  input.focus();

  cancel.addEventListener("click", renderRows);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") save.click();
    if (e.key === "Escape") cancel.click();
  });
  save.addEventListener("click", () =>
    withButton(save, async () => {
      try {
        const url = input.value.trim();
        const badUrl = urlProblem(url);
        if (badUrl) {
          showFormError(error, badUrl);
          input.classList.add("invalid");
          return;
        }
        await api("/data-sources/" + id, { method: "PUT", body: { url: url === "" ? null : url } });
        await loadSources();
        showMessage('URL updated for "' + source.name + '".', "success");
      } catch (e) {
        showFormError(error, e.message);
        input.classList.add("invalid");
      }
    })
  );
}

/* ---------- Register ---------- */

async function registerSource(event) {
  event.preventDefault();
  const form = event.target;
  const errorBox = document.getElementById("add-error");
  errorBox.hidden = true;
  form.querySelectorAll(".invalid").forEach((el) => el.classList.remove("invalid"));

  const name = document.getElementById("add-name").value.trim();
  const type = document.getElementById("add-type").value.trim();
  const url = document.getElementById("add-url").value.trim();
  if (!name || !type) {
    if (!name) document.getElementById("add-name").classList.add("invalid");
    if (!type) document.getElementById("add-type").classList.add("invalid");
    showFormError(errorBox, "Name and type are required.");
    return;
  }

  const badUrl = urlProblem(url);
  if (badUrl) {
    document.getElementById("add-url").classList.add("invalid");
    showFormError(errorBox, badUrl);
    return;
  }

  await withButton(form.querySelector("button[type=submit]"), async () => {
    try {
      const created = await api("/data-sources", {
        method: "POST",
        body: { name: name, type: type, url: url === "" ? null : url },
      });
      form.reset();
      form.hidden = true;
      await loadSources();
      showMessage('Registered "' + created.name + '". It starts disabled.', "success");

        // Name and type can't be changed later and sources can't be deleted, so check before saving.
      const sure = await confirmDialog({
          title: "Register this source?",
          body:
          '<dl class="facts confirm-facts">' +
              "<dt>Name</dt><dd>" + esc(name) + "</dd>" +
              "<dt>Type</dt><dd>" + esc(type) + "</dd>" +
              "<dt>Feed URL</dt><dd>" + (url ? esc(url) : "Default from config") + "</dd>" +
          "</dl>" +
            '<div class="note note-warn">' + icon("warning", 17) +
            "<div>The <strong>name and type can't be changed</strong> after registering, and sources can't be deleted " +
              "(only disabled). The URL can be edited later.</div></div>",
          confirmText: "Register source",
      });
      if (!sure) return;
      
    } catch (error) {
      // 409 = name taken, 400 = URL not allowed or name/type mismatch. Show it inside the form, next to the fields.
      if (error.status === 409) document.getElementById("add-name").classList.add("invalid");
      if (error.status === 400 && /url|host|https/i.test(error.message)) document.getElementById("add-url").classList.add("invalid");
      showFormError(errorBox, error.message);
    }
  });
}

/**
 * Quick check of a feed URL's shape before anything is sent, so obvious typos are caught straight away.
 * The backend still does the real check (it owns the allowlist); this only saves a round trip.
 * Returns an error message, or null if the URL looks fine (or is empty, which means "use the default").
 */
function urlProblem(url) {
  if (!url) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch (e) {
    return "That isn't a full web address. It should look like https://api-open.data.gov.sg/...";
  }
  if (parsed.protocol !== "https:") return "The feed URL must start with https://";
  if (parsed.username || parsed.password) return "The feed URL must not contain a username or password.";
  if (parsed.port) return "The feed URL must not include a port number.";
  if (!parsed.hostname.includes(".")) {
    return "\"" + parsed.hostname + "\" isn't a full host name. It should look like https://api-open.data.gov.sg/...";
  }
  if (allowedHosts && !allowedHosts.includes(parsed.hostname)) {
    return "The host " + parsed.hostname + " isn't allowed. Allowed: " + allowedHosts.join(", ");
  }
  return null;
}

function showFormError(box, text) {
  box.innerHTML = icon("warning", 16) + "<span>" + esc(text) + "</span>";
  box.hidden = false;
}

/* ---------- Backfill pop-up ---------- */

function setUpBackfillModal() {
  const modal = document.getElementById("backfill-modal");
  const close = () => (modal.hidden = true);
  document.getElementById("backfill-close").addEventListener("click", close);
  document.getElementById("backfill-cancel").addEventListener("click", close);
  modal.addEventListener("mousedown", (e) => { if (e.target === modal) close(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !modal.hidden) close(); });
  document.getElementById("backfill-form").addEventListener("submit", runBackfill);

  // Calendar (flatpickr library): pick a start and an end day; no future dates.
  rangePicker = flatpickr("#backfill-range", {
    mode: "range",
    dateFormat: "Y-m-d",      // what the API gets: 2026-11-01
    altInput: true,
    altFormat: "j M Y",       // what the admin sees: 1 Nov 2026
    maxDate: "today",
    static: true,             // open right under the date box, inside the pop-up
    locale: { firstDayOfWeek: 1, rangeSeparator: "  →  " },
    onChange: () => (document.getElementById("backfill-error").hidden = true),
  });
}

function openBackfill(id) {
  backfillSource = sources.find((s) => String(s.id) === id);
  document.getElementById("backfill-source").textContent = backfillSource.name;
  document.getElementById("backfill-result").innerHTML = "";
  document.getElementById("backfill-error").hidden = true;
  rangePicker.clear();
  document.getElementById("backfill-modal").hidden = false;
}

async function runBackfill(event) {
  event.preventDefault();
  const errorBox = document.getElementById("backfill-error");
  const box = document.getElementById("backfill-result");
  errorBox.hidden = true;

  const dates = rangePicker.selectedDates;
  if (dates.length === 0) {
    showFormError(errorBox, "Pick the first and last day to load.");
    return;
  }
  const from = rangePicker.formatDate(dates[0], "Y-m-d");
  const to = rangePicker.formatDate(dates[dates.length - 1], "Y-m-d"); // one day picked = from and to are the same
  const days = Math.round((dates[dates.length - 1] - dates[0]) / 86400000) + 1;
  if (days > MAX_BACKFILL_DAYS) {
    showFormError(errorBox, "That's " + days + " days. Pick at most " + MAX_BACKFILL_DAYS + " days per run.");
    return;
  }

  await doBackfill(from, to);
}

/** Runs one backfill call and shows the result. Also used by "Retry these days". */
async function doBackfill(from, to) {
  const errorBox = document.getElementById("backfill-error");
  const box = document.getElementById("backfill-result");
  errorBox.hidden = true;
  box.innerHTML = '<div class="note note-info" style="margin-top: 16px">' + icon("clock", 17) + "<div>Loading readings… a full month can take a while.</div></div>";

  await withButton(document.getElementById("run-backfill"), async () => {
    try {
      const r = await api(BACKFILL_ENDPOINTS[backfillSource.name] + "?from=" + from + "&to=" + to, { method: "POST" });
      box.innerHTML =
        '<div class="result-grid">' +
          resultStat("Days", r.days) +
          resultStat("New rows", r.saved, r.malformed + " flagged malformed") +
          resultStat("Already stored", r.duplicates, "skipped") +
          resultStat("Failed rows", r.failed) +
        "</div>" +
        (r.failedDays && r.failedDays.length ? failedDaysNote(r.failedDays)
          : '<div class="note note-success" style="margin-top: 12px">' + icon("check", 17) + "<div>All days loaded completely.</div></div>");
      const retry = document.getElementById("retry-failed");
      if (retry) retry.addEventListener("click", () => retryDays(r.failedDays));
    } catch (error) {
      box.innerHTML = "";
      // 409 = the source was disabled meanwhile; 400 = bad dates or too many days.
      showFormError(errorBox, error.message);
    }
  });
}

/**
 * Some days couldn't be fetched or stored completely (failedDays), usually because data.gov.sg was slow or
 * returned an error for that day. Rerunning is safe: readings already stored are skipped.
 */
function failedDaysNote(days) {
  const list = days.map((d) => esc(formatDay(d))).join(", ");
  return '<div class="note note-warn" style="margin-top: 12px">' + icon("warning", 17) +
    "<div><strong class=\"note-title\">" + days.length + (days.length === 1 ? " day wasn't" : " days weren't") + " fully loaded: " + list + "</strong>" +
    "The data feed didn't return complete data for " + (days.length === 1 ? "that day" : "those days") +
    ", usually a temporary problem on data.gov.sg. Everything else was saved. " +
    '<div style="margin-top: 10px"><button type="button" class="btn btn-secondary btn-sm" id="retry-failed">' + icon("refresh", 14) +
    " Retry " + (days.length === 1 ? "this day" : "these days") + "</button></div></div></div>";
}

/** Retries each failed day on its own (they may not be next to each other). */
async function retryDays(days) {
  if (days.length === 1) {
    await doBackfill(days[0], days[0]);
    return;
  }
  const sorted = days.slice().sort();
  await doBackfill(sorted[0], sorted[sorted.length - 1]); // one call over the span; stored days are skipped as duplicates
}

/** "2026-10-16" -> "16 Oct 2026". */
function formatDay(isoDate) {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-SG", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

function resultStat(name, value, note) {
  return '<div class="factor"><span>' + esc(name) + "</span><strong>" + esc(value) + "</strong>" +
    (note ? "<small>" + esc(note) + "</small>" : "") + "</div>";
}
