/*
 * Shared helpers used by every page.
 *
 * The frontend holds no business rules: it only calls the API and shows what comes back.
 * The backend stays the single source of truth (validation, allowed transitions, confidence, audit).
 */

const TOKEN_KEY = "hw_token";

/* ---------- Token (the same thing you paste into Swagger's Authorize box) ---------- */

function saveToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

function logout() {
  localStorage.removeItem(TOKEN_KEY);
  window.location.href = "login.html";
}

/** Reads the JWT's middle part (payload). Only used to show the username; the server still checks the signature. */
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
    localStorage.removeItem(TOKEN_KEY);
    window.location.href = "login.html?expired=" + (expired ? "1" : "0");
    return false;
  }
  return true;
}

/* ---------- Calling the API ---------- */

/** Error with the HTTP status and the backend's message (ApiErrorResponse.message). */
class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * Calls the backend with the token attached. Returns the parsed JSON (or null for an empty body).
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

  const response = await fetch(path, {
    method: options.method || "GET",
    headers: headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  // Token missing or expired: go back to login (except on the login call itself).
  if (response.status === 401 && !path.startsWith("/auth/")) {
    logout();
    throw new ApiError(401, "Your session has expired. Please log in again.");
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
    if (data.error) return data.error;
  }
  if (status === 403) return "You need an admin account to do this.";
  if (status === 404) return "Not found.";
  return "Request failed (HTTP " + status + ").";
}

/* ---------- Showing things on the page ---------- */

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
    timeZone: "Asia/Singapore",
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** "2026-09-24T03:15:00Z" -> "3 h ago". */
function timeAgo(iso) {
  if (!iso) return "";
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return minutes + " min ago";
  const hours = Math.round(minutes / 60);
  if (hours < 48) return hours + " h ago";
  return Math.round(hours / 24) + " days ago";
}

/** "UNDER_REVIEW" -> "Under review". */
function label(value) {
  if (!value) return "–";
  const text = String(value).replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Coloured pill for a status, severity or confidence level. The CSS class is picked from the value. */
function badge(value) {
  if (!value) return '<span class="badge">–</span>';
  return '<span class="badge badge-' + esc(String(value).toLowerCase()) + '">' + esc(label(value)) + "</span>";
}

function formatNumber(value, digits = 2) {
  return value === null || value === undefined ? "–" : Number(value).toFixed(digits);
}

/** Shows a message in the page's message area. type: "error", "success" or "info". */
function showMessage(text, type = "error") {
  const box = document.getElementById("message");
  if (!box) {
    alert(text);
    return;
  }
  box.className = "message message-" + type;
  box.textContent = text;
  box.hidden = false;
  if (type === "success") {
    setTimeout(() => (box.hidden = true), 4000);
  }
}

function clearMessage() {
  const box = document.getElementById("message");
  if (box) box.hidden = true;
}

/** Disables a button while an action runs, so a double-click can't send the request twice. */
async function withButton(button, action) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "Working…";
  try {
    await action();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

/* ---------- Navigation bar (same on every page) ---------- */

const NAV_LINKS = [
  { href: "queue.html", text: "Review queue" },
  { href: "events.html", text: "Events" },
  { href: "sources.html", text: "Data sources" },
  { href: "tools.html", text: "Demo tools" },
];

function renderNav() {
  const nav = document.getElementById("nav");
  if (!nav) return;
  const current = window.location.pathname.split("/").pop() || "queue.html";
  const payload = tokenPayload();
  const links = NAV_LINKS.map(
    (link) =>
      '<a href="' + link.href + '"' + (link.href === current ? ' class="active"' : "") + ">" + esc(link.text) + "</a>"
  ).join("");
  nav.innerHTML =
    '<div class="brand">HealthWatch <span>admin</span></div>' +
    '<div class="nav-links">' + links + "</div>" +
    '<div class="nav-user">' + esc(payload ? payload.sub : "") +
    ' <button type="button" class="link-button" id="logout">Log out</button></div>';
  document.getElementById("logout").addEventListener("click", logout);
}
