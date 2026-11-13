/*
 * Settings (end users).
 *   Load:   GET /me/profile
 *   Save:   PUT /me/profile  { region, audience, healthConditions }  (full replace; unknown codes give 400)
 *
 * Health conditions are only asked for vulnerable groups: the backend only uses them there (the "why this applies"
 * line, CG-23). For other groups an empty list is saved, so no health data is kept that nothing uses.
 *   Delete: DELETE /me       (anonymises the account; admins get 403, so the option is hidden for them)
 */

if (requireLogin()) {
  renderSidebar();
  buildForm();
  document.getElementById("profile-form").addEventListener("submit", saveProfile);
  document.getElementById("audience").addEventListener("change", showConditionsIfVulnerable);
  setUpDelete();
  loadProfile();
}

/** Region options, audience choices and condition checkboxes, built from the shared code lists in api.js. */
function buildForm() {
  document.getElementById("region").insertAdjacentHTML("beforeend",
    REGIONS.map((r) => '<option value="' + r.code + '">' + esc(r.text) + "</option>").join(""));

  document.getElementById("audience").innerHTML = AUDIENCES.map((a) =>
    '<label class="choice">' +
      '<input type="radio" name="audience" value="' + a.code + '">' +
      "<span><strong>" + esc(a.text) + "</strong><small>" + esc(a.hint) + "</small></span>" +
    "</label>").join("");

  document.getElementById("conditions").innerHTML = HEALTH_CONDITIONS.map((c) =>
    '<label class="check"><input type="checkbox" name="condition" value="' + c.code + '"><span>' + esc(c.text) + "</span></label>"
  ).join("");
}

async function loadProfile() {
  try {
    const profile = await api("/me/profile");
    fillForm(profile);
    const welcome = new URLSearchParams(window.location.search).get("welcome") === "1";
    if (welcome) showMessage("Account created.", "success"); // toast; the instructions below stay on the page
    if (!profile.region || !profile.audience) {
      showMessage("Choose your region and who the advice is for, then save, to start getting warnings.", "info");
    }
  } catch (error) {
    showMessage(error.message);
  }
}

function fillForm(profile) {
  document.getElementById("region").value = profile.region || "";
  document.querySelectorAll('input[name="audience"]').forEach((input) => (input.checked = input.value === profile.audience));
  const conditions = profile.healthConditions || [];
  document.querySelectorAll('input[name="condition"]').forEach((input) => (input.checked = conditions.includes(input.value)));
  showConditionsIfVulnerable();
}

function selectedAudience() {
  const chosen = document.querySelector('input[name="audience"]:checked');
  return chosen ? chosen.value : "";
}

function showConditionsIfVulnerable() {
  document.getElementById("conditions-section").hidden = selectedAudience() !== "VULNERABLE";
}

async function saveProfile(event) {
  event.preventDefault();
  clearMessage();
  const errorBox = document.getElementById("profile-error");
  errorBox.hidden = true;

  const audience = selectedAudience();
  const body = {
    region: document.getElementById("region").value,
    audience: audience,
    healthConditions: audience === "VULNERABLE"
      ? [...document.querySelectorAll('input[name="condition"]:checked')].map((input) => input.value)
      : [],
  };

  // Both are required by the backend; check here first so the message is friendlier than the 400.
  if (!body.region) return showFormError(errorBox, "Choose your region.");
  if (!body.audience) return showFormError(errorBox, "Choose who the advice should be for.");

  await withButton(document.getElementById("save-profile"), async () => {
    try {
      const saved = await api("/me/profile", { method: "PUT", body: body });
      fillForm(saved);
      showMessage("Profile saved. Your warnings now match these settings.", "success");
    } catch (error) {
      showFormError(errorBox, error.message);
    }
  });
}

function showFormError(box, text) {
  box.innerHTML = icon("warning", 16) + "<span>" + esc(text) + "</span>";
  box.hidden = false;
}

function setUpDelete() {
  if (isAdmin()) return; // admin accounts can't be deleted here (403)
  document.getElementById("danger-zone").hidden = false;
  const button = document.getElementById("delete-account");
  button.innerHTML = icon("trash", 17) + " Delete my account";
  button.addEventListener("click", () => deleteAccount(button));
}

async function deleteAccount(button) {
  const confirmed = await confirmDialog({
    title: "Delete your account?",
    body: "<p>Your username, password, region, group and health conditions are removed for good, and you stop " +
      "getting warnings. This can't be undone.</p>",
    confirmText: "Delete account",
    danger: true,
  });
  if (!confirmed) return;

  await withButton(button, async () => {
    try {
      await api("/me", { method: "DELETE" });
      clearToken(); // the old token no longer works anyway
      window.location.href = "login.html?deleted=1";
    } catch (error) {
      showMessage(error.message);
    }
  });
}
