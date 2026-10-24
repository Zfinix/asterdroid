// @ts-check
// `aster-droid acp`: what Zed and JetBrains launch. Every ACP client starts
// its agent as a local process and speaks newline-delimited JSON-RPC on
// stdio, so this pipes those lines to the phone's `/acp` WebSocket (asterctl
// on the LAN or over adb, or the relay) one text message per line.
//
// Stdout carries protocol and nothing else; everything for people goes to
// stderr, which editors show in their logs.
//
// Before pairing it still answers `initialize`, offering a terminal auth
// method, so the editor can run `aster-droid pair` for the person and then
// call `authenticate`, after which the bridge connects and replays the
// editor's `initialize` to the phone.

import readline from "node:readline";
import WebSocket from "ws";
import { runAdb } from "./adb.js";
import { choose, closeReason, endpoint, httpReason } from "./phone.js";
import { version } from "./version.js";

/** The terminal auth method. Clients append `args` to the agent's own launch command. */
export const PAIR_AUTH = {
  id: "pair",
  name: "Pair with your phone",
  description: "Pairs over USB when adb sees your phone, or says how to pair over Wi-Fi.",
  type: "terminal",
  args: ["--pair"],
};

/** ACP's "authentication required" error. */
export const AUTH_REQUIRED = -32000;
const INIT_ID = "aster-droid:init";

/**
 * @param {{
 *   env?: NodeJS.ProcessEnv,
 *   stdin?: NodeJS.ReadableStream,
 *   stdout?: NodeJS.WritableStream,
 *   stderr?: NodeJS.WritableStream,
 *   wanted?: string,
 *   run?: import("./adb.js").Runner,
 * }} [options]
 * @returns {Promise<number>} the exit code
 */
export function runAcp(options = {}) {
  const { env = process.env, stdin = process.stdin, stdout = process.stdout, stderr = process.stderr, wanted, run = runAdb } = options;
  return new Promise((resolve) => {
    /** @type {import("./store.js").Phone | null} */
    let phone = null;
    let problem = "";
    /** @type {WebSocket | null} */
    let ws = null;
    let connecting = false;
    let open = false;
    let ending = false;
    let finished = false;
    /** @type {string | null} */
    let failure = null;
    /** @type {unknown} */
    let initParams = null;
    let replayInit = false;
    /** @type {string[]} */
    const queue = [];
    /** Requests from the editor still waiting for the phone, so a drop can answer them. */
    const pending = new Map();
    /** Ids of the editor's `initialize` requests, whose answers get our auth method. */
    const initIds = new Set();

    const log = (/** @type {string} */ m) => stderr.write(`aster-droid: ${m}\n`);
    const write = (/** @type {unknown} */ msg) => stdout.write(`${JSON.stringify(msg)}\n`);
    const reply = (/** @type {unknown} */ id, /** @type {unknown} */ result) => write({ jsonrpc: "2.0", id, result });
    const fail = (/** @type {unknown} */ id, /** @type {number} */ code, /** @type {string} */ message) =>
      write({ jsonrpc: "2.0", id, error: { code, message } });

    const rl = readline.createInterface({ input: stdin, crlfDelay: Infinity });
    /** @type {Promise<unknown>} */
    let chain = Promise.resolve();
    const ready = choose(wanted, env).then((r) => {
      phone = r.phone;
      problem = r.problem ?? "";
    }).catch((e) => {
      phone = null;
      problem = String(e?.message ?? e);
    });

    rl.on("line", (line) => {
      chain = chain.then(() => ready).then(() => handle(line)).catch((e) => log(String(e?.message ?? e)));
    });
    rl.on("close", () => {
      chain.then(() => {
        ending = true;
        if (ws && open) ws.close(1000);
        else if (ws) ws.terminate();
        else finish(0);
      });
    });

    function finish(/** @type {number} */ code) {
      if (finished) return;
      finished = true;
      rl.close();
      resolve(code);
    }

    async function handle(/** @type {string} */ line) {
      if (finished || line.trim() === "") return;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        write({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
        return;
      }
      const isRequest = msg && typeof msg === "object" && typeof msg.method === "string" && msg.id !== undefined;
      if (isRequest && msg.method === "initialize") {
        initParams = msg.params ?? {};
        if (!phone) return reply(msg.id, localInit());
        initIds.add(key(msg.id));
        return send(msg, true);
      }
      if (isRequest && msg.method === "authenticate" && msg.params?.methodId === PAIR_AUTH.id) {
        if (!phone) {
          const again = await choose(wanted, env).catch((e) => ({ phone: null, problem: String(e?.message ?? e) }));
          phone = again.phone;
          problem = again.problem ?? "";
        }
        if (!phone) return fail(msg.id, AUTH_REQUIRED, `${problem} Then try again.`);
        if (!connecting) replayInit = initParams !== null;
        reply(msg.id, {});
        await connect();
        return;
      }
      if (!phone) {
        if (isRequest) fail(msg.id, AUTH_REQUIRED, `Authentication required. ${problem}`);
        return;
      }
      send(msg, isRequest);
    }

    function send(/** @type {any} */ msg, /** @type {boolean} */ track) {
      if (track) pending.set(key(msg.id), msg.id);
      const text = JSON.stringify(msg);
      if (ws && open) ws.send(text);
      else {
        queue.push(text);
        connect().catch((e) => {
          failure ??= String(e?.message ?? e);
          drop(1006, "");
        });
      }
    }

    async function connect() {
      if (connecting || !phone) return;
      connecting = true;
      let target;
      try {
        target = await endpoint(phone, "acp", run);
      } catch (e) {
        failure = String(/** @type {Error} */ (e).message);
      }
      if (ending) return finish(0);
      if (!target) {
        failure ??= `${phone.name} has no editors' key saved. Pair again with aster-droid pair --lan or --adb and give the editors' key.`;
        return drop(1006, "");
      }
      /** @type {WebSocket} */
      let socket;
      try {
        socket = new WebSocket(target.url, {
          headers: { authorization: `Bearer ${target.token}`, "user-agent": `aster-droid/${version}` },
          handshakeTimeout: 20_000,
        });
      } catch (e) {
        failure = `Cannot reach the phone at ${target.url}: ${/** @type {Error} */ (e).message}`;
        return drop(1006, "");
      }
      ws = socket;
      socket.on("unexpected-response", (_req, res) => {
        failure = httpReason(res.statusCode ?? 0);
        socket.terminate();
      });
      socket.on("error", (e) => {
        failure ??= `Cannot reach the phone at ${target.url}: ${e.message}`;
      });
      socket.on("open", () => {
        open = true;
        log(`connected to ${phone?.name}`);
        if (replayInit) {
          replayInit = false;
          socket.send(JSON.stringify({ jsonrpc: "2.0", id: INIT_ID, method: "initialize", params: initParams }));
        }
        for (const text of queue.splice(0)) socket.send(text);
      });
      socket.on("message", (data, binary) => {
        if (binary) return;
        let msg;
        try {
          msg = JSON.parse(data.toString());
        } catch {
          log("the phone sent a message that is not JSON; dropped it");
          return;
        }
        const isResponse = msg && typeof msg === "object" && msg.method === undefined && msg.id !== undefined;
        if (isResponse) {
          if (msg.id === INIT_ID) {
            if (msg.error) log(`the phone's agent refused initialize: ${msg.error.message}`);
            return;
          }
          const k = key(msg.id);
          pending.delete(k);
          if (initIds.delete(k) && msg.result && typeof msg.result === "object") {
            msg.result.authMethods = [PAIR_AUTH];
          }
        }
        write(msg);
      });
      socket.on("close", (code, reason) => drop(code, reason.toString()));
    }

    function drop(/** @type {number} */ code, /** @type {string} */ reason) {
      open = false;
      if (finished) return;
      if (ending) return finish(0);
      const message = failure ?? closeReason(code, reason);
      for (const id of pending.values()) fail(id, -32000, message);
      pending.clear();
      if (code !== 1000 || failure) log(message);
      finish(code === 1000 && !failure ? 0 : 1);
    }
  });
}

/** What the bridge answers to `initialize` before there is a phone to ask. */
function localInit() {
  return {
    protocolVersion: 1,
    agentCapabilities: {
      loadSession: true,
      promptCapabilities: { image: true, audio: false, embeddedContext: true },
      mcpCapabilities: { http: true, sse: true },
    },
    agentInfo: { name: "aster-droid", title: "Droid", version },
    authMethods: [PAIR_AUTH],
  };
}

/** JSON-RPC ids may be numbers or strings; 1 and "1" are different requests. */
function key(/** @type {unknown} */ id) {
  return `${typeof id}:${String(id)}`;
}
