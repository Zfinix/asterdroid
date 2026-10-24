//! Aster Relay, the phone's end. One outbound WebSocket to the relay carries MCP
//! and ACP for clients paired from anywhere, so no port opens on the phone and
//! no Tailscale is needed. `relay/` is the other end and docs/RELAY.md the wire
//! format. The app drives this process with the one-shot verbs (`relay pair`,
//! `relay clients`, ...), which talk to it over the `aster-relay` socket.

use crate::acp::{self, Agent, Lease};
use crate::frame::{self, Frame};
use crate::mcp::{self, Access};
use axum::http::{HeaderValue, Uri};
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use base64::Engine;
use ed25519_dalek::{Signer, SigningKey};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::Message;

pub const URL_ENV: &str = "ASTER_RELAY_URL";
pub const DEFAULT_URL: &str = "https://relay.withaster.dev";
const CONTROL_NAME: &str = "aster-relay";
const LINK_LABEL: &str = "aster-relay/v1/link";
const FP_LABEL: &str = "aster-relay/v1/fp\n";
/// The relay answers `ping` without waking its Durable Object, so this costs
/// nothing there and keeps carrier NATs from dropping an idle link.
const PING_EVERY: Duration = Duration::from_secs(25);
/// The relay answers every ping, so this long without a word from it means the
/// path is dead even though TCP has not noticed yet.
#[cfg(not(test))]
const SILENCE: Duration = Duration::from_secs(PING_EVERY.as_secs() * 3);
#[cfg(test)]
const SILENCE: Duration = Duration::from_secs(2);
#[cfg(not(test))]
const DIAL_TIMEOUT: Duration = Duration::from_secs(20);
#[cfg(test)]
const DIAL_TIMEOUT: Duration = Duration::from_secs(2);
const BACKOFF_CEILING: Duration = Duration::from_secs(60);
/// A link that lasted this long was healthy, so the next drop starts over at 1 s.
const STABLE: Duration = Duration::from_secs(60);
const ASK_TIMEOUT: Duration = Duration::from_secs(15);
/// The relay's own cap on an MCP request; one JSON-RPC message never comes near it.
const MAX_BODY: usize = 1 << 20;
const MAX_CONTROL_LINE: u64 = 64 << 10;
const QUEUE: usize = 256;
const DEFAULT_SCOPES: [&str; 2] = ["phone:act", "agent:chat"];
const PAIR_TTL: u64 = 300;
const CLAIM_KIND: &str = "mcp-shim";

/// The phone's relay identity. The phone id is a hash of the public key, so
/// it needs no registration and nobody else can claim it.
pub struct Identity {
    key: SigningKey,
}

impl Identity {
    pub fn from_seed(seed: &[u8; 32]) -> Identity {
        Identity {
            key: SigningKey::from_bytes(seed),
        }
    }

    pub fn load_or_create(dir: &Path) -> std::io::Result<Identity> {
        let path = dir.join("device.json");
        // Only a missing file means a new phone; any other failure would replace its key.
        let text = match std::fs::read_to_string(&path) {
            Ok(text) => Some(text),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(e) => return Err(e),
        };
        if let Some(text) = text {
            let seed = serde_json::from_str::<Value>(&text)
                .ok()
                .and_then(|v| v.get("ed25519_sk")?.as_str().map(str::to_string))
                .and_then(|s| B64.decode(s).ok())
                .and_then(|b| <[u8; 32]>::try_from(b).ok())
                .ok_or_else(|| {
                    std::io::Error::other(format!(
                        "{} is damaged; remove it to give this phone a new relay id",
                        path.display()
                    ))
                })?;
            return Ok(Identity::from_seed(&seed));
        }
        let mut seed = [0u8; 32];
        getrandom::getrandom(&mut seed).map_err(std::io::Error::other)?;
        let identity = Identity::from_seed(&seed);
        let device = json!({ "ed25519_sk": B64.encode(seed), "phone_id": identity.phone_id() });
        write_private(&path, &device.to_string())?;
        Ok(identity)
    }

    pub fn public(&self) -> [u8; 32] {
        self.key.verifying_key().to_bytes()
    }

    pub fn phone_id(&self) -> String {
        phone_id(&self.public())
    }

    pub fn fingerprint(&self) -> String {
        fingerprint(&self.public())
    }

    pub fn sign_link(&self, phone_id: &str, nonce: &str, host: &str) -> String {
        B64.encode(
            self.key
                .sign(link_message(phone_id, nonce, host).as_bytes())
                .to_bytes(),
        )
    }
}

pub fn phone_id(public: &[u8]) -> String {
    format!("ph_{}", &base32(&Sha256::digest(public))[..26])
}

/// Printed in the pairing QR so a client can check it reached this phone.
pub fn fingerprint(public: &[u8]) -> String {
    let mut hash = Sha256::new();
    hash.update(FP_LABEL.as_bytes());
    hash.update(public);
    base32(&hash.finalize())[..16].to_string()
}

/// The host is signed too, so a relay cannot pass a challenge from another
/// relay through to this phone and log in there as it.
pub fn link_message(phone_id: &str, nonce: &str, host: &str) -> String {
    format!("{LINK_LABEL}\n{phone_id}\n{nonce}\n{host}")
}

/// RFC 4648 base32, lowercase and unpadded.
fn base32(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";
    let mut out = String::with_capacity(bytes.len().div_ceil(5) * 8);
    let (mut acc, mut bits) = (0u32, 0u32);
    for &b in bytes {
        acc = (acc << 8) | u32::from(b);
        bits += 8;
        while bits >= 5 {
            out.push(ALPHABET[((acc >> (bits - 5)) & 31) as usize] as char);
            bits -= 5;
        }
    }
    if bits > 0 {
        out.push(ALPHABET[((acc << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// Secrets live in the app's private directory, and only the app's uid reads them.
fn write_private(path: &Path, text: &str) -> std::io::Result<()> {
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
    if let Some(dir) = path.parent() {
        std::fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)?;
    }
    let tmp = path.with_extension("tmp");
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&tmp)?;
    file.write_all(text.as_bytes())?;
    file.sync_all()?;
    std::fs::rename(tmp, path)
}

/// Where the relay is, in the three spellings the link needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Endpoint {
    /// `https://relay.withaster.dev`, for links people open.
    pub web: String,
    /// `relay.withaster.dev`, as signed in the link challenge.
    pub host: String,
    /// `wss://relay.withaster.dev`, for the link itself.
    pub ws: String,
}

impl Endpoint {
    /// Plain http only reaches a relay on this machine or the emulator's host,
    /// since the link carries the screen.
    pub fn parse(url: &str) -> Result<Endpoint, String> {
        let uri: Uri = url
            .trim()
            .trim_end_matches('/')
            .parse()
            .map_err(|e| format!("{URL_ENV} is not a URL: {e}"))?;
        let authority = uri
            .authority()
            .ok_or_else(|| format!("{URL_ENV} has no host: {url}"))?;
        let local = matches!(authority.host(), "localhost" | "127.0.0.1" | "10.0.2.2");
        let (scheme, ws, default_port) = match uri.scheme_str() {
            Some("https") => ("https", "wss", 443),
            Some("http") if local => ("http", "ws", 80),
            _ => return Err(format!("{URL_ENV} must be an https:// URL, not {url}")),
        };
        let host = match authority.port_u16() {
            Some(port) if port != default_port => format!("{}:{port}", authority.host()),
            _ => authority.host().to_string(),
        };
        Ok(Endpoint {
            web: format!("{scheme}://{host}"),
            ws: format!("{ws}://{host}"),
            host,
        })
    }
}

/// A client this phone approved, kept on the phone as well as on the relay, so
/// a relay that invents a client or widens one's scopes gets nowhere.
#[derive(Clone, Debug, PartialEq)]
pub struct Client {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub scopes: Vec<String>,
}

impl Client {
    fn from_value(v: &Value) -> Option<Client> {
        Some(Client {
            id: v.get("id")?.as_str()?.to_string(),
            name: v
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            kind: v
                .get("kind")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            scopes: strings(v.get("scopes")),
        })
    }

    fn to_value(&self) -> Value {
        json!({ "id": self.id, "name": self.name, "kind": self.kind, "scopes": self.scopes })
    }
}

fn strings(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

fn load_clients(dir: &Path) -> Vec<Client> {
    std::fs::read_to_string(dir.join("clients.json"))
        .ok()
        .and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .and_then(|v| {
            v.as_array()
                .map(|a| a.iter().filter_map(Client::from_value).collect())
        })
        .unwrap_or_default()
}

/// The scopes a stream may use: what the relay says, cut down to what this
/// phone approved. `None` for a client the phone never approved.
pub fn granted(clients: &[Client], id: &str, offered: &[String]) -> Option<Vec<String>> {
    let client = clients.iter().find(|c| c.id == id)?;
    Some(
        offered
            .iter()
            .filter(|s| client.scopes.contains(s))
            .cloned()
            .collect(),
    )
}

/// A client id the phone picks itself, so it is on the phone's list before the
/// relay hands out a token for it.
fn new_client_id() -> std::io::Result<String> {
    let mut bytes = [0u8; 7];
    getrandom::getrandom(&mut bytes).map_err(std::io::Error::other)?;
    Ok(format!("c_{}", &base32(&bytes)[..10]))
}

/// A code this phone opened, and the scopes it was opened with. The relay's
/// claim cannot add to them.
struct Pairing {
    scopes: Vec<String>,
    exp: u64,
}

#[derive(Default)]
struct Status {
    online: bool,
    entitled: bool,
}

struct Shared {
    identity: Identity,
    phone_id: String,
    endpoint: Endpoint,
    dir: PathBuf,
    agent: Agent,
    status: Mutex<Status>,
    link: Mutex<Option<mpsc::Sender<Message>>>,
    asks: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    pending: Mutex<HashMap<String, Value>>,
    pairings: Mutex<HashMap<String, Pairing>>,
    clients: Mutex<Vec<Client>>,
    next: AtomicU64,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn binary(frame: &Frame) -> Message {
    Message::Binary(frame.encode().into())
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or_default()
}

impl Shared {
    fn save_clients(&self, clients: &[Client]) -> std::io::Result<()> {
        let list: Vec<Value> = clients.iter().map(Client::to_value).collect();
        write_private(
            &self.dir.join("clients.json"),
            &Value::Array(list).to_string(),
        )
    }

    fn remember(&self, client: Client) -> std::io::Result<()> {
        let mut clients = lock(&self.clients);
        clients.retain(|c| c.id != client.id);
        clients.push(client);
        self.save_clients(&clients)
    }

    fn forget(&self, ids: &[String]) {
        let mut clients = lock(&self.clients);
        clients.retain(|c| !ids.contains(&c.id));
        let _ = self.save_clients(&clients);
    }

    async fn send(&self, frame: Frame) -> bool {
        let link = lock(&self.link).clone();
        match link {
            Some(tx) => tx.send(binary(&frame)).await.is_ok(),
            None => false,
        }
    }

    /// One CTRL request to the relay and its reply, matched by `rid`.
    async fn ask(&self, mut msg: Value) -> Result<Value, String> {
        let rid = format!("r{}", self.next.fetch_add(1, Ordering::Relaxed));
        msg["rid"] = json!(rid);
        let (tx, rx) = oneshot::channel();
        lock(&self.asks).insert(rid.clone(), tx);
        if !self.send(Frame::ctrl(&msg)).await {
            lock(&self.asks).remove(&rid);
            return Err("the relay is offline".to_string());
        }
        let reply = tokio::time::timeout(ASK_TIMEOUT, rx).await;
        lock(&self.asks).remove(&rid);
        match reply {
            Ok(Ok(reply)) if reply["t"] == "error" => Err(reply["message"]
                .as_str()
                .unwrap_or("the relay refused that")
                .to_string()),
            Ok(Ok(reply)) => Ok(reply),
            _ => Err("the relay did not answer in time".to_string()),
        }
    }

    fn status(&self) -> Value {
        let status = lock(&self.status);
        json!({
            "online": status.online,
            "phone_id": self.phone_id,
            "url": self.endpoint.web,
            "entitled": status.entitled,
        })
    }

    async fn command(&self, req: &Value) -> Value {
        let outcome = match req["op"].as_str().unwrap_or_default() {
            "status" => Ok(self.status()),
            "pair" => self.pair(req).await,
            "clients" => self.list().await,
            "revoke" => self.revoke(req["id"].as_str().unwrap_or_default()).await,
            "approve" => {
                self.approve(
                    req["pending"].as_str().unwrap_or_default(),
                    req["ok"] == true,
                )
                .await
            }
            "add" => {
                self.add(req["link"].as_str().unwrap_or_default(), req.get("scopes"))
                    .await
            }
            op => Err(format!("unknown relay command: {op}")),
        };
        outcome.unwrap_or_else(|e| json!({ "error": e }))
    }

    async fn pair(&self, req: &Value) -> Result<Value, String> {
        let scopes = match strings(req.get("scopes")) {
            s if s.is_empty() => DEFAULT_SCOPES.iter().map(|s| s.to_string()).collect(),
            s => s,
        };
        let ttl = req["ttl"].as_u64().unwrap_or(PAIR_TTL).min(PAIR_TTL);
        let reply = self
            .ask(json!({ "t": "pair.open", "scopes": scopes, "ttl": ttl }))
            .await?;
        let code = reply["code"].as_str().ok_or("the relay sent no code")?;
        lock(&self.pairings).insert(
            code.to_string(),
            Pairing {
                scopes,
                exp: now() + ttl,
            },
        );
        Ok(json!({
            "code": code,
            "exp": reply["exp"],
            "url": format!(
                "{}/pair#1.{}.{code}.{}",
                self.endpoint.web,
                self.phone_id,
                self.identity.fingerprint()
            ),
        }))
    }

    async fn list(&self) -> Result<Value, String> {
        let reply = self.ask(json!({ "t": "client.list" })).await?;
        let clients = reply["clients"].as_array().cloned().unwrap_or_default();
        Ok(Value::Array(
            clients
                .iter()
                .map(|c| {
                    json!({
                        "id": c["id"], "name": c["name"], "kind": c["kind"], "scopes": c["scopes"],
                        "created": c["created"], "last_seen": c["last_seen"],
                    })
                })
                .collect(),
        ))
    }

    async fn revoke(&self, id: &str) -> Result<Value, String> {
        let msg = match id {
            "" => return Err("name a client id, or all".to_string()),
            "all" => json!({ "t": "client.revoke_all" }),
            id => json!({ "t": "client.revoke", "id": id }),
        };
        let reply = self.ask(msg).await?;
        let ids = strings(reply.get("ids"));
        self.forget(&ids);
        if id == "all" {
            let everyone: Vec<String> = lock(&self.clients).iter().map(|c| c.id.clone()).collect();
            self.forget(&everyone);
        }
        Ok(json!({ "revoked": ids }))
    }

    /// A claim uses up one open code, as it does on the relay, and gets no
    /// scope that code was not opened with. `None` when no code is open.
    fn claim(&self, offered: &[String]) -> Option<Vec<String>> {
        let mut pairings = lock(&self.pairings);
        pairings.retain(|_, p| p.exp > now());
        let code = pairings
            .iter()
            .find(|(_, p)| offered.iter().all(|s| p.scopes.contains(s)))
            .or_else(|| pairings.iter().next())
            .map(|(code, _)| code.clone())?;
        let pairing = pairings.remove(&code)?;
        Some(
            offered
                .iter()
                .filter(|s| pairing.scopes.contains(s))
                .cloned()
                .collect(),
        )
    }

    /// The client goes on the phone's list before the relay hears yes, so its
    /// first request, which can arrive straight after, is not refused.
    async fn approve(&self, pending: &str, ok: bool) -> Result<Value, String> {
        let info = lock(&self.pending)
            .remove(pending)
            .ok_or_else(|| format!("no client is waiting as {pending}"))?;
        if info["exp"].as_u64().is_none_or(|exp| exp <= now()) {
            return Err(format!("{pending} stopped waiting; pair again"));
        }
        if lock(&self.clients).iter().any(|c| c.id == pending) {
            return Err(format!("{pending} is already a client of this phone"));
        }
        if ok {
            let client = Client {
                id: pending.to_string(),
                name: info["name"].as_str().unwrap_or_default().to_string(),
                kind: info["kind"].as_str().unwrap_or_default().to_string(),
                scopes: strings(info.get("scopes")),
            };
            self.remember(client)
                .map_err(|e| format!("cannot save the client list: {e}"))?;
        }
        let reply = self
            .ask(json!({ "t": "pair.approve", "pending": pending, "ok": ok }))
            .await;
        if let Err(e) = reply {
            if ok {
                self.forget(&[pending.to_string()]);
            }
            return Err(e);
        }
        Ok(json!({ "ok": true, "client": pending, "allowed": ok }))
    }

    /// The phone scanned a client's QR; scanning it is the consent.
    async fn add(&self, link: &str, scopes: Option<&Value>) -> Result<Value, String> {
        let claim = ClaimLink::parse(link)?;
        let scopes = match strings(scopes) {
            s if s.is_empty() => DEFAULT_SCOPES.iter().map(|s| s.to_string()).collect(),
            s => s,
        };
        let id = new_client_id().map_err(|e| e.to_string())?;
        let client = Client {
            id: id.clone(),
            name: claim.name.clone(),
            kind: CLAIM_KIND.to_string(),
            scopes,
        };
        self.remember(client.clone())
            .map_err(|e| format!("cannot save the client list: {e}"))?;
        let reply = self
            .ask(json!({
                "t": "client.add", "id": id, "nonce": claim.nonce, "noise_pub": claim.client_pub,
                "name": claim.name, "kind": CLAIM_KIND, "scopes": client.scopes,
            }))
            .await;
        if let Err(e) = reply {
            self.forget(&[id]);
            return Err(e);
        }
        Ok(json!({ "ok": true, "client": client.to_value() }))
    }
}

/// `https://<relay>/claim#1.<client_pub>.<nonce>.<name_b64url>`, the QR a
/// client shows for the phone to scan.
#[derive(Debug, PartialEq, Eq)]
pub struct ClaimLink {
    pub client_pub: String,
    pub nonce: String,
    pub name: String,
}

impl ClaimLink {
    pub fn parse(link: &str) -> Result<ClaimLink, String> {
        let bad = || "that is not a Droid claim link".to_string();
        let (_, fragment) = link.trim().split_once("/claim#").ok_or_else(bad)?;
        let parts: Vec<&str> = fragment.split('.').collect();
        let [version, client_pub, nonce, name] = parts.as_slice() else {
            return Err(bad());
        };
        if *version != "1" || nonce.len() < 16 || client_pub.is_empty() {
            return Err(bad());
        }
        let name = B64
            .decode(name)
            .ok()
            .and_then(|n| String::from_utf8(n).ok())
            .ok_or_else(bad)?;
        Ok(ClaimLink {
            client_pub: client_pub.to_string(),
            nonce: nonce.to_string(),
            name: name.chars().take(80).collect(),
        })
    }
}

enum Stream {
    Mcp { body: Vec<u8>, access: Access },
    Acp(mpsc::Sender<String>),
}

pub fn run() {
    let home = acp::Agent::from_env().home;
    let url = std::env::var(URL_ENV).ok().filter(|u| !u.trim().is_empty());
    let endpoint = match Endpoint::parse(url.as_deref().unwrap_or(DEFAULT_URL)) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("error: {e}");
            std::process::exit(2);
        }
    };
    let dir = state_dir(&home);
    let identity = match Identity::load_or_create(&dir) {
        Ok(i) => i,
        Err(e) => {
            eprintln!("error: cannot load the relay key in {}: {e}", dir.display());
            std::process::exit(1);
        }
    };
    let shared = Arc::new(Shared {
        phone_id: identity.phone_id(),
        identity,
        endpoint,
        clients: Mutex::new(load_clients(&dir)),
        dir,
        agent: acp::Agent::from_env(),
        status: Mutex::default(),
        link: Mutex::default(),
        asks: Mutex::default(),
        pending: Mutex::default(),
        pairings: Mutex::default(),
        next: AtomicU64::new(1),
    });
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(connector(shared));
}

fn state_dir(home: &Path) -> PathBuf {
    home.join(".aster").join("relay")
}

async fn connector(shared: Arc<Shared>) {
    let control = match bind_control(&shared.dir) {
        Ok(listener) => listener,
        Err(e) => {
            eprintln!("error: cannot open the {CONTROL_NAME} control socket: {e}; is another relay running?");
            std::process::exit(1);
        }
    };
    tokio::spawn(serve_control(shared.clone(), control));
    let mut backoff = Duration::from_secs(1);
    let mut last = String::new();
    loop {
        let started = std::time::Instant::now();
        let reason = link(&shared).await;
        let was_online = std::mem::take(&mut lock(&shared.status).online);
        if was_online || reason != last {
            println!("relay: offline {reason}");
            last = reason;
        }
        if started.elapsed() > STABLE {
            backoff = Duration::from_secs(1);
        }
        tokio::time::sleep(jitter(backoff)).await;
        backoff = (backoff * 2).min(BACKOFF_CEILING);
    }
}

/// Full jitter, so phones that lost the relay together do not return together.
fn jitter(ceiling: Duration) -> Duration {
    let mut bytes = [0u8; 4];
    let roll = getrandom::getrandom(&mut bytes)
        .map(|()| u32::from_le_bytes(bytes))
        .unwrap_or(u32::MAX);
    ceiling
        .mul_f64(f64::from(roll) / f64::from(u32::MAX))
        .max(Duration::from_millis(250))
}

/// One life of the link, from dial to drop; the reason it ended comes back.
async fn link(shared: &Arc<Shared>) -> String {
    let url = format!("{}/p/{}/link", shared.endpoint.ws, shared.phone_id);
    let mut request = match url.as_str().into_client_request() {
        Ok(r) => r,
        Err(e) => return format!("bad relay URL {url}: {e}"),
    };
    if let Ok(public) = HeaderValue::from_str(&B64.encode(shared.identity.public())) {
        request.headers_mut().insert("x-aster-pub", public);
    }
    let ws =
        match tokio::time::timeout(DIAL_TIMEOUT, tokio_tungstenite::connect_async(request)).await {
            Ok(Ok((ws, _))) => ws,
            Ok(Err(e)) => return format!("cannot reach {}: {e}", shared.endpoint.web),
            Err(_) => return format!("cannot reach {}: it did not answer", shared.endpoint.web),
        };
    let (mut sink, mut source) = ws.split();
    let (tx, mut rx) = mpsc::channel::<Message>(QUEUE);
    let writer = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if sink.send(msg).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });
    let pinger = {
        let tx = tx.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(PING_EVERY);
            tick.tick().await;
            loop {
                tick.tick().await;
                if tx.send(Message::Text("ping".into())).await.is_err() {
                    break;
                }
            }
        })
    };
    let mut streams: HashMap<u32, Stream> = HashMap::new();
    let reason = loop {
        let msg = match tokio::time::timeout(SILENCE, source.next()).await {
            Ok(Some(Ok(msg))) => msg,
            Ok(Some(Err(e))) => break format!("the link failed: {e}"),
            Ok(None) => break "the relay closed the link".to_string(),
            Err(_) => break "the relay stopped answering".to_string(),
        };
        match msg {
            Message::Binary(bytes) => {
                let Some(frame) = Frame::decode(&bytes) else {
                    continue;
                };
                if let Err(reason) = on_frame(shared, &tx, &mut streams, frame).await {
                    break reason;
                }
            }
            Message::Close(close) => break closed(close.map(|c| (c.code, c.reason.to_string()))),
            _ => {}
        }
    };
    *lock(&shared.link) = None;
    pinger.abort();
    drop(streams);
    drop(tx);
    let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
    reason
}

fn closed(close: Option<(CloseCode, String)>) -> String {
    match close {
        Some((code, reason)) if !reason.is_empty() => {
            format!("the relay closed the link ({}: {reason})", u16::from(code))
        }
        Some((code, _)) => format!("the relay closed the link ({})", u16::from(code)),
        None => "the relay closed the link".to_string(),
    }
}

async fn on_frame(
    shared: &Arc<Shared>,
    tx: &mpsc::Sender<Message>,
    streams: &mut HashMap<u32, Stream>,
    frame: Frame,
) -> Result<(), String> {
    match frame.kind {
        frame::CTRL => on_ctrl(shared, tx, frame.value().unwrap_or(Value::Null)).await,
        frame::OPEN => {
            open(shared, tx, streams, &frame).await;
            Ok(())
        }
        frame::DATA => {
            let too_big = match streams.get_mut(&frame.stream) {
                Some(Stream::Mcp { body, .. }) if body.len() + frame.payload.len() > MAX_BODY => {
                    true
                }
                Some(Stream::Mcp { body, .. }) => {
                    body.extend_from_slice(&frame.payload);
                    false
                }
                _ => false,
            };
            if too_big {
                streams.remove(&frame.stream);
                reset(tx, frame.stream, "too_big", "the request is over 1 MiB").await;
            }
            Ok(())
        }
        frame::END => {
            if let Some(Stream::Mcp { body, access }) = streams.remove(&frame.stream) {
                tokio::spawn(answer_mcp(tx.clone(), frame.stream, body, access));
            }
            Ok(())
        }
        frame::RESET => {
            streams.remove(&frame.stream);
            Ok(())
        }
        frame::WSMSG => {
            let alive = match (streams.get(&frame.stream), frame.ws_text()) {
                (Some(Stream::Acp(inbound)), Some(text)) => {
                    inbound.send(text.to_string()).await.is_ok()
                }
                _ => true,
            };
            if !alive {
                streams.remove(&frame.stream);
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

async fn on_ctrl(
    shared: &Arc<Shared>,
    tx: &mpsc::Sender<Message>,
    msg: Value,
) -> Result<(), String> {
    match msg["t"].as_str().unwrap_or_default() {
        "challenge" => {
            let host = msg["host"].as_str().unwrap_or_default();
            if host != shared.endpoint.host {
                return Err(format!(
                    "the relay asked this phone to sign in to {host}, not {}",
                    shared.endpoint.host
                ));
            }
            let nonce = msg["nonce"].as_str().unwrap_or_default();
            let auth = json!({
                "t": "auth",
                "sig": shared.identity.sign_link(&shared.phone_id, nonce, host),
                "app": env!("CARGO_PKG_VERSION"),
                "abi": std::env::consts::ARCH,
            });
            let _ = tx.send(binary(&Frame::ctrl(&auth))).await;
        }
        "ready" => {
            {
                let mut status = lock(&shared.status);
                status.online = true;
                status.entitled = msg["entitled"] == true;
            }
            *lock(&shared.link) = Some(tx.clone());
            println!("relay: online {}", shared.phone_id);
        }
        "entitlement" => lock(&shared.status).entitled = msg["entitled"] == true,
        "pair.claimed" => {
            let Some(pending) = msg["pending"].as_str().filter(|p| p.starts_with("c_")) else {
                return Ok(());
            };
            if lock(&shared.clients).iter().any(|c| c.id == pending) {
                return Ok(());
            }
            let Some(scopes) = shared.claim(&strings(msg.get("scopes"))) else {
                return Ok(());
            };
            let info = json!({
                "name": msg["name"], "kind": msg["kind"], "scopes": scopes,
                "country": msg["country"], "ua": msg["ua"], "exp": now() + 120,
            });
            let mut waiting = lock(&shared.pending);
            waiting.retain(|_, w| w["exp"].as_u64().is_some_and(|exp| exp > now()));
            waiting.insert(pending.to_string(), info.clone());
            drop(waiting);
            println!("relay: approve {pending} {info}");
        }
        _ => {
            let waiter = msg["rid"]
                .as_str()
                .and_then(|rid| lock(&shared.asks).remove(rid));
            if let Some(waiter) = waiter {
                let _ = waiter.send(msg);
            }
        }
    }
    Ok(())
}

async fn reset(tx: &mpsc::Sender<Message>, stream: u32, code: &str, reason: &str) {
    let frame = Frame::json(
        frame::RESET,
        stream,
        &json!({ "code": code, "reason": reason }),
    );
    let _ = tx.send(binary(&frame)).await;
}

async fn open(
    shared: &Arc<Shared>,
    tx: &mpsc::Sender<Message>,
    streams: &mut HashMap<u32, Stream>,
    frame: &Frame,
) {
    let id = frame.stream;
    let req = frame.value().unwrap_or(Value::Null);
    let client = req["client"].as_str().unwrap_or_default();
    let offered = strings(req.get("scopes"));
    let scopes = granted(&lock(&shared.clients), client, &offered);
    let Some(scopes) = scopes else {
        return reset(
            tx,
            id,
            "forbidden",
            "this phone has not approved that client",
        )
        .await;
    };
    let has = |scope: &str| scopes.iter().any(|s| s == scope);
    match req["svc"].as_str().unwrap_or_default() {
        "mcp" => {
            let access = if has("phone:act") {
                Access::Act
            } else if has("phone:read") {
                Access::Read
            } else {
                return reset(tx, id, "forbidden", "this client may not use the phone").await;
            };
            streams.insert(
                id,
                Stream::Mcp {
                    body: Vec::new(),
                    access,
                },
            );
        }
        "acp" => {
            if !has("agent:chat") {
                return reset(tx, id, "forbidden", "this client may not talk to the agent").await;
            }
            let Some(lease) = Lease::take() else {
                return reset(tx, id, "busy", "another editor is driving the phone").await;
            };
            let (in_tx, in_rx) = mpsc::channel(QUEUE);
            streams.insert(id, Stream::Acp(in_tx));
            tokio::spawn(acp_stream(
                shared.agent.clone(),
                tx.clone(),
                id,
                in_rx,
                lease,
            ));
        }
        svc => {
            reset(
                tx,
                id,
                "unsupported",
                &format!("this phone does not serve {svc} over the relay"),
            )
            .await
        }
    }
}

async fn acp_stream(
    agent: Agent,
    tx: mpsc::Sender<Message>,
    id: u32,
    inbound: mpsc::Receiver<String>,
    lease: Lease,
) {
    let (out_tx, mut out_rx) = mpsc::channel::<String>(QUEUE);
    let forward = async {
        while let Some(line) = out_rx.recv().await {
            if tx.send(binary(&Frame::text(id, &line))).await.is_err() {
                break;
            }
        }
    };
    let (outcome, ()) = tokio::join!(acp::bridge(&agent, inbound, out_tx), forward);
    match outcome {
        Ok(()) => {
            let _ = tx
                .send(binary(&Frame::new(frame::END, id, Vec::new())))
                .await;
        }
        Err(e) => reset(&tx, id, "agent", &format!("the agent could not start: {e}")).await,
    }
    drop(lease);
}

async fn answer_mcp(tx: mpsc::Sender<Message>, id: u32, body: Vec<u8>, access: Access) {
    let reply = tokio::task::spawn_blocking(move || match serde_json::from_slice::<Value>(&body) {
        Ok(req) => mcp::handle_as(&req, access),
        Err(e) => Some(mcp::failure(
            Value::Null,
            -32700,
            &format!("parse error: {e}"),
        )),
    })
    .await;
    let head = |status: u16| {
        Frame::json(
            frame::HEAD,
            id,
            &json!({ "status": status, "headers": { "content-type": "application/json" } }),
        )
    };
    let mut frames = match reply {
        Ok(Some(reply)) => {
            let mut frames = vec![head(200)];
            frames.extend(frame::data(id, reply.to_string().as_bytes()));
            frames
        }
        Ok(None) => vec![head(202)],
        Err(_) => vec![head(500)],
    };
    frames.push(Frame::new(frame::END, id, Vec::new()));
    for frame in frames {
        if tx.send(binary(&frame)).await.is_err() {
            return;
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn control_addr(_dir: &Path) -> std::io::Result<std::os::unix::net::SocketAddr> {
    #[cfg(target_os = "android")]
    use std::os::android::net::SocketAddrExt;
    #[cfg(target_os = "linux")]
    use std::os::linux::net::SocketAddrExt;
    std::os::unix::net::SocketAddr::from_abstract_name(CONTROL_NAME)
}

/// Hosts without abstract sockets use a file, for development. It sits in /tmp
/// because a socket path has to fit in about 100 bytes.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn control_addr(_dir: &Path) -> std::io::Result<std::os::unix::net::SocketAddr> {
    // SAFETY: getuid has no preconditions and cannot fail.
    let uid = unsafe { libc::getuid() };
    std::os::unix::net::SocketAddr::from_pathname(format!("/tmp/{CONTROL_NAME}-{uid}.sock"))
}

fn bind_control(dir: &Path) -> std::io::Result<tokio::net::UnixListener> {
    let addr = control_addr(dir)?;
    if let Some(path) = addr.as_pathname() {
        let _ = std::fs::remove_file(path);
    }
    let listener = std::os::unix::net::UnixListener::bind_addr(&addr)?;
    listener.set_nonblocking(true)?;
    tokio::net::UnixListener::from_std(listener)
}

async fn serve_control(shared: Arc<Shared>, listener: tokio::net::UnixListener) {
    // SAFETY: getuid has no preconditions and cannot fail.
    let uid = unsafe { libc::getuid() };
    loop {
        let Ok((stream, _)) = listener.accept().await else {
            continue;
        };
        // An abstract socket has no file permissions; any app could connect,
        // and pairing is control of the phone, so only this app's uid may.
        if stream.peer_cred().map(|c| c.uid()).ok() != Some(uid) {
            continue;
        }
        let shared = shared.clone();
        tokio::spawn(async move {
            let (read, mut write) = stream.into_split();
            let mut line = String::new();
            let _ = BufReader::new(read.take(MAX_CONTROL_LINE))
                .read_line(&mut line)
                .await;
            let reply = match serde_json::from_str::<Value>(&line) {
                Ok(req) => shared.command(&req).await,
                Err(_) => json!({ "error": "the control request is not JSON" }),
            };
            let _ = write.write_all(format!("{reply}\n").as_bytes()).await;
            let _ = write.shutdown().await;
        });
    }
}

/// `asterctl relay <verb>`: one request to the running connector, its JSON
/// reply on stdout. The exit code is 1 when the reply is an error.
pub fn verb(args: &[String]) -> i32 {
    let req = match request(args) {
        Ok(req) => req,
        Err(e) => {
            println!("{}", json!({ "error": e }));
            return 2;
        }
    };
    let home = acp::Agent::from_env().home;
    let dir = state_dir(&home);
    let reply = ask_connector(&dir, &req).unwrap_or_else(|e| {
        if req["op"] == "status" {
            return offline_status(&dir);
        }
        json!({ "error": format!("the relay connector is not running ({e}); turn on the relay in Droid") })
    });
    println!("{reply}");
    i32::from(reply.get("error").is_some())
}

fn request(args: &[String]) -> Result<Value, String> {
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or_default();
    let flag = |name: &str| {
        args.iter()
            .position(|a| a == name)
            .and_then(|i| args.get(i + 1))
    };
    match arg(0) {
        "status" => Ok(json!({ "op": "status" })),
        "clients" => Ok(json!({ "op": "clients" })),
        "pair" => {
            let mut req = json!({ "op": "pair" });
            if let Some(scopes) = flag("--scopes") {
                req["scopes"] = json!(scopes.split(',').map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>());
            }
            if let Some(ttl) = flag("--ttl").and_then(|t| t.parse::<u64>().ok()) {
                req["ttl"] = json!(ttl);
            }
            Ok(req)
        }
        "revoke" if !arg(1).is_empty() => Ok(json!({ "op": "revoke", "id": arg(1) })),
        "approve" => match arg(2) {
            "yes" => Ok(json!({ "op": "approve", "pending": arg(1), "ok": true })),
            "no" => Ok(json!({ "op": "approve", "pending": arg(1), "ok": false })),
            _ => Err("usage: asterctl relay approve <pendingId> yes|no".to_string()),
        },
        "add" if !arg(1).is_empty() => Ok(json!({ "op": "add", "link": arg(1) })),
        _ => Err("usage: asterctl relay [pair|clients|revoke <id|all>|status|approve <id> yes|no|add <claim link>]".to_string()),
    }
}

fn ask_connector(dir: &Path, req: &Value) -> std::io::Result<Value> {
    let mut stream = std::os::unix::net::UnixStream::connect_addr(&control_addr(dir)?)?;
    stream.set_read_timeout(Some(ASK_TIMEOUT + Duration::from_secs(5)))?;
    stream.write_all(format!("{req}\n").as_bytes())?;
    stream.flush()?;
    let mut reply = String::new();
    stream.read_to_string(&mut reply)?;
    serde_json::from_str(reply.trim()).map_err(std::io::Error::other)
}

fn offline_status(dir: &Path) -> Value {
    let phone_id = std::fs::read_to_string(dir.join("device.json"))
        .ok()
        .and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .map(|v| v["phone_id"].clone())
        .unwrap_or(Value::Null);
    let url = std::env::var(URL_ENV)
        .ok()
        .and_then(|u| Endpoint::parse(&u).ok())
        .map_or_else(|| DEFAULT_URL.to_string(), |e| e.web);
    json!({ "online": false, "phone_id": phone_id, "url": url, "entitled": false })
}

#[cfg(test)]
#[path = "relay_tests.rs"]
mod tests;
