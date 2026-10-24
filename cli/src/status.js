// @ts-check
// `aster-droid status` and `aster-droid unpair`.

import { runAdb, unforward } from "./adb.js";
import { checkMcp } from "./pair.js";
import { endpoint, httpReason } from "./phone.js";
import { credentialsPath, load, removePhone } from "./store.js";
import { version } from "./version.js";

/** @typedef {import("./store.js").Phone} Phone */

/**
 * Whether a phone answers right now, in a few words.
 * @param {Phone} phone
 * @returns {Promise<{ reachable: boolean, detail: string }>}
 */
export async function probe(phone, fetchImpl = fetch, run = runAdb) {
  if (phone.via === "relay") {
    const token = phone.mcp?.token ?? phone.acp?.token;
    if (!phone.relay || !token) return { reachable: false, detail: "no token saved" };
    try {
      const response = await fetchImpl(`${phone.relay}/p/${phone.id}/status`, {
        headers: { authorization: `Bearer ${token}`, "user-agent": `aster-droid/${version}` },
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) {
        await response.body?.cancel();
        return { reachable: false, detail: response.status === 401 ? "the relay refused the token; it may have been revoked" : httpReason(response.status) };
      }
      const s = /** @type {{ online?: boolean, last_seen?: number | null, entitled?: boolean }} */ (await response.json());
      if (s.entitled === false) return { reachable: false, detail: "Aster Relay is not active for this phone" };
      if (s.online) return { reachable: true, detail: "online" };
      return { reachable: false, detail: `offline${s.last_seen ? `, last seen ${ago(s.last_seen)}` : ""}` };
    } catch (e) {
      return { reachable: false, detail: `cannot reach ${phone.relay}: ${String(/** @type {Error} */ (e).message)}` };
    }
  }
  try {
    const mcp = await endpoint(phone, "mcp", run);
    if (!mcp) return { reachable: false, detail: "only the editors' key is saved, which is not checked here" };
    await checkMcp(mcp.url, mcp.token, fetchImpl);
    return { reachable: true, detail: "answering" };
  } catch (e) {
    return { reachable: false, detail: String(/** @type {Error} */ (e).message) };
  }
}

function ago(/** @type {number} */ seconds) {
  const minutes = Math.round((Date.now() / 1000 - seconds) / 60);
  if (minutes < 1) return "just now";
  if (minutes < 120) return `${minutes}m ago`;
  return `${Math.round(minutes / 60)}h ago`;
}

const VIA = { relay: "relay", lan: "LAN", adb: "USB" };

/**
 * @param {{ env?: NodeJS.ProcessEnv, out: NodeJS.WritableStream, json?: boolean, offline?: boolean,
 *   fetchImpl?: typeof fetch, run?: import("./adb.js").Runner }} o
 */
export async function status(o) {
  const data = await load(o.env);
  const phones = Object.values(data.phones);
  const rows = [];
  for (const phone of phones) {
    const check = o.offline ? null : await probe(phone, o.fetchImpl, o.run);
    rows.push({
      id: phone.id,
      name: phone.name,
      via: phone.via,
      default: phone.id === data.default,
      mcp: Boolean(phone.mcp?.token),
      acp: Boolean(phone.acp?.token),
      relay: phone.relay ?? null,
      reachable: check?.reachable ?? null,
      detail: check?.detail ?? null,
    });
  }
  if (o.json) {
    o.out.write(`${JSON.stringify(rows)}\n`);
    return rows;
  }
  if (rows.length === 0) {
    o.out.write("No phone is paired with this computer. Run aster-droid pair.\n");
    return rows;
  }
  for (const r of rows) {
    const what = [r.mcp && "MCP", r.acp && "ACP"].filter(Boolean).join(" and ") || "nothing";
    o.out.write(`${r.default ? "*" : " "} ${r.name} (${r.id}) over ${VIA[r.via]}${r.relay ? ` ${r.relay}` : ""}, ${what}\n`);
    if (r.detail) o.out.write(`    ${r.detail}\n`);
  }
  o.out.write(`\nSaved in ${credentialsPath(o.env)}\n`);
  return rows;
}

/**
 * Forgets one phone, or all of them. The relay keeps the token valid until
 * the phone revokes it, so the person is told where to do that.
 * @param {{ env?: NodeJS.ProcessEnv, out: NodeJS.WritableStream, wanted?: string, all?: boolean, run?: import("./adb.js").Runner }} o
 */
export async function unpair(o) {
  const data = await load(o.env);
  let ids;
  if (o.all) ids = Object.keys(data.phones);
  else {
    const wanted = o.wanted ?? data.default ?? (Object.keys(data.phones).length === 1 ? Object.keys(data.phones)[0] : undefined);
    const phone = wanted ? (data.phones[wanted] ?? Object.values(data.phones).find((p) => p.name === wanted)) : undefined;
    if (!phone) throw new Error(wanted ? `No paired phone is called ${wanted}.` : "Say which phone to unpair, or pass --all.");
    ids = [phone.id];
  }
  const removed = [];
  for (const id of ids) {
    const phone = await removePhone(id, o.env);
    if (!phone) continue;
    removed.push(phone);
    if (phone.via === "adb" && phone.adb) await unforward(phone.adb.serial, [phone.adb.mcp_port, phone.adb.acp_port], o.run);
    o.out.write(`Forgot ${phone.name} (${phone.id}).\n`);
    if (phone.via === "relay") {
      o.out.write("Its token still works until the phone revokes it: in Droid, open Reach from anywhere and remove this computer.\n");
    }
  }
  if (removed.length === 0) o.out.write("No phone is paired with this computer.\n");
  return removed;
}
