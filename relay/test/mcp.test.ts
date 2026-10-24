import { describe, expect, it } from "vitest";
import { newClientId } from "../src/auth";
import * as F from "../src/frame";
import { FakePhone, mcp, pair, relay } from "./helpers";

const ping = { jsonrpc: "2.0", id: 7, method: "ping" };

describe("MCP passthrough", () => {
  it("carries a call to the phone and its reply back", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const call = mcp(phone, client.token, ping);
    const { open, body } = await phone.answerMcp((req) => ({ jsonrpc: "2.0", id: (req as { id: number }).id, result: {} }));
    expect(open).toMatchObject({ svc: "mcp", client: client.client_id, scopes: ["phone:act", "agent:chat"], method: "POST" });
    expect((open.headers as Record<string, string>)["mcp-protocol-version"]).toBe("2025-06-18");
    expect(body).toEqual(ping);
    const response = await call;
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ jsonrpc: "2.0", id: 7, result: {} });
  });

  it("reassembles a reply cut into many frames", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const call = mcp(phone, client.token, ping);
    const big = "x".repeat(F.MAX_CHUNK * 2 + 10);
    await phone.answerMcp(() => ({ jsonrpc: "2.0", id: 7, result: { content: [{ type: "text", text: big }] } }));
    const reply = (await (await call).json()) as { result: { content: Array<{ text: string }> } };
    expect(reply.result.content[0].text.length).toBe(big.length);
  });

  it("says plainly that the phone is offline", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    phone.socket!.ws.close(1000, "bye");
    await new Promise((r) => setTimeout(r, 50));
    const response = await mcp(phone, client.token, ping);
    expect(response.status).toBe(200);
    const reply = (await response.json()) as { id: number; error: { code: number; message: string } };
    expect(reply.id).toBe(7);
    expect(reply.error.code).toBe(-32000);
    expect(reply.error.message).toMatch(/^the phone is offline \(last seen .*\); open Droid on it$/);
  });

  it("answers a notification to an offline phone with 202", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    phone.socket!.ws.close(1000, "bye");
    await new Promise((r) => setTimeout(r, 50));
    const response = await mcp(phone, client.token, { jsonrpc: "2.0", method: "notifications/initialized" });
    expect(response.status).toBe(202);
  });

  it("turns a phone that never answers into a JSON-RPC timeout", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const response = await mcp(phone, client.token, ping);
    const reply = (await response.json()) as { error: { message: string } };
    expect(reply.error.message).toMatch(/did not answer within \d+s; read the screen before trying again/);
    const frames = [await phone.frames.next(), await phone.frames.next(), await phone.frames.next(), await phone.frames.next()];
    expect(frames.map((f) => f.kind)).toEqual([F.OPEN, F.DATA, F.END, F.RESET]);
    expect(F.parse(frames[3])).toMatchObject({ code: "timeout" });
  });

  it("fails a call at once when a new link replaces the one it went out on", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const call = mcp(phone, client.token, ping);
    for (let i = 0; i < 3; i++) await phone.frames.next();
    const started = Date.now();
    await phone.connect();
    const reply = (await (await call).json()) as { error: { message: string } };
    expect(reply.error.message).toBe("the phone reconnected during the call");
    expect(Date.now() - started).toBeLessThan(300);
  });

  it("drops a revoked client's call on both sides", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const call = mcp(phone, client.token, ping);
    const open = await phone.frames.next();
    await phone.frames.next();
    await phone.frames.next();
    phone.ctrl({ t: "client.revoke", id: client.client_id });
    const reset = await phone.frames.next();
    expect([reset.kind, reset.stream, F.parse(reset)]).toEqual([F.RESET, open.stream, { code: "revoked", reason: "the client was revoked" }]);
    const reply = (await (await call).json()) as { error: { message: string } };
    expect(reply.error.message).toBe("this client was revoked");
  });

  it("passes on why the phone refused a stream", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const call = mcp(phone, client.token, ping);
    const open = await phone.frames.next();
    phone.send(F.json(F.RESET, open.stream, { code: "forbidden", reason: "this phone has not approved that client" }));
    const reply = (await (await call).json()) as { error: { message: string } };
    expect(reply.error.message).toBe("this phone has not approved that client");
  });

  it("refuses a missing or wrong token before reaching the phone", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const none = await relay(`/p/${phone.id}/mcp`, { method: "POST", body: JSON.stringify(ping) });
    expect(none.status).toBe(401);
    expect(none.headers.get("www-authenticate")).toBe("Bearer");
    const forged = client.token.slice(0, -4) + "AAAA";
    expect((await mcp(phone, forged, ping)).status).toBe(401);
    const other = await FakePhone.create();
    await other.connect();
    expect((await mcp(other, client.token, ping)).status).toBe(401);
  });

  it("allows only POST on the MCP endpoint", async () => {
    const phone = await FakePhone.create();
    const response = await relay(`/p/${phone.id}/mcp`);
    expect(response.status).toBe(405);
    expect(response.headers.get("allow")).toBe("POST");
  });

  it("refuses MCP to a client paired only for the agent", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone, ["agent:chat"]);
    expect((await mcp(phone, client.token, ping)).status).toBe(403);
  });

  it("refuses a body over 1 MiB", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const response = await mcp(phone, client.token, { jsonrpc: "2.0", id: 1, method: "x", params: "y".repeat(1024 * 1024) });
    expect(response.status).toBe(413);
  });
});

describe("rate limits", () => {
  it("counts a client's requests only once its token checks out", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const junk = client.token.slice(0, -4) + "AAAA";
    for (let i = 0; i < 25; i++) {
      const response = await relay(`/p/${phone.id}/status`, { headers: { authorization: `Bearer ${junk}`, "cf-connecting-ip": "198.51.100.1" } });
      expect(response.status).toBe(401);
    }
    const status = (token: string) => relay(`/p/${phone.id}/status`, { headers: { authorization: `Bearer ${token}`, "cf-connecting-ip": "198.51.100.2" } });
    expect((await status(client.token)).status).toBe(200);
    let limited = false;
    for (let i = 0; i < 25 && !limited; i++) limited = (await status(client.token)).status === 429;
    expect(limited).toBe(true);
  });

  it("limits an address however many client ids it makes up", async () => {
    const phone = await FakePhone.create();
    let limited = false;
    for (let i = 0; i < 120 && !limited; i++) {
      const response = await relay(`/p/${phone.id}/status`, {
        headers: { authorization: `Bearer ast_c_${newClientId()}_${"A".repeat(32)}`, "cf-connecting-ip": "198.51.100.3" },
      });
      limited = response.status === 429;
    }
    expect(limited).toBe(true);
  });
});

describe("status", () => {
  it("tells a paired client whether the phone is online", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const status = await relay(`/p/${phone.id}/status`, { headers: { authorization: `Bearer ${client.token}` } });
    expect(await status.json()).toMatchObject({ online: true, entitled: true, plan: "self-hosted", app: "test" });
    expect((await relay(`/p/${phone.id}/status`)).status).toBe(401);
  });

  it("counts a link that stopped pinging as offline", async () => {
    const quiet = await FakePhone.create();
    await quiet.connect();
    const quietClient = await pair(quiet);
    const pinging = await FakePhone.create();
    await pinging.connect();
    const pingingClient = await pair(pinging);
    const pinger = setInterval(() => pinging.socket!.ws.send("ping"), 500);
    await new Promise((r) => setTimeout(r, 3300));
    clearInterval(pinger);
    const online = async (phone: FakePhone, token: string) =>
      ((await (await relay(`/p/${phone.id}/status`, { headers: { authorization: `Bearer ${token}` } })).json()) as { online: boolean }).online;
    expect(await online(pinging, pingingClient.token)).toBe(true);
    expect(await online(quiet, quietClient.token)).toBe(false);
    const reply = (await (await mcp(quiet, quietClient.token, ping)).json()) as { error: { message: string } };
    expect(reply.error.message).toMatch(/^the phone is offline/);
  });
});
