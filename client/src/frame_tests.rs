use super::*;

/// The vectors the Worker's tests also read, so the two codecs cannot drift.
pub const VECTORS: &str = include_str!("../../relay/test/frame-vectors.json");

pub fn generate_test_vectors() -> Value {
    serde_json::from_str(VECTORS).unwrap()
}

fn unhex(hex: &str) -> Vec<u8> {
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn frame_encode_matches_the_golden_vectors() {
    for case in generate_test_vectors()["frames"].as_array().unwrap() {
        let frame = Frame::new(
            case["kind"].as_u64().unwrap() as u8,
            case["stream"].as_u64().unwrap() as u32,
            unhex(case["payload_hex"].as_str().unwrap()),
        );
        assert_eq!(
            frame.encode(),
            unhex(case["frame_hex"].as_str().unwrap()),
            "{}",
            case["name"]
        );
    }
}

#[test]
fn frame_decode_matches_the_golden_vectors() {
    for case in generate_test_vectors()["frames"].as_array().unwrap() {
        let frame = Frame::decode(&unhex(case["frame_hex"].as_str().unwrap())).unwrap();
        assert_eq!(
            frame.kind as u64,
            case["kind"].as_u64().unwrap(),
            "{}",
            case["name"]
        );
        assert_eq!(
            frame.stream as u64,
            case["stream"].as_u64().unwrap(),
            "{}",
            case["name"]
        );
        assert_eq!(
            frame.payload,
            unhex(case["payload_hex"].as_str().unwrap()),
            "{}",
            case["name"]
        );
    }
}

#[test]
fn frame_decode_rejects_a_short_header() {
    assert_eq!(Frame::decode(&[0, 0, 0, 0]), None);
    assert_eq!(
        Frame::decode(&[4, 0, 0, 0, 1]),
        Some(Frame::new(END, 1, vec![]))
    );
}

#[test]
fn frame_data_chunks_like_the_vectors() {
    let chunk = &generate_test_vectors()["chunk"];
    let body = vec![7u8; chunk["body_len"].as_u64().unwrap() as usize];
    let lens: Vec<u64> = data(3, &body)
        .iter()
        .map(|f| f.payload.len() as u64)
        .collect();
    let expected: Vec<u64> = chunk["frame_lens"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_u64().unwrap())
        .collect();
    assert_eq!(lens, expected);
    assert!(data(3, &[]).is_empty());
}

#[test]
fn frame_ws_text_reads_only_text_messages() {
    assert_eq!(Frame::text(5, "hi").ws_text(), Some("hi"));
    assert_eq!(Frame::new(WSMSG, 5, vec![1, b'h']).ws_text(), None);
    assert_eq!(Frame::new(WSMSG, 5, vec![]).ws_text(), None);
}
