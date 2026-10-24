import assert from "node:assert/strict";
import { chmod, readFile, stat, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";
import { addPhone, configDir, credentialsPath, load, pick, removePhone, save } from "../src/store.js";
import { tempDir } from "./fake.js";

const posix = process.platform !== "win32";

function phone(id, extra = {}) {
  return { id, name: `phone ${id}`, via: "lan", added: 1, mcp: { url: "http://192.168.1.2:7071/mcp", token: "t".repeat(40) }, ...extra };
}

test("the config dir follows ASTER_DROID_HOME, then XDG_CONFIG_HOME, then ~/.config", () => {
  assert.equal(configDir({ ASTER_DROID_HOME: "/x/y" }), "/x/y");
  assert.equal(configDir({ XDG_CONFIG_HOME: "/cfg" }), path.join("/cfg", "aster-droid"));
  assert.match(configDir({}), /\.config[\\/]aster-droid$/);
});

test("credentials are written 0600 in a 0700 directory", { skip: !posix }, async () => {
  const home = path.join(await tempDir(), "nested", "aster-droid");
  const env = { ASTER_DROID_HOME: home };
  await addPhone(phone("lan:192.168.1.2"), env);
  assert.equal((await stat(credentialsPath(env))).mode & 0o777, 0o600);
  assert.equal((await stat(home)).mode & 0o777, 0o700);
  await addPhone(phone("lan:192.168.1.3"), env);
  assert.equal((await stat(credentialsPath(env))).mode & 0o777, 0o600);
});

test("a credentials file others can read is tightened on load and on save", { skip: !posix }, async () => {
  const home = await tempDir();
  const env = { ASTER_DROID_HOME: home };
  await mkdir(home, { recursive: true });
  await writeFile(credentialsPath(env), JSON.stringify({ version: 1, default: null, phones: {} }), { mode: 0o644 });
  await chmod(credentialsPath(env), 0o644);
  await load(env);
  assert.equal((await stat(credentialsPath(env))).mode & 0o777, 0o600);
  await chmod(home, 0o755);
  await save(await load(env), env);
  assert.equal((await stat(home)).mode & 0o777, 0o700);
});

test("several phones are kept, the last paired is the default, and removing it moves the default", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  await addPhone(phone("lan:192.168.1.2"), env);
  await addPhone(phone("ph_abcdefghijklmnopqrstuvwxyz", { via: "relay", name: "work phone" }), env);
  let data = await load(env);
  assert.deepEqual(Object.keys(data.phones).sort(), ["lan:192.168.1.2", "ph_abcdefghijklmnopqrstuvwxyz"]);
  assert.equal(data.default, "ph_abcdefghijklmnopqrstuvwxyz");
  assert.equal(pick(data, undefined)?.id, "ph_abcdefghijklmnopqrstuvwxyz");
  assert.equal(pick(data, "work phone")?.id, "ph_abcdefghijklmnopqrstuvwxyz");
  assert.equal(pick(data, "lan:192.168.1.2")?.id, "lan:192.168.1.2");
  assert.equal(pick(data, "nope"), null);
  await removePhone("ph_abcdefghijklmnopqrstuvwxyz", env);
  data = await load(env);
  assert.equal(data.default, "lan:192.168.1.2");
  assert.equal(await removePhone("ph_abcdefghijklmnopqrstuvwxyz", env), null);
});

test("no file means no phones, and a broken file is reported rather than overwritten", async () => {
  const env = { ASTER_DROID_HOME: await tempDir() };
  assert.deepEqual(await load(env), { version: 1, default: null, phones: {} });
  await writeFile(credentialsPath(env), "{not json");
  await assert.rejects(load(env), /not valid JSON/);
  assert.equal(await readFile(credentialsPath(env), "utf8"), "{not json");
});
