use super::*;
use crate::acp::tests::{generate_test_agent, SERIAL};
use axum::extract::ws::{Message as Ws, WebSocket, WebSocketUpgrade};
use axum::routing::get;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};

fn generate_test_identity() -> (Identity, Value) {
    let vectors = crate::frame::tests::generate_test_vectors();
    let identity = &vectors["identity"];
    let hex = identity["seed_hex"].as_str().unwrap();
    let seed: Vec<u8> = (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
        .collect();
    (
        Identity::from_seed(&seed.try_into().unwrap()),
        identity.clone(),
    )
}

fn generate_test_shared(endpoint: Endpoint, clients: Vec<Client>) -> Arc<Shared> {
    let (identity, _) = generate_test_identity();
    let agent = generate_test_agent();
    Arc::new(Shared {
        phone_id: identity.phone_id(),
        identity,
        endpoint,
        dir: agent.home.join(".aster").join("relay"),
        agent,
        clients: Mutex::new(clients),
        status: Mutex::default(),
        link: Mutex::default(),
        asks: Mutex::default(),
        pending: Mutex::default(),
        pairings: Mutex::default(),
        next: AtomicU64::new(1),
    })
}

fn client(id: &str, scopes: &[&str]) -> Client {
    Client {
        id: id.to_string(),
        name: "Test".to_string(),
        kind: "mcp-http".to_string(),
        scopes: scopes.iter().map(|s| s.to_string()).collect(),
    }
}

#[test]
fn identity_matches_the_golden_vectors() {
    let (identity, expected) = generate_test_identity();
    assert_eq!(
        B64.encode(identity.public()),
        expected["pub_b64url"].as_str().unwrap()
    );
    assert_eq!(identity.phone_id(), expected["phone_id"].as_str().unwrap());
    assert_eq!(identity.fingerprint(), expected["fp"].as_str().unwrap());
    let phone = expected["phone_id"].as_str().unwrap();
    let (nonce, host) = (
        expected["nonce"].as_str().unwrap(),
        expected["host"].as_str().unwrap(),
    );
    assert_eq!(
        link_message(phone, nonce, host),
        expected["message"].as_str().unwrap()
    );
    assert_eq!(
        identity.sign_link(phone, nonce, host),
        expected["sig_b64url"].as_str().unwrap()
    );
}

#[test]
fn identity_is_created_once_and_kept_private() {
    let dir = generate_test_agent().home.join("relay");
    let first = Identity::load_or_create(&dir).unwrap();
    let again = Identity::load_or_create(&dir).unwrap();
    assert_eq!(first.phone_id(), again.phone_id());
    assert!(first.phone_id().starts_with("ph_") && first.phone_id().len() == 29);
    use std::os::unix::fs::PermissionsExt;
    let mode = std::fs::metadata(dir.join("device.json"))
        .unwrap()
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);
    let saved: Value =
        serde_json::from_str(&std::fs::read_to_string(dir.join("device.json")).unwrap()).unwrap();
    assert_eq!(saved["phone_id"], first.phone_id());
}

#[test]
fn identity_is_never_replaced_when_device_json_cannot_be_read() {
    let dir = generate_test_agent().home.join("relay");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("device.json");
    std::fs::write(&path, b"{\"ed25519_sk\":\"\xff\"}").unwrap();
    assert!(Identity::load_or_create(&dir).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), b"{\"ed25519_sk\":\"\xff\"}");
}

#[test]
fn endpoint_parse_keeps_https_and_local_http_only() {
    assert_eq!(
        Endpoint::parse("https://relay.withaster.dev/").unwrap(),
        Endpoint {
            web: "https://relay.withaster.dev".into(),
            host: "relay.withaster.dev".into(),
            ws: "wss://relay.withaster.dev".into(),
        }
    );
    assert_eq!(
        Endpoint::parse("https://r.example:443").unwrap().host,
        "r.example"
    );
    assert_eq!(
        Endpoint::parse("http://127.0.0.1:8787").unwrap().ws,
        "ws://127.0.0.1:8787"
    );
    assert!(Endpoint::parse("http://relay.example").is_err());
    assert!(Endpoint::parse("ftp://relay.example").is_err());
}

#[test]
fn claim_link_parse_reads_the_client_qr() {
    let name = B64.encode("Claude Code on mbp");
    let link = format!("https://relay.withaster.dev/claim#1.cHVi.0123456789abcdef0123.{name}");
    assert_eq!(
        ClaimLink::parse(&link).unwrap(),
        ClaimLink {
            client_pub: "cHVi".into(),
            nonce: "0123456789abcdef0123".into(),
            name: "Claude Code on mbp".into()
        }
    );
    assert!(ClaimLink::parse("https://relay.withaster.dev/claim#2.a.b.c").is_err());
    assert!(ClaimLink::parse("https://relay.withaster.dev/pair#1.a.0123456789abcdef.c").is_err());
}

#[test]
fn granted_cuts_scopes_down_to_what_the_phone_approved() {
    let clients = vec![client("c_aaaaaaaaaa", &["phone:read", "agent:chat"])];
    let offered = vec!["phone:act".to_string(), "phone:read".to_string()];
    assert_eq!(
        granted(&clients, "c_aaaaaaaaaa", &offered),
        Some(vec!["phone:read".to_string()])
    );
    assert_eq!(granted(&clients, "c_bbbbbbbbbb", &offered), None);
}

#[test]
fn request_parses_the_control_verbs() {
    let args = |s: &str| s.split_whitespace().map(str::to_string).collect::<Vec<_>>();
    assert_eq!(request(&args("status")).unwrap(), json!({ "op": "status" }));
    assert_eq!(
        request(&args("revoke all")).unwrap(),
        json!({ "op": "revoke", "id": "all" })
    );
    assert_eq!(
        request(&args("approve c_1 yes")).unwrap(),
        json!({ "op": "approve", "pending": "c_1", "ok": true })
    );
    assert_eq!(
        request(&args("pair --scopes phone:read,agent:chat --ttl 60")).unwrap(),
        json!({ "op": "pair", "scopes": ["phone:read", "agent:chat"], "ttl": 60 })
    );
    assert!(request(&args("approve c_1 maybe")).is_err());
    assert!(request(&args("revoke")).is_err());
}

/// A relay that hands each phone link to the test, which then plays the
/// Durable Object's side frame by frame.
async fn generate_test_relay() -> (Endpoint, mpsc::Receiver<WebSocket>) {
    let (tx, rx) = mpsc::channel(4);
    let app = axum::Router::new().route(
        "/p/{id}/link",
        get(
            move |upgrade: WebSocketUpgrade, headers: axum::http::HeaderMap| {
                let tx = tx.clone();
                async move {
                    assert!(headers.get("x-aster-pub").is_some());
                    upgrade.on_upgrade(move |ws| async move {
                        let _ = tx.send(ws).await;
                    })
                }
            },
        ),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (
        Endpoint::parse(&format!("http://127.0.0.1:{}", addr.port())).unwrap(),
        rx,
    )
}

async fn send(ws: &mut WebSocket, frame: Frame) {
    ws.send(Ws::Binary(frame.encode().into())).await.unwrap();
}

async fn recv(ws: &mut WebSocket) -> Frame {
    loop {
        match tokio::time::timeout(Duration::from_secs(5), ws.recv())
            .await
            .unwrap()
        {
            Some(Ok(Ws::Binary(bytes))) => return Frame::decode(&bytes).unwrap(),
            Some(Ok(_)) => continue,
            other => panic!("link ended: {other:?}"),
        }
    }
}

/// Runs the challenge against the phone and checks its signature the way the
/// Worker does, then says ready.
async fn generate_test_link(
    shared: &Arc<Shared>,
    links: &mut mpsc::Receiver<WebSocket>,
) -> WebSocket {
    let runner = shared.clone();
    tokio::spawn(async move { link(&runner).await });
    let mut ws = tokio::time::timeout(Duration::from_secs(5), links.recv())
        .await
        .unwrap()
        .unwrap();
    let host = shared.endpoint.host.clone();
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "challenge", "nonce": "bm9uY2UtMQ", "host": host })),
    )
    .await;
    let auth = recv(&mut ws).await.value().unwrap();
    assert_eq!(auth["t"], "auth");
    let sig = Signature::from_slice(&B64.decode(auth["sig"].as_str().unwrap()).unwrap()).unwrap();
    let key = VerifyingKey::from_bytes(&shared.identity.public()).unwrap();
    key.verify(
        link_message(&shared.phone_id, "bm9uY2UtMQ", &host).as_bytes(),
        &sig,
    )
    .unwrap();
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "ready", "entitled": true, "clients": [] })),
    )
    .await;
    for _ in 0..100 {
        if lock(&shared.status).online {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    ws
}

#[tokio::test]
async fn link_refuses_a_challenge_for_another_host() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![]);
    let runner = shared.clone();
    let ended = tokio::spawn(async move { link(&runner).await });
    let mut ws = links.recv().await.unwrap();
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "challenge", "nonce": "n", "host": "evil.example" })),
    )
    .await;
    let reason = tokio::time::timeout(Duration::from_secs(5), ended)
        .await
        .unwrap()
        .unwrap();
    assert!(reason.contains("evil.example"), "{reason}");
}

#[tokio::test]
async fn link_serves_mcp_for_an_approved_client() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![client("c_aaaaaaaaaa", &["phone:act"])]);
    let mut ws = generate_test_link(&shared, &mut links).await;
    assert_eq!(shared.status()["online"], true);
    assert_eq!(shared.status()["entitled"], true);

    let open = json!({ "svc": "mcp", "client": "c_aaaaaaaaaa", "scopes": ["phone:act"], "method": "POST", "path": "/mcp" });
    send(&mut ws, Frame::json(frame::OPEN, 1, &open)).await;
    for chunk in frame::data(1, br#"{"jsonrpc":"2.0","id":7,"method":"ping"}"#) {
        send(&mut ws, chunk).await;
    }
    send(&mut ws, Frame::new(frame::END, 1, vec![])).await;
    let head = recv(&mut ws).await;
    assert_eq!((head.kind, head.stream), (frame::HEAD, 1));
    assert_eq!(head.value().unwrap()["status"], 200);
    let body = recv(&mut ws).await;
    assert_eq!(body.kind, frame::DATA);
    assert_eq!(
        body.value().unwrap(),
        json!({ "jsonrpc": "2.0", "id": 7, "result": {} })
    );
    assert_eq!(recv(&mut ws).await, Frame::new(frame::END, 1, vec![]));

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            3,
            &json!({ "svc": "mcp", "client": "c_aaaaaaaaaa", "scopes": ["phone:act"] }),
        ),
    )
    .await;
    send(
        &mut ws,
        Frame::new(
            frame::DATA,
            3,
            br#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#.to_vec(),
        ),
    )
    .await;
    send(&mut ws, Frame::new(frame::END, 3, vec![])).await;
    assert_eq!(recv(&mut ws).await.value().unwrap()["status"], 202);
    assert_eq!(recv(&mut ws).await.kind, frame::END);
}

#[tokio::test]
async fn link_resets_streams_the_phone_did_not_approve() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![client("c_aaaaaaaaaa", &["phone:read"])]);
    let mut ws = generate_test_link(&shared, &mut links).await;

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            5,
            &json!({ "svc": "mcp", "client": "c_zzzzzzzzzz", "scopes": ["phone:act"] }),
        ),
    )
    .await;
    let reset = recv(&mut ws).await;
    assert_eq!((reset.kind, reset.stream), (frame::RESET, 5));
    assert_eq!(reset.value().unwrap()["code"], "forbidden");

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            7,
            &json!({ "svc": "acp", "client": "c_aaaaaaaaaa", "scopes": ["agent:chat"] }),
        ),
    )
    .await;
    let reset = recv(&mut ws).await;
    assert_eq!((reset.kind, reset.stream), (frame::RESET, 7));
    assert_eq!(reset.value().unwrap()["code"], "forbidden");

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            9,
            &json!({ "svc": "mirror", "client": "c_aaaaaaaaaa", "scopes": ["phone:read"] }),
        ),
    )
    .await;
    assert_eq!(recv(&mut ws).await.value().unwrap()["code"], "unsupported");
}

#[tokio::test]
async fn link_bridges_acp_and_rewrites_the_session() {
    let _serial = SERIAL.lock().await;
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![client("c_aaaaaaaaaa", &["agent:chat"])]);
    let home = shared.agent.home.to_string_lossy().into_owned();
    let mut ws = generate_test_link(&shared, &mut links).await;

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            11,
            &json!({ "svc": "acp", "client": "c_aaaaaaaaaa", "scopes": ["agent:chat"] }),
        ),
    )
    .await;
    let new = json!({ "jsonrpc": "2.0", "id": 1, "method": "session/new", "params": { "cwd": "/Users/x", "mcpServers": [] } });
    send(&mut ws, Frame::text(11, &new.to_string())).await;
    let echoed = recv(&mut ws).await;
    assert_eq!((echoed.kind, echoed.stream), (frame::WSMSG, 11));
    let echoed: Value = serde_json::from_str(echoed.ws_text().unwrap()).unwrap();
    assert_eq!(echoed["params"]["cwd"], home);

    send(
        &mut ws,
        Frame::json(
            frame::OPEN,
            13,
            &json!({ "svc": "acp", "client": "c_aaaaaaaaaa", "scopes": ["agent:chat"] }),
        ),
    )
    .await;
    let busy = recv(&mut ws).await;
    assert_eq!((busy.kind, busy.stream), (frame::RESET, 13));
    assert_eq!(busy.value().unwrap()["code"], "busy");

    send(&mut ws, Frame::new(frame::END, 11, vec![])).await;
    assert_eq!(recv(&mut ws).await, Frame::new(frame::END, 11, vec![]));
}

#[tokio::test]
async fn control_pairs_and_approves_through_the_relay() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![]);
    let mut ws = generate_test_link(&shared, &mut links).await;

    let asker = shared.clone();
    let paired = tokio::spawn(async move { asker.command(&json!({ "op": "pair" })).await });
    let open = recv(&mut ws).await.value().unwrap();
    assert_eq!(open["t"], "pair.open");
    assert_eq!(open["scopes"], json!(["phone:act", "agent:chat"]));
    let code =
        json!({ "t": "pair.code", "rid": open["rid"], "code": "7K2Q9MXD", "exp": 1_761_200_300 });
    send(&mut ws, Frame::ctrl(&code)).await;
    let paired = paired.await.unwrap();
    assert_eq!(paired["code"], "7K2Q9MXD");
    let url = format!(
        "{}/pair#1.{}.7K2Q9MXD.{}",
        shared.endpoint.web,
        shared.phone_id,
        shared.identity.fingerprint()
    );
    assert_eq!(paired["url"], url);

    let claimed = json!({ "t": "pair.claimed", "pending": "c_pending000", "name": "Claude Code", "kind": "mcp-http", "scopes": ["phone:act"] });
    send(&mut ws, Frame::ctrl(&claimed)).await;
    for _ in 0..100 {
        if lock(&shared.pending).contains_key("c_pending000") {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let asker = shared.clone();
    let approved = tokio::spawn(async move {
        asker
            .command(&json!({ "op": "approve", "pending": "c_pending000", "ok": true }))
            .await
    });
    let approve = recv(&mut ws).await.value().unwrap();
    assert_eq!(
        (approve["t"].as_str(), approve["ok"].as_bool()),
        (Some("pair.approve"), Some(true))
    );
    assert_eq!(
        granted(
            &lock(&shared.clients),
            "c_pending000",
            &["phone:act".to_string()]
        ),
        Some(vec!["phone:act".to_string()])
    );
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "ok", "rid": approve["rid"] })),
    )
    .await;
    assert_eq!(approved.await.unwrap()["ok"], true);
    assert_eq!(
        load_clients(&shared.dir),
        vec![client_named("c_pending000", "Claude Code", &["phone:act"])]
    );

    let asker = shared.clone();
    let revoked = tokio::spawn(async move {
        asker
            .command(&json!({ "op": "revoke", "id": "c_pending000" }))
            .await
    });
    let revoke = recv(&mut ws).await.value().unwrap();
    assert_eq!(
        (revoke["t"].as_str(), revoke["id"].as_str()),
        (Some("client.revoke"), Some("c_pending000"))
    );
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "revoked", "rid": revoke["rid"], "ids": ["c_pending000"] })),
    )
    .await;
    assert_eq!(
        revoked.await.unwrap(),
        json!({ "revoked": ["c_pending000"] })
    );
    assert!(load_clients(&shared.dir).is_empty());

    let refused = shared
        .command(&json!({ "op": "approve", "pending": "c_nobody0000", "ok": true }))
        .await;
    assert!(refused["error"].as_str().unwrap().contains("c_nobody0000"));
}

fn client_named(id: &str, name: &str, scopes: &[&str]) -> Client {
    Client {
        name: name.to_string(),
        kind: "mcp-http".to_string(),
        ..client(id, scopes)
    }
}

#[tokio::test]
async fn control_reports_offline_without_a_link() {
    let shared = generate_test_shared(Endpoint::parse(DEFAULT_URL).unwrap(), vec![]);
    let status = shared.command(&json!({ "op": "status" })).await;
    assert_eq!(status["online"], false);
    assert_eq!(status["url"], DEFAULT_URL);
    let pair = shared.command(&json!({ "op": "pair" })).await;
    assert_eq!(pair["error"], "the relay is offline");
}

#[tokio::test]
async fn link_ends_when_the_relay_goes_silent() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![]);
    let runner = shared.clone();
    let ended = tokio::spawn(async move { link(&runner).await });
    let mut ws = links.recv().await.unwrap();
    let host = shared.endpoint.host.clone();
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "challenge", "nonce": "bm9uY2UtMQ", "host": host })),
    )
    .await;
    assert_eq!(recv(&mut ws).await.value().unwrap()["t"], "auth");
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "ready", "entitled": true, "clients": [] })),
    )
    .await;
    let reason = tokio::time::timeout(SILENCE * 3, ended)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(reason, "the relay stopped answering");
    assert!(lock(&shared.link).is_none());
    drop(ws);
}

#[tokio::test]
async fn link_gives_up_on_a_relay_that_never_answers_the_dial() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let held = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        tokio::time::sleep(DIAL_TIMEOUT * 4).await;
        drop(stream);
    });
    let endpoint = Endpoint::parse(&format!("http://127.0.0.1:{}", addr.port())).unwrap();
    let shared = generate_test_shared(endpoint, vec![]);
    let reason = tokio::time::timeout(DIAL_TIMEOUT * 3, link(&shared))
        .await
        .unwrap();
    assert!(reason.starts_with("cannot reach"), "{reason}");
    held.abort();
}

/// Sends a claim and gives the phone time to take or drop it.
async fn claim(ws: &mut WebSocket, pending: &str, scopes: Value) {
    let claimed = json!({ "t": "pair.claimed", "pending": pending, "name": "Claude Code", "kind": "mcp-http", "scopes": scopes });
    send(ws, Frame::ctrl(&claimed)).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
}

#[tokio::test]
async fn control_keeps_a_claim_to_the_scopes_the_phone_paired_with() {
    let (endpoint, mut links) = generate_test_relay().await;
    let shared = generate_test_shared(endpoint, vec![]);
    let mut ws = generate_test_link(&shared, &mut links).await;

    claim(&mut ws, "c_uninvited0", json!(["phone:act"])).await;
    assert!(lock(&shared.pending).is_empty());

    let asker = shared.clone();
    let paired = tokio::spawn(async move {
        asker
            .command(&json!({ "op": "pair", "scopes": ["phone:read"] }))
            .await
    });
    let open = recv(&mut ws).await.value().unwrap();
    assert_eq!(open["scopes"], json!(["phone:read"]));
    let code =
        json!({ "t": "pair.code", "rid": open["rid"], "code": "7K2Q9MXD", "exp": 1_761_200_300 });
    send(&mut ws, Frame::ctrl(&code)).await;
    assert_eq!(paired.await.unwrap()["code"], "7K2Q9MXD");

    claim(
        &mut ws,
        "c_pending000",
        json!(["phone:act", "agent:chat", "phone:read"]),
    )
    .await;
    assert_eq!(
        lock(&shared.pending)["c_pending000"]["scopes"],
        json!(["phone:read"])
    );
    claim(&mut ws, "c_pending001", json!(["phone:read"])).await;
    assert!(!lock(&shared.pending).contains_key("c_pending001"));

    let asker = shared.clone();
    let approved = tokio::spawn(async move {
        asker
            .command(&json!({ "op": "approve", "pending": "c_pending000", "ok": true }))
            .await
    });
    let approve = recv(&mut ws).await.value().unwrap();
    send(
        &mut ws,
        Frame::ctrl(&json!({ "t": "ok", "rid": approve["rid"] })),
    )
    .await;
    assert_eq!(approved.await.unwrap()["ok"], true);
    assert_eq!(
        load_clients(&shared.dir),
        vec![client_named("c_pending000", "Claude Code", &["phone:read"])]
    );
}

#[tokio::test]
async fn control_refuses_a_claim_for_a_client_already_approved() {
    let (endpoint, mut links) = generate_test_relay().await;
    let existing = client("c_aaaaaaaaaa", &["phone:read"]);
    let shared = generate_test_shared(endpoint, vec![existing.clone()]);
    let mut ws = generate_test_link(&shared, &mut links).await;
    lock(&shared.pairings).insert(
        "7K2Q9MXD".to_string(),
        Pairing {
            scopes: vec!["phone:act".to_string()],
            exp: now() + 60,
        },
    );
    claim(&mut ws, "c_aaaaaaaaaa", json!(["phone:act"])).await;
    assert!(lock(&shared.pending).is_empty());

    lock(&shared.pending).insert(
        "c_aaaaaaaaaa".to_string(),
        json!({ "name": "Evil", "kind": "mcp-http", "scopes": ["phone:act"], "exp": now() + 60 }),
    );
    let refused = shared
        .command(&json!({ "op": "approve", "pending": "c_aaaaaaaaaa", "ok": false }))
        .await;
    assert!(refused["error"].is_string());
    assert_eq!(*lock(&shared.clients), vec![existing]);

    lock(&shared.pending).insert(
        "c_stale00000".to_string(),
        json!({ "name": "Late", "kind": "mcp-http", "scopes": ["phone:act"], "exp": now() - 1 }),
    );
    let stale = shared
        .command(&json!({ "op": "approve", "pending": "c_stale00000", "ok": true }))
        .await;
    assert!(stale["error"].as_str().unwrap().contains("stopped waiting"));
    assert_eq!(lock(&shared.clients).len(), 1);
}
