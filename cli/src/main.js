// @ts-check
// The command line. `acp` and `mcp` own stdout for protocol, so every word
// meant for a person goes to stderr there.

import os from "node:os";
import { parseArgs } from "node:util";
import { runAcp } from "./acp.js";
import { runMcp } from "./mcp.js";
import { onUsb, pairAdb, pairCode, pairLan, pairQr, RELAY_SOON } from "./pair.js";
import { endpoint } from "./phone.js";
import { apply, CLIENTS, installed, launchCommand, plan } from "./setup.js";
import { status, unpair } from "./status.js";
import { load, pick } from "./store.js";
import { version } from "./version.js";

const HELP = `aster-droid ${version}: reach your phone running Droid from MCP clients and ACP editors.

Usage: aster-droid <command> [options]

Commands:
  pair                  Pair over USB when adb sees one phone, or show how to pair over Wi-Fi
  pair --lan <ip>       Pair with a phone on this network or Tailscale, using the keys Droid shows
  pair --adb            Pair with a phone on a USB cable through adb, using the keys Droid shows
  pair --relay <url>    Pair by scanning a QR code with Droid (Aster Relay, coming soon)
  pair --code <code>    Pair with the code or pair link Droid shows (Aster Relay, coming soon)
  acp                   Run as an ACP agent on stdio (what Zed and JetBrains launch)
  mcp                   Run as an MCP server on stdio
  setup <client>        Add Droid to claude, codex, cursor, zed, jetbrains, vscode or all
  status                List paired phones and whether they answer
  unpair [phone]        Forget a phone on this computer (--all for every one)

Pair options:
  --relay <url>         Relay to use, or set ASTER_RELAY_URL (Aster Relay, coming soon)
  --name <name>         What to call the phone here
  --token <key>         One key for both MCP and ACP (LAN and adb)
  --mcp-token <key>     The other agents' key (LAN and adb)
  --acp-token <key>     The editors' key (LAN and adb)
  --mcp-port <port>     Default 7071      --acp-port <port>  Default 7072
  --serial <serial>     Which adb device  --no-check         Save without trying the keys

Setup options:
  --print               Show the config instead of writing it
  --http                Write the phone's URL and token into the config instead of starting aster-droid
  --command <path>      Start this program instead of npx -y @withaster/droid

Common options:
  --phone <id|name>     Which paired phone to use (default: the last one paired)
  --json                Machine-readable status
  -h, --help            Show this help
  -v, --version         Show the version

Credentials live in ~/.config/aster-droid/credentials.json (0600). Set ASTER_DROID_HOME to move them.
`;

/**
 * @param {string[]} argv
 * @param {{ env?: NodeJS.ProcessEnv, stdout?: NodeJS.WritableStream, stderr?: NodeJS.WritableStream,
 *   run?: import("./adb.js").Runner }} [io]
 * @returns {Promise<number>}
 */
export async function main(argv, io = {}) {
  const env = io.env ?? process.env;
  const stdout = io.stdout ?? process.stdout;
  const stderr = io.stderr ?? process.stderr;
  const [command, ...rest] = argv;
  if (rest.includes("--help") || rest.includes("-h")) {
    stdout.write(HELP);
    return 0;
  }
  try {
    switch (command) {
      case undefined:
      case "help":
      case "-h":
      case "--help":
        stdout.write(HELP);
        return 0;
      case "-v":
      case "--version":
        stdout.write(`${version}\n`);
        return 0;
      case "--pair":
      case "pair":
        return await pair(rest, env, stdout, stderr, io.run);
      case "acp": {
        const { values } = parse(rest, { phone: { type: "string" }, pair: { type: "boolean" }, relay: { type: "string" } });
        if (values.pair) return await pair(values.relay ? ["--relay", values.relay] : [], env, stdout, stderr, io.run);
        return await runAcp({ env, wanted: values.phone });
      }
      case "mcp": {
        const { values } = parse(rest, { phone: { type: "string" } });
        return await runMcp({ env, wanted: values.phone });
      }
      case "setup":
        return await setup(rest, env, stdout);
      case "status": {
        const { values } = parse(rest, { json: { type: "boolean" }, offline: { type: "boolean" } });
        await status({ env, out: stdout, json: values.json, offline: values.offline });
        return 0;
      }
      case "unpair": {
        const { values, positionals } = parse(rest, { all: { type: "boolean" }, phone: { type: "string" } }, true);
        await unpair({ env, out: stdout, all: values.all, wanted: values.phone ?? positionals[0] });
        return 0;
      }
      default:
        stderr.write(`aster-droid: there is no ${command} command. Run aster-droid --help.\n`);
        return 2;
    }
  } catch (e) {
    stderr.write(`aster-droid: ${String(/** @type {Error} */ (e).message ?? e)}\n`);
    return 1;
  }
}

/**
 * @template {import("node:util").ParseArgsConfig["options"]} T
 * @param {string[]} args
 * @param {T} options
 */
function parse(args, options, positionals = false) {
  try {
    return parseArgs({ args, options, allowPositionals: positionals, strict: true });
  } catch (e) {
    throw new Error(`${String(/** @type {Error} */ (e).message)}. Run aster-droid --help.`);
  }
}

/** @param {string | undefined} value @param {string} what */
function port(value, what) {
  if (value === undefined) return undefined;
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1 || n > 65535) throw new Error(`${what} must be a port number.`);
  return n;
}

async function pair(
  /** @type {string[]} */ args,
  /** @type {NodeJS.ProcessEnv} */ env,
  /** @type {NodeJS.WritableStream} */ out,
  /** @type {NodeJS.WritableStream} */ err,
  /** @type {import("./adb.js").Runner | undefined} */ run,
) {
  const { values } = parse(args, {
    code: { type: "string" },
    lan: { type: "string" },
    adb: { type: "boolean" },
    serial: { type: "string" },
    relay: { type: "string" },
    name: { type: "string" },
    token: { type: "string" },
    "mcp-token": { type: "string" },
    "acp-token": { type: "string" },
    "mcp-port": { type: "string" },
    "acp-port": { type: "string" },
    "no-check": { type: "boolean" },
  });
  const relay = values.relay ?? (env.ASTER_RELAY_URL || undefined);
  const direct = {
    env,
    out,
    name: values.name,
    mcpToken: values["mcp-token"] ?? values.token,
    acpToken: values["acp-token"] ?? values.token,
    mcpPort: port(values["mcp-port"], "--mcp-port"),
    acpPort: port(values["acp-port"], "--acp-port"),
    check: !values["no-check"],
  };
  let phone;
  if (values.lan !== undefined) phone = await pairLan({ ...direct, host: values.lan });
  else if (values.adb) phone = await pairAdb({ ...direct, serial: values.serial, run });
  else if (values.code !== undefined) phone = await pairCode({ env, out, relay, code: values.code, name: values.name });
  else if (relay !== undefined) phone = await pairQr({ env, out, relay, name: values.name });
  else {
    const usb = await onUsb(values.serial, run);
    if (!("serial" in usb)) {
      err.write(`${usb.why ? `${usb.why}\n\n` : ""}${RELAY_SOON}`);
      return 1;
    }
    out.write(`Pairing with ${usb.serial} over USB.\n`);
    phone = await pairAdb({ ...direct, serial: usb.serial, run });
  }
  const what = [phone.mcp && "MCP", phone.acp && "ACP"].filter(Boolean).join(" and ");
  out.write(`\nPaired with ${phone.name} (${phone.id}) for ${what}.\n`);
  out.write("Next: aster-droid setup all adds it to the clients on this computer, or aster-droid setup --print shows the config.\n");
  return 0;
}

async function setup(/** @type {string[]} */ args, /** @type {NodeJS.ProcessEnv} */ env, /** @type {NodeJS.WritableStream} */ out) {
  const { values, positionals } = parse(
    args,
    { print: { type: "boolean" }, http: { type: "boolean" }, phone: { type: "string" }, command: { type: "string" } },
    true,
  );
  const target = positionals[0] ?? (values.print ? "all" : undefined);
  const known = /** @type {readonly string[]} */ (CLIENTS);
  if (!target || (target !== "all" && !known.includes(target))) {
    throw new Error(`Say which client: ${CLIENTS.join(", ")} or all.`);
  }
  /** @type {{ url: string, token: string } | null} */
  let http = null;
  if (values.http) {
    const phone = pick(await load(env), values.phone);
    if (!phone) throw new Error("--http needs a paired phone. Run aster-droid pair first.");
    if (phone.via === "adb") throw new Error("--http does not work over adb, whose port changes; leave --http off.");
    http = await endpoint(phone, "mcp");
    if (!http?.url) throw new Error(`${phone.name} has no MCP key saved.`);
  }
  const ctx = {
    home: env.HOME || env.USERPROFILE || os.homedir(),
    platform: process.platform,
    env,
    launch: launchCommand(process.platform, values.command),
    phone: values.phone,
    http,
  };
  const clients = target === "all" ? CLIENTS.filter((c) => values.print || installed(c, ctx)) : [/** @type {import("./setup.js").Client} */ (target)];
  if (target === "all") {
    const skipped = CLIENTS.filter((c) => !clients.includes(c));
    if (skipped.length > 0) out.write(`Not found on this computer, so skipped: ${skipped.join(", ")}.\n\n`);
  }
  for (const client of clients) {
    for (const edit of plan(client, ctx)) {
      const r = await apply(edit, values.print);
      switch (r.status) {
        case "printed":
          out.write(`${edit.label}, in ${edit.file}:\n${r.snippet}\n\n`);
          break;
        case "added":
          out.write(`Added the ${edit.label} to ${edit.file}.\n`);
          break;
        case "present":
          out.write(`The ${edit.label} is already in ${edit.file}.\n`);
          break;
        case "kept":
          out.write(`Left ${edit.file} alone: it ${r.note}.\n`);
          break;
        case "manual":
          out.write(`Left ${edit.file} alone: it ${r.note}. Add this yourself:\n${r.snippet}\n\n`);
          break;
      }
    }
  }
  if (http && !values.print) out.write("\nThose files now hold the phone's token. Anyone who can read them can use your phone.\n");
  return 0;
}
