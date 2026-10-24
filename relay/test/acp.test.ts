import { describe, expect, it } from "vitest";
import * as F from "../src/frame";
import { FakePhone, pair, Socket } from "./helpers";

async function openAcp(phone: FakePhone, token: string, via: "header" | "subprotocol" = "header") {
  const headers: Record<string, string> =
    via === "header" ? { authorization: `Bearer ${token}` } : { "sec-websocket-protocol": `acp, bearer.${token}` };
  return Socket.open(`/p/${phone.id}/acp`, headers);
}

describe("ACP over the relay", () => {
  it("tunnels JSON-RPC lines both ways", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const { socket } = await openAcp(phone, client.token);
    const open = await phone.frames.next();
    expect(open.kind).toBe(F.OPEN);
    expect(F.parse(open)).toEqual({ svc: "acp", client: client.client_id, scopes: client.scopes });
    socket!.ws.send('{"jsonrpc":"2.0","id":1,"method":"initialize"}');
    const up = await phone.frames.next();
    expect([up.kind, up.stream, F.wsPayload(up)]).toEqual([F.WSMSG, open.stream, '{"jsonrpc":"2.0","id":1,"method":"initialize"}']);
    phone.send(F.wsMessage(open.stream, '{"jsonrpc":"2.0","id":1,"result":{}}'));
    expect(await socket!.messages.next()).toBe('{"jsonrpc":"2.0","id":1,"result":{}}');
    phone.send(F.encode(F.END, open.stream));
    expect(await socket!.closed.next()).toMatchObject({ code: 1000 });
  });

  it("takes the token as a subprotocol and echoes it", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const { socket, response } = await openAcp(phone, client.token, "subprotocol");
    expect(response.status).toBe(101);
    expect(response.headers.get("sec-websocket-protocol")).toBe(`bearer.${client.token}`);
    expect((await phone.frames.next()).kind).toBe(F.OPEN);
    socket!.ws.close(1000, "done");
    expect((await phone.frames.next()).kind).toBe(F.END);
  });

  it("closes a second editor as busy when the phone says so", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const { socket } = await openAcp(phone, client.token);
    const open = await phone.frames.next();
    phone.send(F.json(F.RESET, open.stream, { code: "busy", reason: "another editor is driving the phone" }));
    expect(await socket!.closed.next()).toEqual({ code: 4409, reason: "busy" });
  });

  it("closes with 4503 when the phone is offline", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    phone.socket!.ws.close(1000, "bye");
    await new Promise((r) => setTimeout(r, 50));
    const { socket } = await openAcp(phone, client.token);
    expect(await socket!.closed.next()).toMatchObject({ code: 4503 });
  });

  it("refuses a client without agent:chat, and one without a token", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone, ["phone:read"]);
    expect((await openAcp(phone, client.token)).response.status).toBe(403);
    expect((await Socket.open(`/p/${phone.id}/acp`, {})).response.status).toBe(401);
  });

  it("ends the session on both sides when its client is revoked", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const { socket } = await openAcp(phone, client.token);
    const open = await phone.frames.next();
    phone.ctrl({ t: "client.revoke", id: client.client_id });
    const end = await phone.frames.next();
    expect([end.kind, end.stream]).toEqual([F.END, open.stream]);
    expect(await phone.nextCtrl()).toMatchObject({ t: "revoked", ids: [client.client_id] });
    expect(await socket!.closed.next()).toMatchObject({ code: 4401, reason: "revoked" });
  });

  it("closes the session when a new link replaces the phone's", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const client = await pair(phone);
    const { socket } = await openAcp(phone, client.token);
    await phone.frames.next();
    await phone.connect();
    expect(await socket!.closed.next()).toEqual({ code: 4503, reason: "the phone reconnected" });
  });
});
