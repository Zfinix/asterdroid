// @ts-check
// The ways a computer pairs with a phone, and what each one saves:
//
// - QR: this computer shows a claim link as a QR, the phone scans it in
//   Droid, and the relay hands back a token (docs/RELAY.md, b2).
// - Code: the phone shows a short code or a pair link, and this computer
//   trades it for a token once the person taps Allow on the phone (b1).
// - LAN and adb: free and relay-less. The person pastes the keys Droid shows
//   for other agents and editors, and this computer talks to asterctl directly.
//
// Aster Relay is coming soon, so a bare `pair` uses adb when it sees one phone
// and otherwise explains the free paths; only a relay someone names is dialled.

import { generateKeyPairSync, randomBytes } from "node:crypto";
import os from "node:os";
import readline from "node:readline";
import qrcode from "qrcode-terminal";
import WebSocket from "ws";
import { device, forward, runAdb } from "./adb.js";
import { ACP_PORT, b64url, DEFAULT_RELAY, bracketed, checkToken, isPrivateHost, MCP_PORT, relayOrigin } from "./net.js";
import { httpReason } from "./phone.js";
import { addPhone } from "./store.js";
import { version } from "./version.js";

/** @typedef {import("./store.js").Phone} Phone */
/** @typedef {NodeJS.WritableStream} Out */

const PHONE_ID = /^ph_[a-z2-7]{26}$/;
const KIND = "mcp-shim";
/** A scanned QR's grant is kept for five minutes, so waiting longer gains nothing. */
const QR_WAIT_MS = 5 * 60_000;
/** The claim waits 120 s for Allow on the phone; this leaves room for the network. */
const CLAIM_TIMEOUT_MS = 135_000;

/** The name the phone shows when it asks whether to allow this computer. */
export function clientName() {
  return `aster-droid on ${os.hostname().replace(/\.local$/, "")}`.slice(0, 80);
}

/**
 * `https://<relay>/claim#1.<client_pub>.<nonce>.<name>`, plus the key it names.
 * The key is an X25519 pair kept for end-to-end encryption, which the relay
 * does not do yet; the phone stores the public half with the client.
 */
export function claimLink(/** @type {string} */ origin, name = clientName()) {
  const { publicKey, privateKey } = generateKeyPairSync("x25519");
  const pub = /** @type {string} */ (publicKey.export({ format: "jwk" }).x);
  const secret = /** @type {string} */ (privateKey.export({ format: "jwk" }).d);
  const nonce = b64url(randomBytes(16));
  return { link: `${origin}/claim#1.${pub}.${nonce}.${b64url(name)}`, nonce, secret };
}

/**
 * Pairing b2: show a QR and wait for the phone to scan it.
 * @param {{ env?: NodeJS.ProcessEnv, relay: string, name?: string, out: Out, fetchImpl?: typeof fetch,
 *   waitMs?: number, render?: (text: string) => Promise<string> }} o
 */
export async function pairQr(o) {
  const origin = relayOrigin(o.relay);
  const fetchImpl = o.fetchImpl ?? fetch;
  const render = o.render ?? qr;
  const { link, nonce, secret } = claimLink(origin);
  o.out.write("Open Droid on your phone, go to Reach from anywhere, tap Scan a computer and scan this code.\n\n");
  o.out.write(await render(link));
  o.out.write(`\n${link}\n\nWaiting for the phone. Press Ctrl+C to stop.\n`);
  o.out.write("No camera handy? Run aster-droid pair --code and type the code Droid shows instead.\n");
  const deadline = Date.now() + (o.waitMs ?? QR_WAIT_MS);
  let warned = false;
  while (Date.now() < deadline) {
    let response;
    try {
      response = await fetchImpl(`${origin}/v1/pair/wait/${nonce}`, {
        headers: { "user-agent": `aster-droid/${version}` },
        signal: AbortSignal.timeout(130_000),
      });
    } catch (e) {
      if (!warned) o.out.write(`Cannot reach ${origin} (${String(/** @type {Error} */ (e).message)}). Still trying.\n`);
      warned = true;
      await sleep(3000);
      continue;
    }
    if (response.status === 408) {
      await response.body?.cancel();
      continue;
    }
    if (response.status === 429) {
      await response.body?.cancel();
      await sleep(Math.min(60, Number(response.headers.get("retry-after")) || 60) * 1000);
      continue;
    }
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Error(`The relay refused the wait: ${body?.message ?? httpReason(response.status)}`);
    const phone = relayPhone(body, origin, o.name);
    phone.client_key = secret;
    await addPhone(phone, o.env);
    return phone;
  }
  throw new Error("The phone did not scan the code in time. Run aster-droid pair again for a new one.");
}

/** A code typed from Droid (`7K2Q-9MXD`), or the pair link Droid shares, `<relay>/pair#1.<phoneId>.<code>.<fp>`. */
export function parseCode(/** @type {string} */ input) {
  const text = input.trim();
  const hash = text.indexOf("/pair#");
  if (hash < 0) return { code: text.replace(/\s+/g, ""), phone: null, fp: null, origin: null };
  const parts = text.slice(hash + "/pair#".length).split(".");
  if (parts.length !== 4 || parts[0] !== "1" || !PHONE_ID.test(parts[1])) {
    throw new Error("That is not a Droid pair link.");
  }
  return { code: parts[2], phone: parts[1], fp: parts[3], origin: relayOrigin(text.slice(0, hash)) };
}

const CLAIM_ERRORS = /** @type {Record<number, string>} */ ({
  402: "Aster Relay is not active for this phone. Turn it on in Droid's settings.",
  403: "The phone said no. Nothing was saved.",
  404: "That code is wrong or has expired. A code works once and lasts five minutes; make a new one in Droid.",
  408: "Nobody tapped Allow on the phone within two minutes. Make a new code in Droid and try again.",
  409: "The phone already has 20 computers paired. Revoke one in Droid first.",
  429: "Too many tries from this network. Wait a minute and try again.",
  503: "The phone is offline. Open Droid on it and try again.",
});

/**
 * Pairing b1: trade the phone's code for a token once the person taps Allow.
 * @param {{ env?: NodeJS.ProcessEnv, relay?: string, code: string, name?: string, out: Out, fetchImpl?: typeof fetch }} o
 */
export async function pairCode(o) {
  const parsed = parseCode(o.code);
  const env = o.env ?? process.env;
  const origin = o.relay ? relayOrigin(o.relay) : (parsed.origin ?? relayOrigin(env.ASTER_RELAY_URL || DEFAULT_RELAY));
  if (!/^[0-9A-Za-z-]{8,12}$/.test(parsed.code)) throw new Error("A pairing code is 8 letters and digits, like 7K2Q-9MXD.");
  const fetchImpl = o.fetchImpl ?? fetch;
  o.out.write("Check your phone and tap Allow.\n");
  /** @type {Record<string, unknown>} */
  const body = { code: parsed.code, client: { name: clientName(), kind: KIND } };
  if (parsed.phone) body.phone = parsed.phone;
  const response = await fetchImpl(`${origin}/v1/pair/claim`, {
    method: "POST",
    headers: { "content-type": "application/json", "user-agent": `aster-droid/${version}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(CLAIM_TIMEOUT_MS),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new Error(CLAIM_ERRORS[response.status] ?? `The relay refused the code: ${result?.message ?? httpReason(response.status)}`);
  if (parsed.phone && result?.phone_id !== parsed.phone) {
    throw new Error("The relay answered for a different phone than the link names. Nothing was saved.");
  }
  if (parsed.fp && result?.phone_fp !== parsed.fp) {
    throw new Error("The phone's fingerprint does not match the link. Nothing was saved.");
  }
  const phone = relayPhone(result, origin, o.name);
  await addPhone(phone, o.env);
  return phone;
}

/**
 * The saved pairing for a claim result, checked so that the token only ever
 * goes back to the relay it came from.
 * @returns {Phone}
 */
export function relayPhone(/** @type {any} */ result, /** @type {string} */ origin, /** @type {string | undefined} */ name) {
  if (!result || typeof result !== "object" || !PHONE_ID.test(String(result.phone_id))) {
    throw new Error("The relay sent back something that is not a pairing.");
  }
  const token = String(result.token ?? "");
  if (!token.startsWith("ast_c_")) throw new Error("The relay sent back a pairing without a token.");
  const host = new URL(origin).host;
  /** @param {unknown} url @param {string[]} schemes */
  const same = (url, schemes) => {
    if (typeof url !== "string") return null;
    const u = new URL(url);
    if (u.host !== host || !schemes.includes(u.protocol)) throw new Error(`The relay pointed this computer at ${u.host}, not ${host}. Nothing was saved.`);
    return url;
  };
  const scopes = Array.isArray(result.scopes) ? result.scopes.map(String) : [];
  const mcp = same(result.endpoints?.mcp, ["https:", "http:"]);
  const acp = scopes.includes("agent:chat") ? same(result.endpoints?.acp, ["wss:", "ws:"]) : null;
  /** @type {Phone} */
  const phone = {
    id: result.phone_id,
    name: name || `phone ${result.phone_id.slice(3, 9)}`,
    via: "relay",
    added: Math.floor(Date.now() / 1000),
    relay: origin,
    client_id: String(result.client_id ?? ""),
    scopes,
    phone_fp: typeof result.phone_fp === "string" ? result.phone_fp : null,
  };
  if (mcp) phone.mcp = { url: mcp, token };
  if (acp) phone.acp = { url: acp, token };
  return phone;
}

/**
 * @typedef {{ env?: NodeJS.ProcessEnv, name?: string, out: Out, mcpToken?: string, acpToken?: string,
 *   mcpPort?: number, acpPort?: number, check?: boolean, fetchImpl?: typeof fetch,
 *   input?: NodeJS.ReadableStream & { isTTY?: boolean } }} Direct
 */

/** The two keys, from flags or pasted at a prompt. */
async function keys(/** @type {Direct} */ o) {
  let mcp = o.mcpToken;
  let acp = o.acpToken;
  const input = o.input ?? process.stdin;
  if (mcp === undefined && acp === undefined) {
    if (!input.isTTY) throw new Error("Pass the keys with --token, or --mcp-token and --acp-token.");
    o.out.write("Droid shows these keys under Connect a computer. Press Enter to skip one.\n");
    mcp = (await ask("Other agents' key (MCP): ", { input, secret: true })) || undefined;
    acp = (await ask("Editors' key (ACP): ", { input, secret: true })) || undefined;
  }
  if (!mcp && !acp) throw new Error("Give at least one key: the other agents' key for MCP or the editors' key for ACP.");
  return {
    mcp: mcp ? checkToken(mcp, "other agents' key") : undefined,
    acp: acp ? checkToken(acp, "editors' key") : undefined,
  };
}

/**
 * The phone on this network, through asterctl's own MCP and ACP servers.
 * They listen on the phone's loopback unless Droid's addresses are set to the
 * Wi-Fi address or 0.0.0.0.
 * @param {Direct & { host: string }} o
 */
export async function pairLan(o) {
  const host = o.host.trim().replace(/^\[|\]$/g, "");
  if (!isPrivateHost(host)) {
    throw new Error(`${host} is not a private address. Keys travel in plain http on the LAN, so only addresses like 192.168.x.x, 10.x.x.x or a Tailscale 100.x address are allowed. Use the relay to reach a phone elsewhere.`);
  }
  const mcpUrl = `http://${bracketed(host)}:${o.mcpPort ?? MCP_PORT}/mcp`;
  const acpUrl = `ws://${bracketed(host)}:${o.acpPort ?? ACP_PORT}/acp`;
  try {
    new URL(mcpUrl);
    new URL(acpUrl);
  } catch {
    throw new Error(`${host} cannot be used in a URL; drop the %zone and use the phone's Wi-Fi address.`);
  }
  const k = await keys(o);
  if (o.check !== false) await check(k, mcpUrl, acpUrl, o, "lan");
  /** @type {Phone} */
  const phone = { id: `lan:${host}`, name: o.name || `phone at ${host}`, via: "lan", added: Math.floor(Date.now() / 1000), host };
  if (k.mcp) phone.mcp = { url: mcpUrl, token: k.mcp };
  if (k.acp) phone.acp = { url: acpUrl, token: k.acp };
  await addPhone(phone, o.env);
  return phone;
}

/**
 * The phone on a USB cable, through `adb forward` to asterctl on its loopback.
 * @param {Direct & { serial?: string, run?: import("./adb.js").Runner }} o
 */
export async function pairAdb(o) {
  const run = o.run ?? runAdb;
  const serial = await device(o.serial, run);
  const k = await keys(o);
  const mcpPort = o.mcpPort ?? MCP_PORT;
  const acpPort = o.acpPort ?? ACP_PORT;
  if (o.check !== false) {
    const mcpUrl = k.mcp ? `http://127.0.0.1:${await forward(serial, mcpPort, run)}/mcp` : "";
    const acpUrl = k.acp ? `ws://127.0.0.1:${await forward(serial, acpPort, run)}/acp` : "";
    await check(k, mcpUrl, acpUrl, o, "adb");
  }
  /** @type {Phone} */
  const phone = {
    id: `adb:${serial}`,
    name: o.name || `phone over USB (${serial})`,
    via: "adb",
    added: Math.floor(Date.now() / 1000),
    adb: { serial, mcp_port: mcpPort, acp_port: acpPort },
  };
  if (k.mcp) phone.mcp = { token: k.mcp };
  if (k.acp) phone.acp = { token: k.acp };
  await addPhone(phone, o.env);
  return phone;
}

/** Why a bare `pair` cannot reach Aster Relay yet, and the ways that work today. */
export const RELAY_SOON = `Aster Relay, for pairing from anywhere, is coming soon. Until then, pair over Wi-Fi or USB:

  aster-droid pair --lan <phone ip>   The address and keys are under Connect a computer in Droid.
  aster-droid pair --adb              With the phone on a USB cable and USB debugging allowed.
`;

/**
 * The one phone adb sees, or why there is none. adb missing from PATH counts
 * as none, so a bare `pair` can fall back to explaining the other ways.
 * @returns {Promise<{ serial: string } | { why: string | null }>}
 */
export async function onUsb(/** @type {string | undefined} */ wanted, run = runAdb) {
  try {
    return { serial: await device(wanted, run) };
  } catch (e) {
    const why = String(/** @type {Error} */ (e).message ?? e);
    return { why: why.startsWith("adb is not installed") ? null : why };
  }
}

/** Tries each key once, so a typo shows up now rather than in an editor's log. */
async function check(
  /** @type {{ mcp?: string, acp?: string }} */ k,
  /** @type {string} */ mcpUrl,
  /** @type {string} */ acpUrl,
  /** @type {Direct} */ o,
  /** @type {"lan" | "adb"} */ via,
) {
  const hint = (/** @type {string} */ what) =>
    via === "lan"
      ? ` In Droid, set ${what} to 0.0.0.0 so it listens on Wi-Fi, and check that this computer is on the same network.`
      : " Check that Droid is running and that the access is turned on in its settings.";
  if (k.mcp) {
    o.out.write(`Checking ${mcpUrl}\n`);
    await checkMcp(mcpUrl, k.mcp, o.fetchImpl ?? fetch, hint("Other agents' address"));
  }
  if (k.acp) {
    o.out.write(`Checking ${acpUrl}\n`);
    await checkAcp(acpUrl, k.acp, hint("Editors' address"));
  }
}

/** One MCP `ping`. */
export async function checkMcp(/** @type {string} */ url, /** @type {string} */ token, fetchImpl = fetch, hint = "") {
  let response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: "aster-droid:check", method: "ping" }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new Error(`Nothing answered at ${url}.${hint}`);
  }
  await response.body?.cancel();
  if (response.status === 401) throw new Error("The phone refused the other agents' key. Copy it again from Droid.");
  if (!response.ok) throw new Error(`${url}: ${httpReason(response.status)}`);
}

/** Opens and closes one ACP socket. */
export function checkAcp(/** @type {string} */ url, /** @type {string} */ token, hint = "") {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers: { authorization: `Bearer ${token}` }, handshakeTimeout: 10_000 });
    let done = false;
    ws.on("unexpected-response", (_req, res) => {
      done = true;
      ws.terminate();
      reject(new Error(res.statusCode === 401 ? "The phone refused the editors' key. Copy it again from Droid." : `${url}: ${httpReason(res.statusCode ?? 0)}`));
    });
    ws.on("open", () => {
      done = true;
      ws.close(1000);
      resolve(undefined);
    });
    ws.on("error", () => {
      if (done) return;
      done = true;
      reject(new Error(`Nothing answered at ${url}.${hint}`));
    });
  });
}

/** @returns {Promise<string>} */
function qr(/** @type {string} */ text) {
  return new Promise((resolve) => qrcode.generate(text, { small: true }, (/** @type {string} */ out) => resolve(`${out}\n`)));
}

function sleep(/** @type {number} */ ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * One line from the person. A secret is not echoed.
 * @param {string} question
 * @param {{ input?: NodeJS.ReadableStream, output?: NodeJS.WritableStream, secret?: boolean }} [o]
 * @returns {Promise<string>}
 */
export function ask(question, o = {}) {
  const input = o.input ?? process.stdin;
  const output = o.output ?? process.stderr;
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input, output, terminal: Boolean(/** @type {any} */ (input).isTTY) });
    if (o.secret) {
      /** @type {any} */ (rl)._writeToOutput = (/** @type {string} */ s) => {
        if (s.includes(question)) output.write(question);
      };
    }
    rl.question(question, (answer) => {
      rl.close();
      if (o.secret) output.write("\n");
      resolve(answer.trim());
    });
  });
}
