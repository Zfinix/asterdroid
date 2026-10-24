import { DurableObject } from "cloudflare:workers";
import { sha256Hex } from "./auth";
import { PAIR_TTL_S } from "./limits";

/**
 * Short codes and client-QR nonces, each mapped to the phone that made it.
 * Sharded by the first two characters, so no single object sees every pairing.
 * Only hashes are stored, and every entry is single-use.
 */
export class PairIndex extends DurableObject<Env> {
  private waiters = new Map<string, Array<(result: string | null) => void>>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS codes (hash TEXT PRIMARY KEY, phone TEXT NOT NULL, scopes TEXT NOT NULL, exp INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS grants (hash TEXT PRIMARY KEY, result TEXT, exp INTEGER NOT NULL);
      `);
    });
  }

  /** `false` when the code is taken, so the phone side picks another. */
  async register(code: string, phone: string, scopes: string[], exp: number): Promise<boolean> {
    const hash = await sha256Hex(code);
    const taken = this.ctx.storage.sql.exec("SELECT 1 FROM codes WHERE hash = ? AND exp > ?", hash, now()).toArray();
    if (taken.length > 0) return false;
    this.ctx.storage.sql.exec("INSERT OR REPLACE INTO codes (hash, phone, scopes, exp) VALUES (?, ?, ?, ?)", hash, phone, JSON.stringify(scopes), exp);
    await this.sweepLater();
    return true;
  }

  /** The code's phone and scopes, burning the code whether or not the claim goes on to succeed. */
  async take(code: string): Promise<{ phone: string; scopes: string[] } | null> {
    const hash = await sha256Hex(code);
    const rows = this.ctx.storage.sql
      .exec<{ phone: string; scopes: string; exp: number }>("DELETE FROM codes WHERE hash = ? RETURNING phone, scopes, exp", hash)
      .toArray();
    const row = rows[0];
    if (!row || row.exp <= now()) return null;
    return { phone: row.phone, scopes: JSON.parse(row.scopes) as string[] };
  }

  /** The phone scanned a client's QR. `false` if that nonce was already used. */
  async deliver(nonce: string, result: string): Promise<boolean> {
    const hash = await sha256Hex(nonce);
    const used = this.ctx.storage.sql.exec("SELECT 1 FROM grants WHERE hash = ?", hash).toArray();
    if (used.length > 0) return false;
    const exp = now() + PAIR_TTL_S;
    // A waiter may be a poll whose client already hung up, so the row keeps the result for the next poll.
    this.ctx.storage.sql.exec("INSERT INTO grants (hash, result, exp) VALUES (?, ?, ?)", hash, result, exp);
    const waiting = this.waiters.get(hash) ?? [];
    this.waiters.delete(hash);
    for (const waiter of waiting) waiter(result);
    await this.sweepLater();
    return true;
  }

  /** The claim result as JSON text once the phone scans the client's QR, or `null` after `ms`. */
  async wait(nonce: string, ms: number): Promise<string | null> {
    const hash = await sha256Hex(nonce);
    const rows = this.ctx.storage.sql
      .exec<{ result: string | null; exp: number }>("SELECT result, exp FROM grants WHERE hash = ?", hash)
      .toArray();
    const row = rows[0];
    if (row && row.exp > now()) {
      if (row.result === null) return null;
      this.ctx.storage.sql.exec("UPDATE grants SET result = NULL WHERE hash = ?", hash);
      return row.result;
    }
    return new Promise((resolve) => {
      const list = this.waiters.get(hash) ?? [];
      const done = (result: string | null) => {
        clearTimeout(timer);
        resolve(result);
      };
      const timer = setTimeout(() => {
        const left = (this.waiters.get(hash) ?? []).filter((w) => w !== done);
        if (left.length > 0) this.waiters.set(hash, left);
        else this.waiters.delete(hash);
        resolve(null);
      }, ms);
      list.push(done);
      this.waiters.set(hash, list);
    });
  }

  async alarm(): Promise<void> {
    const t = now();
    this.ctx.storage.sql.exec("DELETE FROM codes WHERE exp <= ?", t);
    this.ctx.storage.sql.exec("DELETE FROM grants WHERE exp <= ?", t);
    const left = this.ctx.storage.sql.exec<{ n: number }>("SELECT (SELECT COUNT(*) FROM codes) + (SELECT COUNT(*) FROM grants) AS n").one().n;
    if (left > 0) await this.ctx.storage.setAlarm(Date.now() + (PAIR_TTL_S + 5) * 1000);
  }

  private async sweepLater(): Promise<void> {
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(Date.now() + (PAIR_TTL_S + 5) * 1000);
    }
  }
}

function now(): number {
  return Math.floor(Date.now() / 1000);
}
