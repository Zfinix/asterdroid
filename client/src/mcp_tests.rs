use super::*;

fn request(id: i64, method: &str, params: Value) -> Value {
    json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params })
}

#[test]
fn initialize_echoes_a_known_version_and_falls_back_otherwise() {
    let known = handle(&request(1, "initialize", json!({ "protocolVersion": "2025-03-26" }))).unwrap();
    assert_eq!(known["result"]["protocolVersion"], "2025-03-26");
    let unknown = handle(&request(2, "initialize", json!({ "protocolVersion": "1999-01-01" }))).unwrap();
    assert_eq!(unknown["result"]["protocolVersion"], PROTOCOL_VERSIONS[0]);
}

#[test]
fn notifications_get_no_reply() {
    let note = json!({ "jsonrpc": "2.0", "method": "notifications/initialized" });
    assert_eq!(handle(&note), None);
}

#[test]
fn unknown_methods_and_tools_are_errors() {
    assert_eq!(
        handle(&request(3, "nope", Value::Null)),
        Some(json!({ "jsonrpc": "2.0", "id": 3, "error": { "code": -32601, "message": "method not found: nope" } })),
    );
    assert_eq!(
        handle(&request(4, "tools/call", json!({ "name": "phone", "arguments": { "command": "  " } }))),
        Some(json!({ "jsonrpc": "2.0", "id": 4, "error": { "code": -32602, "message": "phone needs a command" } })),
    );
}

#[test]
fn an_unreadable_resource_is_not_found() {
    let reply = handle(&request(5, "resources/read", json!({ "uri": "file:///etc/passwd" }))).unwrap();
    assert_eq!(reply["error"]["code"], -32002);
}

#[test]
fn a_reply_with_an_error_line_is_a_tool_error() {
    assert_eq!(
        text_result("note: locked\nerror: no element 9\n".to_string()),
        json!({ "content": [{ "type": "text", "text": "note: locked\nerror: no element 9\n" }], "isError": true }),
    );
}

#[test]
fn a_screenshot_becomes_an_image_and_its_file_is_removed() {
    let path = std::env::temp_dir().join(format!("asterctl-shot-{}.png", std::process::id()));
    std::fs::write(&path, [1u8, 2, 3]).unwrap();
    let reply = format!("note: locked\nshot {} (1x1, 3 bytes)\nA1 top left\n", path.display());
    assert_eq!(
        screenshot(&reply),
        json!({
            "content": [
                { "type": "image", "data": "AQID", "mimeType": "image/png" },
                { "type": "text", "text": "note: locked\nA1 top left" },
            ],
            "isError": false,
        }),
    );
    assert!(!path.exists());
}

#[test]
fn only_local_origins_pass() {
    assert!(local_origin("http://localhost:3000"));
    assert!(local_origin("http://127.0.0.1"));
    assert!(!local_origin("https://evil.example"));
    assert!(!local_origin("http://localhost.evil.example"));
}

#[test]
fn tokens_compare_whole() {
    assert!(same(b"abc", b"abc"));
    assert!(!same(b"abc", b"abd"));
    assert!(!same(b"abc", b"abcd"));
}

#[test]
fn read_access_refuses_verbs_that_act() {
    let tap = request(6, "tools/call", json!({ "name": "phone", "arguments": { "command": "tap 4" } }));
    let reply = handle_as(&tap, Access::Read).unwrap();
    assert_eq!(reply["result"]["isError"], true);
    assert!(reply["result"]["content"][0]["text"].as_str().unwrap().contains("phone:act"));
}

#[test]
fn read_access_refuses_alerts_that_change_settings() {
    for command in ["alerts add whatsapp", "alerts battery off", "alerts remove whatsapp"] {
        let call = request(7, "tools/call", json!({ "name": "phone", "arguments": { "command": command } }));
        let reply = handle_as(&call, Access::Read).unwrap();
        assert_eq!(reply["result"]["isError"], true, "{command}");
        assert!(reply["result"]["content"][0]["text"].as_str().unwrap().contains("phone:act"));
    }
}
