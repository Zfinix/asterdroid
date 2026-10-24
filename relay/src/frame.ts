// The link's framing, shared with client/src/frame.rs: one binary WebSocket
// message is `u8 kind | u32 BE stream | payload`. docs/RELAY.md is the spec, and
// both sides test against test/frame-vectors.json.

export const CTRL = 0x00;
export const OPEN = 0x01;
export const DATA = 0x02;
export const HEAD = 0x03;
export const END = 0x04;
export const RESET = 0x05;
export const WSMSG = 0x06;
export const CREDIT = 0x07;
export const HEADER = 5;
/** A screenshot is cut into frames this size so it never holds up another stream for long. */
export const MAX_CHUNK = 256 * 1024;

const WS_TEXT = 0;
const WS_BINARY = 1;
const utf8 = new TextEncoder();
const text = new TextDecoder();

export interface Frame {
  kind: number;
  stream: number;
  payload: Uint8Array;
}

export function encode(kind: number, stream: number, payload: Uint8Array = new Uint8Array()): Uint8Array {
  const out = new Uint8Array(HEADER + payload.length);
  const view = new DataView(out.buffer);
  view.setUint8(0, kind);
  view.setUint32(1, stream >>> 0, false);
  out.set(payload, HEADER);
  return out;
}

/** `null` for a message too short to hold a header. Unknown kinds decode, so callers can skip them. */
export function decode(bytes: ArrayBuffer | Uint8Array): Frame | null {
  const buf = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (buf.length < HEADER) return null;
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  return { kind: view.getUint8(0), stream: view.getUint32(1, false), payload: buf.subarray(HEADER) };
}

export function json(kind: number, stream: number, value: unknown): Uint8Array {
  return encode(kind, stream, utf8.encode(JSON.stringify(value)));
}

export function ctrl(value: unknown): Uint8Array {
  return json(CTRL, 0, value);
}

export function parse<T = Record<string, unknown>>(frame: Frame): T | null {
  try {
    return JSON.parse(text.decode(frame.payload)) as T;
  } catch {
    return null;
  }
}

/** A body as DATA frames of at most MAX_CHUNK bytes; an empty body is no frames. */
export function chunks(stream: number, body: Uint8Array): Uint8Array[] {
  const out: Uint8Array[] = [];
  for (let at = 0; at < body.length; at += MAX_CHUNK) {
    out.push(encode(DATA, stream, body.subarray(at, at + MAX_CHUNK)));
  }
  return out;
}

export function wsMessage(stream: number, message: string | ArrayBuffer | Uint8Array): Uint8Array {
  const isText = typeof message === "string";
  const data = isText ? utf8.encode(message) : message instanceof Uint8Array ? message : new Uint8Array(message);
  const payload = new Uint8Array(data.length + 1);
  payload[0] = isText ? WS_TEXT : WS_BINARY;
  payload.set(data, 1);
  return encode(WSMSG, stream, payload);
}

/** The tunnelled message a WSMSG frame carries, or `null` for an empty one. */
export function wsPayload(frame: Frame): string | Uint8Array | null {
  if (frame.payload.length === 0) return null;
  const body = frame.payload.subarray(1);
  if (frame.payload[0] === WS_TEXT) return text.decode(body);
  if (frame.payload[0] === WS_BINARY) return body;
  return null;
}
