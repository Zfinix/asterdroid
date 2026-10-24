import { describe, expect, it } from "vitest";
import { fingerprint, fromB64url, linkMessage, mintToken, newClientId, newCode, normalizeCode, parseToken, phoneIdOf, verifyLink, CLIENT_ID } from "../src/auth";
import vectors from "./frame-vectors.json";

const id = vectors.identity;

describe("auth", () => {
  it("derives the phone id and fingerprint like asterctl", async () => {
    const pub = fromB64url(id.pub_b64url)!;
    expect(await phoneIdOf(pub)).toBe(id.phone_id);
    expect(await fingerprint(pub)).toBe(id.fp);
    expect(linkMessage(id.phone_id, id.nonce, id.host)).toBe(id.message);
  });

  it("verifies the signature asterctl makes, and only for its host", async () => {
    const pub = fromB64url(id.pub_b64url)!;
    expect(await verifyLink(pub, id.phone_id, id.nonce, id.host, id.sig_b64url)).toBe(true);
    expect(await verifyLink(pub, id.phone_id, id.nonce, "other.example", id.sig_b64url)).toBe(false);
    expect(await verifyLink(pub, id.phone_id, "another-nonce", id.host, id.sig_b64url)).toBe(false);
    expect(await verifyLink(pub, id.phone_id, id.nonce, id.host, "not-a-signature")).toBe(false);
  });

  it("mints tokens that parse back to their client", () => {
    const clientId = newClientId();
    expect(clientId).toMatch(CLIENT_ID);
    const { token, secret } = mintToken(clientId);
    expect(token).toMatch(/^ast_c_c_[a-z2-7]{10}_[0-9A-Za-z]{32}$/);
    expect(parseToken(token)).toEqual({ clientId, secret });
    expect(parseToken("ast_c_c_short_abc")).toBeNull();
    expect(parseToken(`${token}x`)).toBeNull();
  });

  it("forgives how a person types a code", () => {
    const code = newCode();
    expect(code).toMatch(/^[0-9A-HJKMNP-TV-Z]{8}$/);
    expect(normalizeCode("7k2q-9mxd")).toBe("7K2Q9MXD");
    expect(normalizeCode("OIL2 3456")).toBe("01123456");
    expect(normalizeCode("7K2Q9MX")).toBeNull();
    expect(normalizeCode("7K2Q9MXU")).toBeNull();
  });
});
