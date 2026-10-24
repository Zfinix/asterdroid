// Values that are not in wrangler.jsonc: secrets, and knobs only tests set.
interface Env {
  /** Enables /v1/admin/*; at least 32 characters. */
  ADMIN_SECRET?: string;
  MCP_TIMEOUT_MS?: string;
  CLAIM_WAIT_MS?: string;
  LINK_STALE_MS?: string;
}
