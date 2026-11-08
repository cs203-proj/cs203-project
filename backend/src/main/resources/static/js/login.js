/* Sign-in page: POST /auth/login, keep the token for this browser session, go to the review warnings. */

const passwordInput = document.getElementById("password");
const toggleButton = document.getElementById("toggle-password");
const submitButton = document.getElementById("submit");

document.getElementById("login-mark").innerHTML = icon("pulse", 22);
toggleButton.innerHTML = icon("eye");
submitButton.innerHTML = "Sign in " + icon("arrow", 17);

if (new URLSearchParams(window.location.search).get("expired") === "1") {
  showMessage("Your session expired. Please sign in again.", "info");
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

      // Only admins can use this console. The server compares roles case-insensitively, so do the same.
      const payload = tokenPayload();
      if (!payload || String(payload.role).toUpperCase() !== "ADMIN") {
        clearToken();
        showMessage("This console is for admins only.");
        return;
      }
      window.location.href = "queue.html";
    } catch (error) {
      // The backend gives the same answer for a wrong username and a wrong password (by design).
      showMessage(error.status === 401 ? "Invalid username or password." : error.message);
    }
  });
});
