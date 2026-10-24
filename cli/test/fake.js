// A stand-in for the phone (asterctl's /mcp and /acp) and for the relay's
// pairing routes, on 127.0.0.1, plus small stream helpers.

import { mkdtemp } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import { WebSocketServer } from "ws";

export const MCP_TOKEN = "m".repeat(40);
export const ACP_TOKEN = "a".repeat(40);

export async function tempDir(prefix = "aster-droid-") {
  return mkdtemp(path.join(os.tmpdir(), prefix));
}

/** A writable that keeps what it was given, with a way to wait for a line. */
export function sink() {
  const stream = new PassThrough();
  let text = "";
  const waiters = [];
  stream.on("data", (chunk) => {
    text += chunk.toString();
    for (const w of [...waiters]) w();
  });
  return {
    stream,
    text: () => text,
    lines: () => text.split("\n").filter((l) => l.trim() !== ""),
    /** The first line matching `test`, waiting for it up to `ms`. */
    until(test, ms = 5000) {
      return new Promise((resolve, reject) => {
        const check = () => {
          const line = text.split("\n").find((l) => l.trim() !== "" && test(l));
          if (line === undefined) return false;
          waiters.splice(waiters.indexOf(check), 1);
          clearTimeout(timer);
          resolve(line);
          return true;
        };
        const timer = setTimeout(() => {
          waiters.splice(waiters.indexOf(check), 1);
          reject(new Error(`timed out waiting; got:\n${text}`));
        }, ms);
        waiters.push(check);
        check();
      });
    },
  };
}

/** The JSON line answering request `id`. */
export async function answer(out, id, ms) {
  return JSON.parse(await out.until((l) => {
    try {
      const m = JSON.parse(l);
      return m.id === id && m.method === undefined;
    } catch {
      return false;
    }
  }, ms));
}

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

/**
 * The phone: POST /mcp with MCP_TOKEN, and a WebSocket at /acp with ACP_TOKEN
 * that plays a tiny `aster acp`.
 */
export async function fakePhone() {
  const seen = { mcp: [], mcpHeaders: [], acp: [], sockets: 0 };
  const server = http.createServer(async (req, res) => {
    if (req.url !== "/mcp") {
      res.writeHead(404).end();
      return;
    }
    if (req.method !== "POST") {
      res.writeHead(405).end();
      return;
    }
    if (req.headers.authorization !== `Bearer ${MCP_TOKEN}`) {
      res.writeHead(401, { "www-authenticate": "Bearer" }).end(JSON.stringify({ error: "unauthorized" }));
      return;
    }
    let body = "";
    for await (const chunk of req) body += chunk;
    const msg = JSON.parse(body);
    seen.mcp.push(msg);
    seen.mcpHeaders.push(req.headers);
    if (msg.id === undefined) {
      res.writeHead(202).end();
      return;
    }
    if (msg.method === "initialize") {
      res.writeHead(200, { "content-type": "application/json", "mcp-session-id": "sess-1" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "asterctl" } } }, null, 2));
      return;
    }
    if (msg.method === "tools/list") {
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: { tools: [{ name: "map" }] } })}\n\n`);
      return;
    }
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {} }));
  });
  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, socket, head) => {
    if (!req.url?.startsWith("/acp") || req.headers.authorization !== `Bearer ${ACP_TOKEN}`) {
      socket.end("HTTP/1.1 401 Unauthorized\r\ncontent-length: 0\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => acpAgent(ws, seen));
  });
  const port = await listen(server);
  return {
    port,
    seen,
    mcpUrl: `http://127.0.0.1:${port}/mcp`,
    acpUrl: `ws://127.0.0.1:${port}/acp`,
    close: () =>
      new Promise((resolve) => {
        for (const ws of wss.clients) ws.terminate();
        server.closeAllConnections?.();
        server.close(() => resolve(undefined));
      }),
  };
}

function acpAgent(ws, seen) {
  seen.sockets += 1;
  const prompts = new Map();
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    seen.acp.push(msg);
    const send = (m) => ws.send(JSON.stringify(m));
    if (msg.method === "initialize") {
      send({ jsonrpc: "2.0", id: msg.id, result: { protocolVersion: 1, agentCapabilities: { loadSession: true }, authMethods: [{ id: "login", name: "Sign in", type: "terminal", args: ["login"] }] } });
    } else if (msg.method === "session/new") {
      send({ jsonrpc: "2.0", id: msg.id, result: { sessionId: "s1" } });
    } else if (msg.method === "session/prompt") {
      prompts.set(99, msg.id);
      send({ jsonrpc: "2.0", method: "session/update", params: { sessionId: "s1", update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "hi" } } } });
      send({ jsonrpc: "2.0", id: 99, method: "session/request_permission", params: { sessionId: "s1", options: [] } });
    } else if (msg.id === 99 && msg.method === undefined) {
      send({ jsonrpc: "2.0", id: prompts.get(99), result: { stopReason: "end_turn" } });
    } else if (msg.method === "test/busy") {
      ws.close(4409, "busy");
    }
  });
}

/**
 * The relay's pairing routes. `grant(origin)` is what a successful pairing returns.
 * `waits` counts long-polls; the first one answers 408.
 */
export async function fakeRelay(options = {}) {
  const state = { waits: 0, claims: [], waitedFor: [] };
  let origin = "";
  const grant = () => ({
    phone_id: "ph_abcdefghijklmnopqrstuvwxyz",
    client_id: "c_aaaaaaaaaa",
    token: `ast_c_c_aaaaaaaaaa_${"s".repeat(32)}`,
    scopes: ["phone:act", "agent:chat"],
    phone_fp: "fpfpfpfpfpfpfpfp",
    endpoints: { mcp: `${options.endpointOrigin ?? origin}/p/ph_abcdefghijklmnopqrstuvwxyz/mcp`, acp: `${(options.endpointOrigin ?? origin).replace(/^http/, "ws")}/p/ph_abcdefghijklmnopqrstuvwxyz/acp`, mirror: null },
    snippets: {},
  });
  const server = http.createServer(async (req, res) => {
    const json = (status, body) => res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
    const wait = /^\/v1\/pair\/wait\/([A-Za-z0-9_-]+)$/.exec(req.url ?? "");
    if (wait) {
      state.waits += 1;
      state.waitedFor.push(wait[1]);
      if (state.waits === 1) return json(408, { error: "timeout" });
      return json(200, grant());
    }
    if (req.url === "/v1/pair/claim" && req.method === "POST") {
      let body = "";
      for await (const chunk of req) body += chunk;
      const claim = JSON.parse(body);
      state.claims.push(claim);
      if (claim.code === "DENY0000") return json(403, { error: "denied" });
      if (claim.code === "GONE0000") return json(404, { error: "invalid_or_expired" });
      return json(200, grant());
    }
    if (req.url === "/p/ph_abcdefghijklmnopqrstuvwxyz/status") {
      if (req.headers.authorization !== `Bearer ${grant().token}`) return json(401, { error: "unauthorized" });
      return json(200, { online: true, last_seen: Math.floor(Date.now() / 1000), plan: "self-hosted", entitled: true, app: null });
    }
    json(404, { error: "not_found" });
  });
  const port = await listen(server);
  origin = `http://127.0.0.1:${port}`;
  return {
    origin,
    state,
    grant,
    close: () => new Promise((resolve) => {
      server.closeAllConnections?.();
      server.close(() => resolve(undefined));
    }),
  };
}
