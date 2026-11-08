/*
 * Data sources page (CG-58).
 *   List / search:  GET /data-sources[?name=...]   (no match -> 200 [], shown as "no match")
 *   Register:       POST /data-sources {name, type, url}   (created disabled)
 *   Edit URL:       PUT /data-sources/{id} {url}           (name and type are immutable)
 *   Enable/disable: PATCH /data-sources/{id}/enabled {enabled}
 * Ids come from the list response; the user only ever sees names.
 */

let sources = [];
let searchName = "";

if (requireLogin()) {
  renderNav();
  setUpControls();
  loadSources();
}

function setUpControls() {
  document.getElementById("search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    searchName = document.getElementById("search-name").value.trim();
    loadSources();
  });
  document.getElementById("clear-search").addEventListener("click", () => {
    document.getElementById("search-name").value = "";
    searchName = "";
    loadSources();
  });

  const addForm = document.getElementById("add-form");
  document.getElementById("show-add").addEventListener("click", () => (addForm.hidden = false));
  document.getElementById("cancel-add").addEventListener("click", () => {
    addForm.reset();
    addForm.hidden = true;
  });
  addForm.addEventListener("submit", registerSource);
}

async function loadSources() {
  clearMessage();
  const rows = document.getElementById("source-rows");
  rows.innerHTML = '<tr><td colspan="6" class="empty">Loading…</td></tr>';
  try {
    sources = await api("/data-sources" + (searchName ? "?name=" + encodeURIComponent(searchName) : ""));
    if (sources.length === 0) {
      rows.innerHTML = '<tr><td colspan="6" class="empty">' +
        (searchName ? 'No source is named "' + esc(searchName) + '".' : "No data sources registered.") + "</td></tr>";
      return;
    }
    rows.innerHTML = sources.map(sourceRow).join("");
    rows.querySelectorAll("[data-toggle]").forEach((b) => b.addEventListener("click", () => toggleEnabled(b)));
    rows.querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => startEdit(b.dataset.edit)));
  } catch (error) {
    rows.innerHTML = "";
    showMessage(error.message);
  }
}

function sourceRow(source) {
  return (
    '<tr data-row="' + esc(source.id) + '">' +
      "<td><strong>" + esc(source.name) + "</strong></td>" +
      "<td>" + esc(source.type) + "</td>" +
      '<td class="url-cell">' + urlText(source.url) + "</td>" +
      "<td>" + badge(source.enabled ? "ENABLED" : "DISABLED") + "</td>" +
      "<td>" + (source.ingestionAvailable ? "Yes" : '<span class="muted">No parser in this build</span>') + "</td>" +
      '<td style="white-space: nowrap">' +
        '<button type="button" class="small" data-edit="' + esc(source.id) + '">Edit URL</button> ' +
        '<button type="button" class="small ' + (source.enabled ? "danger" : "primary") + '" data-toggle="' + esc(source.id) + '">' +
          (source.enabled ? "Disable" : "Enable") + "</button>" +
      "</td>" +
    "</tr>"
  );
}

function urlText(url) {
  return url ? '<span class="mono">' + esc(url) + "</span>" : '<span class="muted">Default from config</span>';
}

/** PATCH sends the value we want (not "toggle"), so a double-click or retry can't undo itself. */
async function toggleEnabled(button) {
  const source = sources.find((s) => s.id == button.dataset.toggle);
  const enable = !source.enabled;
  if (!enable && !confirm('Disable "' + source.name + '"? Ingestion and backfill will skip it until re-enabled.')) return;

  await withButton(button, async () => {
    try {
      await api("/data-sources/" + source.id + "/enabled", { method: "PATCH", body: { enabled: enable } });
      await loadSources(); // reloading clears messages, so show the result after it
      showMessage('"' + source.name + '" ' + (enable ? "enabled" : "disabled") + ".", "success");
    } catch (error) {
      showMessage(error.status === 404 ? "This source no longer exists. Refreshing the list." : error.message);
      if (error.status === 404) loadSources();
    }
  });
}

/** Turns the URL cell into an input with Save / Cancel. */
function startEdit(id) {
  const source = sources.find((s) => s.id == id);
  const cell = document.querySelector('tr[data-row="' + CSS.escape(String(id)) + '"] .url-cell');
  cell.innerHTML =
    '<div class="inline-form">' +
      '<input type="url" value="' + esc(source.url || "") + '" placeholder="Leave empty to use the default">' +
      '<button type="button" class="small primary">Save</button>' +
      '<button type="button" class="small">Cancel</button>' +
    "</div>";
  const [input] = cell.getElementsByTagName("input");
  const [save, cancel] = cell.getElementsByTagName("button");
  input.focus();
  cancel.addEventListener("click", () => (cell.innerHTML = urlText(source.url)));
  save.addEventListener("click", () =>
    withButton(save, async () => {
      try {
        const url = input.value.trim();
        await api("/data-sources/" + id, { method: "PUT", body: { url: url === "" ? null : url } });
        await loadSources();
        showMessage("URL updated for \"" + source.name + "\".", "success");
      } catch (error) {
        showMessage(error.message); // e.g. 400 when the host is not on the allowlist
      }
    })
  );
}

async function registerSource(event) {
  event.preventDefault();
  const form = event.target;
  const url = document.getElementById("add-url").value.trim();

  await withButton(form.querySelector("button[type=submit]"), async () => {
    try {
      const created = await api("/data-sources", {
        method: "POST",
        body: {
          name: document.getElementById("add-name").value.trim(),
          type: document.getElementById("add-type").value.trim(),
          url: url === "" ? null : url,
        },
      });
      form.reset();
      form.hidden = true;
      await loadSources();
      showMessage('Registered "' + created.name + '" (disabled). Enable it when you want it ingested.', "success");
    } catch (error) {
      showMessage(error.message); // 409 = name taken, 400 = bad url or wrong type
    }
  });
}
