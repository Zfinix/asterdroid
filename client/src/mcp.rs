//! The phone as an MCP server, so any harness can drive it, not only the Aster
//! agent on the phone. Stdio for `adb shell run-as`, HTTP for everything else.

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use base64::Engine;
use serde_json::{json, Value};
use std::io::{BufRead, Write};

pub const DEFAULT_PORT: u16 = 7071;
/// Plain HTTP carries the token in the clear, so the network is opt-in:
/// localhost reaches adb forward, and a Tailscale address is encrypted.
pub const DEFAULT_BIND: &str = "127.0.0.1";
pub const TOKEN_ENV: &str = "ASTER_MCP_TOKEN";
pub const MIN_TOKEN_CHARS: usize = 32;
/// Longer than the longest verb, `wait` at its 30 s default plus a slow read.
const CALL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(120);
const HELP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const PROTOCOL_VERSIONS: [&str; 3] = ["2025-06-18", "2025-03-26", "2024-11-05"];
const SKILL_URI: &str = "asterdroid://skills/android-use";
const SKILL_PATH: &str = "/data/data/dev.aster.probe/files/.aster/skills/android-use/SKILL.md";
/// The service's own `help` goes into the tool description; a runaway reply must not.
const MAX_HELP_CHARS: usize = 8_000;
const MAX_REPLY_CHARS: usize = 40_000;
const MAX_SHOT_BYTES: u64 = 6 << 20;
const INSTRUCTIONS: &str = "This server is an Android phone. Read the screen with `phone` \
    and `map`, act with the element indices it returns, and check the `changed:` line after \
    every action: an empty diff means the action did not land. The full guide is the \
    asterdroid://skills/android-use resource.";

pub fn stdio() {
    let stdin = std::io::stdin();
    let mut stdout = std::io::stdout();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        let reply = match serde_json::from_str::<Value>(&line) {
            Ok(req) => handle(&req),
            Err(e) => Some(failure(Value::Null, -32700, &format!("parse error: {e}"))),
        };
        if let Some(reply) = reply {
            let _ = writeln!(stdout, "{reply}");
            let _ = stdout.flush();
        }
    }
}

pub fn http(port: u16, bind: &str) {
    let token = required_token(TOKEN_ENV);
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(serve(port, bind, token));
}

/// The token a network listener demands, or the process ends saying how to set it.
pub(crate) fn required_token(var: &str) -> String {
    let token = std::env::var(var).unwrap_or_default().trim().to_string();
    if token.chars().count() < MIN_TOKEN_CHARS {
        eprintln!(
            "error: set {var} to at least {MIN_TOKEN_CHARS} random characters; \
             whoever holds it controls the phone"
        );
        std::process::exit(2);
    }
    token
}

async fn serve(port: u16, bind: &str, token: String) {
    let app = Router::new()
        .route("/mcp", post(rpc).get(|| async { StatusCode::METHOD_NOT_ALLOWED }))
        .with_state(token);
    let addr = format!("{bind}:{port}");
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));
    println!("mcp on http://{addr}/mcp");
    axum::serve(listener, app).await.expect("serve");
}

async fn rpc(State(token): State<String>, headers: HeaderMap, body: String) -> Response {
    let presented = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    if !presented.is_some_and(|p| same(p.as_bytes(), token.as_bytes())) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    // A page in a browser can reach a local port; MCP clients send no Origin.
    if headers.get("origin").is_some_and(|o| !local_origin(o.to_str().unwrap_or_default())) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let req = match serde_json::from_str::<Value>(&body) {
        Ok(req) => req,
        Err(e) => return Json(failure(Value::Null, -32700, &format!("parse error: {e}"))).into_response(),
    };
    match tokio::task::spawn_blocking(move || handle(&req)).await {
        Ok(Some(reply)) => Json(reply).into_response(),
        Ok(None) => StatusCode::ACCEPTED.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

pub(crate) fn local_origin(origin: &str) -> bool {
    let host = origin
        .split_once("://")
        .map_or(origin, |(_, rest)| rest)
        .split(['/', ':'])
        .next()
        .unwrap_or_default();
    matches!(host, "localhost" | "127.0.0.1")
}

pub(crate) fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// What a caller may do with the phone. Local callers hold the token and get
/// everything; a relay client paired with `phone:read` only gets the verbs
/// that look.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Access {
    Act,
    Read,
}

const READ_VERBS: [&str; 8] = ["map", "find", "shot", "ocr", "notes", "apps", "wait", "help"];

/// One JSON-RPC message in, the reply out; `None` for a notification.
pub(crate) fn handle(req: &Value) -> Option<Value> {
    handle_as(req, Access::Act)
}

pub(crate) fn handle_as(req: &Value, access: Access) -> Option<Value> {
    let id = req.get("id").cloned()?;
    let params = req.get("params").cloned().unwrap_or(Value::Null);
    let method = req.get("method").and_then(Value::as_str).unwrap_or_default();
    let result = match method {
        "initialize" => Ok(initialize(&params)),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(json!({ "tools": tools() })),
        "tools/call" => call(&params, access),
        "resources/list" => Ok(json!({ "resources": [{
            "uri": SKILL_URI,
            "name": "android-use",
            "description": "How to drive this phone: reading, acting, verifying, and the verbs",
            "mimeType": "text/markdown",
        }]})),
        "resources/read" => read_resource(&params),
        _ => Err((-32601, format!("method not found: {method}"))),
    };
    Some(match result {
        Ok(result) => json!({ "jsonrpc": "2.0", "id": id, "result": result }),
        Err((code, message)) => failure(id, code, &message),
    })
}

pub(crate) fn failure(id: Value, code: i64, message: &str) -> Value {
    json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message } })
}

fn initialize(params: &Value) -> Value {
    let asked = params.get("protocolVersion").and_then(Value::as_str);
    let version = asked
        .filter(|v| PROTOCOL_VERSIONS.contains(v))
        .unwrap_or(PROTOCOL_VERSIONS[0]);
    json!({
        "protocolVersion": version,
        "capabilities": { "tools": {}, "resources": {} },
        "serverInfo": { "name": "asterdroid", "version": env!("CARGO_PKG_VERSION") },
        "instructions": INSTRUCTIONS,
    })
}

fn tools() -> Value {
    let help = crate::socket::one_within("help", HELP_TIMEOUT)
        .map(|h| h.chars().take(MAX_HELP_CHARS).collect::<String>())
        .unwrap_or_else(|e| format!("(the accessibility service is not answering: {e}; call `help` once it is on)"));
    json!([
        {
            "name": "phone",
            "description": format!(
                "Run one command on the Android phone and return its reply. Actions reply with a \
                 `receipt:` line and a `changed:` line; a warning in place of `changed:` means \
                 nothing happened. `later` only wakes the Aster agent on the phone.\n\n{help}"
            ),
            "inputSchema": {
                "type": "object",
                "properties": { "command": { "type": "string", "description": "A verb and its arguments, like `map` or `tap 4`" } },
                "required": ["command"],
            },
        },
        {
            "name": "phone_screenshot",
            "description": "A PNG of the phone's screen. `target` crops to an element index, a \
                            l,t-r,b rectangle or a grid cell; `grid` overlays lettered cells that \
                            `tap` accepts.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "target": { "type": "string" },
                    "grid": { "type": "boolean" },
                },
            },
        },
    ])
}

fn call(params: &Value, access: Access) -> Result<Value, (i64, String)> {
    let name = params.get("name").and_then(Value::as_str).unwrap_or_default();
    let args = params.get("arguments").cloned().unwrap_or(Value::Null);
    match name {
        "phone" => {
            let command = args
                .get("command")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|c| !c.is_empty())
                .ok_or((-32602, "phone needs a command".to_string()))?;
            let verb = command.split_whitespace().next().unwrap_or_default();
            // Bare `alerts` shows the settings; anything after it changes them.
            let reads = READ_VERBS.contains(&verb) || command == "alerts";
            if access == Access::Read && !reads {
                return Ok(text_result(format!(
                    "error: this client may only read the screen, and `{verb}` acts on it; pair it again with phone:act to allow that\n"
                )));
            }
            Ok(text_result(run(&command.replace('\n', " "))))
        }
        "phone_screenshot" => {
            let grid = args.get("grid").and_then(Value::as_bool).unwrap_or_default();
            let target = args.get("target").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            let command = [if grid { "shot grid" } else { "shot" }, target].join(" ");
            Ok(screenshot(&run(command.trim_end())))
        }
        _ => Err((-32602, format!("unknown tool: {name}"))),
    }
}

fn run(command: &str) -> String {
    match crate::socket::one_within(command, CALL_TIMEOUT) {
        Ok(reply) if reply.chars().count() > MAX_REPLY_CHARS => {
            let kept: String = reply.chars().take(MAX_REPLY_CHARS).collect();
            format!("{kept}\n(cut at {MAX_REPLY_CHARS} chars)\n")
        }
        Ok(reply) => reply,
        Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
            format!("error: the phone did not answer within {}s; read the screen before trying again\n", CALL_TIMEOUT.as_secs())
        }
        Err(e) => format!(
            "error: cannot reach the phone's screen service ({e}); turn on Aster under Settings > Accessibility\n"
        ),
    }
}

fn text_result(reply: String) -> Value {
    let failed = reply.lines().any(|l| l.starts_with("error:"));
    json!({ "content": [{ "type": "text", "text": reply }], "isError": failed })
}

/// `shot <path> (WxH, N bytes)` then any legend; the file is read and removed,
/// since nothing on the phone will look at it again.
fn screenshot(reply: &str) -> Value {
    let Some(path) = reply
        .lines()
        .find_map(|l| l.strip_prefix("shot "))
        .and_then(|rest| rest.split_whitespace().next())
    else {
        return text_result(reply.to_string());
    };
    let too_big = std::fs::metadata(path).map(|m| m.len() > MAX_SHOT_BYTES).unwrap_or(true);
    let bytes = if too_big { None } else { std::fs::read(path).ok() };
    let _ = std::fs::remove_file(path);
    let Some(bytes) = bytes else {
        return text_result(format!("error: the screenshot at {path} could not be read or is over {MAX_SHOT_BYTES} bytes\n"));
    };
    let legend = reply.lines().filter(|l| !l.starts_with("shot ")).collect::<Vec<_>>().join("\n");
    let mut content = vec![json!({
        "type": "image",
        "data": base64::engine::general_purpose::STANDARD.encode(bytes),
        "mimeType": "image/png",
    })];
    if !legend.trim().is_empty() {
        content.push(json!({ "type": "text", "text": legend }));
    }
    json!({ "content": content, "isError": false })
}

fn read_resource(params: &Value) -> Result<Value, (i64, String)> {
    let uri = params.get("uri").and_then(Value::as_str).unwrap_or_default();
    if uri != SKILL_URI {
        return Err((-32002, format!("resource not found: {uri}")));
    }
    let text = std::fs::read_to_string(SKILL_PATH)
        .map_err(|e| (-32603, format!("cannot read {SKILL_PATH}: {e}; open the app once to install it")))?;
    Ok(json!({ "contents": [{ "uri": SKILL_URI, "mimeType": "text/markdown", "text": text }] }))
}

#[cfg(test)]
#[path = "mcp_tests.rs"]
mod tests;
