//! The mirror: H.264 out over a websocket, touches back in, all on
//! localhost. A browser on the phone opens the viewer and drives the screen
//! directly; no agent in the loop. Telegram's webview reaches the same page
//! through `tailscale serve`, since a web app button needs HTTPS.

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::response::{Html, IntoResponse};
use axum::routing::get;
use axum::Router;
use serde::Deserialize;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};

const VIEWER: &str = include_str!("../viewer.html");
const MUXER: &str = include_str!("../jmuxer.min.js");
/// A viewer here wins over the built-in one, so the page can be pushed and
/// reloaded without reinstalling the app, which costs the capture consent and
/// the accessibility toggle every time.
const VIEWER_OVERRIDE: &str = "/data/data/dev.aster.probe/files/viewer.html";
pub const DEFAULT_PORT: u16 = 7070;
pub const DEFAULT_BIND: &str = "127.0.0.1";
// A frame over this is a desync, not a picture.
const MAX_PACKET: usize = 8 << 20;
const NAL_SPS: u8 = 7;
const NAL_PPS: u8 = 8;

/// The one knob the viewer has: how many bits the encoder may spend. The
/// capture itself has a fixed size and rate on the phone.
#[derive(Clone, Copy)]
struct Settings {
    kbps: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { kbps: 6_000 }
    }
}

pub fn run(port: u16, bind: &str) {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(serve(port, bind));
}

async fn serve(port: u16, bind: &str) {
    let app = Router::new()
        .route("/", get(root))
        .route("/jmuxer.min.js", get(muxer))
        .route("/ws", get(mirror))
        .with_state((Arc::new(Mutex::new(())), Arc::new(Mutex::new(Settings::default()))));
    let addr = format!("{bind}:{port}");
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| panic!("cannot bind {addr}: {e}"));
    println!("mirror on http://{addr}");
    axum::serve(listener, app).await.expect("serve");
}

async fn root() -> Html<String> {
    let path = std::env::var("ASTER_MIRROR_UI").unwrap_or_else(|_| VIEWER_OVERRIDE.to_string());
    Html(std::fs::read_to_string(path).unwrap_or_else(|_| VIEWER.to_string()))
}

async fn muxer() -> impl IntoResponse {
    ([("content-type", "application/javascript")], MUXER)
}

type Commands = Arc<Mutex<()>>;
type SettingsHandle = Arc<Mutex<Settings>>;

async fn mirror(
    upgrade: WebSocketUpgrade,
    State((commands, settings)): State<(Commands, SettingsHandle)>,
) -> impl IntoResponse {
    upgrade.on_upgrade(move |socket| session(socket, commands, settings))
}

async fn session(mut ws: WebSocket, commands: Commands, settings: SettingsHandle) {
    // Video arrives in bursts the stills never did, so the queue is deeper.
    let (tx, mut rx) = mpsc::channel::<Message>(64);
    let mut producer = Producer::spawn(tx.clone(), &settings).await;
    loop {
        tokio::select! {
            frame = rx.recv() => match frame {
                Some(msg) => { if ws.send(msg).await.is_err() { break } }
                None => break,
            },
            input = ws.recv() => match input {
                Some(Ok(Message::Text(text))) => {
                    if act(&text, &commands, &settings, &tx).await {
                        producer.stop();
                        producer = Producer::spawn(tx.clone(), &settings).await;
                    }
                }
                None | Some(Ok(Message::Close(_))) | Some(Err(_)) => break,
                Some(Ok(_)) => {}
            },
        }
    }
    producer.stop();
}

/// The stream for one viewer, and the means to end it. Aborting the task is
/// not enough: its socket read is a blocking
/// thread that outlives the abort and keeps feeding the channel, so a preset
/// switch would leave a second stream at the old size stacked on the new one.
/// Shutting the socket is what actually ends it.
struct Producer {
    task: tokio::task::JoinHandle<()>,
    socket: Arc<std::sync::Mutex<Option<UnixStream>>>,
}

impl Producer {
    async fn spawn(tx: mpsc::Sender<Message>, settings: &SettingsHandle) -> Self {
        let s = *settings.lock().await;
        let socket = Arc::new(std::sync::Mutex::new(None));
        let task = tokio::spawn(video(tx, s, socket.clone()));
        Producer { task, socket }
    }

    fn stop(&self) {
        if let Ok(mut slot) = self.socket.lock() {
            if let Some(sock) = slot.take() {
                let _ = sock.shutdown(Shutdown::Both);
            }
        }
        self.task.abort();
    }
}

/// scrcpy's header, which the phone writes because this parser was ported from
/// one already written against it.
const FLAG_CONFIG: u64 = 1 << 63;
const FLAG_KEY: u64 = 1 << 62;

/// Frame kinds the viewer reads off byte zero of a binary message.
const KIND_CONFIG: u8 = 0;
const KIND_KEY: u8 = 1;
const KIND_DELTA: u8 = 2;

/// The encoder's output, forwarded as fast as it arrives. The socket read is
/// blocking, so it gets a thread of its own rather than starving the runtime.
async fn video(
    tx: mpsc::Sender<Message>,
    s: Settings,
    handle: Arc<std::sync::Mutex<Option<UnixStream>>>,
) {
    let cmd = format!("stream h264 0 0 {}", s.kbps);
    let _ = tokio::task::spawn_blocking(move || pump(&cmd, tx, handle)).await;
}

fn pump(cmd: &str, tx: mpsc::Sender<Message>, handle: Arc<std::sync::Mutex<Option<UnixStream>>>) {
    let Ok(mut sock) = crate::socket::connect() else {
        return;
    };
    // A clone in the session's hands, so it can hang up on this thread.
    match (sock.try_clone(), handle.lock()) {
        (Ok(dup), Ok(mut slot)) => *slot = Some(dup),
        _ => return,
    }
    if sock.write_all(format!("{cmd}\n").as_bytes()).is_err() || sock.flush().is_err() {
        return;
    }
    let mut reader = BufReader::new(sock);
    // Lines until the stream starts: `note: ...` is a state the viewer shows
    // (the phone waiting on its consent dialog, say), `error: ...` is the end,
    // and `stream <w> <h> <fps> screen <W>x<H>` is the picture arriving.
    let mut head = String::new();
    loop {
        head.clear();
        if reader.read_line(&mut head).is_err() || head.is_empty() {
            return;
        }
        if head.starts_with("note:") {
            let _ = tx.blocking_send(Message::Text(head.trim().to_string().into()));
            continue;
        }
        break;
    }
    let Some((w, h)) = parse_stream(&head) else {
        let _ = tx.blocking_send(Message::Text(head.trim().to_string().into()));
        return;
    };
    let hello = hello(w, h, screen_of(&head));
    if tx.blocking_send(Message::Text(hello.into())).is_err() {
        return;
    }

    let mut header = [0u8; 12];
    loop {
        if reader.read_exact(&mut header).is_err() {
            return;
        }
        let pts = u64::from_be_bytes(header[0..8].try_into().unwrap_or_default());
        let size = u32::from_be_bytes(header[8..12].try_into().unwrap_or_default()) as usize;
        if size == 0 || size > MAX_PACKET {
            return;
        }
        let mut payload = vec![0u8; size];
        if reader.read_exact(&mut payload).is_err() {
            return;
        }
        let message = if pts & FLAG_CONFIG != 0 {
            let nals = split_nals(&payload);
            let codec = codec_string(&nals);
            let config = format!("{{\"type\":\"config\",\"codec\":\"{codec}\"}}");
            if tx.blocking_send(Message::Text(config.into())).is_err() {
                return;
            }
            let mut out = vec![KIND_CONFIG];
            out.extend_from_slice(&build_avcc(&nals));
            out
        } else {
            let kind = if pts & FLAG_KEY != 0 { KIND_KEY } else { KIND_DELTA };
            let mut out = Vec::with_capacity(payload.len() + 9);
            out.push(kind);
            out.extend_from_slice(&(pts & !(FLAG_CONFIG | FLAG_KEY)).to_be_bytes());
            out.extend_from_slice(&to_avcc(&payload));
            out
        };
        if tx.blocking_send(Message::Binary(message.into())).is_err() {
            return;
        }
    }
}

/// The frame size is what the viewer draws; the screen size is what it taps.
/// They differ whenever the frame is scaled, which is every preset but `full`.
fn hello(w: u32, h: u32, screen: Option<(u32, u32)>) -> String {
    let mut out = format!("{{\"type\":\"hello\",\"width\":{w},\"height\":{h}");
    if let Some((sw, sh)) = screen {
        out.push_str(&format!(",\"screen_width\":{sw},\"screen_height\":{sh}"));
    }
    out.push('}');
    out
}

/// The `screen WxH` the phone appends after the frame size.
fn screen_of(line: &str) -> Option<(u32, u32)> {
    let first = line.lines().next()?;
    let rest = first.split(" screen ").nth(1)?;
    let (w, h) = rest.trim().split_once('x')?;
    Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
}

fn parse_stream(line: &str) -> Option<(u32, u32)> {
    let rest = line.trim().strip_prefix("stream ")?;
    let mut parts = rest.split_whitespace();
    Some((parts.next()?.parse().ok()?, parts.next()?.parse().ok()?))
}

/// Annex-B in, the NAL bodies out, start codes dropped.
fn split_nals(data: &[u8]) -> Vec<&[u8]> {
    let mut starts: Vec<(usize, usize)> = Vec::new();
    let mut i = 0;
    while i + 2 < data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            if i > 0 && data[i - 1] == 0 {
                starts.push((i - 1, 4));
            } else {
                starts.push((i, 3));
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut nals = Vec::with_capacity(starts.len());
    for (n, &(at, code)) in starts.iter().enumerate() {
        let from = at + code;
        let to = starts.get(n + 1).map(|&(next, _)| next).unwrap_or(data.len());
        if from < to {
            nals.push(&data[from..to]);
        }
    }
    nals
}

/// WebCodecs takes length-prefixed NALs, not start codes.
fn to_avcc(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() + 8);
    for nal in split_nals(data) {
        out.extend_from_slice(&(nal.len() as u32).to_be_bytes());
        out.extend_from_slice(nal);
    }
    out
}

/// The profile, constraints and level the SPS declares, which is the whole of
/// what `VideoDecoder` needs to pick a decoder.
fn codec_string(nals: &[&[u8]]) -> String {
    for nal in nals {
        if nal.len() >= 4 && nal[0] & 0x1F == NAL_SPS {
            return format!("avc1.{:02x}{:02x}{:02x}", nal[1], nal[2], nal[3]);
        }
    }
    "avc1.42001e".to_string()
}

/// The `avcC` box `VideoDecoder` wants as its `description`.
fn build_avcc(nals: &[&[u8]]) -> Vec<u8> {
    let sps: Vec<&&[u8]> = nals.iter().filter(|n| !n.is_empty() && n[0] & 0x1F == NAL_SPS).collect();
    let pps: Vec<&&[u8]> = nals.iter().filter(|n| !n.is_empty() && n[0] & 0x1F == NAL_PPS).collect();
    let Some(first) = sps.first() else {
        return Vec::new();
    };
    if first.len() < 4 {
        return Vec::new();
    }
    let mut out = vec![1, first[1], first[2], first[3], 0xFF, 0xE0 | (sps.len() as u8 & 0x1F)];
    for nal in &sps {
        out.extend_from_slice(&(nal.len() as u16).to_be_bytes());
        out.extend_from_slice(nal);
    }
    out.push(pps.len() as u8);
    for nal in &pps {
        out.extend_from_slice(&(nal.len() as u16).to_be_bytes());
        out.extend_from_slice(nal);
    }
    out
}

/// A finger on the viewer is a finger on the phone: `down`, then a `move` per
/// pointer event, then `up`. These go to the service's `live` verbs, which
/// dispatch and return rather than reading the screen and waiting for it to
/// settle, and nothing here waits for the reply.
#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum Input {
    Tap { x: f32, y: f32 },
    Press { x: f32, y: f32 },
    Down { x: f32, y: f32 },
    Move { x: f32, y: f32, ms: u64 },
    Up,
    Key { name: String },
    Text { text: String },
    Cfg {
        kbps: u32,
    },
}

/// True when the source has to be rebuilt, which is what a new `cfg` means.
async fn act(
    json: &str,
    commands: &Commands,
    settings: &SettingsHandle,
    tx: &mpsc::Sender<Message>,
) -> bool {
    let Ok(input) = serde_json::from_str::<Input>(json) else {
        return false;
    };
    if let Input::Cfg { kbps } = input {
        // Only a real change is worth restarting the stream for. The viewer
        // sends its preset the moment it connects, and rebuilding the stream
        // for a setting it already has costs a second join, a second consent
        // prompt, and a fresh keyframe nobody needed.
        let next = Settings { kbps: kbps.clamp(200, 20_000) };
        let mut current = settings.lock().await;
        if current.kbps == next.kbps {
            return false;
        }
        *current = next;
        return true;
    }
    // Touches are sent and left. Waiting on the reply would serialise the
    // finger behind whatever the service is doing, and a finger that arrives
    // late is a finger in the wrong place.
    let live = match &input {
        Input::Tap { x, y } => Some(format!("live tap {x:.0},{y:.0}")),
        Input::Press { x, y } => Some(format!("live press {x:.0},{y:.0}")),
        Input::Down { x, y } => Some(format!("live down {x:.0},{y:.0}")),
        Input::Move { x, y, ms } => Some(format!("live move {x:.0},{y:.0} {ms}")),
        Input::Up => Some("live up".to_string()),
        Input::Key { name } => match name.as_str() {
            "back" | "home" | "recents" | "notifications" => Some(format!("live key {name}")),
            _ => None,
        },
        _ => None,
    };
    if let Some(cmd) = live {
        let _ = tokio::task::spawn_blocking(move || crate::socket::fire(&cmd)).await;
        return false;
    }

    let cmd = match input {
        Input::Key { name } => match name.as_str() {
            "volume_up" => "volume up".to_string(),
            "volume_down" => "volume down".to_string(),
            _ => return false,
        },
        Input::Text { text } => format!("text {}", text.replace(['\n', '\r'], " ")),
        _ => return false,
    };
    let _guard = commands.lock().await;
    let reply = command(&cmd).await;
    if reply.starts_with("error:") {
        let _ = tx.send(Message::Text(reply.into())).await;
    }
    false
}

async fn command(cmd: &str) -> String {
    let owned = cmd.to_string();
    tokio::task::spawn_blocking(move || crate::socket::one(&owned).unwrap_or_default())
        .await
        .unwrap_or_default()
}