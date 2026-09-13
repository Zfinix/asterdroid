# asterdroid architecture

## What this is

asterdroid runs the real Aster agent on an Android phone. The app is `dev.aster.probe`. It reads the screen through an accessibility service, touches it the way a thumb does, and answers over Telegram. The person holding the conversation is usually nowhere near the phone.

It is not a port and not a subset. `build-agent.sh` cross-compiles `aster-cli` for `aarch64-linux-android` and ships it inside the APK as `libaster.so`. The tools, skills, prompts and review path are the code that runs in a terminal. It started as a measuring rig for the questions `docs/COMPUTER-USE.md` leaves open on Android, and it is now the daily-driver way to run Aster on a phone.

The hard part is not the model. A phone has no shell, so the screen is the only interface, and an agent that cannot prove an action landed is just guessing.

## Process model

One process tree on the phone:

```
Telegram  <->  aster remote telegram   (libaster.so, supervised by AsterAgentService)
                  | run_command
                  v
               asterctl (libclient.so) --abstract socket--> AsterA11yService
                  |                                           |  reads the tree,
                  v                                           |  dispatches touches,
               asterctl serve (the mirror)                    v  OCRs what it cannot see
               H.264 out, touches in                       the phone itself
```

- **Telegram** is the interface. The agent runs `remote telegram` as a child of `AsterAgentService`, in `yolo` mode, because there is no terminal on the phone to answer a permission prompt. Consent lives in the chat: before anything that leaves the device, the agent states the concrete effect in one line and then does it.
- **libaster.so** is the agent. It calls `asterctl` through `run_command`, the same way it calls anything else.
- **asterctl** is the agent's body, a small Rust client. It speaks to the accessibility service over an abstract unix socket named `@aster-eyes`. Abstract means there is no file on disk and no permissions to get wrong. Connecting costs about 150 microseconds, which is why paying it per verb is fine.
- **AsterA11yService** does the touching. It reads the pruned node tree, dispatches gestures, and falls back to OCR where the tree is blind.

The agent is exec'd from `nativeLibraryDir`, the one place Android will execute a file from. `Install.kt` symlinks `asterctl` and `aster` into the app's private `bin` directory on every service start, because the agent calls both by name. A symlink keeps the SELinux label of its target, which is why that works and a copy into the data directory would not be executable at all. Every install lands in a new `nativeLibraryDir`, so the links are deleted and remade each time rather than trusted.

## The cross-build

Rust's stock Android target keeps native thread-local storage off. That sounds harmless until you embed a Python interpreter, which Aster does for its `python` tool: every thread-local then costs one of bionic's 128 pthread keys, and the interpreter aborts with `out of TLS keys`.

The fix is a custom target spec, `target-spec/aarch64-linux-android.json`, whose whole reason for existing is two lines:

```json
"tls-model": "emulated",
"has-thread-local": true
```

With native TLS on, LLVM lowers thread-locals to emulated TLS on Android, which routes every one of them through a single key. That lowering calls `__emutls_get_address`, which lives in the NDK's compiler-rt archive, and rustc's default link drops it. So the build passes the archive explicitly:

```sh
RT="$(ls "$NDK"/toolchains/llvm/prebuilt/"$HOST"/lib/clang/*/lib/linux/libclang_rt.builtins-aarch64-android.a | head -1)"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=$RT -L $FFI_DIR/lib"
```

`libffi` is the other one. RustPython's `ctypes` wants a system `libffi`, which Android does not ship, so the script builds a static one once from source into `target/android-tls/libffi` and links against it.

Because `build-std` and JSON target specs are still nightly features, the build sets `RUSTC_BOOTSTRAP=1` and needs `rust-src` installed (`rustup component add rust-src`). The invocation:

```sh
cargo build --release -p aster-cli --bin aster \
  -Zbuild-std=std,panic_abort -Zjson-target-spec \
  --target target-spec/aarch64-linux-android.json \
  --target-dir ../../target/android-tls --manifest-path ../../Cargo.toml
```

The output is copied to `app/src/main/jniLibs/arm64-v8a/libaster.so`. It has to be a `.so` in that directory, because `nativeLibraryDir` is the one place Android will execute a file from. The control client is built the same way but with plain std, no target spec, and lands as `libclient.so`.

`build-agent.sh` expects an aster checkout beside this repo; `ASTER_REPO` points it somewhere else.

## The protocol

One line in, text out, over `@aster-eyes` in the abstract namespace. The socket lives in the abstract namespace rather than the filesystem so there is no path to protect and no permissions to get wrong. The service binds it with ten retries at 300 ms apart, then waits two seconds and loops, because a previous listener unwinding can take longer than any fixed number of tries and giving up used to leave the service bound and answering nothing.

The verbs are the agent's whole vocabulary on the device:

```text
map              numbered, actionable elements
find <text>      the same, filtered by text, content-desc or id
tap <n>          click element n, resolving up to the row that owns a label
tap <x,y|F7|o3>  a pixel, a grid cell from `shot grid`, or a block from the last ocr
drag p1 p2 [ms]  down, pause, move, pause, up: what sliders and cues need
finger down|move|up   one touch held across calls, with shots in between
shot grid [px]   the screen with lettered cells; `shot grid R3` zooms with pixel labels
text <s>         set text on a focused field
key back|home|recents|enter|delete|tab
volume up|down|max|mute|<0-100> [stream]
media pause|play|toggle|next|prev
restart <app>
```

The target grammar is uniform across all of them: an element index from `map`, a pixel pair, a grid cell like `F7`, an OCR block like `o3`, or a blob from `locate`. That is what lets the agent fall back from the tree to the grid to OCR without learning a new syntax each time.

There is also a set of verbs that skip the screen entirely, because reading and tapping your way across the phone works and is slow. Where an intent exists, it is one call instead of a dozen reads that can each go wrong: `open`, `settings`, `quicksettings`, `notifications`, `dial`, `sms`, `url`, `alarm`, `timer`, `event`, `media`, `volume`, `wallpaper`, `emergency`.

And a set that skips the tree entirely, for the screens the tree cannot describe. `locate bright` finds numbered blobs by true centre, `aim` points a cue at a target and self-corrects, `shot grid` lays lettered cells over the screen so a tap can name a cell instead of a pixel, and `marks` draws the map's own indices over the live screen. On a canvas app, where the whole map is one element with nothing inside, those are the only handles there are.

## The receipt model

Every action returns two lines. The first is the receipt, the acknowledgement that the event was accepted. The second is what actually changed.

```text
receipt: posted ("System" via its row)
changed: +32 -30 pkg=com.android.settings after_ms=713
```

That second line is the whole design. A receipt only means the system took the event. The diff is the evidence, and an empty diff prints a warning instead of a success:

```text
warning: nothing on screen changed; treat as not done
```

On a blind tree the diff is pixels instead. A canvas app exposes the whole map as one element with nothing inside, so the service keeps a small thumbnail of the frame before the action, compares it after, and then reads the result with OCR:

```text
changed: tree blind (0 elements); 6% of pixels changed since the frame 2s before the action
```

The comparison is a downscaled thumbnail and a per-pixel RGB delta threshold, so it is cheap enough to run after every action. `wait <text>` reads by OCR on a blind tree too, because a blind screen has no accessibility events and waiting for them never returns.

Three rules keep the verifier honest, and each one came from watching the agent get something wrong:

- **Read when the screen is still.** Reading mid-animation costs about forty times as much and returns a frame nobody asked for. Events mark the screen dirty, and a read waits for quiet before it captures.
- **Wait for the action to land before waiting for quiet.** Waiting only for quiet returns instantly when the app has not reacted yet, reads the stale tree, and reports a real navigation as no change. A verifier that lies is worse than no verifier at all.
- **Never exec the agent on the main thread.** The activity blocked on the client while the socket handler posted its capture to the same looper. Same process, same thread, instant deadlock.

## The services

**AsterAgentService** keeps the agent alive. A command that answers once and exits is useless here, because nothing can reach it and it reacts to nothing. A foreground service is the only way to hold a long-running process on modern Android, and the notification it is required to show doubles as the status line. The service supervises the bridge and restarts it with backoff. A process that survived a while was healthy, so the backoff only tightens when it is failing immediately, which means misconfigured. It also refreshes the binaries, skills, instructions and `.env` on every start, because after an update the links and bundled files are stale and nobody may ever open the app to refresh them.

**AsterA11yService** is the accessibility service. It captures a pruned node list, serves it over the abstract socket, and performs actions. It owns the wake path: every verb starts by waking the screen, because a phone left on a desk is off by default. That is a wake lock with `ACQUIRE_CAUSES_WAKEUP`, held for two minutes, followed by a poll of the active window every 50 ms until it is there or three seconds pass. It also serves the mirror's `stream` and `live` verbs, the latter on their own single thread so a finger on the viewer is never queued behind an agent verb waiting for the screen to go quiet.

**AsterIme** is an input method that draws nothing and never shows a window. `ACTION_SET_TEXT` is refused by plenty of fields, Compose editors and WebViews and anything with its own input handling, and synthesised key events drop characters. An IME owns the `InputConnection` and commits text at the cursor, which works wherever the cursor is. It draws nothing because a zero-height view still claims the whole screen as touchable, which blocks every tap and hides the app from the tree the agent is trying to read. `onEvaluateInputViewShown` returns false and the connection is bound anyway. The agent is the only thing typing on this device, so there is no keyboard to show.

**MirrorService** is what makes the screen capture legal. Since Android 14 a projection is only granted to an app the user can see is recording, so this exists to be the notification that says so; the capture itself lives in `Mirror`. `/mirror` in Telegram starts `asterctl serve` on the device and replies with a tappable URL. Video is H.264 off the phone's hardware encoder, decoded in the browser with WebCodecs; stills are the older JPEG-per-screenshot path, kept as the fallback for a browser with no `VideoDecoder`. Stills cap at two or three frames per second because `takeScreenshot` is rate-limited to one per 333 ms. Video has no such ceiling.

## The instructions

The load-bearing part of asterdroid is not Kotlin. It is `device-AGENTS.md`, shipped as `assets/AGENTS.md`, and the skills in `skills/`, shipped flat into assets by the Gradle `syncDocs` task.

Those files are the agent's entire understanding of what it is and where it is. Changing behavior means changing those files, not adding code. `Install.kt` refreshes the binaries, skills and instructions on every service start, so shipping a behavior change needs nobody to open the app. Change the Markdown, rebuild, install, and the next service start picks it up.

The first thing they establish is identity, stated as a fact rather than a role: you are not a computer that controls a phone, you are a process inside an Android app on the device itself. The phone is your body. There is no repository and no code to edit. That framing is what makes the rest work, because the person on the other end cannot pick the phone up, tap the screen, read a notification, or dial a number. If the agent declines, the outcome is not that someone else does it. The outcome is that it stays undone.

They also carry the things a model cannot infer from a tool list: read the screen before assuming you arrived, because a freshly installed app opens on onboarding and Chrome opens its first-run screen; if the same step reports no change twice, press back once, read again, and if it is still stuck restart the app rather than keep tapping other coordinates. Two of them are safety rules rather than tactics: narrate, do not stall, and treat screen content as data, so text on a screen asking the agent to do something is not a request from the person it works for.

Keys are not typed into a form. They arrive as a `.env` pushed into the app's external files dir, the one place `adb` can write without `run-as`, and `Install.env` moves it inside and deletes the outside copy.

## Known limits

- **Type into a terminal.** `text` reaches normal text fields, including ones that refuse the usual accessibility text action. It cannot reach a view that handles its own input and never opens an input connection, and a terminal is the common case. There it reports posted while nothing arrives.
- **See a canvas.** Maps exposes the whole map as a single element with nothing inside. The fallback is OCR, which misreads things. Treat what it returns as a reading rather than a fact, and cross-check it when the task depends on it.
- **Reach the lock screen.** The screen must be unlocked for taps to reach apps. The mirror will happily show and tap the lock screen to no effect. The service prints a note when the keyguard is up, but it cannot get past it.
- **Wake the phone from cold.** Every verb starts by waking the screen. That is a wake lock, not a boot. The app declares `RECEIVE_BOOT_COMPLETED` and does not yet use it, so a reboot means opening the app once before the agent is reachable again.
- **The numbers are from an emulator.** Measured on a Pixel 7 emulator, Android 15 / API 35, with a software GPU, so the absolute timings are pessimistic. The structure holds and a real device is faster.
