import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { PassThrough } from "node:stream";
import { test } from "node:test";
import { events, runMcp } from "../src/mcp.js";
import { addPhone, credentialsPath } from "../src/store.js";
import { answer, fakePhone, MCP_TOKEN, sink, tempDir } from "./fake.js";

function proxy(env) {
  const stdin = new PassThrough();
  const out = sink();
  const err = sink();
  const done = runMcp({ env, stdin, stdout: out.stream, stderr: err.stream });
  const send = (m) => stdin.write(`${JSON.stringify(m)}\n`);
  return { stdin, out, err, done, send };
}

async function paired(url, token = MCP_TOKEN) {
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone({ id: "lan:127.0.0.1", name: "test phone", via: "lan", added: 1, mcp: { url, token } }, env);
  return env;
}

test("mcp proxies each line as one POST and writes each reply as one line", async () => {
  const phone = await fakePhone();
  const p = proxy(await paired(phone.mcpUrl));
  p.send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t" } } });
  p.send({ jsonrpc: "2.0", method: "notifications/initialized" });
  p.send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
  p.send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "map", arguments: {} } });
  const init = await answer(p.out, 1);
  assert.equal(init.result.serverInfo.name, "asterctl");
  assert.deepEqual((await answer(p.out, 2)).result, { tools: [{ name: "map" }] });
  assert.deepEqual((await answer(p.out, 3)).result, {});
  p.stdin.end();
  assert.equal(await p.done, 0);
  const lines = p.out.lines();
  assert.equal(lines.length, 3, "the notification gets no line and the pretty-printed reply is one line");
  for (const l of lines) JSON.parse(l);
  assert.equal(phone.seen.mcp.length, 4);
  const later = phone.seen.mcpHeaders.slice(1);
  for (const h of later) {
    assert.equal(h["mcp-session-id"], "sess-1");
    assert.equal(h["mcp-protocol-version"], "2025-06-18");
    assert.equal(h.authorization, `Bearer ${MCP_TOKEN}`);
  }
  await phone.close();
});

test("a refused token becomes a JSON-RPC error that says to pair again", async () => {
  const phone = await fakePhone();
  const p = proxy(await paired(phone.mcpUrl, "w".repeat(40)));
  p.send({ jsonrpc: "2.0", id: 1, method: "ping" });
  const r = await answer(p.out, 1);
  assert.equal(r.error.code, -32000);
  assert.match(r.error.message, /aster-droid pair/);
  p.stdin.end();
  await p.done;
  await phone.close();
});

test("an unreachable phone becomes a JSON-RPC error naming the address", async () => {
  const phone = await fakePhone();
  const url = phone.mcpUrl;
  await phone.close();
  const p = proxy(await paired(url));
  p.send({ jsonrpc: "2.0", id: "a", method: "ping" });
  const r = await answer(p.out, "a");
  assert.match(r.error.message, new RegExp(`Cannot reach the phone at ${url.replace(/[.]/g, "\\.")}`));
  p.stdin.end();
  await p.done;
});

test("before pairing every request is answered with the way to pair", async () => {
  const p = proxy({ ASTER_DROID_HOME: await tempDir() });
  p.send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} });
  p.send({ jsonrpc: "2.0", method: "notifications/initialized" });
  const r = await answer(p.out, 1);
  assert.match(r.error.message, /npx -y @withaster\/droid pair/);
  p.stdin.end();
  assert.equal(await p.done, 0);
  assert.equal(p.out.lines().length, 1);
});

test("a credentials file that cannot be read answers every request instead of crashing", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  await mkdir(path.dirname(credentialsPath(env)), { recursive: true });
  await writeFile(credentialsPath(env), "{bad");
  const p = proxy(env);
  p.send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} });
  const r = await answer(p.out, 1);
  assert.equal(r.error.code, -32000);
  assert.match(r.error.message, /is not valid JSON/);
  p.stdin.end();
  assert.equal(await p.done, 0);
});

test("a reply cut off mid-body still answers the request", async () => {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream", "mcp-session-id": "sess-1" });
    res.write('data: {"jsonrpc":"2.0",');
    setTimeout(() => res.socket?.destroy(), 50);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${server.address().port}/mcp`;
  const p = proxy(await paired(url));
  p.send({ jsonrpc: "2.0", id: 7, method: "tools/list" });
  const r = await answer(p.out, 7);
  assert.equal(r.error.code, -32000);
  assert.match(r.error.message, /Lost the answer from the phone/);
  p.stdin.end();
  assert.equal(await p.done, 0);
  await new Promise((resolve) => server.close(resolve));
});

test("events() reads the data of each server-sent event", () => {
  assert.deepEqual(events("event: message\ndata: {\"a\":1}\n\ndata: x\ndata: y\n\n: comment\n\n"), ['{"a":1}', "x\ny"]);
});
