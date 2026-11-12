/*
 * My warnings (end users).
 *   Warnings: GET /me/warnings  (approved, active, matching the user's region and audience; sorted by the backend)
 *   Profile:  GET /me/profile   (shown above the list, so the user can see who the warnings are for)
 *
 * Everything shown comes from the API as it is: the confidence meaning and "why this applies" are written by the
 * backend in code, and the AI line is shown only with the label the backend gives it.
 */

if (requireLogin()) {
  renderSidebar();
  setUpRefresh(document.getElementById("refresh"), loadPage);
  loadPage();
}

async function loadPage() {
  clearMessage();
  const container = document.getElementById("my-warnings");
  container.innerHTML = '<div class="panel empty">Loading\u2026</div>';

  // The profile strip is a nice-to-have, so its failure doesn't block the warnings.
  api("/me/profile").then(renderProfileStrip).catch(() => (document.getElementById("profile-strip").hidden = true));

  try {
    const feed = await api("/me/warnings");
    if (feed.message) {
      // e.g. "Complete your profile (region and audience) to see warnings that apply to you."
      container.innerHTML =
        '<div class="panel empty-state">' + icon("user", 28) +
          "<h2>Set up your profile</h2><p>" + esc(feed.message) + "</p>" +
          '<a class="btn btn-primary" href="settings.html">Go to settings ' + icon("arrow", 16) + "</a>" +
        "</div>";
      return;
    }
    if (feed.items.length === 0) {
      container.innerHTML =
        '<div class="panel empty-state all-clear">' + icon("check", 28) +
          "<h2>No active warnings</h2><p>Nothing needs your attention in your area right now.</p></div>";
      return;
    }
    container.innerHTML = feed.items.map(myWarningCard).join("");
  } catch (error) {
    container.innerHTML = "";
    showMessage(error.message);
  }
}

/** "Central · Vulnerable groups · Asthma, COPD   Edit" */
function renderProfileStrip(profile) {
  const strip = document.getElementById("profile-strip");
  if (!profile.region || !profile.audience) {
    strip.hidden = true;
    return;
  }
  const conditions = profile.healthConditions.map((c) => codeText(HEALTH_CONDITIONS, c)).join(", ");
  strip.innerHTML =
    '<span class="profile-item">' + icon("pin", 16) + esc(codeText(REGIONS, profile.region)) + "</span>" +
    '<span class="profile-item">' + icon("user", 16) + esc(codeText(AUDIENCES, profile.audience)) + "</span>" +
    (conditions ? '<span class="profile-item muted">' + esc(conditions) + "</span>" : "") +
    '<a class="profile-edit" href="settings.html">' + icon("edit", 15) + " Edit</a>";
  strip.hidden = false;
}

function myWarningCard(w) {
  const confidence = w.confidence || {};
  return (
    '<article class="warning-card my-warning">' +
      '<div class="severity-line severity-' + esc(String(w.severity).toLowerCase()) + '"></div>' +
      '<div class="warning-head">' +
        '<div class="badges">' + badge(w.severity) + '<span class="badge">' + esc(codeText(REGIONS, w.region)) + "</span></div>" +
        '<span class="wait" title="Approved ' + esc(formatTime(w.approvedAt)) + '">' + icon("clock", 15) +
          " Issued " + esc(duration(w.approvedAt)) + " ago</span>" +
      "</div>" +
      '<p class="warning-text my-warning-text">' + esc(w.text) + "</p>" +
      aiLine(w.aiLine) +
      '<div class="my-warning-facts">' +
        '<div><span class="label-sm">Why you\u2019re seeing this</span><p>' + esc(w.whyThisApplies) + "</p></div>" +
        '<div><span class="label-sm">How sure we are</span>' +
          "<p>" + (confidence.level ? confidenceBadge(confidence.level) + " " : "") +
          esc(confidence.meaning || "This warning has no confidence score.") + "</p></div>" +
      "</div>" +
      (w.dataQualityNote
        ? '<div class="note note-warn">' + icon("warning", 16) + "<div>" + esc(w.dataQualityNote) + "</div></div>"
        : "") +
    "</article>"
  );
}

/** The AI-written sentence is kept apart from the rest and always carries the backend's label ("AI-generated"). */
function aiLine(line) {
  if (!line || !line.text) return "";
  return (
    '<div class="ai-line">' + icon("sparkle", 16) +
      "<div><span class=\"label-sm\">" + esc(line.label || "AI-generated") + "</span><p>" + esc(line.text) + "</p></div>" +
    "</div>"
  );
}
