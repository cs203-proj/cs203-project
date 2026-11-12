/*
 * Shared helpers used by every page.
 *
 * The frontend holds no business rules: it calls the API and shows what comes back.
 * The backend stays the source of truth (validation, allowed transitions, confidence, audit).
 *
 * Non-ASCII characters in code are written as escapes ("–" for a dash, "σ" for sigma), never typed in.
 * The .js files are served without a charset, so a browser may decode them as Windows-1252 and show "â€“" instead
 * of "–". Escapes look the same whatever encoding is guessed.
 */

/* ---------- Constants (every number the pages use lives here, with a name) ---------- */

/** HTTP status codes the pages react to. */
const HTTP = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  SERVER_ERROR: 500,
};

/**
 * Most items a list request asks for. Matches the backend's maximum page size for GET /events and
 * GET /warnings (EventService.MAX_LIMIT and WarningService.MAX_LIMIT; larger values get a 400),
 * so the pages fetch "everything up to this many" in one call. Keep the two in step.
 */
const MAX_PAGE_SIZE = 200;

/** For requests where only the "total" in the response is needed (tab counts, sidebar badge). */
const COUNT_ONLY = 1;

/** How long a success message stays at the bottom of the screen. */
const TOAST_DURATION_MS = 3500;

/** Time units, so time maths reads as words. */
const MS_PER_SECOND = 1000;
const SECONDS_PER_MINUTE = 60;
const MINUTES_PER_HOUR = 60;
const HOURS_PER_DAY = 24;
const MS_PER_MINUTE = MS_PER_SECOND * SECONDS_PER_MINUTE;
const MS_PER_HOUR = MS_PER_MINUTE * MINUTES_PER_HOUR;
const MS_PER_DAY = MS_PER_HOUR * HOURS_PER_DAY;

/** Durations up to this many hours are shown in hours ("36 h"); longer ones in days ("3 days"). */
const SHOW_HOURS_UP_TO = 48;

/* ---------- Token (the same thing you paste into Swagger's Authorize box) ---------- */

const TOKEN_KEY = "hw_token";

// sessionStorage (CG-4 AC): the token is gone when the browser tab/window is closed, and is never hard-coded.
function saveToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

/** Tells the server, then discards the token. The token is discarded even if the server can't be reached. */
async function logout() {
  try {
    await api("/auth/logout", { method: "POST" });
  } catch (e) {
    // Signing out must still work when the server is down; the token is discarded below either way.
  }
  clearToken();
  window.location.href = "login.html";
}

/** Reads the JWT's middle part (payload) to show the username. The server still checks the signature. */
function tokenPayload() {
  const token = getToken();
  if (!token) return null;
  try {
    const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64));
  } catch (e) {
    return null;
  }
}

/** Sends the user to the login page if there is no token or it has expired (tokens last 30 min). */
function requireLogin() {
  const payload = tokenPayload();
  const expired = payload && payload.exp && payload.exp * MS_PER_SECOND < Date.now(); // exp is in seconds
  if (!payload || expired) {
    clearToken();
    window.location.href = "login.html" + (expired ? "?expired=1" : "");
    return false;
  }
  return true;
}

/** True for an admin token. The server compares roles case-insensitively, so this does too. */
function isAdmin() {
  const payload = tokenPayload();
  return !!payload && String(payload.role).toUpperCase() === "ADMIN";
}

/** Where a signed-in person starts: the review queue for admins, their own warnings for everyone else. */
function homePage() {
  return isAdmin() ? "queue.html" : "my-warnings.html";
}

/**
 * For the admin console pages. A signed-in non-admin is sent to their own pages instead of seeing 403 errors.
 * The server still enforces this (SecurityConfig: hasRole("ADMIN")).
 */
function requireAdmin() {
  if (!requireLogin()) return false;
  if (!isAdmin()) {
    window.location.href = homePage();
    return false;
  }
  return true;
}

/* ---------- Calling the API ---------- */

/** Error carrying the HTTP status and the backend's message (ApiErrorResponse.message). */
class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * Calls the backend with the token attached. Returns parsed JSON (or null for an empty body).
 * Throws ApiError with the server's message on any non-2xx response.
 *
 * Example: const page = await api("/events?status=NEW");
 *          await api("/events/" + id + "/status", { method: "PATCH", body: { status: "CONFIRMED" } });
 */
async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  const token = getToken();
  if (token) headers.Authorization = "Bearer " + token;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  let response;
  try {
    response = await fetch(path, {
      method: options.method || "GET",
      headers: headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch (e) {
    throw new ApiError(0, "Can't reach the server. Check your connection and try again.");
  }

  // Token missing or expired: back to login (except on the login call itself, where 401 = wrong password).
  if (response.status === HTTP.UNAUTHORIZED && !path.startsWith("/auth/")) {
    clearToken();
    window.location.href = "login.html?expired=1";
    throw new ApiError(HTTP.UNAUTHORIZED, "Your session has expired. Please sign in again.");
  }

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (e) {
      data = text;
    }
  }
  if (!response.ok) {
    throw new ApiError(response.status, errorMessage(response.status, data));
  }
  return data;
}

/** Picks the most useful message from an error response. */
function errorMessage(status, data) {
  if (data && typeof data === "object") {
    // Validation errors (400) come as details like "password: password must be between 8 and 72 characters".
    // Show just the messages, without the field name, as sentences.
    if (Array.isArray(data.details) && data.details.length) {
      return data.details.map(toSentence).join(" ");
    }
    if (data.message) return data.message;
    if (data.error && status !== HTTP.FORBIDDEN) return data.error;
  }
  if (status === HTTP.FORBIDDEN) return "You need an admin account to do this.";
  if (status === HTTP.NOT_FOUND) return "Not found.";
  if (status >= HTTP.SERVER_ERROR) return "Something went wrong on the server (HTTP " + status + "). Try again in a moment.";
  return "Request failed (HTTP " + status + ").";
}

/** "password: password must be ..." -> "Password must be ...". */
function toSentence(detail) {
  const text = String(detail).replace(/^[\w.]+:\s*/, "");
  return text.charAt(0).toUpperCase() + text.slice(1) + (/[.!?]$/.test(text) ? "" : ".");
}

/* ---------- Formatting ---------- */

/**
 * Escapes text before it goes into innerHTML, so AI-written or admin-entered text can never inject HTML/scripts.
 * Every value from the API that is put into a template string goes through esc().
 */
function esc(value) {
  if (value === null || value === undefined) return "";
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** "2026-09-24T03:15:00Z" -> "24 Sep 2026, 11:15 am" in Singapore time. */
function formatTime(iso) {
  if (!iso) return "\u2013";
  return new Date(iso).toLocaleString("en-SG", {
    timeZone: "Asia/Singapore", day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit",
  });
}

/** "2026-09-24T03:15:00Z" -> "3 h". */
function duration(iso) {
  if (!iso) return "";
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / MS_PER_MINUTE));
  if (minutes < MINUTES_PER_HOUR) return minutes + " min";
  const hours = Math.round(minutes / MINUTES_PER_HOUR);
  if (hours < SHOW_HOURS_UP_TO) return hours + " h";
  return Math.round(hours / HOURS_PER_DAY) + " days";
}

/** "UNDER_REVIEW" -> "Under review". */
function label(value) {
  if (!value) return "\u2013";
  const text = String(value).replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function formatNumber(value, digits = 2) {
  return value === null || value === undefined ? "\u2013" : Number(value).toFixed(digits);
}

/** Coloured pill for a status, severity, verdict or confidence level. The CSS class comes from the value. */
function badge(value, withDot = false) {
  if (!value) return '<span class="badge">\u2013</span>';
  return '<span class="badge badge-' + esc(String(value).toLowerCase()) + '">' +
    (withDot ? '<i class="dot"></i>' : "") + esc(label(value)) + "</span>";
}

/** Confidence uses its own colours: high confidence is good news (green), unlike high severity (red). */
function confidenceBadge(level) {
  if (!level) return '<span class="badge">\u2013</span>';
  return '<span class="badge badge-conf-' + esc(String(level).toLowerCase()) + '">' + esc(label(level)) + "</span>";
}

/** "pm25" -> "PM2.5". Unknown signal types are shown as they are. */
const SIGNAL_NAMES = { pm25: "PM2.5", aqi: "AQI", psi: "PSI" };
function signalName(type) {
  return SIGNAL_NAMES[String(type).toLowerCase()] || type;
}

/*
 * Profile codes and how to show them. The codes are the backend's (Region, Audience and HealthCondition enums,
 * frozen for CG-154 / CG-22); only the wording here is the frontend's. The backend rejects any other code with a 400.
 */
const REGIONS = [
  { code: "central", text: "Central" },
  { code: "north", text: "North" },
  { code: "south", text: "South" },
  { code: "east", text: "East" },
  { code: "west", text: "West" },
];
const AUDIENCES = [
  { code: "GENERAL_PUBLIC", text: "General public", hint: "Standard advice for healthy adults." },
  { code: "VULNERABLE", text: "Vulnerable groups",
    hint: "Children, older adults, pregnant women and people with heart or lung conditions." },
  { code: "HEALTHCARE_WORKER", text: "Healthcare worker", hint: "Advice for clinics, hospitals and carers." },
];
const HEALTH_CONDITIONS = [
  { code: "ASTHMA", text: "Asthma" },
  { code: "COPD", text: "COPD" },
  { code: "HEART_DISEASE", text: "Heart disease" },
  { code: "DIABETES", text: "Diabetes" },
  { code: "PREGNANCY", text: "Pregnancy" },
];

/** Display text for a code from one of the lists above, e.g. codeText(AUDIENCES, "VULNERABLE"). */
function codeText(list, code) {
  const item = list.find((entry) => entry.code === code);
  return item ? item.text : label(code);
}

/** 0.75 -> 75 (a 0..1 fraction as a whole percentage, kept between 0 and 100). */
function toPercent(fraction) {
  return Math.max(0, Math.min(100, Math.round((fraction || 0) * 100)));
}

/** Thin bar showing a 0..1 score. */
function scoreBar(score) {
  const width = toPercent(score);
  return '<span class="score-bar"><i style="width: ' + width + '%"></i></span>';
}

/* ---------- Messages and buttons ---------- */

/**
 * Shows a message. Success messages pop up at the bottom for a few seconds (so the page doesn't jump);
 * errors and info stay at the top of the page until the next action. type: "error", "success", "info" or "warn".
 */
function showMessage(text, type = "error") {
  const iconName = type === "success" ? "check" : type === "info" ? "info" : "warning";
  const html = '<div class="note note-' + type + '">' + icon(iconName, 17) + "<div>" + esc(text) + "</div></div>";

  if (type === "success") {
    let toast = document.getElementById("toast");
    if (!toast) {
      toast = document.createElement("div");
      toast.id = "toast";
      toast.className = "toast";
      toast.setAttribute("role", "status");
      document.body.appendChild(toast);
    }
    toast.innerHTML = html;
    toast.classList.add("show");
    clearTimeout(showMessage.timer);
    showMessage.timer = setTimeout(() => toast.classList.remove("show"), TOAST_DURATION_MS);
    return;
  }
  const box = document.getElementById("message");
  if (!box) return;
  box.innerHTML = html;
  box.hidden = false;
}

function clearMessage() {
  const box = document.getElementById("message");
  if (box) box.hidden = true;
}

/**
 * Styled "are you sure?" pop-up. Returns a Promise that resolves to true (confirmed) or false (cancelled).
 *   if (!(await confirmDialog({ title: "Disable source?", body: "...", confirmText: "Disable", danger: true }))) return;
 * body is HTML, so escape anything from the API with esc() before passing it in.
 */
function confirmDialog({ title, body, confirmText = "Confirm", cancelText = "Cancel", danger = false }) {
  return new Promise((resolve) => {
    const backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop";
    backdrop.innerHTML =
      '<div class="modal" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">' +
        '<div class="modal-head"><h2 id="confirm-title">' + esc(title) + "</h2></div>" +
        '<div class="confirm-body">' + body + "</div>" +
        '<div class="form-actions">' +
          '<button type="button" class="btn btn-ghost" data-answer="no">' + esc(cancelText) + "</button>" +
          '<button type="button" class="btn ' + (danger ? "btn-danger-solid" : "btn-primary") + '" data-answer="yes">' + esc(confirmText) + "</button>" +
        "</div>" +
      "</div>";
    document.body.appendChild(backdrop);
    const yes = backdrop.querySelector('[data-answer="yes"]');
    yes.focus();

    const finish = (answer) => {
      document.removeEventListener("keydown", onKey);
      backdrop.remove();
      resolve(answer);
    };
    const onKey = (e) => { if (e.key === "Escape") finish(false); };
    document.addEventListener("keydown", onKey);
    yes.addEventListener("click", () => finish(true));
    backdrop.querySelector('[data-answer="no"]').addEventListener("click", () => finish(false));
    backdrop.addEventListener("mousedown", (e) => { if (e.target === backdrop) finish(false); });
  });
}

/** Disables a button while an action runs, so a double-click can't send the request twice. */
async function withButton(button, action) {
  const original = button.innerHTML;
  button.disabled = true;
  button.textContent = "Working\u2026";
  try {
    await action();
  } finally {
    button.disabled = false;
    button.innerHTML = original;
  }
}

/**
 * The round-arrow refresh button in a page's top bar (icon only; the tooltip says "Refresh").
 * Reloads that page's data; the arrow spins while it loads, and extra clicks are ignored until it's done.
 */
function setUpRefresh(button, reload) {
  button.innerHTML = icon("refresh", 18);
  button.title = "Refresh";
  button.setAttribute("aria-label", "Refresh");
  button.addEventListener("click", async () => {
    if (button.disabled) return;
    button.disabled = true;
    button.classList.add("spinning");
    try {
      await reload();
    } finally {
      button.disabled = false;
      button.classList.remove("spinning");
    }
  });
}

/* ---------- Icons (inline SVG, so no icon library is needed) ---------- */

const ICON_PATHS = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  pulse: '<path d="M3 12h4l2.2-7 4.2 14 2.2-7H21"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  back: '<path d="m15 18-6-6 6-6"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.5"/>',
  eyeOff: '<path d="M3 3l18 18"/><path d="M10.6 6.1A10.8 10.8 0 0 1 12 6c6.5 0 10 6 10 6a17.7 17.7 0 0 1-3.2 3.9M6.6 6.6A17.4 17.4 0 0 0 2 12s3.5 6 10 6a9.7 9.7 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>',
  arrow: '<path d="M5 12h14"/><path d="m14 7 5 5-5 5"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  x: '<path d="m6 6 12 12M18 6 6 18"/>',
  edit: '<path d="m4 20 4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10L4 20Z"/><path d="m14 7 3 3"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
  logout: '<path d="M10 17l5-5-5-5M15 12H3"/><path d="M14 4h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
  warning: '<path d="M10.3 3.7 2.2 18a2 2 0 0 0 1.7 3h16.2a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
  power: '<path d="M12 3v9"/><path d="M6.4 6.4a8 8 0 1 0 11.2 0"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
  sidebar: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
  bell: '<path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6"/><path d="M5 7l1 13a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1l1-13M9 7V4h6v3"/>',
  sparkle: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8Z"/>',
  pin: '<path d="M12 21s-7-6.2-7-12a7 7 0 0 1 14 0c0 5.8-7 12-7 12Z"/><circle cx="12" cy="9" r="2.5"/>',
};

function icon(name, size = 18) {
  return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + ICON_PATHS[name] + "</svg>";
}

/* ---------- Sidebar (same on every page) ---------- */

/** Admins get the console; everyone else gets their own warnings and settings. */
const ADMIN_NAV_LINKS = [
  { href: "queue.html", text: "Review warnings", icon: "grid" },
  { href: "events.html", text: "Events", icon: "pulse" },
  { href: "sources.html", text: "Data sources", icon: "database" },
];
const USER_NAV_LINKS = [
  { href: "my-warnings.html", text: "My warnings", icon: "bell" },
  { href: "settings.html", text: "Settings", icon: "user" },
];

function renderSidebar() {
  const sidebar = document.getElementById("sidebar");
  if (!sidebar) return;
  const current = window.location.pathname.split("/").pop() || homePage();
  const username = (tokenPayload() || {}).sub || "";
  const admin = isAdmin();
  const links = admin ? ADMIN_NAV_LINKS : USER_NAV_LINKS;

  applySidebarState();
  sidebar.innerHTML =
    '<div class="side-brand"><div class="brand-mark">' + icon("pulse", 21) + "</div>" +
      "<div><strong>HealthWatch</strong><span>" + (admin ? "Admin console" : "Your health alerts") + "</span></div>" +
      '<button type="button" class="collapse-btn" id="collapse-sidebar" aria-label="Collapse sidebar" title="Collapse sidebar">' +
        icon("sidebar", 18) + "</button></div>" +
    '<div class="nav-label">' + (admin ? "Workspace" : "Your account") + "</div>" +
    "<nav>" +
      links.map((link) =>
        '<a href="' + link.href + '" title="' + esc(link.text) + '"' + (link.href === current ? ' class="active"' : "") + ">" +
          icon(link.icon) + "<span>" + esc(link.text) + "</span>" +
          (link.href === "queue.html" ? '<b id="pending-count" hidden></b>' : "") +
        "</a>").join("") +
    "</nav>" +
    '<div class="side-bottom"><div class="user-card">' +
      '<div class="avatar">' + esc(username.slice(0, 2)) + "</div>" +
      "<div><strong>" + esc(username) + "</strong><span>" + (admin ? "Administrator" : "User") + "</span></div>" +
      '<button type="button" id="logout" title="Sign out" aria-label="Sign out">' + icon("logout", 17) + "</button>" +
    "</div></div>";

  document.getElementById("logout").addEventListener("click", logout);
  document.getElementById("collapse-sidebar").addEventListener("click", () => {
    setSidebarCollapsed(!document.body.classList.contains("sidebar-collapsed"));
  });
  setSidebarCollapsed(document.body.classList.contains("sidebar-collapsed")); // sets the button's label
  if (admin) updatePendingCount();
}

/* The sidebar's open/closed state is remembered in this browser (localStorage), so it stays the same across pages. */
const SIDEBAR_KEY = "hw_sidebar_collapsed";

function applySidebarState() {
  let collapsed = false;
  try {
    collapsed = localStorage.getItem(SIDEBAR_KEY) === "1";
  } catch (e) {
    // Storage can be blocked (e.g. some private modes); then the sidebar simply starts open.
  }
  document.body.classList.toggle("sidebar-collapsed", collapsed);
}

function setSidebarCollapsed(collapsed) {
  document.body.classList.toggle("sidebar-collapsed", collapsed);
  const button = document.getElementById("collapse-sidebar");
  button.title = collapsed ? "Expand sidebar" : "Collapse sidebar";
  button.setAttribute("aria-label", button.title);
  try {
    localStorage.setItem(SIDEBAR_KEY, collapsed ? "1" : "0");
  } catch (e) {
    // Not remembered, but it still works for this page.
  }
}

/**
 * Small "?" button that shows a pop-over with help text when clicked. Returns HTML; call setUpHelp() after
 * putting it on the page. contentHtml is HTML, so escape API values with esc() first.
 */
function helpButton(id, contentHtml) {
  return '<span class="help"><button type="button" class="help-btn" aria-expanded="false" aria-controls="' + id + '" ' +
    'aria-label="More information">?</button><span class="help-pop" id="' + id + '" role="tooltip" hidden>' + contentHtml + "</span></span>";
}

function setUpHelp(root = document) {
  root.querySelectorAll(".help-btn").forEach((button) => {
    const pop = document.getElementById(button.getAttribute("aria-controls"));
    button.addEventListener("click", (e) => {
      e.preventDefault(); // the button sits inside a <label>; don't move focus to the input
      e.stopPropagation();
      const open = pop.hidden;
      document.querySelectorAll(".help-pop").forEach((p) => (p.hidden = true));
      document.querySelectorAll(".help-btn").forEach((b) => b.setAttribute("aria-expanded", "false"));
      pop.hidden = !open;
      button.setAttribute("aria-expanded", String(open));
    });
  });
}

// Clicking anywhere else, or pressing Escape, closes any open help pop-over.
document.addEventListener("click", (e) => {
  if (!e.target.closest(".help")) document.querySelectorAll(".help-pop").forEach((p) => (p.hidden = true));
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") document.querySelectorAll(".help-pop").forEach((p) => (p.hidden = true));
});

/** Number of warnings waiting for review, shown next to "Review warnings" in the sidebar. */
async function updatePendingCount() {
  try {
    const page = await api("/warnings?status=PENDING_REVIEW&limit=" + COUNT_ONLY);
    const count = document.getElementById("pending-count");
    count.textContent = page.total;
    count.hidden = page.total === 0;
  } catch (e) {
    // The count is a nice-to-have; the page itself reports real errors.
  }
}
