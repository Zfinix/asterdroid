// The pairing page: trades the code the phone shows for a token, then shows
// the config for each MCP client. Everything stays in this page; the fragment
// (#1.<phoneId>.<code>.<fingerprint>) is never sent to the server.
(() => {
  const form = document.getElementById("claim");
  const codeInput = document.getElementById("code");
  const nameInput = document.getElementById("name");
  const go = document.getElementById("go");
  const status = document.getElementById("status");
  const labels = { claude: "Claude Code", codex: "Codex", cursor: "Cursor", vscode: "VS Code", windsurf: "Windsurf", zed: "Zed", mcp_json: ".mcp.json" };
  const failures = {
    400: "That request was not understood. Reload the page and try again.",
    402: "Aster Relay is not active for that phone. Turn it on in Droid first.",
    403: "Your phone said no. Make a new code to try again.",
    404: "That code is wrong or has expired. Make a new one in Droid.",
    408: "Nobody answered on the phone in time. Make a new code and keep Droid open.",
    409: "That phone already has the most computers it can have. Revoke one in Droid first.",
    429: "Too many tries from here. Wait a minute and try again.",
    503: "The phone is offline. Open Droid on it and make a new code.",
  };

  const [version, phone, code, fingerprint] = location.hash.slice(1).split(".");
  const linked = version === "1" && /^ph_[a-z2-7]{26}$/.test(phone || "") ? { phone, fingerprint } : null;
  if (linked && code) codeInput.value = code.slice(0, 4) + "-" + code.slice(4);
  history.replaceState(null, "", location.pathname);

  function say(text, error) {
    status.textContent = text;
    status.classList.toggle("error", Boolean(error));
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    go.disabled = true;
    say("Waiting for you to allow this on your phone.");
    try {
      const response = await fetch("/v1/pair/claim", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          code: codeInput.value,
          phone: linked ? linked.phone : undefined,
          client: { name: nameInput.value.trim() || "A computer", kind: "mcp-http" },
        }),
      });
      if (!response.ok) {
        say(failures[response.status] || "Something went wrong. Try again in a moment.", true);
        return;
      }
      const result = await response.json();
      if (linked && linked.fingerprint && result.phone_fp !== linked.fingerprint) {
        say("The relay answered for a different phone than the one in the QR code. Nothing was connected; do not use this relay.", true);
        return;
      }
      show(result);
    } catch {
      say("Cannot reach the relay. Check your connection and try again.", true);
    } finally {
      go.disabled = false;
    }
  });

  function show(result) {
    form.hidden = true;
    document.getElementById("done").hidden = false;
    document.getElementById("mcp").textContent = result.endpoints.mcp;
    document.getElementById("acp").textContent = result.endpoints.acp;
    document.getElementById("scopes").textContent = result.scopes.join(", ");
    const tabs = document.getElementById("tabs");
    const snippet = document.getElementById("snippet");
    const pick = (key) => {
      const value = result.snippets[key];
      snippet.textContent = typeof value === "string" ? value : JSON.stringify(value, null, 2);
      for (const tab of tabs.children) tab.setAttribute("aria-selected", String(tab.dataset.key === key));
    };
    for (const key of Object.keys(labels)) {
      if (!(key in result.snippets)) continue;
      const tab = document.createElement("button");
      tab.type = "button";
      tab.role = "tab";
      tab.dataset.key = key;
      tab.textContent = labels[key];
      tab.addEventListener("click", () => pick(key));
      tabs.appendChild(tab);
    }
    pick("claude");
    document.getElementById("copy").addEventListener("click", async (event) => {
      await navigator.clipboard.writeText(snippet.textContent);
      event.target.textContent = "Copied";
      setTimeout(() => (event.target.textContent = "Copy"), 1500);
    });
  }
})();
