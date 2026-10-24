// Aster Relay: phones dial in, paired clients reach them from anywhere.
// Routes are listed in docs/RELAY.md; this file only checks shapes and limits
// and hands each request to the phone's Durable Object.

import { bearer, normalizeCode, PHONE_ID, sameSecret } from "./auth";
import { CLAIM_WAIT_MS, CLIENT_KINDS, MAX_NAME, PAIR_WAIT_MS } from "./limits";
import { allowed, cleanName, json } from "./phone";

export { Phone } from "./phone";
export { PairIndex } from "./pair";

const PHONE_ROUTE = /^\/p\/(ph_[a-z2-7]{26})\/(link|mcp|acp|status)$/;
const WAIT_ROUTE = /^\/v1\/pair\/wait\/([A-Za-z0-9_-]{16,64})$/;
/** Headers the Worker sets for the Durable Object; a client never gets to. */
const INTERNAL = ["x-aster-phone", "x-aster-host", "x-aster-origin"];
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "authorization, content-type, mcp-protocol-version, mcp-session-id",
  "access-control-expose-headers": "mcp-session-id",
  "access-control-max-age": "86400",
};
const PAGE_HEADERS = {
  "content-security-policy":
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const phone = PHONE_ROUTE.exec(url.pathname);
    if (phone) return withCors(await phoneRoute(request, env, url, phone[1], phone[2]));
    if (url.pathname === "/v1/pair/claim") return withCors(await claim(request, env, url));
    const wait = WAIT_ROUTE.exec(url.pathname);
    if (wait) return withCors(await pairWait(request, env, wait[1]));
    if (url.pathname === "/v1/admin/entitlement") return admin(request, env);
    if (url.pathname === "/health") return json({ ok: true });
    if (url.pathname === "/" || url.pathname === "/pair" || url.pathname === "/claim") {
      return page(env, url, url.pathname === "/claim" ? "/claim.html" : "/pair.html");
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;

async function phoneRoute(request: Request, env: Env, url: URL, phoneId: string, route: string): Promise<Response> {
  if (route === "mcp" && request.method !== "POST") {
    return json({ error: "method_not_allowed", message: "this MCP endpoint takes POST only" }, 405, { allow: "POST" });
  }
  if (route === "link") {
    if (!(await allowed(env.RL_LINK, ip(request)))) return json({ error: "rate_limited" }, 429);
  } else if (!(await allowed(env.RL_IP, ip(request)))) {
    return json({ error: "rate_limited", message: "over 600 requests a minute from this address" }, 429, { "retry-after": "60" });
  }
  const headers = new Headers(request.headers);
  for (const name of INTERNAL) headers.delete(name);
  headers.set("x-aster-phone", phoneId);
  headers.set("x-aster-host", env.RELAY_HOST || url.host);
  headers.set("x-aster-origin", url.origin);
  return env.PHONE.getByName(phoneId).fetch(new Request(request, { headers }));
}

/** Pairing b1: a client trades the code the phone shows for a token, once the phone says Allow. */
async function claim(request: Request, env: Env, url: URL): Promise<Response> {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  if (!(await allowed(env.RL_CLAIM, ip(request)))) return json({ error: "rate_limited" }, 429, { "retry-after": "60" });
  let body: { code?: unknown; phone?: unknown; client?: { name?: unknown; kind?: unknown; noise_pub?: unknown } };
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad_request", message: "send JSON: {code, client: {name, kind}}" }, 400);
  }
  const code = typeof body.code === "string" ? normalizeCode(body.code) : null;
  const wanted = typeof body.phone === "string" && body.phone !== "" ? body.phone : null;
  if (!code || (wanted !== null && !PHONE_ID.test(wanted))) return json({ error: "invalid_or_expired" }, 404);
  const entry = await env.PAIR.getByName(`code:${code.slice(0, 2)}`).take(code);
  if (!entry || (wanted !== null && wanted !== entry.phone)) return json({ error: "invalid_or_expired" }, 404);
  const kind = (CLIENT_KINDS as readonly string[]).includes(String(body.client?.kind)) ? String(body.client?.kind) : "mcp-http";
  const outcome = await env.PHONE.getByName(entry.phone).claim({
    name: cleanName(body.client?.name).slice(0, MAX_NAME),
    kind,
    scopes: entry.scopes,
    origin: url.origin,
    noise_pub: typeof body.client?.noise_pub === "string" ? body.client.noise_pub.slice(0, 64) : undefined,
    country: typeof request.cf?.country === "string" ? request.cf.country : undefined,
    ua: (request.headers.get("user-agent") ?? "").slice(0, 120) || undefined,
  });
  return new Response(outcome.body, { status: outcome.status, headers: { "content-type": "application/json" } });
}

/** Pairing b2: a client that showed a QR waits here until the phone scans it. */
async function pairWait(request: Request, env: Env, nonce: string): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405, { allow: "GET" });
  if (!(await allowed(env.RL_CLAIM, ip(request)))) return json({ error: "rate_limited" }, 429, { "retry-after": "60" });
  const wait = Math.min(Number(env.CLAIM_WAIT_MS) || CLAIM_WAIT_MS, PAIR_WAIT_MS);
  const result = await env.PAIR.getByName(`nonce:${nonce.slice(0, 2)}`).wait(nonce, wait);
  if (result === null) return json({ error: "timeout", message: "not scanned yet; ask again" }, 408);
  return new Response(result, { headers: { "content-type": "application/json" } });
}

/** Test-only switch for a phone's entitlement. Off unless ADMIN_SECRET is set. */
async function admin(request: Request, env: Env): Promise<Response> {
  const secret = env.ADMIN_SECRET ?? "";
  if (secret.length < 32) return json({ error: "not_found" }, 404);
  if (!(await sameSecret(bearerToken(request), secret))) return json({ error: "unauthorized" }, 401);
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  const body = (await request.json().catch(() => ({}))) as { phone?: unknown; entitled?: unknown };
  if (typeof body.phone !== "string" || !PHONE_ID.test(body.phone) || typeof body.entitled !== "boolean") {
    return json({ error: "bad_request", message: "send {phone, entitled}" }, 400);
  }
  const result = await env.PHONE.getByName(body.phone).setEntitlement(body.entitled);
  return json({ phone: body.phone, ...result });
}

async function page(env: Env, url: URL, path: string): Promise<Response> {
  const asset = await env.ASSETS.fetch(new URL(path, url));
  const headers = new Headers(asset.headers);
  for (const [k, v] of Object.entries(PAGE_HEADERS)) headers.set(k, v);
  return new Response(asset.body, { status: asset.status, headers });
}

function withCors(response: Response): Response {
  if (response.webSocket) return response;
  const headers = new Headers(response.headers);
  for (const [k, v] of Object.entries(CORS)) headers.set(k, v);
  return new Response(response.body, { status: response.status, headers });
}

function bearerToken(request: Request): string {
  return bearer(request)?.token ?? "";
}

/** The caller's rate-limit key: its IPv4 address, or the /64 of an IPv6 one, since one host can hold a whole /64. */
function ip(request: Request): string {
  const addr = request.headers.get("cf-connecting-ip") ?? "unknown";
  if (!addr.includes(":")) return addr;
  if (addr.includes(".")) return addr.slice(addr.lastIndexOf(":") + 1);
  const [head, tail] = addr.toLowerCase().split("::");
  const front = head ? head.split(":") : [];
  const back = tail ? tail.split(":") : [];
  const groups = tail === undefined ? front : [...front, ...Array(Math.max(8 - front.length - back.length, 0)).fill("0"), ...back];
  return `${groups.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, "")).join(":")}::/64`;
}
