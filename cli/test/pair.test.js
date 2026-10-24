import assert from "node:assert/strict";
import { stat } from "node:fs/promises";
import { test } from "node:test";
import { main } from "../src/main.js";
import { isPrivateHost, relayOrigin } from "../src/net.js";
import { claimLink, pairAdb, pairCode, pairLan, pairQr, parseCode, relayPhone } from "../src/pair.js";
import { status, unpair } from "../src/status.js";
import { credentialsPath, load } from "../src/store.js";
import { ACP_TOKEN, fakePhone, fakeRelay, MCP_TOKEN, sink, tempDir } from "./fake.js";

const render = async () => "[qr]\n";

test("the claim link carries an X25519 key, a 128-bit nonce and the computer's name", () => {
  const { link, nonce, secret } = claimLink("https://relay.example", "aster-droid on mbp");
  const [, fragment] = link.split("/claim#");
  const [v, pub, n, name] = fragment.split(".");
  assert.equal(v, "1");
  assert.equal(Buffer.from(pub, "base64url").length, 32);
  assert.equal(n, nonce);
  assert.equal(Buffer.from(nonce, "base64url").length, 16);
  assert.equal(Buffer.from(name, "base64url").toString(), "aster-droid on mbp");
  assert.equal(Buffer.from(secret, "base64url").length, 32);
});

test("pair (QR) waits through a 408, then saves the relay pairing 0600", async () => {
  const relay = await fakeRelay();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const out = sink();
  const phone = await pairQr({ env, relay: relay.origin, out: out.stream, render });
  assert.equal(relay.state.waits, 2);
  assert.match(out.text(), /Scan a computer/);
  assert.match(out.text(), new RegExp(`${relay.origin}/claim#1\\.`));
  assert.ok(out.text().includes(relay.state.waitedFor[0]));
  const data = await load(env);
  assert.equal(data.default, "ph_abcdefghijklmnopqrstuvwxyz");
  const saved = data.phones[phone.id];
  assert.equal(saved.via, "relay");
  assert.equal(saved.mcp.url, `${relay.origin}/p/ph_abcdefghijklmnopqrstuvwxyz/mcp`);
  assert.equal(saved.acp.url, `${relay.origin.replace("http", "ws")}/p/ph_abcdefghijklmnopqrstuvwxyz/acp`);
  assert.equal(saved.mcp.token, relay.grant().token);
  assert.ok(saved.client_key);
  if (process.platform !== "win32") assert.equal((await stat(credentialsPath(env))).mode & 0o777, 0o600);

  const s = sink();
  const rows = await status({ env, out: s.stream });
  assert.equal(rows[0].reachable, true);
  assert.match(s.text(), /online/);
  await relay.close();
});

test("pair --code sends the code and this computer's name, and checks the link's fingerprint", async () => {
  const relay = await fakeRelay();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const phone = await pairCode({ env, relay: relay.origin, code: "7K2Q-9MXD", out: sink().stream });
  assert.equal(phone.id, "ph_abcdefghijklmnopqrstuvwxyz");
  assert.equal(relay.state.claims[0].code, "7K2Q-9MXD");
  assert.match(relay.state.claims[0].client.name, /^aster-droid on /);

  const env2 = { ASTER_DROID_HOME: await tempDir() };
  const good = `${relay.origin}/pair#1.ph_abcdefghijklmnopqrstuvwxyz.7K2Q9MXD.fpfpfpfpfpfpfpfp`;
  await pairCode({ env: env2, code: good, out: sink().stream });
  assert.equal(relay.state.claims[1].phone, "ph_abcdefghijklmnopqrstuvwxyz");

  const env3 = { ASTER_DROID_HOME: await tempDir() };
  const forged = `${relay.origin}/pair#1.ph_abcdefghijklmnopqrstuvwxyz.7K2Q9MXD.0000000000000000`;
  await assert.rejects(pairCode({ env: env3, code: forged, out: sink().stream }), /fingerprint does not match/);
  assert.deepEqual((await load(env3)).phones, {});
  await relay.close();
});

test("pair --code explains a refusal and saves nothing", async () => {
  const relay = await fakeRelay();
  const env = { ASTER_DROID_HOME: await tempDir() };
  await assert.rejects(pairCode({ env, relay: relay.origin, code: "DENY0000", out: sink().stream }), /The phone said no/);
  await assert.rejects(pairCode({ env, relay: relay.origin, code: "GONE0000", out: sink().stream }), /wrong or has expired/);
  await assert.rejects(pairCode({ env, relay: relay.origin, code: "x", out: sink().stream }), /8 letters and digits/);
  assert.deepEqual((await load(env)).phones, {});
  await relay.close();
});

test("a relay that points the token at another host is refused", async () => {
  const relay = await fakeRelay({ endpointOrigin: "https://evil.example" });
  const env = { ASTER_DROID_HOME: await tempDir() };
  await assert.rejects(pairCode({ env, relay: relay.origin, code: "7K2Q9MXD", out: sink().stream }), /not 127\.0\.0\.1/);
  assert.deepEqual((await load(env)).phones, {});
  assert.throws(() => relayPhone({ phone_id: "nope" }, "https://r.example"), /not a pairing/);
  await relay.close();
});

test("relays must be https except on this computer, and LAN phones must be private", () => {
  assert.equal(relayOrigin("https://relay.withaster.dev/x"), "https://relay.withaster.dev");
  assert.equal(relayOrigin("http://127.0.0.1:8787"), "http://127.0.0.1:8787");
  assert.throws(() => relayOrigin("http://relay.withaster.dev"), /must be https/);
  assert.throws(() => relayOrigin("https://u:p@relay.example"), /name or password/);
  for (const h of ["192.168.1.20", "10.0.0.5", "172.16.3.4", "100.101.102.103", "phone.local", "fd00::1", "[fe80::1]"]) assert.ok(isPrivateHost(h), h);
  for (const h of ["8.8.8.8", "172.32.0.1", "example.com", "100.128.0.1", "2001:db8::1"]) assert.ok(!isPrivateHost(h), h);
  assert.deepEqual(parseCode(" 7K2Q-9MXD "), { code: "7K2Q-9MXD", phone: null, fp: null, origin: null });
  assert.throws(() => parseCode("https://r.example/pair#2.x.y.z"), /not a Droid pair link/);
});

test("pair --lan tries both keys against the phone and saves direct endpoints", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const saved = await pairLan({ env, out: sink().stream, host: "127.0.0.1", mcpToken: MCP_TOKEN, acpToken: ACP_TOKEN, mcpPort: phone.port, acpPort: phone.port });
  assert.equal(saved.id, "lan:127.0.0.1");
  assert.equal(saved.mcp.url, phone.mcpUrl);
  assert.equal(saved.acp.url, phone.acpUrl);
  assert.equal(phone.seen.mcp[0].method, "ping");
  assert.equal(phone.seen.sockets, 1);
  await assert.rejects(
    pairLan({ env, out: sink().stream, host: "127.0.0.1", mcpToken: "z".repeat(40), mcpPort: phone.port }),
    /refused the other agents' key/,
  );
  await assert.rejects(pairLan({ env, out: sink().stream, host: "8.8.8.8", mcpToken: MCP_TOKEN }), /not a private address/);
  await assert.rejects(pairLan({ env, out: sink().stream, host: "127.0.0.1", mcpToken: "short" }), /too short/);
  await assert.rejects(pairLan({ env, out: sink().stream, host: "fe80::1%en0", mcpToken: MCP_TOKEN, check: false }), /cannot be used in a URL/);
  assert.equal((await load(env)).phones["lan:fe80::1%en0"], undefined);
  await phone.close();
});

test("pair --adb forwards the ports, reuses forwards, and unpair removes them", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const forwards = [];
  const calls = [];
  const run = async (args) => {
    calls.push(args.join(" "));
    if (args[0] === "devices") return "List of devices attached\nR5CT1234\tdevice\nemulator-5554\toffline\n\n";
    if (args[0] === "forward" && args[1] === "--list") return forwards.map((f) => `${f.serial} tcp:${f.local} tcp:${f.remote}`).join("\n");
    if (args[2] === "forward" && args[3] === "tcp:0") {
      forwards.push({ serial: args[1], local: phone.port, remote: Number(args[4].slice(4)) });
      return `${phone.port}\n`;
    }
    if (args[2] === "forward" && args[3] === "--remove") return "";
    throw new Error(`unexpected adb ${args.join(" ")}`);
  };
  const saved = await pairAdb({ env, out: sink().stream, run, mcpToken: MCP_TOKEN, acpToken: ACP_TOKEN });
  assert.equal(saved.id, "adb:R5CT1234");
  assert.deepEqual(saved.adb, { serial: "R5CT1234", mcp_port: 7071, acp_port: 7072 });
  assert.deepEqual(forwards.map((f) => f.remote), [7071, 7072]);
  const rows = await status({ env, out: sink().stream, run });
  assert.equal(rows[0].reachable, true);
  assert.equal(calls.filter((c) => c.includes("tcp:0")).length, 2, "the status check reused the forward");
  await unpair({ env, out: sink().stream, run });
  assert.equal(calls.filter((c) => c.includes("--remove")).length, 2);
  assert.deepEqual((await load(env)).phones, {});
  await phone.close();
});

/** adb that sees `devices` and forwards every port to `port`. */
function fakeAdb(devices, port) {
  const forwards = [];
  return async (args) => {
    if (args[0] === "devices") return `List of devices attached\n${devices.map((d) => `${d}\tdevice`).join("\n")}\n\n`;
    if (args[0] === "forward" && args[1] === "--list") return forwards.map((f) => `${f.serial} tcp:${port} tcp:${f.remote}`).join("\n");
    if (args[2] === "forward" && args[3] === "tcp:0") {
      forwards.push({ serial: args[1], remote: Number(args[4].slice(4)) });
      return `${port}\n`;
    }
    throw new Error(`unexpected adb ${args.join(" ")}`);
  };
}

test("bare pair never dials Aster Relay: without one adb phone it explains the free ways and fails", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  const missing = async () => {
    throw new Error("adb is not installed or not on PATH. Install Android platform-tools, or set ADB to its path.");
  };
  const out = sink();
  const err = sink();
  assert.equal(await main(["pair"], { env, stdout: out.stream, stderr: err.stream, run: missing }), 1);
  assert.match(err.text(), /Aster Relay, for pairing from anywhere, is coming soon/);
  assert.match(err.text(), /aster-droid pair --lan <phone ip>/);
  assert.match(err.text(), /Connect a computer in Droid/);
  assert.match(err.text(), /aster-droid pair --adb/);
  assert.doesNotMatch(err.text(), /not installed|relay\.withaster\.dev/);
  assert.equal(out.text(), "");

  const two = sink();
  assert.equal(await main(["pair"], { env, stdout: sink().stream, stderr: two.stream, run: fakeAdb(["R5CT1234", "R5CT5678"], 1) }), 1);
  assert.match(two.text(), /adb sees 2 phones/);
  assert.match(two.text(), /coming soon/);
  assert.deepEqual((await load(env)).phones, {});
});

test("bare pair with one phone on adb pairs over USB", async () => {
  const phone = await fakePhone();
  const env = { ASTER_DROID_HOME: await tempDir() };
  const out = sink();
  const args = ["pair", "--mcp-token", MCP_TOKEN, "--acp-token", ACP_TOKEN];
  assert.equal(await main(args, { env, stdout: out.stream, stderr: sink().stream, run: fakeAdb(["R5CT1234"], phone.port) }), 0);
  assert.match(out.text(), /Pairing with R5CT1234 over USB/);
  assert.equal((await load(env)).phones["adb:R5CT1234"].via, "adb");
  await phone.close();
});

test("ASTER_RELAY_URL keeps bare pair on the relay's QR flow", async () => {
  const relay = await fakeRelay();
  const env = { ASTER_DROID_HOME: await tempDir(), ASTER_RELAY_URL: relay.origin };
  const out = sink();
  const never = async () => {
    throw new Error("adb should not be asked");
  };
  assert.equal(await main(["pair"], { env, stdout: out.stream, stderr: sink().stream, run: never }), 0);
  assert.match(out.text(), new RegExp(`${relay.origin}/claim#1\\.`));
  assert.equal((await load(env)).phones["ph_abcdefghijklmnopqrstuvwxyz"].via, "relay");
  await relay.close();
});
