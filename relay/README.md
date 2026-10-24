# Aster Relay

Reach a phone running Droid from anywhere. The phone dials out to this Worker
over one WebSocket; MCP clients and ACP editors reach it through the Worker
with a token the phone approved. The wire format, pairing, scopes and threat
model are in [docs/RELAY.md](../docs/RELAY.md).

## Deploy your own

```sh
npm install
npx wrangler login
npx wrangler deploy
```

Then set `ASTER_RELAY_URL` on the phone to the `https://aster-relay.<account>.workers.dev`
address it prints. `BILLING` is `off`, so every phone that links is entitled.
`routes` is commented out in `wrangler.jsonc` on purpose; uncomment and edit it
only for a custom domain you own.

## Develop

```sh
npm test            # vitest inside workerd, with a fake phone
npm run typecheck
npm run types       # after changing wrangler.jsonc
npx wrangler dev    # http://127.0.0.1:8787, for `asterctl relay` with ASTER_RELAY_URL set to it
```

| Path | What |
| --- | --- |
| `src/index.ts` | Router, rate limits, pairing endpoints, admin switch |
| `src/phone.ts` | `Phone` Durable Object: link auth, streams, clients, entitlement |
| `src/pair.ts` | `PairIndex` Durable Object: short codes and client-QR nonces |
| `src/frame.ts` | The frame codec, shared with `client/src/frame.rs` |
| `src/auth.ts` | Phone ids, link signatures, tokens, codes |
| `public/` | The `/pair` code-entry page and the `/claim` landing page |
| `test/frame-vectors.json` | Golden vectors both codecs test against |
