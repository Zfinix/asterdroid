import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { PassThrough } from "node:stream";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { AUTH_REQUIRED, PAIR_AUTH, runAcp } from "../src/acp.js";
import { addPhone, credentialsPath } from "../src/store.js";
import { ACP_TOKEN, answer, fakePhone, sink, tempDir } from "./fake.js";

const BIN = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "bin", "aster-droid.js");

function lanPhone(url, token = ACP_TOKEN) {
  return { id: "lan:127.0.0.1", name: "test phone", via: "lan", added: 1, acp: { url, token } };
}

function bridge(env) {
  const stdin = new PassThrough();
  const out = sink();
  const err = sink();
  const done = runAcp({ env, stdin, stdout: out.stream, stderr: err.stream });
  const send = (m) => stdin.write(`${JSON.stringify(m)}\n`);
  return { stdin, out, err, done, send };
}

function onlyJson(lines) {
  for (const line of lines) assert.doesNotThrow(() => JSON.parse(line), `stdout carried a line that is not JSON: ${line}`);
}

test("acp pipes JSON-RPC both ways, answers the agent's requests, and swaps in the pair auth method", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(lanPhone(phone.acpUrl), env);
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {} } });
  const init = await answer(b.out, 0);
  assert.deepEqual(init.result.authMethods, [PAIR_AUTH]);
  b.send({ jsonrpc: "2.0", id: 1, method: "session/new", params: { cwd: "/Users/me/project", mcpServers: [] } });
  assert.deepEqual((await answer(b.out, 1)).result, { sessionId: "s1" });
  b.send({ jsonrpc: "2.0", id: 2, method: "session/prompt", params: { sessionId: "s1", prompt: [{ type: "text", text: "hi" }] } });
  const ask = JSON.parse(await b.out.until((l) => JSON.parse(l).method === "session/request_permission"));
  assert.equal(ask.id, 99);
  await b.out.until((l) => JSON.parse(l).method === "session/update");
  b.send({ jsonrpc: "2.0", id: 99, result: { outcome: { outcome: "selected", optionId: "allow" } } });
  assert.deepEqual((await answer(b.out, 2)).result, { stopReason: "end_turn" });
  b.stdin.end();
  assert.equal(await b.done, 0);
  assert.deepEqual(phone.seen.acp.map((m) => m.method ?? "response"), ["initialize", "session/new", "session/prompt", "response"]);
  assert.equal(phone.seen.acp[1].params.cwd, "/Users/me/project");
  onlyJson(b.out.lines());
  await phone.close();
});

test("a busy phone fails the waiting request with a readable error and exits 1", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(lanPhone(phone.acpUrl), env);
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 7, method: "test/busy", params: {} });
  const r = await answer(b.out, 7);
  assert.equal(r.error.code, -32000);
  assert.match(r.error.message, /Another editor is already driving the phone/);
  assert.equal(await b.done, 1);
  assert.match(b.err.text(), /Another editor/);
  await phone.close();
});

test("a wrong token fails the request with the pairing hint", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(lanPhone(phone.acpUrl, "x".repeat(40)), env);
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1 } });
  const r = await answer(b.out, 0);
  assert.match(r.error.message, /refused this computer's token/);
  assert.equal(await b.done, 1);
  await phone.close();
});

test("before pairing it offers terminal auth, and after pairing authenticate connects and replays initialize", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1, clientInfo: { name: "zed" } } });
  const init = await answer(b.out, 0);
  assert.equal(init.result.protocolVersion, 1);
  assert.deepEqual(init.result.authMethods, [PAIR_AUTH]);
  assert.equal(init.result.authMethods[0].type, "terminal");
  b.send({ jsonrpc: "2.0", id: 1, method: "session/new", params: { cwd: "/", mcpServers: [] } });
  assert.equal((await answer(b.out, 1)).error.code, AUTH_REQUIRED);
  b.send({ jsonrpc: "2.0", id: 2, method: "authenticate", params: { methodId: "pair" } });
  assert.equal((await answer(b.out, 2)).error.code, AUTH_REQUIRED);
  assert.equal(phone.seen.sockets, 0);

  await addPhone(lanPhone(phone.acpUrl), env);
  b.send({ jsonrpc: "2.0", id: 3, method: "authenticate", params: { methodId: "pair" } });
  assert.deepEqual((await answer(b.out, 3)).result, {});
  b.send({ jsonrpc: "2.0", id: 4, method: "session/new", params: { cwd: "/", mcpServers: [] } });
  assert.deepEqual((await answer(b.out, 4)).result, { sessionId: "s1" });
  assert.equal(phone.seen.acp[0].method, "initialize");
  assert.equal(phone.seen.acp[0].params.clientInfo.name, "zed");
  assert.equal(b.out.lines().filter((l) => JSON.parse(l).id === "aster-droid:init").length, 0);
  b.stdin.end();
  assert.equal(await b.done, 0);
  onlyJson(b.out.lines());
  await phone.close();
});

test("a credentials file that cannot be read still answers initialize with the pair method", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  await mkdir(path.dirname(credentialsPath(env)), { recursive: true });
  await writeFile(credentialsPath(env), "{bad");
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1 } });
  assert.deepEqual((await answer(b.out, 0)).result.authMethods, [PAIR_AUTH]);
  b.send({ jsonrpc: "2.0", id: 1, method: "authenticate", params: { methodId: "pair" } });
  const r = await answer(b.out, 1);
  assert.equal(r.error.code, AUTH_REQUIRED);
  assert.match(r.error.message, /is not valid JSON/);
  b.stdin.end();
  assert.equal(await b.done, 0);
});

test("a saved address that is not a URL fails the request instead of crashing", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(lanPhone("ws://[fe80::1%en0]:7072/acp"), env);
  const b = bridge(env);
  b.send({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1 } });
  const r = await answer(b.out, 0);
  assert.equal(r.error.code, -32000);
  assert.match(r.error.message, /Cannot reach the phone at ws:\/\/\[fe80::1%en0\]/);
  assert.equal(await b.done, 1);
});

function runBin(env, lines) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [BIN, "acp"], { env: { ...process.env, ...env }, stdio: ["pipe", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (d) => (stdout += d));
    child.stderr.on("data", (d) => (stderr += d));
    child.on("exit", (code) => resolve({ code, stdout, stderr }));
    for (const l of lines) child.stdin.write(`${JSON.stringify(l)}\n`);
    setTimeout(() => child.stdin.end(), 500);
  });
}

test("the real bin writes nothing but JSON-RPC to stdout, paired or not", async () => {
  const unpaired = await runBin({ ASTER_DROID_HOME: await tempDir() }, [
    { jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1 } },
    { jsonrpc: "2.0", id: 1, method: "session/new", params: { cwd: "/", mcpServers: [] } },
  ]);
  assert.equal(unpaired.code, 0);
  const lines = unpaired.stdout.split("\n").filter(Boolean);
  onlyJson(lines);
  assert.equal(lines.length, 2);
  assert.equal(JSON.parse(lines[0]).result.authMethods[0].id, "pair");

  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(lanPhone(phone.acpUrl), env);
  const paired = await runBin(env, [
    { jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: 1 } },
    { jsonrpc: "2.0", id: 1, method: "session/new", params: { cwd: "/", mcpServers: [] } },
  ]);
  assert.equal(paired.code, 0);
  onlyJson(paired.stdout.split("\n").filter(Boolean));
  assert.match(paired.stderr, /connected to test phone/);
  await phone.close();
});
