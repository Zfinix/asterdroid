// @ts-check
// The USB path: adb forwards a local port to the phone's loopback, where
// asterctl listens by default, so nothing on the phone has to face the network.
// Forwards vanish when adb restarts or the cable comes out, so every
// connection asks for its forward again instead of trusting a saved port.

import { execFile } from "node:child_process";

/** @typedef {(args: string[]) => Promise<string>} Runner */

/** @type {Runner} */
export function runAdb(args) {
  const bin = process.env.ADB || "adb";
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout: 15_000 }, (error, stdout, stderr) => {
      if (error) {
        const code = /** @type {NodeJS.ErrnoException} */ (error).code;
        if (code === "ENOENT") {
          reject(new Error("adb is not installed or not on PATH. Install Android platform-tools, or set ADB to its path."));
        } else {
          reject(new Error(`adb ${args.join(" ")} failed: ${String(stderr || error.message).trim()}`));
        }
        return;
      }
      resolve(String(stdout));
    });
  });
}

/** The serial of the one phone adb sees, or `wanted` once it is confirmed attached. */
export async function device(/** @type {string | undefined} */ wanted, run = runAdb) {
  const lines = (await run(["devices"])).split("\n").slice(1);
  const ready = lines
    .map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[1] === "device")
    .map((p) => p[0]);
  if (wanted) {
    if (!ready.includes(wanted)) throw new Error(`adb does not see ${wanted}. Check the cable and that USB debugging is allowed.`);
    return wanted;
  }
  if (ready.length === 0) throw new Error("adb sees no phone. Plug it in and allow USB debugging on it.");
  if (ready.length > 1) throw new Error(`adb sees ${ready.length} phones. Pick one with --serial (${ready.join(", ")}).`);
  return ready[0];
}

/**
 * A local port that reaches `remote` on the phone, reusing a forward that
 * already exists so repeated launches do not pile them up.
 * @param {string} serial
 * @param {number} remote
 */
export async function forward(serial, remote, run = runAdb) {
  const list = await run(["forward", "--list"]);
  for (const line of list.split("\n")) {
    const [s, local, target] = line.trim().split(/\s+/);
    if (s === serial && target === `tcp:${remote}` && local?.startsWith("tcp:")) return Number(local.slice(4));
  }
  const port = Number((await run(["-s", serial, "forward", "tcp:0", `tcp:${remote}`])).trim());
  if (!Number.isInteger(port) || port <= 0) throw new Error(`adb did not report a port for tcp:${remote}.`);
  return port;
}

/** Removes this computer's forwards to the phone. Failures are ignored: the phone may be gone. */
export async function unforward(/** @type {string} */ serial, /** @type {number[]} */ remotes, run = runAdb) {
  let list = "";
  try {
    list = await run(["forward", "--list"]);
  } catch {
    return;
  }
  for (const line of list.split("\n")) {
    const [s, local, target] = line.trim().split(/\s+/);
    if (s !== serial || !remotes.some((r) => target === `tcp:${r}`)) continue;
    await run(["-s", serial, "forward", "--remove", local]).catch(() => {});
  }
}
