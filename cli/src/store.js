// @ts-check
// Paired phones and their tokens, in one file only this user can read.
// A token in here controls a phone (calls, messages, money), so the file is
// written 0600 inside a 0700 directory, and replaced atomically so a crash
// never leaves half a file.

import { randomBytes } from "node:crypto";
import { chmod, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

/**
 * @typedef {{ url?: string, token: string }} Endpoint
 * @typedef {{
 *   id: string,
 *   name: string,
 *   via: "relay" | "lan" | "adb",
 *   added: number,
 *   relay?: string,
 *   client_id?: string,
 *   scopes?: string[],
 *   phone_fp?: string | null,
 *   client_key?: string,
 *   host?: string,
 *   adb?: { serial: string, mcp_port: number, acp_port: number },
 *   mcp?: Endpoint,
 *   acp?: Endpoint,
 * }} Phone
 * @typedef {{ version: 1, default: string | null, phones: Record<string, Phone> }} Credentials
 */

const FILE = "credentials.json";

/** `ASTER_DROID_HOME` wins, then `$XDG_CONFIG_HOME/aster-droid`, then `~/.config/aster-droid`. */
export function configDir(env = process.env) {
  if (env.ASTER_DROID_HOME) return env.ASTER_DROID_HOME;
  const base = env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
  return path.join(base, "aster-droid");
}

export function credentialsPath(env = process.env) {
  return path.join(configDir(env), FILE);
}

/** @returns {Credentials} */
function empty() {
  return { version: 1, default: null, phones: {} };
}

/**
 * The stored credentials, or an empty set when there are none yet. A file
 * someone else can read is tightened back to 0600 on the way in.
 * @returns {Promise<Credentials>}
 */
export async function load(env = process.env) {
  const file = credentialsPath(env);
  let text;
  try {
    text = await readFile(file, "utf8");
  } catch (e) {
    if (/** @type {NodeJS.ErrnoException} */ (e).code === "ENOENT") return empty();
    throw e;
  }
  if (process.platform !== "win32") {
    const mode = (await stat(file)).mode & 0o777;
    if (mode & 0o077) await chmod(file, 0o600);
  }
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(`${file} is not valid JSON. Fix it or delete it and pair again.`);
  }
  if (!data || typeof data !== "object" || typeof data.phones !== "object" || data.phones === null) return empty();
  return { version: 1, default: typeof data.default === "string" ? data.default : null, phones: data.phones };
}

/** @param {Credentials} data */
export async function save(data, env = process.env) {
  const dir = configDir(env);
  await mkdir(dir, { recursive: true, mode: 0o700 });
  if (process.platform !== "win32") await chmod(dir, 0o700);
  const file = path.join(dir, FILE);
  const temp = path.join(dir, `.${FILE}.${randomBytes(6).toString("hex")}`);
  try {
    await writeFile(temp, `${JSON.stringify(data, null, 2)}\n`, { mode: 0o600, flag: "wx" });
    await rename(temp, file);
  } catch (e) {
    await rm(temp, { force: true });
    throw e;
  }
  if (process.platform !== "win32") await chmod(file, 0o600);
}

/**
 * The phone `wanted` names, by id or by name. With nothing wanted it is the
 * default phone, or the only one.
 * @param {Credentials} data
 * @param {string | undefined} wanted
 * @returns {Phone | null}
 */
export function pick(data, wanted) {
  const phones = Object.values(data.phones);
  if (wanted) {
    return data.phones[wanted] ?? phones.find((p) => p.name === wanted) ?? null;
  }
  if (data.default && data.phones[data.default]) return data.phones[data.default];
  return phones.length === 1 ? phones[0] : null;
}

/** Saves `phone` and makes it the default, replacing an earlier pairing with the same id. */
export async function addPhone(/** @type {Phone} */ phone, env = process.env) {
  const data = await load(env);
  data.phones[phone.id] = phone;
  data.default = phone.id;
  await save(data, env);
  return data;
}

/** @returns {Promise<Phone | null>} the phone that was removed */
export async function removePhone(/** @type {string} */ id, env = process.env) {
  const data = await load(env);
  const phone = data.phones[id];
  if (!phone) return null;
  delete data.phones[id];
  if (data.default === id) data.default = Object.keys(data.phones)[0] ?? null;
  await save(data, env);
  return phone;
}
