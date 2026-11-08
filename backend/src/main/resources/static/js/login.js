/* Login page: POST /auth/login, keep the token, go to the review queue. */

if (new URLSearchParams(window.location.search).get("expired") === "1") {
  showMessage("Your session expired. Please log in again.", "info");
}

document.getElementById("login-form").addEventListener("submit", async (event) => {
  event.preventDefault(); // stop the browser from reloading the page
  clearMessage();
  const button = event.target.querySelector("button");

  await withButton(button, async () => {
    try {
      const result = await api("/auth/login", {
        method: "POST",
        body: {
          username: document.getElementById("username").value.trim(),
          password: document.getElementById("password").value,
        },
      });
      saveToken(result.token);

      // Only admins can use this console; end users get a clear message instead of broken pages.
      const payload = tokenPayload();
      // The server compares roles case-insensitively (JwtAuthFilter upper-cases them), so do the same here.
      if (!payload || String(payload.role).toUpperCase() !== "ADMIN") {
        localStorage.removeItem(TOKEN_KEY);
        showMessage("This console is for admins only.");
        return;
      }
      window.location.href = "queue.html";
    } catch (error) {
      // The backend returns the same message for a wrong username and a wrong password (by design).
      showMessage(error.status === 401 ? "Invalid username or password." : error.message);
    }
  });
});
