import { describe, expect, it } from "vitest";
import { FakePhone, relay } from "./helpers";

const SECRET = "test-admin-secret-0123456789-0123456789";

function flip(phone: string, entitled: boolean, secret = SECRET): Promise<Response> {
  return relay("/v1/admin/entitlement", {
    method: "POST",
    headers: { authorization: `Bearer ${secret}` },
    body: JSON.stringify({ phone, entitled }),
  });
}

describe("entitlement", () => {
  it("is on for every phone while BILLING is off, whatever the flag says", async () => {
    const phone = await FakePhone.create();
    await phone.connect();
    const response = await flip(phone.id, false);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ phone: phone.id, entitled: true });
    expect(await phone.nextCtrl()).toEqual({ t: "entitlement", entitled: true });
  });

  it("needs the admin secret", async () => {
    const phone = await FakePhone.create();
    expect((await flip(phone.id, true, "wrong-wrong-wrong-wrong-wrong-wrong-wrong")).status).toBe(401);
    expect((await relay("/v1/admin/entitlement", { method: "POST", body: "{}" })).status).toBe(401);
  });
});

describe("pages", () => {
  it("serves the pairing page with a strict policy", async () => {
    const response = await relay("/pair");
    expect(response.status).toBe(200);
    expect(response.headers.get("content-security-policy")).toContain("default-src 'none'");
    expect(await response.text()).toContain("pair.js");
  });

  it("answers health checks and CORS preflights", async () => {
    expect(await (await relay("/health")).json()).toEqual({ ok: true });
    const preflight = await relay("/v1/pair/claim", { method: "OPTIONS" });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("access-control-allow-headers")).toContain("authorization");
  });
});
