---
name: mirror
description: Run and expose the asterdroid phone mirror: asterctl serve, the viewer UI, quality settings, and Tailscale or cloudflared exposure. Use when starting, testing, or sharing the mirror.
---

# Asterdroid mirror skill

The mirror streams the phone's screen to a browser page and sends taps back. It lives in the asterdroid repo, package `dev.aster.probe`. This skill covers how it works, how to start it, and how to expose it beyond the phone.

## How it works

- `asterctl serve [port]` on the phone starts an axum server on `127.0.0.1:7070`
  (default). It never exits; run it detached. `/mirror` in Telegram is the
  usual way to start it.
- `GET /` serves `viewer.html`: a canvas viewer with a quality picker, an fps readout, and a control bar (back, home, recents, vol-, vol+, notifications, text box). It opens on live video.
- There are two sources behind that picker, and they are not the same pipeline:
  - **Video** (`video` 720p30, `smooth` 1080p60) is H.264 off the phone's hardware encoder, decoded in the browser with WebCodecs. This is the default and the fast one.
  - **Stills** (`full`, `high`, `balanced`, `fast`) is the old JPEG-per-screenshot path, kept as the fallback for a browser with no `VideoDecoder`.
- `GET /ws` is the WebSocket. Inbound: `cfg` (`kbps`) picked up live, the touch messages `down`, `move` (with the `ms` the move really took), `up` and `tap`, plus `key`, `text`, `volume up|down`, `notifications`. Outbound: `hello` (screen dims, `video: true` on the video path), then either JPEG binaries (stills) or, on video, a `config` message naming the codec followed by binary packets whose first byte is the kind: `0` the avcC description, `1` a keyframe, `2` a delta. Key and delta frames carry an 8-byte big-endian timestamp before the AVCC payload.
- The encoder runs at realtime priority with `KEY_LATENCY` at 1, so it emits a frame as soon as one is queued rather than filling a pipeline first, and keyframes are ten seconds apart because a joining viewer asks for its own.
- Video frames come from MediaProjection: a VirtualDisplay renders straight into a MediaCodec input Surface, so the pixels never touch the heap. The phone writes scrcpy's 12-byte header (pts with the top two bits as config/key flags, then length) over the `stream h264 <width> <fps> <kbps>` socket verb, and the Rust side converts Annex-B to AVCC for WebCodecs.
- Still frames come from the accessibility service's `takeScreenshot`, which the system rate-limits to one per 333ms. That is why stills cap at ~2-3 fps and no amount of quality or width tuning moves it. Video has no such ceiling.
- A locked phone is where the mirror used to die: Android stops a projection
  the instant the device locks, so a stream asked for at a locked phone was
  granted and then killed before its first frame, leaving the page on "waiting
  for the first frame" with nothing to wait for. Joining wakes the screen and
  dismisses the keyguard (`MirrorConsentActivity`), and a screen-bright wake
  lock holds it awake until the last viewer leaves. A PIN or pattern is the one
  case a person still has to answer.
- MediaProjection needs consent: the first stream raises the system capture
  dialog (`MirrorConsentActivity`) and a `mediaProjection` foreground service
  (`MirrorService`) must be up first, which is what the ongoing "Aster mirror"
  notification is. The consent token is held for the life of the process, so
  reconnecting a viewer does not re-prompt. `MirrorAutoAccept` answers the
  dialog itself: the `stream` verb arms a watcher that presses the dialog's
  Start button through the accessibility service, for as long as
  `Mirror.consentPending` is true and never past the 30s consent window.
- Touches take the service's `live` verbs (`live tap|down|move|up|key`), which dispatch the gesture and return. They skip the screenshot the agent's verbs take first and the settle they wait out after, and they run off the socket's accept loop on their own thread, so a finger is never queued behind an agent verb. A tap lands in about 15ms where `tap` took one to two seconds.
- A drag is a live stroke, not a finished shape: the viewer sends one `down`, a `move` per animation frame while the finger travels, then `up`, each continuing the last through `continueStroke`. Scrolls fling the way a real one does.
- A viewer that closes mid-drag leaves a finger on the glass, and every gesture after it is cancelled by the one still down, so the next `down` lifts whatever is held first.
- The Telegram bridge has `/mirror` (Android builds only): starts the server
  detached, waits for it to answer, and replies with a tappable URL naming the
  phone's Tailscale or LAN address. `/mirror off` stops it. The port probe
  tells the mirror from any other listener, so a squatter on 7070 reports a
  conflict instead of a false "already up".

## Quick reference

```sh
# build and install (from the asterdroid repo)
./build-client.sh && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# start the mirror on the phone (detached, it blocks otherwise)
adb shell "asterctl serve >/dev/null 2>&1 &"

# test from the Mac
adb forward tcp:7071 tcp:7070   # then open http://localhost:7071

# verify on device
adb shell curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:7070/
```

## Procedure

1. **Start the mirror.** Send `/mirror` in Telegram. It probes the port (free,
   ours, or squatted), starts `asterctl serve` detached when free, waits for
   the HTTP probe to answer, and replies with a tappable URL. The URL is the
   phone's Tailscale address when the tailnet is up, its LAN address otherwise,
   never `127.0.0.1`. Check: the reply arrives within a few seconds and the
   button opens a live picture.
2. **Consent answers itself.** The first stream raises the system capture
   dialog; `MirrorAutoAccept` (armed by the `stream` verb) presses Start with
   the accessibility service's thumb within the 30s consent window. Nobody
   needs to be holding the phone. Check: the "Aster mirror" notification is up
   and the viewer shows video without a human tap.
3. **Test locally without Telegram.** On the phone run `asterctl serve`,
   detached. Check: `curl` on `127.0.0.1:7070/` returns `200`; open Chrome on
   the phone at that URL, or `adb forward tcp:7071 tcp:7070` and open
   `http://localhost:7071` on the Mac.
4. **Expose beyond the tailnet (rare).** Tailscale serve needs the CLI, which
   the Android app does not ship, so run the exposure from a machine that has
   it:
   ```sh
   adb forward tcp:7071 tcp:7070        # on the Mac, reach the phone
   tailscale serve 7071                 # https://<mac>.<tailnet>.ts.net
   ```
   Check: the ts.net URL loads the viewer from another tailnet device. Traffic
   stays inside the tailnet; nothing is public.
5. **Fallback: cloudflared.** When Tailscale is unavailable, expose the same
   adb-forwarded port publicly:
   ```sh
   cloudflared tunnel --url http://localhost:7071
   ```
   Check: the trycloudflare URL loads the viewer. This is public behind no auth
   beyond obscurity, so use it for a quick demo only and tear it down after.

## Pitfalls

- Port 7070 collides with the `another` MCP server on the Mac. Host-side testing needs `asterctl serve 7079`; on the phone 7070 is free.
- `/mirror` says the port is held by another app: something non-mirror is
  listening on 7070 on the phone. Find it and stop it; the mirror will not
  kill a process it does not own.
- `asterctl serve` blocks the calling shell. Detach it or the command times out; that timeout is not a failure.
- A stale installed binary is the usual cause of "serve is not a verb". Rebuild with `./build-client.sh`, reinstall, then launch the app once so `Install.kt` refreshes the bin symlinks.
- A secure keyguard (PIN, pattern, fingerprint) is the one lock the mirror cannot open itself: the viewer gets `screen capture was not allowed` until someone unlocks the phone.
- The viewer's touch mapping uses the dims from `hello`, so rotation is handled; do not cache dims across reconnects.

## Verification

- `curl` the viewer page: `200` with the viewer HTML.
- WebSocket probe, video: `hello` carries `video: true`, a `config` message names an `avc1.*` codec, then binary packets arrive tens per second with byte 0 in `0..2`.
- WebSocket probe, stills: `hello` reports scaled dims after a `cfg`, and frames arrive as JPEG (check the `ffd8` magic), a couple per second.
- If video stalls but stills work, look at the capture consent first: no "Aster mirror" notification means `MirrorService` never came up and the projection was refused.
- End-to-end: a tap in the browser produces the visible effect on the phone within one frame interval.