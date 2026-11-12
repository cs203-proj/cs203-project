/* Sign-in page: POST /auth/login, keep the token for this browser session, then go to the right home page
 * (admins: review warnings; everyone else: their own warnings). */

const passwordInput = document.getElementById("password");
const toggleButton = document.getElementById("toggle-password");
const submitButton = document.getElementById("submit");

document.getElementById("login-mark").innerHTML = icon("pulse", 22);
toggleButton.innerHTML = icon("eye");
submitButton.innerHTML = "Sign in " + icon("arrow", 17);

const params = new URLSearchParams(window.location.search);
if (params.get("expired") === "1") {
  showMessage("Your session expired. Please sign in again.", "info");
}
if (params.get("deleted") === "1") {
  showMessage("Your account has been deleted and your personal details removed.", "info");
}

/* Eye button: switch the password between hidden (dots) and visible text. */
toggleButton.addEventListener("click", () => {
  const show = passwordInput.type === "password";
  passwordInput.type = show ? "text" : "password";
  toggleButton.innerHTML = icon(show ? "eyeOff" : "eye");
  toggleButton.setAttribute("aria-label", show ? "Hide password" : "Show password");
});

document.getElementById("login-form").addEventListener("submit", async (event) => {
  event.preventDefault(); // stop the browser from reloading the page
  clearMessage();

  await withButton(submitButton, async () => {
    try {
      const result = await api("/auth/login", {
        method: "POST",
        body: { username: document.getElementById("username").value.trim(), password: passwordInput.value },
      });
      saveToken(result.token);
      window.location.href = homePage();
    } catch (error) {
      // The backend gives the same answer for a wrong username and a wrong password (by design).
      showMessage(error.status === HTTP.UNAUTHORIZED ? "Invalid username or password." : error.message);
    }
  });
});
