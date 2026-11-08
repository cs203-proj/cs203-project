/*
 * Shared helpers used by every page.
 *
 * The frontend holds no business rules: it calls the API and shows what comes back.
 * The backend stays the source of truth (validation, allowed transitions, confidence, audit).
 */

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

function logout() {
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
  const expired = payload && payload.exp && payload.exp * 1000 < Date.now();
  if (!payload || expired) {
    clearToken();
    window.location.href = "login.html" + (expired ? "?expired=1" : "");
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
  if (response.status === 401 && !path.startsWith("/auth/")) {
    clearToken();
    window.location.href = "login.html?expired=1";
    throw new ApiError(401, "Your session has expired. Please sign in again.");
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
    const details = Array.isArray(data.details) && data.details.length ? " (" + data.details.join("; ") + ")" : "";
    if (data.message) return data.message + details;
    if (data.error && status !== 403) return data.error;
  }
  if (status === 403) return "You need an admin account to do this.";
  if (status === 404) return "Not found.";
  if (status >= 500) return "Something went wrong on the server (HTTP " + status + "). Try again in a moment.";
  return "Request failed (HTTP " + status + ").";
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
  if (!iso) return "–";
  return new Date(iso).toLocaleString("en-SG", {
    timeZone: "Asia/Singapore", day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit",
  });
}

/** "2026-09-24T03:15:00Z" -> "3 h". */
function duration(iso) {
  if (!iso) return "";
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (minutes < 60) return minutes + " min";
  const hours = Math.round(minutes / 60);
  if (hours < 48) return hours + " h";
  return Math.round(hours / 24) + " days";
}

/** "UNDER_REVIEW" -> "Under review". */
function label(value) {
  if (!value) return "–";
  const text = String(value).replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function formatNumber(value, digits = 2) {
  return value === null || value === undefined ? "–" : Number(value).toFixed(digits);
}

/** Coloured pill for a status, severity, verdict or confidence level. The CSS class comes from the value. */
function badge(value, withDot = false) {
  if (!value) return '<span class="badge">–</span>';
  return '<span class="badge badge-' + esc(String(value).toLowerCase()) + '">' +
    (withDot ? '<i class="dot"></i>' : "") + esc(label(value)) + "</span>";
}

/** Confidence uses its own colours: high confidence is good news (green), unlike high severity (red). */
function confidenceBadge(level) {
  if (!level) return '<span class="badge">–</span>';
  return '<span class="badge badge-conf-' + esc(String(level).toLowerCase()) + '">' + esc(label(level)) + "</span>";
}

/** "pm25" -> "PM2.5". Unknown signal types are shown as they are. */
const SIGNAL_NAMES = { pm25: "PM2.5", aqi: "AQI", psi: "PSI" };
function signalName(type) {
  return SIGNAL_NAMES[String(type).toLowerCase()] || type;
}

/** Thin bar showing a 0..1 score. */
function scoreBar(score) {
  const width = Math.max(0, Math.min(100, Math.round((score || 0) * 100)));
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
    showMessage.timer = setTimeout(() => toast.classList.remove("show"), 3500);
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
  button.textContent = "Working…";
  try {
    await action();
  } finally {
    button.disabled = false;
    button.innerHTML = original;
  }
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
};

function icon(name, size = 18) {
  return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + ICON_PATHS[name] + "</svg>";
}

/* ---------- Sidebar (same on every page) ---------- */

const NAV_LINKS = [
  { href: "queue.html", text: "Review warnings", icon: "grid" },
  { href: "events.html", text: "Events", icon: "pulse" },
  { href: "sources.html", text: "Data sources", icon: "database" },
];

function renderSidebar() {
  const sidebar = document.getElementById("sidebar");
  if (!sidebar) return;
  const current = window.location.pathname.split("/").pop() || "queue.html";
  const username = (tokenPayload() || {}).sub || "";

  applySidebarState();
  sidebar.innerHTML =
    '<div class="side-brand"><div class="brand-mark">' + icon("pulse", 21) + "</div>" +
      "<div><strong>HealthWatch</strong><span>Admin console</span></div>" +
      '<button type="button" class="collapse-btn" id="collapse-sidebar" aria-label="Collapse sidebar" title="Collapse sidebar">' +
        icon("sidebar", 18) + "</button></div>" +
    '<div class="nav-label">Workspace</div>' +
    "<nav>" +
      NAV_LINKS.map((link) =>
        '<a href="' + link.href + '" title="' + esc(link.text) + '"' + (link.href === current ? ' class="active"' : "") + ">" +
          icon(link.icon) + "<span>" + esc(link.text) + "</span>" +
          (link.href === "queue.html" ? '<b id="pending-count" hidden></b>' : "") +
        "</a>").join("") +
    "</nav>" +
    '<div class="side-bottom"><div class="user-card">' +
      '<div class="avatar">' + esc(username.slice(0, 2)) + "</div>" +
      "<div><strong>" + esc(username) + "</strong><span>Administrator</span></div>" +
      '<button type="button" id="logout" title="Sign out" aria-label="Sign out">' + icon("logout", 17) + "</button>" +
    "</div></div>";

  document.getElementById("logout").addEventListener("click", logout);
  document.getElementById("collapse-sidebar").addEventListener("click", () => {
    setSidebarCollapsed(!document.body.classList.contains("sidebar-collapsed"));
  });
  setSidebarCollapsed(document.body.classList.contains("sidebar-collapsed")); // sets the button's label
  updatePendingCount();
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
    const page = await api("/warnings?status=PENDING_REVIEW&limit=1");
    const count = document.getElementById("pending-count");
    count.textContent = page.total;
    count.hidden = page.total === 0;
  } catch (e) {
    // The count is a nice-to-have; the page itself reports real errors.
  }
}
