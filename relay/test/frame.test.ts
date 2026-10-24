import { describe, expect, it } from "vitest";
import * as F from "../src/frame";
import vectors from "./frame-vectors.json";

const unhex = (hex: string) => Uint8Array.from(hex.match(/../g) ?? [], (b) => parseInt(b, 16));
const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

describe("frame", () => {
  it("encodes like the golden vectors", () => {
    for (const c of vectors.frames) {
      expect(hex(F.encode(c.kind, c.stream, unhex(c.payload_hex))), c.name).toBe(c.frame_hex);
    }
  });

  it("decodes like the golden vectors", () => {
    for (const c of vectors.frames) {
      const frame = F.decode(unhex(c.frame_hex))!;
      expect([frame.kind, frame.stream, hex(frame.payload)], c.name).toEqual([c.kind, c.stream, c.payload_hex]);
    }
  });

  it("rejects a message shorter than the header", () => {
    expect(F.decode(new Uint8Array([0, 0, 0, 0]))).toBeNull();
  });

  it("chunks a body like the vectors", () => {
    const body = new Uint8Array(vectors.chunk.body_len);
    expect(F.chunks(3, body).map((f) => f.length - F.HEADER)).toEqual(vectors.chunk.frame_lens);
    expect(F.chunks(3, new Uint8Array())).toEqual([]);
  });

  it("round-trips tunnelled text and binary messages", () => {
    expect(F.wsPayload(F.decode(F.wsMessage(5, "hi"))!)).toBe("hi");
    expect(F.wsPayload(F.decode(F.wsMessage(5, new Uint8Array([1, 2])))!)).toEqual(new Uint8Array([1, 2]));
    expect(hex(F.wsMessage(5, "hi"))).toBe(vectors.frames.find((c) => c.name === "wsmsg text")!.frame_hex);
  });
});
