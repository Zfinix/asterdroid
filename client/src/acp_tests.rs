use super::*;
use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message as Client;

/// The driver lease is process-wide, so tests that open agent sessions take turns.
pub static SERIAL: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

pub const TOKEN: &str = "test-token-0123456789-0123456789-abcdef";

/// A stand-in for `aster acp` that echoes every line, so a test sees exactly
/// what the bridge wrote to the agent.
pub fn generate_test_agent() -> Agent {
    let dir = std::env::temp_dir().join(format!(
        "asterctl-acp-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let bin = dir.join("fake-aster");
    std::fs::write(&bin, "#!/bin/sh\nexec cat\n").unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
    Agent {
        bin: bin.to_string_lossy().into_owned(),
        home: dir,
    }
}

fn rand_suffix() -> u64 {
    let mut b = [0u8; 8];
    getrandom::getrandom(&mut b).unwrap();
    u64::from_le_bytes(b)
}

async fn start(agent: Agent) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(serve(listener, TOKEN.to_string(), agent));
    format!("ws://{addr}/acp")
}

fn with_bearer(url: &str, token: &str) -> axum::http::Request<()> {
    let mut req = url.into_client_request().unwrap();
    req.headers_mut()
        .insert("authorization", format!("Bearer {token}").parse().unwrap());
    req
}

async fn next_text<S>(ws: &mut S) -> String
where
    S: StreamExt<Item = Result<Client, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        match tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
            .await
            .unwrap()
        {
            Some(Ok(Client::Text(t))) => return t.to_string(),
            Some(Ok(Client::Close(c))) => panic!("closed: {c:?}"),
            Some(Ok(_)) => continue,
            other => panic!("socket ended: {other:?}"),
        }
    }
}

#[test]
fn rewrite_moves_new_sessions_home_and_drops_stdio_servers() {
    let line = json!({
        "jsonrpc": "2.0", "id": 1, "method": "session/new",
        "params": {
            "cwd": "/Users/someone/project",
            "mcpServers": [
                { "name": "fs", "command": "/usr/local/bin/fs-mcp", "args": [], "env": [] },
                { "type": "stdio", "name": "typed", "command": "x", "args": [], "env": [] },
                { "type": "http", "name": "web", "url": "https://mcp.example/mcp", "headers": [] },
                { "type": "sse", "name": "old", "url": "https://mcp.example/sse", "headers": [] },
            ],
        },
    })
    .to_string();
    let out: Value =
        serde_json::from_str(&rewrite(&line, "/data/data/dev.aster.probe/files")).unwrap();
    assert_eq!(out["params"]["cwd"], "/data/data/dev.aster.probe/files");
    let names: Vec<&str> = out["params"]["mcpServers"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["web", "old"]);
}

#[test]
fn rewrite_also_covers_session_load() {
    let line = r#"{"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"s1","cwd":"C:\\work","mcpServers":[]}}"#;
    let out: Value = serde_json::from_str(&rewrite(line, "/home/phone")).unwrap();
    assert_eq!(out["params"]["cwd"], "/home/phone");
    assert_eq!(out["params"]["sessionId"], "s1");
}

#[test]
fn rewrite_leaves_other_messages_alone() {
    let prompt = r#"{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"cwd":"/keep"}}"#;
    assert_eq!(rewrite(prompt, "/home/phone"), prompt);
    assert_eq!(
        rewrite("not json\nat all", "/home/phone"),
        "not json at all"
    );
    let split = "{\"jsonrpc\":\"2.0\",\n\"id\":4}";
    assert!(!rewrite(split, "/home/phone").contains('\n'));
}

#[tokio::test]
async fn ws_rejects_a_missing_or_wrong_token() {
    let url = start(generate_test_agent()).await;
    let missing = tokio_tungstenite::connect_async(url.as_str())
        .await
        .unwrap_err();
    assert!(missing.to_string().contains("401"), "{missing}");
    let wrong = tokio_tungstenite::connect_async(with_bearer(
        &url,
        "nope-nope-nope-nope-nope-nope-nope-nope",
    ))
    .await
    .unwrap_err();
    assert!(wrong.to_string().contains("401"), "{wrong}");
    let mut foreign = with_bearer(&url, TOKEN);
    foreign
        .headers_mut()
        .insert("origin", "https://evil.example".parse().unwrap());
    let foreign = tokio_tungstenite::connect_async(foreign).await.unwrap_err();
    assert!(foreign.to_string().contains("403"), "{foreign}");
}

#[tokio::test]
async fn ws_pumps_lines_and_rewrites_the_session() {
    let _serial = SERIAL.lock().await;
    let agent = generate_test_agent();
    let home = agent.home.to_string_lossy().into_owned();
    let url = start(agent).await;
    let (mut ws, _) = tokio_tungstenite::connect_async(with_bearer(&url, TOKEN))
        .await
        .unwrap();
    let new = json!({
        "jsonrpc": "2.0", "id": 1, "method": "session/new",
        "params": { "cwd": "/Users/x/proj", "mcpServers": [{ "name": "fs", "command": "fs", "args": [], "env": [] }] },
    });
    ws.send(Client::Text(new.to_string().into())).await.unwrap();
    let echoed: Value = serde_json::from_str(&next_text(&mut ws).await).unwrap();
    assert_eq!(echoed["params"]["cwd"], home);
    assert_eq!(echoed["params"]["mcpServers"], json!([]));
    ws.send(Client::Text(
        r#"{"jsonrpc":"2.0","id":2,"method":"initialize"}"#.into(),
    ))
    .await
    .unwrap();
    assert_eq!(
        next_text(&mut ws).await,
        r#"{"jsonrpc":"2.0","id":2,"method":"initialize"}"#
    );
    ws.close(None).await.unwrap();
}

#[tokio::test]
async fn ws_takes_the_token_as_a_subprotocol() {
    let _serial = SERIAL.lock().await;
    let url = start(generate_test_agent()).await;
    let mut req = url.as_str().into_client_request().unwrap();
    let protocol = format!("acp, bearer.{TOKEN}");
    req.headers_mut()
        .insert("sec-websocket-protocol", protocol.parse().unwrap());
    let (mut ws, response) = tokio_tungstenite::connect_async(req).await.unwrap();
    assert_eq!(
        response.headers().get("sec-websocket-protocol").unwrap(),
        format!("bearer.{TOKEN}").as_str()
    );
    ws.send(Client::Text("{}".into())).await.unwrap();
    assert_eq!(next_text(&mut ws).await, "{}");
    ws.close(None).await.unwrap();
}

#[tokio::test]
async fn ws_closes_a_second_driver_as_busy() {
    let _serial = SERIAL.lock().await;
    let url = start(generate_test_agent()).await;
    let (mut first, _) = tokio_tungstenite::connect_async(with_bearer(&url, TOKEN))
        .await
        .unwrap();
    first.send(Client::Text("{}".into())).await.unwrap();
    assert_eq!(next_text(&mut first).await, "{}");
    let (mut second, _) = tokio_tungstenite::connect_async(with_bearer(&url, TOKEN))
        .await
        .unwrap();
    let close = loop {
        match tokio::time::timeout(std::time::Duration::from_secs(5), second.next())
            .await
            .unwrap()
        {
            Some(Ok(Client::Close(frame))) => break frame.unwrap(),
            Some(Ok(_)) => continue,
            other => panic!("no close frame: {other:?}"),
        }
    };
    assert_eq!(u16::from(close.code), BUSY);
    assert_eq!(close.reason.as_str(), "busy");
    first.close(None).await.unwrap();
    drop(first);
    let mut again = None;
    for _ in 0..50 {
        if let Some(lease) = Lease::take() {
            again = Some(lease);
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert!(
        again.is_some(),
        "the lease was not released after the first editor left"
    );
}

#[tokio::test]
async fn ws_closes_with_an_error_when_the_agent_cannot_start() {
    let _serial = SERIAL.lock().await;
    let mut agent = generate_test_agent();
    agent.bin = "/nonexistent/aster".to_string();
    let url = start(agent).await;
    let (mut ws, _) = tokio_tungstenite::connect_async(with_bearer(&url, TOKEN))
        .await
        .unwrap();
    let close = loop {
        match tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
            .await
            .unwrap()
        {
            Some(Ok(Client::Close(frame))) => break frame.unwrap(),
            Some(Ok(_)) => continue,
            other => panic!("no close frame: {other:?}"),
        }
    };
    assert_eq!(u16::from(close.code), 1011);
    assert!(close
        .reason
        .as_str()
        .starts_with("the agent could not start"));
}

#[tokio::test]
async fn ws_lets_go_of_an_editor_that_went_silent() {
    let _serial = SERIAL.lock().await;
    let url = start(generate_test_agent()).await;
    let (mut ws, _) = tokio_tungstenite::connect_async(with_bearer(&url, TOKEN))
        .await
        .unwrap();
    ws.send(Client::Text("{}".into())).await.unwrap();
    assert_eq!(next_text(&mut ws).await, "{}");
    assert!(Lease::take().is_none());
    let mut again = None;
    for _ in 0..200 {
        if let Some(lease) = Lease::take() {
            again = Some(lease);
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert!(
        again.is_some(),
        "an editor that stopped answering pings kept the lease"
    );
    drop(ws);
}
