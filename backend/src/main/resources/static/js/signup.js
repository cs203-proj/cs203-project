/*
 * Sign-up page (end users).
 *   1. GET /consent             the notice to show, and its version (public)
 *   2. POST /auth/register      { username, password, consent: true, consentVersion }  (201; no token returned)
 *   3. POST /auth/login         same as the sign-in page, then on to Settings to fill in the profile
 *
 * Username and password rules (lengths) live only in the backend (RegisterRequest). They are not repeated here, so
 * they can't drift apart: if a rule is broken, the backend's 400 message says which one, and it is shown as it is.
 */

let consentVersion = null; // from GET /consent; sent back so the server records exactly what was agreed to

const passwordInput = document.getElementById("password");
const submitButton = document.getElementById("submit");

document.getElementById("signup-mark").innerHTML = icon("pulse", 22);
submitButton.innerHTML = "Create account " + icon("arrow", 17);
setUpPasswordToggle(passwordInput, document.getElementById("toggle-password"));
setUpPasswordToggle(document.getElementById("confirm"), document.getElementById("toggle-confirm"));

/** Eye button: switch one password box between hidden (dots) and visible text. */
function setUpPasswordToggle(input, button) {
  button.innerHTML = icon("eye");
  button.addEventListener("click", () => {
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    button.innerHTML = icon(show ? "eyeOff" : "eye");
    button.setAttribute("aria-label", show ? "Hide password" : "Show password");
  });
}

document.getElementById("signup-form").addEventListener("submit", signUp);
loadConsent();

/** Show the notice; the form can only be sent once it has loaded, since its version goes into the request. */
async function loadConsent() {
  const box = document.getElementById("consent-text");
  try {
    const consent = await api("/consent");
    consentVersion = consent.version;
    box.innerHTML = String(consent.text).split(/\n\s*\n/).map((para) => "<p>" + esc(para.trim()) + "</p>").join("");
    document.getElementById("consent").disabled = false;
    submitButton.disabled = false;
  } catch (error) {
    box.textContent = "";
    showMessage("Couldn't load the consent notice, so sign-up isn't available right now. " + error.message);
  }
}

async function signUp(event) {
  event.preventDefault();
  clearMessage();

  const username = document.getElementById("username").value.trim();
  const password = passwordInput.value;

  // The only check the backend can't do: the confirm box is never sent.
  if (password !== document.getElementById("confirm").value) return showMessage("The passwords don't match.");

  // Consent is sent as it is and checked by the backend, after the username and password rules, so problems with
  // those are reported first.
  const agreed = document.getElementById("consent").checked;

  await withButton(submitButton, async () => {
    try {
      await api("/auth/register", {
        method: "POST",
        body: { username: username, password: password, consent: agreed, consentVersion: consentVersion },
      });
    } catch (error) {
      // e.g. 409 "Username 'x' is already taken", or 400 "Password must be between 8 and 72 characters."
      // The backend's consent message points API users to GET /consent; say it in the page's own words instead.
      const consentMissing = !agreed && error.status === HTTP.BAD_REQUEST && /consent/i.test(error.message);
      showMessage(consentMissing ? "Please tick the box to agree to how your information is used." : error.message);
      return;
    }
    try {
      const result = await api("/auth/login", { method: "POST", body: { username: username, password: password } });
      saveToken(result.token);
      window.location.href = "settings.html?welcome=1";
    } catch (error) {
      // The account exists; only the automatic sign-in failed.
      window.location.href = "login.html";
    }
  });
}
