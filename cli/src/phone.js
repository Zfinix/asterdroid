// @ts-check
// Where a paired phone's MCP and ACP endpoints are right now.

import { forward, runAdb } from "./adb.js";
import { load, pick } from "./store.js";

/** @typedef {import("./store.js").Phone} Phone */

/**
 * The URL and token for `svc`, or null when this pairing has no token for it.
 * An adb pairing gets a fresh forward each time.
 * @param {Phone} phone
 * @param {"mcp" | "acp"} svc
 */
export async function endpoint(phone, svc, run = runAdb) {
  const saved = phone[svc];
  if (!saved?.token) return null;
  if (phone.via === "adb" && phone.adb) {
    const remote = svc === "mcp" ? phone.adb.mcp_port : phone.adb.acp_port;
    const port = await forward(phone.adb.serial, remote, run);
    const url = svc === "mcp" ? `http://127.0.0.1:${port}/mcp` : `ws://127.0.0.1:${port}/acp`;
    return { url, token: saved.token };
  }
  if (!saved.url) return null;
  return { url: saved.url, token: saved.token };
}

/** The phone the user asked for, or a sentence saying why there is none. */
export async function choose(/** @type {string | undefined} */ wanted, env = process.env) {
  const data = await load(env);
  const phone = pick(data, wanted);
  if (phone) return { phone, problem: null };
  const count = Object.keys(data.phones).length;
  if (wanted) return { phone: null, problem: `No paired phone is called ${wanted}. Run aster-droid status to see them.` };
  if (count === 0) return { phone: null, problem: "This computer is not paired with a phone yet. Run npx -y @withaster/droid pair." };
  return { phone: null, problem: `${count} phones are paired and none is the default. Pick one with --phone.` };
}

/** Plain-words reason for a WebSocket close code from asterctl or the relay. */
export function closeReason(/** @type {number} */ code, reason = "") {
  switch (code) {
    case 1000:
      return "The phone ended the session.";
    case 1011:
      return "The phone could not start its agent. Open Droid and check that a model is set up.";
    case 4401:
      return "This computer's access was revoked on the phone. Run aster-droid pair to pair again.";
    case 4402:
      return "Aster Relay is not active for this phone. Turn it on in Droid's settings.";
    case 4403:
      return "The phone refused this computer. Run aster-droid pair to pair again.";
    case 4409:
      return "Another editor is already driving the phone. Close the session there, then try again.";
    case 4503:
      return "The phone is offline. Open Droid on it, then try again.";
    default:
      return `Lost the connection to the phone (${code}${reason ? ` ${reason}` : ""}).`;
  }
}

/** Plain-words reason for an HTTP status from the phone or the relay. */
export function httpReason(/** @type {number} */ status) {
  switch (status) {
    case 401:
      return "The phone refused this computer's token. Run aster-droid pair to pair again.";
    case 403:
      return "The phone refused this request.";
    case 404:
      return "Nothing on the phone answers at that address. Check that Droid is up to date.";
    case 413:
      return "That message is over the relay's 1 MiB limit.";
    case 429:
      return "Too many requests from this computer. Wait a minute and try again.";
    default:
      return `The phone answered with HTTP ${status}.`;
  }
}
