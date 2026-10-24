//! The relay link's framing, shared with `relay/src/frame.ts`: one binary
//! WebSocket message is `u8 kind | u32 BE stream | payload`. docs/RELAY.md is
//! the spec, and both sides test against `relay/test/frame-vectors.json`.

use serde_json::Value;

pub const CTRL: u8 = 0x00;
pub const OPEN: u8 = 0x01;
pub const DATA: u8 = 0x02;
pub const HEAD: u8 = 0x03;
pub const END: u8 = 0x04;
pub const RESET: u8 = 0x05;
pub const WSMSG: u8 = 0x06;
#[expect(
    dead_code,
    reason = "flow control for the mirror tunnel, which is not built yet"
)]
pub const CREDIT: u8 = 0x07;
pub const HEADER: usize = 5;
/// A screenshot is cut into frames this size, so it never holds up another
/// stream on the same link for longer than one frame.
pub const MAX_CHUNK: usize = 256 << 10;
const WS_TEXT: u8 = 0;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: u8,
    pub stream: u32,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(kind: u8, stream: u32, payload: impl Into<Vec<u8>>) -> Frame {
        Frame {
            kind,
            stream,
            payload: payload.into(),
        }
    }

    pub fn ctrl(message: &Value) -> Frame {
        Frame::new(CTRL, 0, message.to_string())
    }

    pub fn json(kind: u8, stream: u32, message: &Value) -> Frame {
        Frame::new(kind, stream, message.to_string())
    }

    pub fn text(stream: u32, text: &str) -> Frame {
        let mut payload = Vec::with_capacity(text.len() + 1);
        payload.push(WS_TEXT);
        payload.extend_from_slice(text.as_bytes());
        Frame::new(WSMSG, stream, payload)
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER + self.payload.len());
        out.push(self.kind);
        out.extend_from_slice(&self.stream.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    /// `None` for a message too short to hold a header. Unknown kinds decode,
    /// so the caller can skip them and a newer relay does not break the link.
    pub fn decode(bytes: &[u8]) -> Option<Frame> {
        let (head, payload) = bytes.split_at_checked(HEADER)?;
        let stream = u32::from_be_bytes([head[1], head[2], head[3], head[4]]);
        Some(Frame::new(head[0], stream, payload))
    }

    pub fn value(&self) -> Option<Value> {
        serde_json::from_slice(&self.payload).ok()
    }

    /// The text of a tunnelled WebSocket message; `None` for binary or junk.
    pub fn ws_text(&self) -> Option<&str> {
        match self.payload.split_first() {
            Some((&WS_TEXT, rest)) => std::str::from_utf8(rest).ok(),
            _ => None,
        }
    }
}

/// A body as DATA frames of at most [`MAX_CHUNK`] bytes; an empty body is no frames.
pub fn data(stream: u32, body: &[u8]) -> Vec<Frame> {
    body.chunks(MAX_CHUNK)
        .map(|chunk| Frame::new(DATA, stream, chunk))
        .collect()
}

#[cfg(test)]
#[path = "frame_tests.rs"]
pub(crate) mod tests;
