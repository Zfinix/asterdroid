// @ts-check
// Which addresses a token may be sent to. A relay must be https; plain http
// is only for a relay on this computer, and for the phone itself on a
// private network, the same rule the app and asterctl apply.

export const DEFAULT_RELAY = "https://relay.withaster.dev";
export const MCP_PORT = 7071;
export const ACP_PORT = 7072;
export const MIN_TOKEN = 32;

/** @param {string} host */
export function isLoopbackHost(host) {
  const bare = host.trim().replace(/^\[|\]$/g, "").toLowerCase();
  if (bare === "localhost" || bare === "::1") return true;
  const v4 = ipv4(bare);
  return v4 !== null && v4[0] === 127;
}

/**
 * RFC 1918, loopback, link-local, carrier-grade NAT (Tailscale), IPv6
 * unique-local and link-local, and `.local` names. Only literals count: a
 * name that resolves to a private address today can resolve elsewhere tomorrow.
 * @param {string} host
 */
export function isPrivateHost(host) {
  const bare = host.trim().replace(/^\[|\]$/g, "").toLowerCase();
  if (bare === "") return false;
  if (bare === "localhost" || (bare.endsWith(".local") && bare.length > ".local".length)) return true;
  const v4 = ipv4(bare);
  if (v4) {
    const [a, b] = v4;
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
      (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127);
  }
  if (!bare.includes(":") || !/^[0-9a-f:.%a-z]+$/.test(bare)) return false;
  if (bare === "::1") return true;
  const first = parseInt(bare.split(":")[0] || "0", 16);
  return (first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80;
}

/** @param {string} host */
function ipv4(host) {
  const parts = host.split(".");
  if (parts.length !== 4) return null;
  const out = [];
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const n = Number(part);
    if (n > 255) return null;
    out.push(n);
  }
  return out;
}

/**
 * The relay's origin, refusing anything a token should not travel to.
 * @param {string} url
 */
export function relayOrigin(url) {
  let parsed;
  try {
    parsed = new URL(url.trim());
  } catch {
    throw new Error(`${url} is not a relay address. It should look like ${DEFAULT_RELAY}.`);
  }
  if (parsed.username || parsed.password) throw new Error("A relay address cannot carry a name or password.");
  if (parsed.protocol === "https:") return parsed.origin;
  if (parsed.protocol === "http:" && isLoopbackHost(parsed.hostname)) return parsed.origin;
  throw new Error(`The relay must be https. Plain http is only allowed for a relay on this computer, not ${parsed.host}.`);
}

/** @param {string} host */
export function bracketed(host) {
  return host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;
}

/** @param {string} token */
export function checkToken(token, what = "token") {
  if (token.length < MIN_TOKEN || /\s/.test(token)) {
    throw new Error(`That ${what} is too short. Droid's keys are at least ${MIN_TOKEN} characters with no spaces.`);
  }
  return token;
}

/** @param {Buffer | Uint8Array | string} bytes */
export function b64url(bytes) {
  return Buffer.from(bytes).toString("base64url");
}
