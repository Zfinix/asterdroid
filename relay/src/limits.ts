// Product limits and protocol timings in one place, so docs/RELAY.md can point here.

/** MCP and hook bodies; one JSON-RPC message never comes near it. */
export const MAX_BODY = 1024 * 1024;
/** asterctl gives a verb 120 s, so the relay waits a little longer than that. */
export const MCP_TIMEOUT_MS = 125_000;
/** A phone link that has not signed the challenge by now is closed. */
export const AUTH_TIMEOUT_MS = 10_000;
/** The phone pings every 25 s, so a link quiet for three of those is dead even if no close arrived. */
export const LINK_STALE_MS = 75_000;
/** How long a code claim waits for Allow on the phone. */
export const CLAIM_WAIT_MS = 120_000;
/** A client QR long-poll; the client asks again after a 408. */
export const PAIR_WAIT_MS = 110_000;
export const PAIR_TTL_S = 300;
export const MAX_INFLIGHT = 8;
export const MAX_CLIENTS = 20;
/** `last_used` is written at most this often, to keep a busy client from writing on every call. */
export const LAST_USED_EVERY_MS = 60_000;
export const MAX_NAME = 80;

export const SCOPES = ["phone:read", "phone:act", "mirror:view", "mirror:control", "agent:chat"] as const;
export const CLIENT_KINDS = ["mcp-http", "mcp-shim", "acp-shim", "browser"] as const;
export const DEFAULT_SCOPES = ["phone:act", "agent:chat"];

export const CLOSE = {
  normal: 1000,
  error: 1011,
  replaced: 4001,
  unauthorized: 4401,
  payment: 4402,
  forbidden: 4403,
  busy: 4409,
  offline: 4503,
} as const;
