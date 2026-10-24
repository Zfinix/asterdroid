// @ts-check
// `aster-droid mcp`: a stdio MCP server for clients that cannot speak HTTP,
// or that should not hold the token in their own config. Each JSON-RPC line
// from stdin becomes one POST to the phone's `/mcp` (asterctl on the LAN or
// over adb, or the relay), and each reply goes back as one line on stdout.
//
// Stdout carries protocol and nothing else; people read stderr.

import readline from "node:readline";
import { runAdb } from "./adb.js";
import { choose, endpoint, httpReason } from "./phone.js";
import { version } from "./version.js";

/** asterctl gives a verb 120 s and the relay 125 s, so the proxy waits a little longer. */
const CALL_TIMEOUT_MS = 130_000;

/**
 * @param {{
 *   env?: NodeJS.ProcessEnv,
 *   stdin?: NodeJS.ReadableStream,
 *   stdout?: NodeJS.WritableStream,
 *   stderr?: NodeJS.WritableStream,
 *   wanted?: string,
 *   run?: import("./adb.js").Runner,
 *   fetchImpl?: typeof fetch,
 * }} [options]
 * @returns {Promise<number>} the exit code
 */
export function runMcp(options = {}) {
  const { env = process.env, stdin = process.stdin, stdout = process.stdout, stderr = process.stderr, wanted, run = runAdb } = options;
  const fetchImpl = options.fetchImpl ?? fetch;
  return new Promise((resolve) => {
    /** @type {{ url: string, token: string } | null} */
    let target = null;
    let problem = "";
    /** @type {string | null} */
    let session = null;
    /** @type {string | null} */
    let protocol = null;
    let warned = false;
    /** Requests wait for an `initialize` that is still in flight, so the session id is known. */
    /** @type {Promise<unknown>} */
    let gate = Promise.resolve();
    const inflight = new Set();

    const log = (/** @type {string} */ m) => stderr.write(`aster-droid: ${m}\n`);
    const write = (/** @type {unknown} */ msg) => stdout.write(`${JSON.stringify(msg)}\n`);

    const ready = choose(wanted, env).then(async (r) => {
      if (!r.phone) {
        problem = r.problem ?? "";
        return;
      }
      try {
        target = await endpoint(r.phone, "mcp", run);
      } catch (e) {
        problem = String(/** @type {Error} */ (e).message);
        return;
      }
      if (!target) problem = `${r.phone.name} has no key for other agents saved. Pair again and give the other agents' key.`;
    }).catch((e) => {
      problem = String(e?.message ?? e);
    });

    const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });
    rl.on("line", (line) => {
      if (line.trim() === "") return;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
        return;
      }
      const before = gate;
      const job = ready.then(() => before).then(() => dispatch(msg)).catch((e) => {
        const message = String(e?.message ?? e);
        log(message);
        failAll(msg, message);
      });
      if (!Array.isArray(msg) && msg?.method === "initialize") gate = job;
      inflight.add(job);
      job.finally(() => inflight.delete(job));
    });
    rl.on("close", async () => {
      await ready;
      while (inflight.size > 0) await Promise.allSettled([...inflight]);
      resolve(0);
    });

    /** Answers every request in `msg` with the same error; notifications need no answer. */
    function failAll(/** @type {any} */ msg, /** @type {string} */ message) {
      for (const m of Array.isArray(msg) ? msg : [msg]) {
        if (m && typeof m.method === "string" && m.id !== undefined) {
          write({ jsonrpc: "2.0", id: m.id, error: { code: -32000, message } });
        }
      }
    }

    async function dispatch(/** @type {any} */ msg) {
      if (!target) {
        if (!warned) log(problem);
        warned = true;
        return failAll(msg, problem);
      }
      /** @type {Record<string, string>} */
      const headers = {
        authorization: `Bearer ${target.token}`,
        "content-type": "application/json",
        accept: "application/json, text/event-stream",
        "user-agent": `aster-droid/${version}`,
      };
      if (session) headers["mcp-session-id"] = session;
      if (protocol) headers["mcp-protocol-version"] = protocol;
      let response;
      try {
        response = await fetchImpl(target.url, {
          method: "POST",
          headers,
          body: JSON.stringify(msg),
          signal: AbortSignal.timeout(CALL_TIMEOUT_MS),
        });
      } catch (e) {
        const err = /** @type {Error & { cause?: { code?: string, message?: string } }} */ (e);
        const why = err.name === "TimeoutError" ? "no answer within 130 s" : err.cause?.code ?? err.cause?.message ?? err.message;
        const message = `Cannot reach the phone at ${target.url}: ${why}.`;
        log(message);
        return failAll(msg, message);
      }
      session = response.headers.get("mcp-session-id") ?? session;
      let text;
      try {
        text = await response.text();
      } catch (e) {
        const err = /** @type {Error} */ (e);
        const message = `Lost the answer from the phone at ${target.url}: ${err.name === "TimeoutError" ? "no answer within 130 s" : err.message}.`;
        log(message);
        return failAll(msg, message);
      }
      if (response.status === 202 || response.status === 204) return;
      if (!response.ok) {
        let detail = "";
        try {
          const body = JSON.parse(text);
          detail = typeof body?.message === "string" ? body.message : "";
        } catch {}
        const message = response.status === 401 || !detail ? httpReason(response.status) : detail;
        log(message);
        return failAll(msg, message);
      }
      const type = response.headers.get("content-type") ?? "";
      const bodies = type.includes("text/event-stream") ? events(text) : [text];
      for (const body of bodies) {
        if (body.trim() === "") continue;
        let reply;
        try {
          reply = JSON.parse(body);
        } catch {
          log("the phone answered with something that is not JSON; dropped it");
          continue;
        }
        if (!Array.isArray(msg) && msg.method === "initialize" && typeof reply?.result?.protocolVersion === "string") {
          protocol = reply.result.protocolVersion;
        }
        write(reply);
      }
    }
  });
}

/** The `data` of each event in a server-sent event stream. */
export function events(/** @type {string} */ text) {
  const out = [];
  for (const block of text.replace(/\r\n/g, "\n").split("\n\n")) {
    const data = block
      .split("\n")
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5).replace(/^ /, ""));
    if (data.length > 0) out.push(data.join("\n"));
  }
  return out;
}
