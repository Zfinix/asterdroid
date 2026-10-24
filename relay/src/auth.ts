// Identities and secrets: the phone id derived from its key, the link
// signature, client tokens and pairing codes. Nothing here is logged.

const utf8 = new TextEncoder();
const BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
/** Crockford base32: no I, L, O or U, so a code read aloud or off a screen survives. */
const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
const LINK_LABEL = "aster-relay/v1/link";
const FP_LABEL = "aster-relay/v1/fp\n";

export const PHONE_ID = /^ph_[a-z2-7]{26}$/;
export const CLIENT_ID = /^c_[a-z2-7]{10}$/;
const TOKEN = /^ast_c_(c_[a-z2-7]{10})_([0-9A-Za-z]{32})$/;

export function base32(bytes: Uint8Array): string {
  let out = "";
  let acc = 0;
  let bits = 0;
  for (const b of bytes) {
    acc = ((acc << 8) | b) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      out += BASE32[(acc >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32[(acc << (5 - bits)) & 31];
  return out;
}

export function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromB64url(s: string): Uint8Array | null {
  try {
    const plain = s.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(plain + "=".repeat((4 - (plain.length % 4)) % 4));
    return Uint8Array.from(bin, (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

export async function sha256(data: Uint8Array | string): Promise<Uint8Array> {
  const bytes = typeof data === "string" ? utf8.encode(data) : data;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function sha256Hex(data: Uint8Array | string): Promise<string> {
  return [...(await sha256(data))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The phone id is a hash of its key, so it needs no registration and nobody else can claim it. */
export async function phoneIdOf(pub: Uint8Array): Promise<string> {
  return "ph_" + base32(await sha256(pub)).slice(0, 26);
}

/** Shown in the pairing QR so a client can check it reached this phone. */
export async function fingerprint(pub: Uint8Array): Promise<string> {
  const msg = new Uint8Array([...utf8.encode(FP_LABEL), ...pub]);
  return base32(await sha256(msg)).slice(0, 16);
}

/** The host is signed too, so one relay cannot replay a phone's answer to another. */
export function linkMessage(phoneId: string, nonce: string, host: string): string {
  return `${LINK_LABEL}\n${phoneId}\n${nonce}\n${host}`;
}

export async function verifyLink(pub: Uint8Array, phoneId: string, nonce: string, host: string, sig: string): Promise<boolean> {
  const signature = fromB64url(sig);
  if (!signature || signature.length !== 64 || pub.length !== 32) return false;
  try {
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, utf8.encode(linkMessage(phoneId, nonce, host)));
  } catch {
    return false;
  }
}

export function random(n: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(n));
}

function pick(alphabet: string, length: number): string {
  // Rejection sampling keeps every character equally likely.
  const limit = 256 - (256 % alphabet.length);
  let out = "";
  while (out.length < length) {
    for (const b of random(length * 2)) {
      if (b < limit && out.length < length) out += alphabet[b % alphabet.length];
    }
  }
  return out;
}

export function newClientId(): string {
  return "c_" + pick(BASE32, 10);
}

/** 40 random bits as 8 Crockford characters. */
export function newCode(): string {
  return pick(CROCKFORD, 8);
}

/** What a person typed, as the code it was meant to be: case, dashes and look-alikes forgiven. */
export function normalizeCode(input: string): string | null {
  const code = input
    .toUpperCase()
    .replace(/[\s-]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  return code.length === 8 && [...code].every((c) => CROCKFORD.includes(c)) ? code : null;
}

/** `ast_c_<clientId>_<secret>`; the fixed prefix lets secret scanners catch a leaked token. */
export function mintToken(clientId: string): { token: string; secret: string } {
  const secret = pick(BASE62, 32);
  return { token: `ast_c_${clientId}_${secret}`, secret };
}

export function parseToken(token: string): { clientId: string; secret: string } | null {
  const m = TOKEN.exec(token);
  return m ? { clientId: m[1], secret: m[2] } : null;
}

export function hashSecret(secret: string): Promise<string> {
  return sha256Hex(secret);
}

export function sameHex(a: string, b: string): boolean {
  const x = utf8.encode(a);
  const y = utf8.encode(b);
  if (x.length !== y.length) return false;
  return crypto.subtle.timingSafeEqual(x, y);
}

export function sameSecret(a: string, b: string): Promise<boolean> {
  return Promise.all([sha256(a), sha256(b)]).then(([x, y]) => crypto.subtle.timingSafeEqual(x, y));
}

/**
 * The bearer token from `Authorization`, or from a `bearer.<token>` WebSocket
 * subprotocol, which is the only way a browser can send one on a socket.
 */
export function bearer(request: Request): { token: string; protocol?: string } | null {
  const header = request.headers.get("authorization");
  if (header?.startsWith("Bearer ")) return { token: header.slice(7).trim() };
  const offered = (request.headers.get("sec-websocket-protocol") ?? "").split(",").map((p) => p.trim());
  const protocol = offered.find((p) => p.startsWith("bearer."));
  return protocol ? { token: protocol.slice(7), protocol } : null;
}
