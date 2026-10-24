import { describe, expect, it } from "vitest";
import { b64url, random } from "../src/auth";
import { FakePhone, mcp, pair, relay } from "./helpers";

function claim(body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return relay("/v1/pair/claim", { method: "POST", body: JSON.stringify(body), headers });
}

describe("pairing by code", () => {
  it("hands out a token once the phone allows it, with snippets", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone, ["phone:read"], "Claude Code on mbp");
    expect(client.phone_id).toBe(phone.id);
    expect(client.scopes).toEqual(["phone:read"]);
    expect(client.token.startsWith(`ast_c_${client.client_id}_`)).toBe(true);
    expect(client.endpoints).toEqual({
      mcp: `http://relay.test/p/${phone.id}/mcp`,
      acp: `ws://relay.test/p/${phone.id}/acp`,
      mirror: null,
    });
    expect(client.snippets.claude).toBe(
      `claude mcp add --transport http phone http://relay.test/p/${phone.id}/mcp --header "Authorization: Bearer ${client.token}"`,
    );
    expect(client.phone_fp).toMatch(/^[a-z2-7]{16}$/);
    const list = await phone.ask({ t: "client.list" });
    expect(list.clients).toEqual([
      expect.objectContaining({ id: client.client_id, name: "Claude Code on mbp", kind: "mcp-http", scopes: ["phone:read"] }),
    ]);
  });

  it("tells the phone who is asking", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const code = await phone.ask({ t: "pair.open", scopes: ["phone:act", "made:up"] });
    const pending = claim({ code: String(code.code).toLowerCase(), client: { name: "Cursor\nnewline", kind: "nope" } }, { "user-agent": "curl/8" });
    const claimed = await phone.nextCtrl();
    expect(claimed).toMatchObject({ t: "pair.claimed", name: "Cursor newline", kind: "mcp-http", scopes: ["phone:act"], ua: "curl/8" });
    expect(String(claimed.pending)).toMatch(/^c_[a-z2-7]{10}$/);
    await phone.ask({ t: "pair.approve", pending: claimed.pending, ok: false });
    expect((await pending).status).toBe(403);
  });

  it("burns a code on its first use", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const code = await phone.ask({ t: "pair.open" });
    const first = claim({ code: code.code, client: { name: "a" } });
    const claimed = await phone.nextCtrl();
    const second = await claim({ code: code.code, client: { name: "b" } });
    expect(second.status).toBe(404);
    expect(await second.json()).toEqual({ error: "invalid_or_expired" });
    await phone.ask({ t: "pair.approve", pending: claimed.pending, ok: true });
    expect((await first).status).toBe(200);
  });

  it("answers an unknown code, a malformed code and the wrong phone alike", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const other = await FakePhone.create();
    const code = await phone.ask({ t: "pair.open" });
    for (const body of [{ code: "ZZZZZZZZ" }, { code: "short" }, { code: code.code, phone: other.id }]) {
      const response = await claim({ ...body, client: { name: "x" } });
      expect(response.status).toBe(404);
      expect(await response.json()).toEqual({ error: "invalid_or_expired" });
    }
  });

  it("gives up with 408 when nobody answers on the phone", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const code = await phone.ask({ t: "pair.open" });
    const response = claim({ code: code.code, client: { name: "x" } });
    await phone.nextCtrl();
    expect((await response).status).toBe(408);
  });

  it("says when the phone is offline", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const code = await phone.ask({ t: "pair.open" });
    phone.socket!.ws.close(1000, "bye");
    await new Promise((r) => setTimeout(r, 50));
    const response = await claim({ code: code.code, client: { name: "x" } });
    expect(response.status).toBe(503);
  });

  it("refuses an approval for a client nobody is waiting on", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    expect(await phone.ask({ t: "pair.approve", pending: "c_aaaaaaaaaa", ok: true })).toMatchObject({ t: "error" });
  });

  it("limits claims per address", async () => {
    let limited = false;
    for (let i = 0; i < 40 && !limited; i++) {
      const response = await claim({ code: "ZZZZZZZZ" }, { "cf-connecting-ip": "203.0.113.9" });
      limited = response.status === 429;
    }
    expect(limited).toBe(true);
  });

  it("limits claims per IPv6 /64, not per address", async () => {
    let limited = false;
    for (let i = 1; i <= 40 && !limited; i++) {
      const response = await claim({ code: "ZZZZZZZZ" }, { "cf-connecting-ip": `2001:db8:2:2::${i.toString(16)}` });
      limited = response.status === 429;
    }
    expect(limited).toBe(true);
  });
});

describe("pairing by client QR", () => {
  it("delivers the token to the waiting client when the phone scans", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const nonce = b64url(random(16));
    const waiting = relay(`/v1/pair/wait/${nonce}`);
    await new Promise((r) => setTimeout(r, 50));
    const added = await phone.ask({ t: "client.add", id: "c_qrqrqrqrqr", nonce, name: "Zed on mbp", kind: "acp-shim", scopes: ["agent:chat"] });
    expect(added).toMatchObject({ t: "client.added", client: { id: "c_qrqrqrqrqr", name: "Zed on mbp", scopes: ["agent:chat"] } });
    const response = await waiting;
    expect(response.status).toBe(200);
    const result = (await response.json()) as { client_id: string; token: string };
    expect(result.client_id).toBe("c_qrqrqrqrqr");
    expect(result.token.startsWith("ast_c_c_qrqrqrqrqr_")).toBe(true);
  });

  it("still hands the token over when an earlier poll hung up", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const nonce = b64url(random(16));
    const gone = new AbortController();
    const first = relay(`/v1/pair/wait/${nonce}`, { signal: gone.signal }).catch(() => null);
    await new Promise((r) => setTimeout(r, 50));
    gone.abort();
    await first;
    const live = relay(`/v1/pair/wait/${nonce}`);
    await new Promise((r) => setTimeout(r, 50));
    expect(await phone.ask({ t: "client.add", id: "c_deaddeadde", nonce, name: "retry" })).toMatchObject({ t: "client.added" });
    const response = await live;
    expect(response.status).toBe(200);
    expect(((await response.json()) as { client_id: string }).client_id).toBe("c_deaddeadde");
  });

  it("keeps the token for a client that starts waiting after the scan, once", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const nonce = b64url(random(16));
    await phone.ask({ t: "client.add", id: "c_latelatela", nonce, name: "late", scopes: ["phone:read"] });
    const first = await relay(`/v1/pair/wait/${nonce}`);
    expect(first.status).toBe(200);
    const second = await relay(`/v1/pair/wait/${nonce}`);
    expect(second.status).toBe(408);
  });

  it("refuses a nonce that was already used, and drops the new client", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const nonce = b64url(random(16));
    await phone.ask({ t: "client.add", id: "c_onceonceon", nonce, name: "once" });
    const again = await phone.ask({ t: "client.add", id: "c_twicetwice", nonce, name: "twice" });
    expect(again).toMatchObject({ t: "error", message: "that claim link was already used" });
    const list = (await phone.ask({ t: "client.list" })).clients as Array<{ id: string }>;
    expect(list.map((c) => c.id)).toEqual(["c_onceonceon"]);
  });

  it("times out a wait with 408 so the client asks again", async () => {
    const response = await relay(`/v1/pair/wait/${b64url(random(16))}`);
    expect(response.status).toBe(408);
  });
});

describe("revoking", () => {
  it("cuts a client off at once", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const keep = await pair(phone);
    const drop = await pair(phone);
    expect(await phone.ask({ t: "client.revoke", id: drop.client_id })).toMatchObject({ t: "revoked", ids: [drop.client_id] });
    expect((await mcp(phone, drop.token, { jsonrpc: "2.0", id: 1, method: "ping" })).status).toBe(401);
    const list = (await phone.ask({ t: "client.list" })).clients as Array<{ id: string }>;
    expect(list.map((c) => c.id)).toEqual([keep.client_id]);
  });

  it("revokes everyone", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const a = await pair(phone);
    const b = await pair(phone);
    const revoked = await phone.ask({ t: "client.revoke_all" });
    expect((revoked.ids as string[]).sort()).toEqual([a.client_id, b.client_id].sort());
    expect((await phone.ask({ t: "client.list" })).clients).toEqual([]);
    expect(await phone.ask({ t: "client.revoke", id: a.client_id })).toMatchObject({ ids: [] });
  });
});
