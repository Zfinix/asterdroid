import assert from "node:assert/strict";
import { copyFile, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { apply, CLIENTS, installed, launchCommand, parseJsonc, plan } from "../src/setup.js";
import { tempDir } from "./fake.js";

const fixtures = path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures");
const NPX = { command: "npx", args: ["-y", "@withaster/droid"] };

async function context(extra = {}) {
  const home = await tempDir("aster-droid-home-");
  return { home, platform: "linux", env: {}, launch: NPX, http: null, ...extra };
}

async function place(file, fixture) {
  await mkdir(path.dirname(file), { recursive: true });
  await copyFile(path.join(fixtures, fixture), file);
}

async function readJson(file) {
  return JSON.parse(await readFile(file, "utf8"));
}

test("claude: adds a stdio server to ~/.claude.json and keeps everything else", async () => {
  const ctx = await context();
  const [edit] = plan("claude", ctx);
  assert.equal(edit.file, path.join(ctx.home, ".claude.json"));
  await place(edit.file, "claude.json");
  const before = await readJson(edit.file);
  assert.equal((await apply(edit)).status, "added");
  const after = await readJson(edit.file);
  assert.deepEqual(after.mcpServers.droid, { type: "stdio", command: "npx", args: ["-y", "@withaster/droid", "mcp"], env: {} });
  assert.deepEqual(after.mcpServers.github, before.mcpServers.github);
  assert.deepEqual(after.projects, before.projects);
  assert.equal(after.numStartups, 12);
  assert.equal((await apply(edit)).status, "present");
});

test("claude: an existing droid entry is never replaced", async () => {
  const ctx = await context();
  const [edit] = plan("claude", ctx);
  await mkdir(path.dirname(edit.file), { recursive: true });
  const mine = { mcpServers: { droid: { type: "http", url: "http://mine" } } };
  await writeFile(edit.file, JSON.stringify(mine));
  const r = await apply(edit);
  assert.equal(r.status, "kept");
  assert.deepEqual(await readJson(edit.file), mine);
});

test("codex: appends an [mcp_servers.droid] table once", async () => {
  const ctx = await context({ env: { CODEX_HOME: "" } });
  const [edit] = plan("codex", ctx);
  assert.equal(edit.file, path.join(ctx.home, ".codex", "config.toml"));
  await place(edit.file, "codex.toml");
  assert.equal((await apply(edit)).status, "added");
  const text = await readFile(edit.file, "utf8");
  assert.ok(text.startsWith(await readFile(path.join(fixtures, "codex.toml"), "utf8")));
  assert.match(text, /\n\n\[mcp_servers\.droid\]\ncommand = "npx"\nargs = \["-y", "@withaster\/droid", "mcp"\]\n$/);
  assert.equal((await apply(edit)).status, "kept");
  assert.equal(await readFile(edit.file, "utf8"), text);
});

test("codex: a droid entry under [mcp_servers] or in an inline table is never duplicated", async () => {
  const ctx = await context();
  const [edit] = plan("codex", ctx);
  await mkdir(path.dirname(edit.file), { recursive: true });
  const cases = [
    ['[mcp_servers]\ndroid = { command = "old" }\n', "kept"],
    ['[mcp_servers]\n"droid".command = "old"\n\n[other]\nx = 1\n', "kept"],
    ['mcp_servers = { droid = { command = "old" } }\n', "kept"],
    ['"mcp_servers".droid = { command = "old" }\n', "kept"],
    ['mcp_servers = { github = { command = "gh-mcp" } }\n', "manual"],
  ];
  for (const [mine, status] of cases) {
    await writeFile(edit.file, mine);
    assert.equal((await apply(edit)).status, status, mine);
    assert.equal(await readFile(edit.file, "utf8"), mine);
  }
  await writeFile(edit.file, '[mcp_servers]\ngithub = { command = "gh-mcp" }\n\n[other]\ndroid = 1\n');
  assert.equal((await apply(edit)).status, "added");
});

test("the default launch goes through cmd /c on Windows, where npx is npx.cmd", () => {
  assert.deepEqual(launchCommand("linux"), NPX);
  assert.deepEqual(launchCommand("darwin"), NPX);
  assert.deepEqual(launchCommand("win32"), { command: "cmd", args: ["/c", "npx", "-y", "@withaster/droid"] });
  assert.deepEqual(launchCommand("win32", "C:\\aster-droid.exe"), { command: "C:\\aster-droid.exe", args: [] });
});

test("codex: --http writes the url and an Authorization header", async () => {
  const ctx = await context({ http: { url: "https://relay.example/p/ph_x/mcp", token: "ast_c_tok" } });
  const [edit] = plan("codex", ctx);
  assert.equal((await apply(edit)).status, "added");
  const text = await readFile(edit.file, "utf8");
  assert.equal(text, '[mcp_servers.droid]\nurl = "https://relay.example/p/ph_x/mcp"\nhttp_headers = { Authorization = "Bearer ast_c_tok" }\n');
  if (process.platform !== "win32") assert.equal((await stat(edit.file)).mode & 0o777, 0o600);
});

test("cursor: keeps the file's four-space indent and its other servers", async () => {
  const ctx = await context();
  const [edit] = plan("cursor", ctx);
  await place(edit.file, "cursor.json");
  assert.equal((await apply(edit)).status, "added");
  const text = await readFile(edit.file, "utf8");
  assert.match(text, /^\{\n {4}"mcpServers"/);
  const after = JSON.parse(text);
  assert.deepEqual(after.mcpServers.droid, { command: "npx", args: ["-y", "@withaster/droid", "mcp"] });
  assert.equal(after.mcpServers.linear.url, "https://mcp.linear.app/mcp");
});

test("zed: adds an external agent and an MCP server next to existing ones", async () => {
  const ctx = await context();
  const edits = plan("zed", ctx);
  assert.equal(edits[0].file, path.join(ctx.home, ".config", "zed", "settings.json"));
  await place(edits[0].file, "zed.json");
  for (const edit of edits) assert.equal((await apply(edit)).status, "added");
  const after = await readJson(edits[0].file);
  assert.deepEqual(after.agent_servers.Droid, { type: "custom", command: "npx", args: ["-y", "@withaster/droid", "acp"], env: {} });
  assert.ok(after.agent_servers["Other Agent"]);
  assert.deepEqual(after.context_servers.droid, { command: "npx", args: ["-y", "@withaster/droid", "mcp"], env: {} });
  assert.equal(after.theme, "One Dark");
});

test("zed: a settings file with comments is left alone and the snippet is handed back", async () => {
  const ctx = await context();
  const edits = plan("zed", ctx);
  await place(edits[0].file, "zed-comments.json");
  const before = await readFile(edits[0].file, "utf8");
  for (const edit of edits) {
    const r = await apply(edit);
    assert.equal(r.status, "manual");
    assert.match(r.note, /comments/);
    assert.ok(JSON.parse(r.snippet));
  }
  assert.equal(await readFile(edits[0].file, "utf8"), before);
});

test("jetbrains: adds to agent_servers in ~/.jetbrains/acp.json and keeps default_mcp_settings", async () => {
  const ctx = await context();
  const [edit] = plan("jetbrains", ctx);
  assert.equal(edit.file, path.join(ctx.home, ".jetbrains", "acp.json"));
  await place(edit.file, "jetbrains.json");
  assert.equal((await apply(edit)).status, "added");
  const after = await readJson(edit.file);
  assert.deepEqual(after.default_mcp_settings, { use_custom_mcp: true, use_idea_mcp: false });
  assert.deepEqual(after.agent_servers.Droid, { command: "npx", args: ["-y", "@withaster/droid", "acp"], env: {} });
  assert.ok(after.agent_servers["Agent Name"]);
});

test("vscode: adds to servers in mcp.json, and leaves a commented settings.json alone", async () => {
  const ctx = await context({ platform: "darwin" });
  const [mcp, acp] = plan("vscode", ctx);
  assert.equal(mcp.file, path.join(ctx.home, "Library", "Application Support", "Code", "User", "mcp.json"));
  await place(mcp.file, "vscode-mcp.json");
  await place(acp.file, "vscode-settings.json");
  assert.equal((await apply(mcp)).status, "added");
  const after = await readFile(mcp.file, "utf8");
  assert.match(after, /^\{\n\t"inputs"/);
  assert.deepEqual(JSON.parse(after).servers.droid, { type: "stdio", command: "npx", args: ["-y", "@withaster/droid", "mcp"] });
  assert.ok(JSON.parse(after).servers.playwright);
  const r = await apply(acp);
  assert.equal(r.status, "manual");
  assert.deepEqual(JSON.parse(r.snippet), { "acp.agents": { Droid: { command: "npx", args: ["-y", "@withaster/droid", "acp"], env: {} } } });
});

test("a new config file is created with its directories", async () => {
  const ctx = await context();
  const [edit] = plan("jetbrains", ctx);
  assert.equal((await apply(edit)).status, "added");
  assert.deepEqual(await readJson(edit.file), { agent_servers: { Droid: { command: "npx", args: ["-y", "@withaster/droid", "acp"], env: {} } } });
});

test("--print writes nothing, for every client", async () => {
  const ctx = await context();
  for (const client of CLIENTS) {
    for (const edit of plan(client, ctx)) {
      const r = await apply(edit, true);
      assert.equal(r.status, "printed");
      await assert.rejects(stat(edit.file), { code: "ENOENT" });
    }
  }
});

test("--phone and --command reach every launch line, and --http writes URLs for MCP only", async () => {
  const ctx = await context({
    launch: { command: "/usr/local/bin/aster-droid", args: [] },
    phone: "work",
    http: { url: "https://relay.example/p/ph_x/mcp", token: "ast_c_tok" },
  });
  const [agent, server] = plan("zed", ctx);
  assert.deepEqual(agent.value, { type: "custom", command: "/usr/local/bin/aster-droid", args: ["acp", "--phone", "work"], env: {} });
  assert.deepEqual(server.value, { url: "https://relay.example/p/ph_x/mcp", headers: { Authorization: "Bearer ast_c_tok" } });
  assert.deepEqual(plan("claude", ctx)[0].value, { type: "http", url: "https://relay.example/p/ph_x/mcp", headers: { Authorization: "Bearer ast_c_tok" } });
  assert.deepEqual(plan("vscode", ctx)[0].value, { type: "http", url: "https://relay.example/p/ph_x/mcp", headers: { Authorization: "Bearer ast_c_tok" } });
  assert.deepEqual(plan("cursor", { ...ctx, http: null })[0].value, { command: "/usr/local/bin/aster-droid", args: ["mcp", "--phone", "work"] });
});

test("installed() looks for each client's own directory", async () => {
  const ctx = await context();
  assert.equal(installed("cursor", ctx), false);
  await mkdir(path.join(ctx.home, ".cursor"));
  assert.equal(installed("cursor", ctx), true);
  await mkdir(path.join(ctx.home, ".config", "zed"), { recursive: true });
  assert.equal(installed("zed", ctx), true);
});

test("parseJsonc reads comments and trailing commas but not broken JSON", () => {
  assert.deepEqual(parseJsonc('// x\n{"a": "//not a comment", /* y */ "b": [1,],}'), { a: "//not a comment", b: [1] });
  assert.equal(parseJsonc("{"), undefined);
});
