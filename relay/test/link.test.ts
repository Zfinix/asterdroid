import { describe, expect, it } from "vitest";
import { b64url } from "../src/auth";
import * as F from "../src/frame";
import { FakePhone, Socket } from "./helpers";

describe("phone link", () => {
  it("challenges, verifies the signature and says ready", async () => {
    const phone = await FakePhone.create();
    const { challenge, socket } = await phone.dial();
    expect(challenge).toMatchObject({ t: "challenge", host: "relay.test" });
    expect(String(challenge.nonce).length).toBeGreaterThanOrEqual(43);
    phone.ctrl({ t: "auth", sig: await phone.sign(String(challenge.nonce), "relay.test"), app: "0.3.0" });
    const ready = await phone.nextCtrl();
    expect(ready).toMatchObject({ t: "ready", entitled: true, plan: "self-hosted", clients: [] });
    socket.ws.close(1000, "done");
  });

  it("closes a link whose signature does not verify", async () => {
    const phone = await FakePhone.create();
    const { challenge, socket } = await phone.dial();
    phone.ctrl({ t: "auth", sig: await phone.sign(String(challenge.nonce), "elsewhere.example") });
    expect(await socket.closed.next()).toMatchObject({ code: 4401 });
  });

  it("closes a link that sends anything before signing", async () => {
    const phone = await FakePhone.create();
    const { socket } = await phone.dial();
    phone.send(F.json(F.OPEN, 1, { svc: "mcp" }));
    expect(await socket.closed.next()).toMatchObject({ code: 4401 });
  });

  it("refuses a key that does not own the phone id", async () => {
    const phone = await FakePhone.create();
    const other = await FakePhone.create();
    const { socket, response } = await Socket.open(`/p/${phone.id}/link`, { "x-aster-pub": b64url(other.pub) });
    expect(socket).toBeUndefined();
    expect(response.status).toBe(403);
  });

  it("refuses a link that is not a WebSocket", async () => {
    const phone = await FakePhone.create();
    const response = await (await import("./helpers")).relay(`/p/${phone.id}/link`, { headers: { "x-aster-pub": b64url(phone.pub) } });
    expect(response.status).toBe(426);
  });

  it("lets a new link replace the old one", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const first = phone.socket!;
    await phone.connect();
    expect(await first.closed.next()).toMatchObject({ code: 4001, reason: "replaced" });
  });

  it("does not let an unsigned link push the phone off", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const live = phone.socket!;
    const intruder = await FakePhone.create();
    const { socket } = await Socket.open(`/p/${phone.id}/link`, { "x-aster-pub": b64url(phone.pub) });
    socket!.ws.send(F.ctrl({ t: "auth", sig: await intruder.sign("x", "relay.test") }));
    expect(await socket!.closed.next()).toMatchObject({ code: 4401 });
    expect(live.ws.readyState).toBe(WebSocket.OPEN);
  });
});
