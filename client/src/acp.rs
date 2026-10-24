//! The phone's own agent for editors on another machine, over the Agent Client
//! Protocol. ACP runs on stdio, so each WebSocket gets its own `aster acp`
//! child and one text message is one JSON-RPC line, both ways. The relay
//! connector drives the same [`bridge`] over its link.

use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;

pub const DEFAULT_PORT: u16 = 7072;
/// The agent can do anything the phone can, so the network is opt-in, as for MCP.
pub const DEFAULT_BIND: &str = "127.0.0.1";
pub const TOKEN_ENV: &str = "ASTER_ACP_TOKEN";
const BIN_ENV: &str = "ASTER_BIN";
/// The close code a second editor gets while another one drives the phone.
pub const BUSY: u16 = 4409;
/// Browsers cannot set headers on a WebSocket, so the token may ride in the
/// subprotocol list instead, as `bearer.<token>`.
const SUBPROTOCOL: &str = "bearer.";
const QUEUE: usize = 64;
/// Editors answer pings, so a socket quiet for `SILENCE` lost its editor without
/// a FIN, and the agent and the driver lease are let go.
#[cfg(not(test))]
const PING_EVERY: Duration = Duration::from_secs(20);
#[cfg(not(test))]
const SILENCE: Duration = Duration::from_secs(60);
#[cfg(test)]
const PING_EVERY: Duration = Duration::from_millis(250);
#[cfg(test)]
const SILENCE: Duration = Duration::from_millis(1500);

/// How to start the agent: which binary, and the directory it treats as home,
/// which on the phone is the app's files directory with the skills and keys.
#[derive(Clone, Debug)]
pub struct Agent {
    pub bin: String,
    pub home: PathBuf,
}

impl Agent {
    pub fn from_env() -> Agent {
        let bin = std::env::var(BIN_ENV)
            .ok()
            .filter(|b| !b.trim().is_empty())
            .unwrap_or_else(|| "aster".to_string());
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::current_dir().ok())
            .unwrap_or_else(|| PathBuf::from("."));
        Agent { bin, home }
    }
}

pub fn ws(port: u16, bind: &str) {
    let token = crate::mcp::required_token(TOKEN_ENV);
    let agent = Agent::from_env();
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(async move {
            let addr = format!("{bind}:{port}");
            let listener = tokio::net::TcpListener::bind(&addr)
                .await
                .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));
            println!("acp on ws://{addr}/acp");
            serve(listener, token, agent).await;
        });
}

#[derive(Clone)]
struct Gate {
    token: Arc<str>,
    agent: Agent,
}

pub async fn serve(listener: tokio::net::TcpListener, token: String, agent: Agent) {
    let app = Router::new().route("/acp", get(upgrade)).with_state(Gate {
        token: token.into(),
        agent,
    });
    axum::serve(listener, app).await.expect("serve");
}

async fn upgrade(
    State(gate): State<Gate>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Response {
    let Some(via) = presented(&headers, &gate.token) else {
        return StatusCode::UNAUTHORIZED.into_response();
    };
    // A page in a browser can open a socket to a local port; editors send no Origin.
    if headers
        .get("origin")
        .is_some_and(|o| !crate::mcp::local_origin(o.to_str().unwrap_or_default()))
    {
        return StatusCode::FORBIDDEN.into_response();
    }
    let upgrade = match via {
        Via::Header => upgrade,
        Via::Subprotocol(chosen) => upgrade.protocols([chosen]),
    };
    let agent = gate.agent.clone();
    upgrade
        .on_upgrade(move |socket| session(socket, agent))
        .into_response()
}

enum Via {
    Header,
    Subprotocol(String),
}

fn presented(headers: &HeaderMap, token: &str) -> Option<Via> {
    let bearer = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    if bearer.is_some_and(|p| crate::mcp::same(p.as_bytes(), token.as_bytes())) {
        return Some(Via::Header);
    }
    headers
        .get_all("sec-websocket-protocol")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(str::trim)
        .find(|p| {
            p.strip_prefix(SUBPROTOCOL)
                .is_some_and(|t| crate::mcp::same(t.as_bytes(), token.as_bytes()))
        })
        .map(|p| Via::Subprotocol(p.to_string()))
}

async fn session(socket: WebSocket, agent: Agent) {
    let (mut sink, mut source) = socket.split();
    let Some(lease) = Lease::take() else {
        let _ = sink
            .send(Message::Close(Some(CloseFrame {
                code: BUSY,
                reason: "busy".into(),
            })))
            .await;
        return;
    };
    let (in_tx, in_rx) = mpsc::channel::<String>(QUEUE);
    let (out_tx, mut out_rx) = mpsc::channel::<String>(QUEUE);
    let reader = tokio::spawn(async move {
        while let Ok(Some(Ok(msg))) = tokio::time::timeout(SILENCE, source.next()).await {
            match msg {
                Message::Text(text) => {
                    if in_tx.send(text.to_string()).await.is_err() {
                        break;
                    }
                }
                Message::Close(_) => break,
                _ => {}
            }
        }
    });
    let writer = async move {
        let mut tick = tokio::time::interval(PING_EVERY);
        tick.tick().await;
        loop {
            let msg = tokio::select! {
                line = out_rx.recv() => match line {
                    Some(line) => Message::Text(line.into()),
                    None => break,
                },
                _ = tick.tick() => Message::Ping(Default::default()),
            };
            if !matches!(
                tokio::time::timeout(SILENCE, sink.send(msg)).await,
                Ok(Ok(()))
            ) {
                break;
            }
        }
        sink
    };
    let (outcome, mut sink) = tokio::join!(bridge(&agent, in_rx, out_tx), writer);
    let close = match outcome {
        Ok(()) => CloseFrame {
            code: 1000,
            reason: "".into(),
        },
        Err(e) => CloseFrame {
            code: 1011,
            reason: format!("the agent could not start: {e}").into(),
        },
    };
    let _ = tokio::time::timeout(
        Duration::from_secs(2),
        sink.send(Message::Close(Some(close))),
    )
    .await;
    reader.abort();
    drop(lease);
}

/// Runs one `aster acp` child for as long as `inbound` stays open and the child
/// keeps its stdout, rewriting what the editor sends on the way in. The child
/// is killed on the way out, whichever side ended it.
pub async fn bridge(
    agent: &Agent,
    mut inbound: mpsc::Receiver<String>,
    outbound: mpsc::Sender<String>,
) -> std::io::Result<()> {
    let mut child = tokio::process::Command::new(&agent.bin)
        .args(["acp", "--permission-mode", "auto"])
        .current_dir(&agent.home)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .kill_on_drop(true)
        .spawn()?;
    let (Some(mut stdin), Some(stdout)) = (child.stdin.take(), child.stdout.take()) else {
        return Err(std::io::Error::other("the agent has no stdio"));
    };
    let mut lines = BufReader::new(stdout).lines();
    let home = agent.home.to_string_lossy().into_owned();
    loop {
        tokio::select! {
            msg = inbound.recv() => {
                let Some(msg) = msg else { break };
                let line = rewrite(&msg, &home) + "\n";
                if stdin.write_all(line.as_bytes()).await.is_err() || stdin.flush().await.is_err() {
                    break;
                }
            }
            line = lines.next_line() => match line {
                Ok(Some(line)) => {
                    if outbound.send(line).await.is_err() {
                        break;
                    }
                }
                Ok(None) | Err(_) => break,
            },
        }
    }
    let _ = child.kill().await;
    Ok(())
}

/// The editor's view of the world does not exist on the phone. Its project path
/// would fail the agent's `canonicalize`, and a stdio MCP server would try to
/// start a desktop binary here, so sessions open in the phone's home and keep
/// only the MCP servers reached over the network.
pub fn rewrite(line: &str, home: &str) -> String {
    let Ok(mut msg) = serde_json::from_str::<Value>(line) else {
        return line.replace(['\r', '\n'], " ");
    };
    let opens_session = matches!(
        msg.get("method").and_then(Value::as_str),
        Some("session/new" | "session/load")
    );
    if opens_session {
        if let Some(params) = msg.get_mut("params").and_then(Value::as_object_mut) {
            params.insert("cwd".to_string(), Value::String(home.to_string()));
            if let Some(servers) = params.get_mut("mcpServers").and_then(Value::as_array_mut) {
                servers.retain(|s| {
                    matches!(s.get("type").and_then(Value::as_str), Some("http" | "sse"))
                });
            }
        }
    }
    if opens_session || line.contains(['\r', '\n']) {
        msg.to_string()
    } else {
        line.to_string()
    }
}

static HELD: AtomicBool = AtomicBool::new(false);

/// One editor drives the phone at a time. `acp --ws` and the relay connector
/// are separate processes, so on the phone the lease is also an abstract
/// socket name, which the kernel frees when the holder dies.
pub struct Lease {
    held: Option<std::os::unix::net::UnixListener>,
}

impl Lease {
    pub fn take() -> Option<Lease> {
        if HELD.swap(true, Ordering::AcqRel) {
            return None;
        }
        match claim_name() {
            Ok(held) => Some(Lease { held }),
            Err(_) => {
                HELD.store(false, Ordering::Release);
                None
            }
        }
    }
}

impl Drop for Lease {
    fn drop(&mut self) {
        drop(self.held.take());
        HELD.store(false, Ordering::Release);
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn claim_name() -> std::io::Result<Option<std::os::unix::net::UnixListener>> {
    #[cfg(target_os = "android")]
    use std::os::android::net::SocketAddrExt;
    #[cfg(target_os = "linux")]
    use std::os::linux::net::SocketAddrExt;
    const LEASE_NAME: &str = "aster-acp-driver";
    // SAFETY: getuid has no preconditions and cannot fail.
    let uid = unsafe { libc::getuid() };
    let addr = std::os::unix::net::SocketAddr::from_abstract_name(format!("{LEASE_NAME}-{uid}"))?;
    std::os::unix::net::UnixListener::bind_addr(&addr).map(Some)
}

/// Hosts without abstract sockets only run tests, which share one process.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn claim_name() -> std::io::Result<Option<std::os::unix::net::UnixListener>> {
    Ok(None)
}

#[cfg(test)]
#[path = "acp_tests.rs"]
pub(crate) mod tests;
