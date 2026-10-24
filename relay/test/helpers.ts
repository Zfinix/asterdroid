import { exports } from "cloudflare:workers";
import { b64url, linkMessage, phoneIdOf } from "../src/auth";
import * as F from "../src/frame";

export const ORIGIN = "http://relay.test";
const utf8 = new TextEncoder();

export function relay(path: string, init?: RequestInit): Promise<Response> {
  return exports.default.fetch(new Request(`${ORIGIN}${path}`, init));
}

/** A queue that a test can await one item at a time. */
export class Inbox<T> {
  private items: T[] = [];
  private waiters: Array<(item: T) => void> = [];

  push(item: T): void {
    const waiter = this.waiters.shift();
    if (waiter) waiter(item);
    else this.items.push(item);
  }

  next(ms = 3000): Promise<T> {
    const item = this.items.shift();
    if (item !== undefined) return Promise.resolve(item);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`nothing arrived within ${ms}ms`)), ms);
      this.waiters.push((it) => {
        clearTimeout(timer);
        resolve(it);
      });
    });
  }
}

export interface Closed {
  code: number;
  reason: string;
}

/** A socket opened against the relay, with its messages and its close queued up. */
export class Socket {
  readonly messages = new Inbox<string | ArrayBuffer>();
  readonly closed = new Inbox<Closed>();

  constructor(readonly ws: WebSocket) {
    ws.binaryType = "arraybuffer";
    ws.accept();
    ws.addEventListener("message", (e) => this.messages.push(e.data as string | ArrayBuffer));
    ws.addEventListener("close", (e) => this.closed.push({ code: e.code, reason: e.reason }));
  }

  static async open(path: string, headers: Record<string, string>): Promise<{ socket?: Socket; response: Response }> {
    const response = await relay(path, { headers: { upgrade: "websocket", ...headers } });
    return { socket: response.webSocket ? new Socket(response.webSocket) : undefined, response };
  }
}

/** The phone's side of the link, played by the test: it signs the challenge and answers frames. */
export class FakePhone {
  socket?: Socket;
  readonly frames = new Inbox<F.Frame>();

  private constructor(
    readonly id: string,
    readonly pub: Uint8Array,
    private readonly key: CryptoKey,
  ) {}

  static async create(): Promise<FakePhone> {
    const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const pub = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
    return new FakePhone(await phoneIdOf(pub), pub, pair.privateKey);
  }

  async sign(nonce: string, host: string): Promise<string> {
    const sig = await crypto.subtle.sign({ name: "Ed25519" }, this.key, utf8.encode(linkMessage(this.id, nonce, host)));
    return b64url(new Uint8Array(sig));
  }

  /** Dials the link and reads the challenge, without answering it. */
  async dial(): Promise<{ challenge: Record<string, unknown>; socket: Socket }> {
    const { socket, response } = await Socket.open(`/p/${this.id}/link`, { "x-aster-pub": b64url(this.pub) });
    if (!socket) throw new Error(`link refused: ${response.status} ${await response.text()}`);
    this.socket = socket;
    const first = F.decode((await socket.messages.next()) as ArrayBuffer)!;
    socket.ws.addEventListener("message", (e) => {
      if (typeof e.data !== "string") this.frames.push(F.decode(e.data as ArrayBuffer)!);
    });
    return { challenge: F.parse(first)!, socket };
  }

  async connect(): Promise<Record<string, unknown>> {
    const { challenge } = await this.dial();
    this.ctrl({ t: "auth", sig: await this.sign(String(challenge.nonce), String(challenge.host)), app: "test", abi: "aarch64" });
    const ready = await this.nextCtrl();
    if (ready.t !== "ready") throw new Error(`expected ready, got ${JSON.stringify(ready)}`);
    return ready;
  }

  send(bytes: Uint8Array): void {
    this.socket!.ws.send(bytes);
  }

  ctrl(msg: Record<string, unknown>): void {
    this.send(F.ctrl(msg));
  }

  async nextCtrl(): Promise<Record<string, unknown>> {
    const frame = await this.frames.next();
    if (frame.kind !== F.CTRL) throw new Error(`expected CTRL, got kind ${frame.kind}`);
    return F.parse(frame)!;
  }

  /** A CTRL request and the reply that carries its rid. */
  async ask(msg: Record<string, unknown>): Promise<Record<string, unknown>> {
    const rid = `r${Math.random().toString(36).slice(2)}`;
    this.ctrl({ ...msg, rid });
    const reply = await this.nextCtrl();
    if (reply.rid !== rid) throw new Error(`reply for another request: ${JSON.stringify(reply)}`);
    return reply;
  }

  /** Answers one MCP stream with `reply`, the way `asterctl relay` does. */
  async answerMcp(reply: (request: unknown) => unknown): Promise<{ open: Record<string, unknown>; body: unknown }> {
    const open = await this.frames.next();
    if (open.kind !== F.OPEN) throw new Error(`expected OPEN, got kind ${open.kind}`);
    const parts: Uint8Array[] = [];
    for (;;) {
      const f = await this.frames.next();
      if (f.kind === F.END) break;
      parts.push(f.payload.slice());
    }
    const body = JSON.parse(new TextDecoder().decode(concat(parts)));
    const answer = utf8.encode(JSON.stringify(reply(body)));
    this.send(F.json(F.HEAD, open.stream, { status: 200, headers: { "content-type": "application/json" } }));
    for (const chunk of F.chunks(open.stream, answer)) this.send(chunk);
    this.send(F.encode(F.END, open.stream));
    return { open: F.parse(open)!, body };
  }
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

export interface Paired {
  token: string;
  client_id: string;
  phone_id: string;
  scopes: string[];
  endpoints: { mcp: string; acp: string; mirror: null };
  snippets: Record<string, unknown>;
  phone_fp: string;
}

/** Pairs a client by short code, with the phone saying Allow. */
export async function pair(phone: FakePhone, scopes = ["phone:act", "agent:chat"], name = "Test client"): Promise<Paired> {
  const code = await phone.ask({ t: "pair.open", scopes, ttl: 300 });
  const claim = relay("/v1/pair/claim", {
    method: "POST",
    body: JSON.stringify({ code: code.code, phone: phone.id, client: { name, kind: "mcp-http" } }),
  });
  const claimed = await phone.nextCtrl();
  if (claimed.t !== "pair.claimed") throw new Error(`expected pair.claimed, got ${JSON.stringify(claimed)}`);
  const ok = await phone.ask({ t: "pair.approve", pending: claimed.pending, ok: true });
  if (ok.t !== "ok") throw new Error(`approve failed: ${JSON.stringify(ok)}`);
  const response = await claim;
  if (response.status !== 200) throw new Error(`claim failed: ${response.status} ${await response.text()}`);
  return (await response.json()) as Paired;
}

export function mcp(phone: FakePhone, token: string, body: unknown): Promise<Response> {
  return relay(`/p/${phone.id}/mcp`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", "mcp-protocol-version": "2025-06-18" },
    body: JSON.stringify(body),
  });
}
