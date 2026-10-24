import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // Short waits so timeouts are testable; the real values are in src/limits.ts.
        bindings: {
          MCP_TIMEOUT_MS: "400",
          CLAIM_WAIT_MS: "1500",
          LINK_STALE_MS: "3000",
          ADMIN_SECRET: "test-admin-secret-0123456789-0123456789",
        },
        ratelimits: {
          RL_CLAIM: { namespace_id: "1001", simple: { limit: 30, period: 60 } },
          RL_CLIENT: { namespace_id: "1002", simple: { limit: 20, period: 60 } },
          RL_LINK: { namespace_id: "1003", simple: { limit: 1000, period: 60 } },
          RL_IP: { namespace_id: "1004", simple: { limit: 100, period: 60 } },
        },
      },
    }),
  ],
});
