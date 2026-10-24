// @ts-check
// `aster-droid setup <client>`: adds Droid to an MCP client or ACP editor's
// own config file. An entry that is already there is never replaced, a file
// with comments is never rewritten (its comments would be lost), and
// anything that cannot be edited safely is printed for the person to paste.
//
// By default every entry starts this CLI over stdio, so the token stays in
// this computer's credentials file and never lands in an editor's config.
// `--http` writes the phone's URL and token straight into the config instead.

import { existsSync } from "node:fs";
import { chmod, mkdir, readFile, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import path from "node:path";

export const CLIENTS = /** @type {const} */ (["claude", "codex", "cursor", "zed", "jetbrains", "vscode"]);
/** @typedef {typeof CLIENTS[number]} Client */

/** The MCP server's name in each config, and the ACP agent's label. */
export const SERVER = "droid";
export const AGENT = "Droid";

/**
 * @typedef {{
 *   home: string,
 *   platform: NodeJS.Platform,
 *   env: NodeJS.ProcessEnv,
 *   launch: { command: string, args: string[] },
 *   phone?: string,
 *   http?: { url: string, token: string } | null,
 * }} Context
 * @typedef {{ client: Client, label: string, file: string, format: "json", path: string[], value: unknown, secret: boolean }
 *   | { client: Client, label: string, file: string, format: "toml", table: string, value: Record<string, unknown>, secret: boolean }} Edit
 * @typedef {{ status: "added" | "present" | "kept" | "manual" | "printed", edit: Edit, snippet: string, note?: string }} Result
 */

/**
 * How a config starts this CLI. On Windows npx is npx.cmd, which clients that
 * spawn without a shell cannot start, so it goes through cmd /c.
 * @param {NodeJS.Platform} platform
 * @param {string} [command]
 */
export function launchCommand(platform, command) {
  if (command) return { command, args: [] };
  const npx = ["npx", "-y", "@withaster/droid"];
  return platform === "win32" ? { command: "cmd", args: ["/c", ...npx] } : { command: npx[0], args: npx.slice(1) };
}

/** @param {Context} ctx */
function dirs(ctx) {
  const { home, platform, env } = ctx;
  const appData = env.APPDATA || path.join(home, "AppData", "Roaming");
  const xdg = env.XDG_CONFIG_HOME || path.join(home, ".config");
  const appSupport = path.join(home, "Library", "Application Support");
  return {
    claude: env.CLAUDE_CONFIG_DIR || home,
    codex: env.CODEX_HOME || path.join(home, ".codex"),
    cursor: path.join(home, ".cursor"),
    zed: platform === "win32" ? path.join(appData, "Zed") : path.join(xdg, "zed"),
    jetbrains: path.join(home, ".jetbrains"),
    jetbrainsApps: platform === "darwin" ? path.join(appSupport, "JetBrains") : platform === "win32" ? path.join(appData, "JetBrains") : path.join(xdg, "JetBrains"),
    vscode: platform === "darwin" ? path.join(appSupport, "Code", "User") : platform === "win32" ? path.join(appData, "Code", "User") : path.join(xdg, "Code", "User"),
  };
}

/** Whether the client looks installed, so `setup all` skips the rest. */
export function installed(/** @type {Client} */ client, /** @type {Context} */ ctx) {
  const d = dirs(ctx);
  switch (client) {
    case "claude":
      return existsSync(path.join(d.claude, ".claude.json")) || existsSync(path.join(ctx.home, ".claude"));
    case "jetbrains":
      return existsSync(d.jetbrains) || existsSync(d.jetbrainsApps);
    default:
      return existsSync(d[client]);
  }
}

/**
 * The edits that add Droid to `client`.
 * @param {Client} client
 * @param {Context} ctx
 * @returns {Edit[]}
 */
export function plan(client, ctx) {
  const d = dirs(ctx);
  const { command, args } = ctx.launch;
  const extra = ctx.phone ? ["--phone", ctx.phone] : [];
  const mcpArgs = [...args, "mcp", ...extra];
  const acpArgs = [...args, "acp", ...extra];
  const http = ctx.http ?? null;
  const headers = http ? { Authorization: `Bearer ${http.token}` } : null;
  const secret = http !== null;
  switch (client) {
    case "claude":
      return [{
        client, label: "Claude Code MCP server", file: path.join(d.claude, ".claude.json"), format: "json", path: ["mcpServers", SERVER], secret,
        value: http ? { type: "http", url: http.url, headers } : { type: "stdio", command, args: mcpArgs, env: {} },
      }];
    case "codex":
      return [{
        client, label: "Codex MCP server", file: path.join(d.codex, "config.toml"), format: "toml", table: `mcp_servers.${SERVER}`, secret,
        value: http ? { url: http.url, http_headers: headers ?? {} } : { command, args: mcpArgs },
      }];
    case "cursor":
      return [{
        client, label: "Cursor MCP server", file: path.join(d.cursor, "mcp.json"), format: "json", path: ["mcpServers", SERVER], secret,
        value: http ? { url: http.url, headers } : { command, args: mcpArgs },
      }];
    case "zed":
      return [
        {
          client, label: "Zed external agent", file: path.join(d.zed, "settings.json"), format: "json", path: ["agent_servers", AGENT], secret: false,
          value: { type: "custom", command, args: acpArgs, env: {} },
        },
        {
          client, label: "Zed MCP server", file: path.join(d.zed, "settings.json"), format: "json", path: ["context_servers", SERVER], secret,
          value: http ? { url: http.url, headers } : { command, args: mcpArgs, env: {} },
        },
      ];
    case "jetbrains":
      return [{
        client, label: "JetBrains ACP agent", file: path.join(d.jetbrains, "acp.json"), format: "json", path: ["agent_servers", AGENT], secret: false,
        value: { command, args: acpArgs, env: {} },
      }];
    case "vscode":
      return [
        {
          client, label: "VS Code MCP server", file: path.join(d.vscode, "mcp.json"), format: "json", path: ["servers", SERVER], secret,
          value: http ? { type: "http", url: http.url, headers } : { type: "stdio", command, args: mcpArgs },
        },
        {
          client, label: "VS Code ACP Client extension agent", file: path.join(d.vscode, "settings.json"), format: "json", path: ["acp.agents", AGENT], secret: false,
          value: { command, args: acpArgs, env: {} },
        },
      ];
  }
}

/** @param {Edit} edit */
export function snippet(edit) {
  if (edit.format === "toml") return tomlTable(edit.table, edit.value);
  /** @type {unknown} */
  let value = edit.value;
  for (const k of [...edit.path].reverse()) value = { [k]: value };
  return JSON.stringify(value, null, 2);
}

/**
 * Applies one edit, or with `print` only shows it.
 * @param {Edit} edit
 * @returns {Promise<Result>}
 */
export async function apply(edit, print = false) {
  const text = snippet(edit);
  if (print) return { status: "printed", edit, snippet: text };
  const current = await readFile(edit.file, "utf8").catch((e) => {
    if (e.code === "ENOENT") return null;
    throw e;
  });
  return edit.format === "toml" ? applyToml(edit, current, text) : applyJson(edit, current, text);
}

/**
 * @param {Extract<Edit, { format: "json" }>} edit
 * @param {string | null} current
 * @param {string} text
 * @returns {Promise<Result>}
 */
async function applyJson(edit, current, text) {
  const source = (current ?? "").replace(/^﻿/, "");
  /** @type {any} */
  let data = {};
  if (source.trim() !== "") {
    try {
      data = JSON.parse(source);
    } catch {
      const loose = parseJsonc(source);
      if (loose !== undefined && lookup(loose, edit.path) !== undefined) {
        return { status: "present", edit, snippet: text, note: "already has an entry" };
      }
      return { status: "manual", edit, snippet: text, note: loose === undefined ? "is not valid JSON" : "has comments, which rewriting would lose" };
    }
  }
  if (!isObject(data)) return { status: "manual", edit, snippet: text, note: "is not a JSON object" };
  let node = data;
  for (const k of edit.path.slice(0, -1)) {
    if (node[k] === undefined) node[k] = {};
    if (!isObject(node[k])) return { status: "manual", edit, snippet: text, note: `has a "${k}" that is not an object` };
    node = node[k];
  }
  const leaf = edit.path[edit.path.length - 1];
  if (node[leaf] !== undefined) {
    return equal(node[leaf], edit.value)
      ? { status: "present", edit, snippet: text }
      : { status: "kept", edit, snippet: text, note: `already has "${edit.path.join(".")}"; left it as it is` };
  }
  node[leaf] = edit.value;
  const indent = /^[{[]\s*\n([ \t]+)/.exec(source)?.[1] ?? "  ";
  await writeSafely(edit.file, `${JSON.stringify(data, null, indent)}\n`, current !== null, edit.secret);
  return { status: "added", edit, snippet: text };
}

/**
 * @param {Extract<Edit, { format: "toml" }>} edit
 * @param {string | null} current
 * @param {string} text
 * @returns {Promise<Result>}
 */
async function applyToml(edit, current, text) {
  const source = current ?? "";
  const [outer] = edit.table.split(".");
  const [parent, name] = edit.table.split(".").map((k) => `(?:"${k}"|'${k}'|${k})`);
  const kept = /** @type {Result} */ ({ status: "kept", edit, snippet: text, note: `already has [${edit.table}]; left it as it is` });
  const header = new RegExp(`^\\s*\\[\\s*${parent}\\s*\\.\\s*${name}\\s*\\]`, "m");
  const dotted = new RegExp(`^\\s*${parent}\\s*\\.\\s*${name}\\s*[.=]`, "m");
  if (header.test(source) || dotted.test(source)) return kept;
  const inline = new RegExp(`^\\s*${parent}\\s*=(.*)$`, "m").exec(source);
  if (inline) {
    if (new RegExp(`[{,]\\s*${name}\\s*[.=]`).test(inline[1])) return kept;
    return { status: "manual", edit, snippet: text, note: `has an inline ${outer} table, which a new table cannot extend` };
  }
  const parentHeader = new RegExp(`^\\s*\\[\\s*${parent}\\s*\\]`);
  const entry = new RegExp(`^\\s*${name}\\s*[.=]`);
  let inParent = false;
  for (const line of source.split(/\r?\n/)) {
    if (/^\s*\[/.test(line)) inParent = parentHeader.test(line);
    else if (inParent && entry.test(line)) return kept;
  }
  const gap = source === "" ? "" : source.endsWith("\n\n") ? "" : source.endsWith("\n") ? "\n" : "\n\n";
  await writeSafely(edit.file, `${source}${gap}${text}\n`, current !== null, edit.secret);
  return { status: "added", edit, snippet: text };
}

/** `[table]` with each value as TOML. JSON strings are valid TOML basic strings. */
export function tomlTable(/** @type {string} */ table, /** @type {Record<string, unknown>} */ value) {
  const lines = [`[${table}]`];
  for (const [k, v] of Object.entries(value)) lines.push(`${k} = ${tomlValue(v)}`);
  return lines.join("\n");
}

/** @returns {string} */
function tomlValue(/** @type {unknown} */ v) {
  if (Array.isArray(v)) return `[${v.map(tomlValue).join(", ")}]`;
  if (isObject(v)) return `{ ${Object.entries(v).map(([k, x]) => `${/^[A-Za-z0-9_-]+$/.test(k) ? k : JSON.stringify(k)} = ${tomlValue(x)}`).join(", ")} }`;
  if (typeof v === "string") return JSON.stringify(v);
  return String(v);
}

/**
 * Replaces the file atomically, through a symlink to its real target, with
 * the mode it had. A new file holding a token is 0600.
 */
async function writeSafely(/** @type {string} */ file, /** @type {string} */ text, /** @type {boolean} */ existed, /** @type {boolean} */ secret) {
  const target = existed ? await realpath(file) : file;
  await mkdir(path.dirname(target), { recursive: true });
  const mode = existed ? (await stat(target)).mode & 0o777 : secret ? 0o600 : 0o644;
  const temp = path.join(path.dirname(target), `.${path.basename(target)}.${randomBytes(6).toString("hex")}`);
  try {
    await writeFile(temp, text, { mode, flag: "wx" });
    if (process.platform !== "win32") await chmod(temp, mode);
    await rename(temp, target);
  } catch (e) {
    await rm(temp, { force: true });
    throw e;
  }
}

/** JSON with comments and trailing commas, as Zed and VS Code allow, or undefined. */
export function parseJsonc(/** @type {string} */ text) {
  let out = "";
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"') j += text[j] === "\\" ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j;
    } else if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
      out += "\n";
    } else if (c === "/" && text[i + 1] === "*") {
      const end = text.indexOf("*/", i + 2);
      i = end < 0 ? text.length : end + 1;
    } else {
      out += c;
    }
  }
  try {
    return JSON.parse(out.replace(/,(\s*[}\]])/g, "$1"));
  } catch {
    return undefined;
  }
}

/** @returns {unknown} */
function lookup(/** @type {any} */ data, /** @type {string[]} */ keys) {
  let node = data;
  for (const k of keys) {
    if (!isObject(node)) return undefined;
    node = node[k];
  }
  return node;
}

/** @returns {v is Record<string, any>} */
function isObject(/** @type {unknown} */ v) {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** @returns {boolean} */
function equal(/** @type {unknown} */ a, /** @type {unknown} */ b) {
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => equal(x, b[i]));
  if (isObject(a) && isObject(b)) {
    const ka = Object.keys(a);
    return ka.length === Object.keys(b).length && ka.every((k) => equal(a[k], b[k]));
  }
  return a === b;
}
