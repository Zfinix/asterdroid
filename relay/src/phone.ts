import { DurableObject } from "cloudflare:workers";
import * as F from "./frame";
import {
  b64url,
  bearer,
  CLIENT_ID,
  fingerprint,
  fromB64url,
  hashSecret,
  mintToken,
  newClientId,
  newCode,
  parseToken,
  phoneIdOf,
  random,
  sameHex,
  verifyLink,
} from "./auth";
import {
  AUTH_TIMEOUT_MS,
  CLAIM_WAIT_MS,
  CLOSE,
  DEFAULT_SCOPES,
  LAST_USED_EVERY_MS,
  LINK_STALE_MS,
  MAX_BODY,
  MAX_CLIENTS,
  MAX_INFLIGHT,
  MCP_TIMEOUT_MS,
  PAIR_TTL_S,
  SCOPES,
} from "./limits";
import { snippets } from "./snippets";

interface PhoneTag {
  role: "phone";
  authed: boolean;
  nonce: string;
  host: string;
  pub: string;
  opened: number;
}

interface AcpTag {
  role: "acp";
  stream: number;
  client: string;
}

type Tag = PhoneTag | AcpTag;

interface ClientRow {
  id: string;
  name: string;
  kind: string;
  token_hash: string;
  scopes: string;
  created: number;
  last_used: number;
  [key: string]: SqlStorageValue;
}

type McpReply =
  | { ok: true; status: number; contentType: string; body: Uint8Array }
  | { ok: false; code: string; reason: string };

interface Pending {
  client: string;
  finish: (reply: McpReply) => void;
  status: number;
  contentType: string;
  body: Uint8Array[];
  size: number;
}

/** What a client said about itself when it asked to pair, plus what the Worker saw. */
export interface ClaimInfo {
  name: string;
  kind: string;
  scopes: string[];
  origin: string;
  noise_pub?: string;
  country?: string;
  ua?: string;
}

/** The body is JSON text, which crosses the RPC boundary as is. */
export interface ClaimOutcome {
  status: number;
  body: string;
}

/** A reply to a phone stream can be large, but not unbounded: a DO has 128 MB. */
const MAX_REPLY = 16 * 1024 * 1024;

/**
 * One per phone, named by its phone id. It holds the phone's link (hibernating
 * while idle, so an idle phone costs nothing), its paired clients, and the
 * streams in flight between the two.
 */
export class Phone extends DurableObject<Env> {
  private mcp = new Map<number, Pending>();
  private claims = new Map<string, (ok: boolean) => void>();
  private seenAt = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Keepalives are answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS clients (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, token_hash TEXT NOT NULL,
          scopes TEXT NOT NULL, created INTEGER NOT NULL, last_used INTEGER NOT NULL DEFAULT 0,
          revoked INTEGER NOT NULL DEFAULT 0
        );
      `);
    });
  }

  async fetch(request: Request): Promise<Response> {
    const phoneId = request.headers.get("x-aster-phone") ?? "";
    switch (new URL(request.url).pathname.split("/")[3]) {
      case "link":
        return this.link(request, phoneId);
      case "mcp":
        return this.mcpRequest(request);
      case "acp":
        return this.acpSocket(request);
      case "status":
        return this.statusRequest(request);
      default:
        return json({ error: "not_found" }, 404);
    }
  }

  // The phone's link.

  private async link(request: Request, phoneId: string): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "the link is a WebSocket" }, 426);
    }
    const pub = fromB64url(request.headers.get("x-aster-pub") ?? "");
    if (!pub || pub.length !== 32 || (await phoneIdOf(pub)) !== phoneId) {
      return json({ error: "that key does not own this phone id" }, 403);
    }
    const { 0: client, 1: server } = new WebSocketPair();
    const nonce = b64url(random(32));
    const host = request.headers.get("x-aster-host") ?? "";
    this.ctx.acceptWebSocket(server, ["phone"]);
    const tag: PhoneTag = { role: "phone", authed: false, nonce, host, pub: b64url(pub), opened: Date.now() };
    server.serializeAttachment(tag);
    this.setMeta("phone_id", phoneId);
    this.setMeta("origin", request.headers.get("x-aster-origin") ?? "");
    server.send(F.ctrl({ t: "challenge", nonce, host }));
    await this.alarmBy(Date.now() + AUTH_TIMEOUT_MS);
    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm(): Promise<void> {
    let next = 0;
    for (const ws of this.ctx.getWebSockets("phone")) {
      const tag = ws.deserializeAttachment() as PhoneTag;
      if (tag.authed) continue;
      const due = tag.opened + AUTH_TIMEOUT_MS;
      if (due <= Date.now()) ws.close(CLOSE.unauthorized, "the challenge was not signed in time");
      else next = next === 0 ? due : Math.min(next, due);
    }
    if (next > 0) await this.ctx.storage.setAlarm(next);
  }

  private async alarmBy(when: number): Promise<void> {
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > when) await this.ctx.storage.setAlarm(when);
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const tag = ws.deserializeAttachment() as Tag | null;
    if (!tag) return;
    if (tag.role === "acp") return this.fromClient(ws, tag, message);
    if (typeof message === "string") return;
    const frame = F.decode(message);
    if (!frame) return;
    if (!tag.authed) return this.authenticate(ws, tag, frame);
    this.touch();
    if (frame.kind === F.CTRL) return this.control(ws, F.parse(frame) ?? {});
    this.fromPhone(frame);
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    this.ended(ws);
    try {
      ws.close(code === 1005 || code === 1006 ? CLOSE.normal : code, reason);
    } catch {
      // Already closed on our side.
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.ended(ws);
  }

  private ended(ws: WebSocket): void {
    const tag = ws.deserializeAttachment() as Tag | null;
    if (tag?.role === "acp") {
      this.phone()?.send(F.encode(F.END, tag.stream));
      return;
    }
    if (tag?.role !== "phone" || !tag.authed) return;
    if (this.phone(ws)) return;
    this.setMeta("last_seen", String(now()));
    this.dropStreams("the phone went offline");
  }

  /** The streams of a link that is gone: its MCP calls fail now and its ACP sessions close. */
  private dropStreams(reason: string): void {
    for (const pending of [...this.mcp.values()]) {
      pending.finish({ ok: false, code: "offline", reason: `${reason} during the call` });
    }
    for (const socket of this.ctx.getWebSockets("acp")) {
      if (socket.readyState === WebSocket.OPEN) socket.close(CLOSE.offline, reason);
    }
  }

  private async authenticate(ws: WebSocket, tag: PhoneTag, frame: F.Frame): Promise<void> {
    const msg = frame.kind === F.CTRL ? F.parse<{ t?: string; sig?: string; app?: string; abi?: string }>(frame) : null;
    const pub = fromB64url(tag.pub);
    const phoneId = this.meta("phone_id") ?? "";
    if (msg?.t !== "auth" || typeof msg.sig !== "string" || !pub) {
      ws.close(CLOSE.unauthorized, "sign the challenge first");
      return;
    }
    if (!(await verifyLink(pub, phoneId, tag.nonce, tag.host, msg.sig))) {
      ws.close(CLOSE.unauthorized, "the challenge signature does not verify");
      return;
    }
    const stored = this.meta("pub");
    if (stored && stored !== tag.pub) {
      ws.close(CLOSE.unauthorized, "this phone id belongs to another key");
      return;
    }
    let replaced = false;
    for (const other of this.ctx.getWebSockets("phone")) {
      if (other !== ws && (other.deserializeAttachment() as PhoneTag).authed) {
        other.close(CLOSE.replaced, "replaced");
        replaced = true;
      }
    }
    if (replaced) this.dropStreams("the phone reconnected");
    ws.serializeAttachment({ ...tag, authed: true, nonce: "" } satisfies PhoneTag);
    this.setMeta("pub", tag.pub);
    this.setMeta("app", String(msg.app ?? "").slice(0, 32));
    this.setMeta("last_seen", String(now()));
    ws.send(
      F.ctrl({
        t: "ready",
        plan: this.env.BILLING === "off" ? "self-hosted" : this.entitled() ? "relay" : "free",
        entitled: this.entitled(),
        limits: { clients: MAX_CLIENTS, inflight: MAX_INFLIGHT, body: MAX_BODY },
        clients: this.clientList(),
      }),
    );
  }

  /** The authenticated phone link, other than `except`. A link that stopped pinging is closed as offline. */
  private phone(except?: WebSocket): WebSocket | undefined {
    const staleMs = Number(this.env.LINK_STALE_MS) || LINK_STALE_MS;
    for (const ws of this.ctx.getWebSockets("phone")) {
      const tag = ws.deserializeAttachment() as PhoneTag;
      if (ws === except || !tag.authed || ws.readyState !== WebSocket.OPEN) continue;
      const heard = Math.max(this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0, tag.opened);
      if (Date.now() - heard <= staleMs) return ws;
      ws.close(CLOSE.offline, "the phone stopped pinging");
      this.dropStreams("the phone went offline");
    }
    return undefined;
  }

  private touch(): void {
    if (Date.now() - this.seenAt < LAST_USED_EVERY_MS) return;
    this.seenAt = Date.now();
    this.setMeta("last_seen", String(now()));
  }

  private fromPhone(frame: F.Frame): void {
    const pending = this.mcp.get(frame.stream);
    if (pending) {
      switch (frame.kind) {
        case F.HEAD: {
          const head = F.parse<{ status?: number; headers?: Record<string, string> }>(frame) ?? {};
          pending.status = typeof head.status === "number" ? head.status : 200;
          pending.contentType = head.headers?.["content-type"] ?? "application/json";
          return;
        }
        case F.DATA:
          pending.size += frame.payload.length;
          if (pending.size > MAX_REPLY) {
            this.phone()?.send(F.json(F.RESET, frame.stream, { code: "too_big", reason: "the reply is over 16 MiB" }));
            pending.finish({ ok: false, code: "too_big", reason: "the phone's reply is over 16 MiB" });
            return;
          }
          pending.body.push(frame.payload.slice());
          return;
        case F.END:
          pending.finish({ ok: true, status: pending.status, contentType: pending.contentType, body: concat(pending.body) });
          return;
        case F.RESET: {
          const r = F.parse<{ code?: string; reason?: string }>(frame) ?? {};
          pending.finish({ ok: false, code: r.code ?? "reset", reason: r.reason ?? "the phone dropped the call" });
          return;
        }
      }
      return;
    }
    const socket = this.ctx.getWebSockets(`s:${frame.stream}`)[0];
    if (!socket) return;
    switch (frame.kind) {
      case F.WSMSG: {
        const message = F.wsPayload(frame);
        if (message !== null) socket.send(message);
        return;
      }
      case F.END:
        socket.close(CLOSE.normal, "the agent ended the session");
        return;
      case F.RESET: {
        const r = F.parse<{ code?: string; reason?: string }>(frame) ?? {};
        const code = r.code === "busy" ? CLOSE.busy : r.code === "forbidden" ? CLOSE.forbidden : CLOSE.error;
        socket.close(code, clip(r.code === "busy" ? "busy" : (r.reason ?? "the phone ended the session")));
        return;
      }
    }
  }

  private fromClient(ws: WebSocket, tag: AcpTag, message: string | ArrayBuffer): void {
    if (ws.readyState !== WebSocket.OPEN) return;
    if (!this.entitled()) {
      this.endAcp(ws, CLOSE.payment, "Aster Relay is not active for this phone");
      return;
    }
    const phone = this.phone();
    if (!phone) {
      ws.close(CLOSE.offline, "the phone is offline");
      return;
    }
    phone.send(F.wsMessage(tag.stream, message));
  }

  /** Ends an ACP session on both sides, without waiting for the client to answer the close. */
  private endAcp(ws: WebSocket, code: number, reason: string): void {
    if (ws.readyState !== WebSocket.OPEN) return;
    const tag = ws.deserializeAttachment() as AcpTag;
    this.phone()?.send(F.encode(F.END, tag.stream));
    ws.close(code, reason);
  }

  // Requests the phone makes over CTRL.

  private async control(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const reply = (body: Record<string, unknown>) => ws.send(F.ctrl({ ...body, rid: msg.rid }));
    const fail = (message: string) => reply({ t: "error", message });
    switch (msg.t) {
      case "pair.open": {
        if (!this.entitled()) return fail("Aster Relay is not active for this phone");
        const scopes = cleanScopes(msg.scopes);
        const ttl = Math.min(Math.max(Number(msg.ttl) || PAIR_TTL_S, 30), PAIR_TTL_S);
        const phoneId = this.meta("phone_id") ?? "";
        for (let attempt = 0; attempt < 3; attempt++) {
          const code = newCode();
          const exp = now() + ttl;
          if (await this.env.PAIR.getByName(`code:${code.slice(0, 2)}`).register(code, phoneId, scopes, exp)) {
            return reply({ t: "pair.code", code, exp });
          }
        }
        return fail("could not make a pairing code; try again");
      }
      case "pair.approve": {
        const decide = this.claims.get(String(msg.pending));
        if (!decide) return fail(`no client is waiting as ${String(msg.pending)}`);
        decide(msg.ok === true);
        return reply({ t: "ok" });
      }
      case "client.add":
        return this.scanned(msg, reply, fail);
      case "client.list":
        return reply({ t: "clients", clients: this.clientList() });
      case "client.revoke":
        return reply({ t: "revoked", ids: this.revoke([String(msg.id)]) });
      case "client.revoke_all":
        return reply({ t: "revoked", ids: this.revoke(this.clientList().map((c) => c.id)) });
      default:
        return fail(`unknown request ${String(msg.t)}`);
    }
  }

  /** The phone scanned a client's QR (pairing b2); scanning it is the consent. */
  private async scanned(
    msg: Record<string, unknown>,
    reply: (body: Record<string, unknown>) => void,
    fail: (message: string) => void,
  ): Promise<void> {
    const id = String(msg.id ?? "");
    const nonce = String(msg.nonce ?? "");
    if (!this.entitled()) return fail("Aster Relay is not active for this phone");
    if (!CLIENT_ID.test(id) || this.client(id, true)) return fail("that client id is not usable");
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(nonce)) return fail("that claim link has no valid nonce");
    if (this.clientList().length >= MAX_CLIENTS) return fail(`this phone already has ${MAX_CLIENTS} clients; revoke one first`);
    const origin = this.meta("origin") ?? "";
    const name = cleanName(msg.name);
    const kind = String(msg.kind ?? "mcp-shim").slice(0, 16);
    const result = await this.addClient(id, name, kind, cleanScopes(msg.scopes), origin);
    const shard = this.env.PAIR.getByName(`nonce:${nonce.slice(0, 2)}`);
    if (!(await shard.deliver(nonce, JSON.stringify(result)))) {
      this.ctx.storage.sql.exec("DELETE FROM clients WHERE id = ?", id);
      return fail("that claim link was already used");
    }
    return reply({ t: "client.added", client: this.clientList().find((c) => c.id === id) });
  }

  // Pairing by short code (b1), called by the Worker.

  async claim(info: ClaimInfo): Promise<ClaimOutcome> {
    if (!this.entitled()) return outcome(402, { error: "relay_inactive" });
    const phone = this.phone();
    if (!phone) return outcome(503, { error: "phone_offline" });
    if (this.clientList().length >= MAX_CLIENTS) return outcome(409, { error: "too_many_clients" });
    const pending = newClientId();
    const wait = Number(this.env.CLAIM_WAIT_MS) || CLAIM_WAIT_MS;
    const decision = new Promise<boolean | null>((resolve) => {
      const timer = setTimeout(() => {
        this.claims.delete(pending);
        resolve(null);
      }, wait);
      this.claims.set(pending, (ok) => {
        clearTimeout(timer);
        this.claims.delete(pending);
        resolve(ok);
      });
    });
    phone.send(
      F.ctrl({
        t: "pair.claimed",
        pending,
        name: info.name,
        kind: info.kind,
        scopes: info.scopes,
        country: info.country ?? null,
        ua: info.ua ?? null,
      }),
    );
    const ok = await decision;
    if (ok === null) return outcome(408, { error: "timeout" });
    if (!ok) return outcome(403, { error: "denied" });
    return outcome(200, await this.addClient(pending, info.name, info.kind, info.scopes, info.origin));
  }

  private async addClient(id: string, name: string, kind: string, scopes: string[], origin: string): Promise<unknown> {
    const { token, secret } = mintToken(id);
    this.ctx.storage.sql.exec(
      "INSERT INTO clients (id, name, kind, token_hash, scopes, created) VALUES (?, ?, ?, ?, ?, ?)",
      id,
      name,
      kind,
      await hashSecret(secret),
      JSON.stringify(scopes),
      now(),
    );
    const phoneId = this.meta("phone_id") ?? "";
    const pub = fromB64url(this.meta("pub") ?? "");
    const mcp = `${origin}/p/${phoneId}/mcp`;
    const acp = `${origin.replace(/^http/, "ws")}/p/${phoneId}/acp`;
    return {
      phone_id: phoneId,
      client_id: id,
      token,
      scopes,
      phone_fp: pub ? await fingerprint(pub) : null,
      endpoints: { mcp, acp, mirror: null },
      snippets: snippets(mcp, acp, token),
    };
  }

  // Clients.

  private client(id: string, includeRevoked = false): ClientRow | undefined {
    const sql = includeRevoked ? "SELECT * FROM clients WHERE id = ?" : "SELECT * FROM clients WHERE id = ? AND revoked = 0";
    return this.ctx.storage.sql.exec<ClientRow>(sql, id).toArray()[0];
  }

  private clientList(): Array<{ id: string; name: string; kind: string; scopes: string[]; created: number; last_seen: number | null }> {
    return this.ctx.storage.sql
      .exec<ClientRow>("SELECT * FROM clients WHERE revoked = 0 ORDER BY created")
      .toArray()
      .map((c) => ({
        id: c.id,
        name: c.name,
        kind: c.kind,
        scopes: JSON.parse(c.scopes) as string[],
        created: c.created,
        last_seen: c.last_used || null,
      }));
  }

  /** Revoked at once: the row is marked, and that client's calls and sessions end on both sides. */
  private revoke(ids: string[]): string[] {
    const done: string[] = [];
    for (const id of ids) {
      if (!this.client(id)) continue;
      this.ctx.storage.sql.exec("UPDATE clients SET revoked = 1 WHERE id = ?", id);
      for (const ws of this.ctx.getWebSockets(id)) this.endAcp(ws, CLOSE.unauthorized, "revoked");
      for (const [stream, pending] of [...this.mcp]) {
        if (pending.client !== id) continue;
        this.phone()?.send(F.json(F.RESET, stream, { code: "revoked", reason: "the client was revoked" }));
        pending.finish({ ok: false, code: "revoked", reason: "this client was revoked" });
      }
      done.push(id);
    }
    return done;
  }

  private async authorize(request: Request): Promise<{ client: ClientRow; scopes: string[]; protocol?: string } | Response> {
    const unauthorized = () =>
      json({ error: "unauthorized", message: "pair this client with the phone again" }, 401, { "www-authenticate": "Bearer" });
    const presented = bearer(request);
    const parsed = presented ? parseToken(presented.token) : null;
    if (!presented || !parsed) return unauthorized();
    const client = this.client(parsed.clientId);
    if (!client || !sameHex(await hashSecret(parsed.secret), client.token_hash)) return unauthorized();
    if (!(await allowed(this.env.RL_CLIENT, client.id))) {
      return json({ error: "rate_limited", message: "over 60 requests a minute from this client" }, 429, { "retry-after": "60" });
    }
    if (Date.now() - client.last_used * 1000 > LAST_USED_EVERY_MS) {
      this.ctx.storage.sql.exec("UPDATE clients SET last_used = ? WHERE id = ?", now(), client.id);
    }
    return { client, scopes: JSON.parse(client.scopes) as string[], protocol: presented.protocol };
  }

  // Client requests.

  private async mcpRequest(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    if (auth instanceof Response) return auth;
    if (!auth.scopes.includes("phone:act") && !auth.scopes.includes("phone:read")) {
      return json({ error: "forbidden", message: "this client may not use the phone over MCP" }, 403);
    }
    if (Number(request.headers.get("content-length") ?? 0) > MAX_BODY) return json({ error: "too_big" }, 413);
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > MAX_BODY) return json({ error: "too_big" }, 413);
    const id = rpcId(body);
    if (!this.entitled()) {
      return rpcError(id, -32001, "Aster Relay is not active for this phone; turn it on in Droid's settings");
    }
    const phone = this.phone();
    if (!phone) {
      if (id === undefined) return new Response(null, { status: 202 });
      return rpcError(id, -32000, `the phone is offline (${this.lastSeen()}); open Droid on it`);
    }
    if (this.mcp.size >= MAX_INFLIGHT) {
      return rpcError(id, -32000, `the phone is already answering ${MAX_INFLIGHT} calls; try again in a moment`);
    }
    const stream = this.nextStream();
    const timeoutMs = Number(this.env.MCP_TIMEOUT_MS) || MCP_TIMEOUT_MS;
    const reply = new Promise<McpReply>((resolve) => {
      const timer = setTimeout(() => {
        this.phone()?.send(F.json(F.RESET, stream, { code: "timeout", reason: "the relay stopped waiting" }));
        finish({ ok: false, code: "timeout", reason: "" });
      }, timeoutMs);
      const finish = (r: McpReply) => {
        clearTimeout(timer);
        this.mcp.delete(stream);
        resolve(r);
      };
      this.mcp.set(stream, { client: auth.client.id, finish, status: 200, contentType: "application/json", body: [], size: 0 });
    });
    phone.send(
      F.json(F.OPEN, stream, {
        svc: "mcp",
        client: auth.client.id,
        scopes: auth.scopes,
        method: "POST",
        path: "/mcp",
        headers: pick(request.headers, ["mcp-protocol-version", "mcp-session-id", "accept"]),
      }),
    );
    for (const chunk of F.chunks(stream, body)) phone.send(chunk);
    phone.send(F.encode(F.END, stream));
    const r = await reply;
    if (r.ok) {
      return new Response(r.status === 202 || r.body.length === 0 ? null : r.body, {
        status: r.status,
        headers: { "content-type": r.contentType },
      });
    }
    if (id === undefined) return new Response(null, { status: 202 });
    if (r.code === "timeout") {
      return rpcError(id, -32000, `the phone did not answer within ${Math.round(timeoutMs / 1000)}s; read the screen before trying again`);
    }
    return rpcError(id, -32000, r.reason || `the phone dropped the call (${r.code})`);
  }

  private async acpSocket(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "ACP here is a WebSocket" }, 426);
    }
    const auth = await this.authorize(request);
    if (auth instanceof Response) return auth;
    if (!auth.scopes.includes("agent:chat")) {
      return json({ error: "forbidden", message: "this client may not talk to the phone's agent" }, 403);
    }
    const { 0: client, 1: server } = new WebSocketPair();
    const stream = this.nextStream();
    this.ctx.acceptWebSocket(server, ["acp", auth.client.id, `s:${stream}`]);
    server.serializeAttachment({ role: "acp", stream, client: auth.client.id } satisfies AcpTag);
    const phone = this.phone();
    if (!this.entitled()) {
      server.close(CLOSE.payment, "Aster Relay is not active for this phone");
    } else if (!phone) {
      server.close(CLOSE.offline, "the phone is offline; open Droid on it");
    } else {
      phone.send(F.json(F.OPEN, stream, { svc: "acp", client: auth.client.id, scopes: auth.scopes }));
    }
    const headers: Record<string, string> = auth.protocol ? { "sec-websocket-protocol": auth.protocol } : {};
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  private async statusRequest(request: Request): Promise<Response> {
    const auth = await this.authorize(request);
    if (auth instanceof Response) return auth;
    const lastSeen = Number(this.meta("last_seen") ?? 0);
    return json({
      online: this.phone() !== undefined,
      last_seen: lastSeen || null,
      plan: this.env.BILLING === "off" ? "self-hosted" : this.entitled() ? "relay" : "free",
      entitled: this.entitled(),
      app: this.meta("app") ?? null,
    });
  }

  // Entitlement.

  entitled(): boolean {
    return this.env.BILLING === "off" || this.meta("entitled") === "1";
  }

  /** Flipped by Stripe in phase 3; for now only by the admin endpoint, for testing. */
  async setEntitlement(on: boolean): Promise<{ entitled: boolean }> {
    this.setMeta("entitled", on ? "1" : "0");
    this.phone()?.send(F.ctrl({ t: "entitlement", entitled: this.entitled() }));
    if (!this.entitled()) {
      for (const ws of this.ctx.getWebSockets("acp")) this.endAcp(ws, CLOSE.payment, "Aster Relay is not active for this phone");
    }
    return { entitled: this.entitled() };
  }

  // Storage helpers.

  private meta(k: string): string | undefined {
    return this.ctx.storage.sql.exec<{ v: string }>("SELECT v FROM meta WHERE k = ?", k).toArray()[0]?.v;
  }

  private setMeta(k: string, v: string): void {
    this.ctx.storage.sql.exec("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", k, v);
  }

  /** Odd ids are the relay's; the phone uses even ones for streams it starts. */
  private nextStream(): number {
    const current = Number(this.meta("next_stream") ?? 1);
    const next = current + 2 > 0x7fffffff ? 1 : current + 2;
    this.setMeta("next_stream", String(next));
    return current;
  }

  private lastSeen(): string {
    const seen = Number(this.meta("last_seen") ?? 0);
    if (!seen) return "it has not connected to this relay yet";
    const minutes = Math.round((now() - seen) / 60);
    if (minutes < 1) return "last seen just now";
    if (minutes < 120) return `last seen ${minutes}m ago`;
    return `last seen ${Math.round(minutes / 60)}h ago`;
  }
}

function outcome(status: number, body: unknown): ClaimOutcome {
  return { status, body: JSON.stringify(body) };
}

function now(): number {
  return Math.floor(Date.now() / 1000);
}

/** A missing binding (a plain `wrangler dev` without rate limits) allows everything. */
export async function allowed(limiter: RateLimit | undefined, key: string): Promise<boolean> {
  if (!limiter) return true;
  return (await limiter.limit({ key })).success;
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** A JSON-RPC error with HTTP 200, so the agent reads why instead of seeing a transport failure. */
function rpcError(id: unknown, code: number, message: string): Response {
  return json({ jsonrpc: "2.0", id: id ?? null, error: { code, message } });
}

/** The request id, or `undefined` for a notification, which gets no reply. */
function rpcId(body: Uint8Array): unknown {
  let msg: unknown;
  try {
    msg = JSON.parse(new TextDecoder().decode(body));
  } catch {
    return null;
  }
  if (Array.isArray(msg)) return undefined;
  if (msg && typeof msg === "object") return "id" in msg ? (msg as { id: unknown }).id : undefined;
  return null;
}

function cleanScopes(value: unknown): string[] {
  const asked = Array.isArray(value) ? value.filter((s): s is string => typeof s === "string") : [];
  const known = asked.filter((s) => (SCOPES as readonly string[]).includes(s));
  return known.length > 0 ? [...new Set(known)] : DEFAULT_SCOPES;
}

export function cleanName(value: unknown): string {
  const name = typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f]/g, " ").trim() : "";
  return name.slice(0, 80) || "Unnamed client";
}

function pick(headers: Headers, names: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const name of names) {
    const v = headers.get(name);
    if (v !== null) out[name] = v;
  }
  return out;
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

/** A close reason is at most 123 bytes. */
function clip(reason: string): string {
  const bytes = new TextEncoder().encode(reason);
  return bytes.length <= 123 ? reason : new TextDecoder().decode(bytes.slice(0, 120)).replace(/�$/, "");
}
