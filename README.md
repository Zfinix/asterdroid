# asterdroid

**Aster as a phone you text.**

![Telegram on the left, the phone's mirrored screen on the right](docs/mirror-wordle.png)

The app (`dev.aster.probe`) runs the full Aster agent on an Android phone. It
reads the screen through an accessibility service, touches it the way a thumb
does, and answers over Telegram. The person holding the conversation is usually
nowhere near the phone.

It is not a port and not a subset. `build-agent.sh` cross-compiles `aster-cli`
for `aarch64-linux-android` and ships it inside the APK as `libaster.so`, so the
tools, skills, prompts and review path are the same code that runs in a
terminal. The hard part was never the model: a phone has no shell, so the screen
is the only interface, and an agent that cannot prove an action landed is just
guessing.

> **Status: a working daily driver, not a product.** It builds from an Aster
> checkout, ships one ABI (`arm64-v8a`), and expects a phone you are willing to
> hand every permission to.

## Quickstart

### What you need

- A phone or emulator on `adb`, Android 8 or newer (`minSdk 26`), and a screen
  you are happy to give away.
- An [Aster](https://github.com/zfinix/aster) checkout beside this repo, or
  `ASTER_REPO` pointing at one.
- `ANDROID_HOME` with build-tools and platform 35, an NDK, and `kotlinc`.
- A nightly-capable Rust toolchain with `rust-src`
  (`rustup component add rust-src`), because the agent's target spec needs
  `build-std`.
- A Telegram bot token from [@BotFather](https://t.me/BotFather).
- An API key for any OpenAI-compatible provider (OpenRouter, OpenAI, Groq,
  Anthropic, or a model on your own machine).

### 1. Build the two binaries

```sh
./build-agent.sh     # the agent, into app/src/main/jniLibs/arm64-v8a/libaster.so
./build-client.sh    # asterctl and the mirror, as libclient.so
```

The first one takes a while: it builds `std` from source and a static `libffi`
for the embedded Python. Both land in `jniLibs` because `nativeLibraryDir` is
the one place Android will execute a file from.

### 2. Build and install the app

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle's `syncDocs` task copies `device-AGENTS.md` and every `skills/*/SKILL.md`
into assets on the way, so the instructions in the APK are the ones in the repo.

### 3. Hand over the permissions

```sh
./grant.sh
```

That grants the runtime permissions, the app-op specials, the notification
listener and Do Not Disturb access, the battery whitelist, the keyboard (enabled
and selected), and the accessibility service, then prints anything still denied.
Without the accessibility service the agent has no eyes and no thumb, so check
that line.

### 4. Give it a bot and a key

Open the app and tap the settings icon in the top right. It lists the keys the
agent runs on, each one a row to paste into:

- **Telegram token**, from [@BotFather](https://t.me/BotFather).
- **Allowed ids**, the Telegram accounts allowed to drive it. Leave it for now.
- **The provider key** for whichever provider is chosen below, such as
  `OPENROUTER_API_KEY`.

They are written to the agent's `.env` on the phone, and saving one restarts the
agent, because keys are read when it starts. Nothing has to be typed on a
computer.

If a computer is easier, a pushed file works too and is folded into whatever the
phone already holds:

```sh
cat > .env <<'EOF'
ASTER_TELEGRAM_TOKEN=123456789:AAE...        # from @BotFather
OPENROUTER_API_KEY=sk-or-...                 # or OPENAI_API_KEY, ANTHROPIC_API_KEY, ...
EOF

adb push .env /sdcard/Android/data/dev.aster.probe/files/.env
```

### 5. Start it, then say hello

Press the one control on the home screen. The notification says "Starting", then
"Connected to Telegram".

Message the bot. The first message from an unknown account gets:

```txt
This bot isn't set up yet. Your user id is 8675309. Restart it with --user 8675309 to allow it.
```

That is the id to allow: paste it into **Allowed ids** under the settings icon.
Every message from outside that list is met with silence from then on, because
the bot's handle is public.

Now say something like "open settings and turn on Do Not Disturb".

## Using it from the chat

Talk to it the way you would talk to someone holding the phone. It reads the
screen, taps, types, waits, and sends screenshots of what it did, because a
description of a screen is a claim and a picture is evidence.

```txt
you    open whatsapp and tell mum I land at 6
you    what did I miss on twitter
you    install duolingo and do today's lesson
you    turn on do not disturb until 7am
you    lets play wordle and win
```

The bridge's commands, as `/help` lists them on a phone build:

| command | what it does |
| --- | --- |
| `/new` (or `/clear`) | start a fresh conversation |
| `/stop`, `/retry`, `/queue` | cancel the running turn, rerun the last message, see what is waiting |
| `/mirror`, `/mirror off` | share this phone's screen in a browser |
| `/mode`, `/model`, `/effort` | how it acts, which model, how hard it thinks |
| `/status`, `/sessions`, `/resume` | where you are, and past conversations |
| `/memory`, `/remember <fact>` | what it keeps between conversations |
| `/skills`, `/learn` | browse skills, and score the last turn into one |
| `/checkins`, `/cron` | reminders it sets itself, and scheduled runs |

`/mirror` replies with a tappable URL naming the phone from outside itself (its
Tailscale address when the tailnet is up, its LAN address otherwise), and the
capture consent answers itself, so nobody has to be holding the phone. The page
shows the screen and takes touches.

The phone starts the agent in `yolo` mode, because there is no terminal on the
device to answer a permission prompt. Consent lives in the chat instead: before
anything that leaves the phone, the agent says what it is about to do in one
line and then does it. `/mode` changes that for a chat.

The app itself is a window onto the run, not a control panel: whether the agent
is live, the three grants that are not runtime permissions, a live feed of every
verb and reply, pickers for provider, model and effort (choosing one restarts the
agent, since the model is read at process start), and the agent's own transcripts,
skills and memory read back off disk.

## Configuration

Everything the agent reads on the phone, and where it comes from:

| variable | set by | what it is |
| --- | --- | --- |
| `ASTER_TELEGRAM_TOKEN` | the settings sheet, or a pushed `.env` | the bot token; without it the bridge exits |
| `ASTER_REMOTE_USERS` | the settings sheet, or a pushed `.env` | comma-separated Telegram ids allowed to drive it |
| `<PROVIDER>_API_KEY` | the settings sheet, or a pushed `.env` | whichever key the chosen provider wants |
| `ASTER_BASE_URL`, `ASTER_MODEL`, `ASTER_EFFORT` | the app's pickers | the provider, model and effort for the next start |
| `ASTER_COMPACT_BUDGET` | the service (60000) | a screen map is thousands of characters and stale in one step, so the desktop budget would resend hours of them |
| `HOME`, `TMPDIR`, `PATH` | the service | the app's private files, its cache, and `files/bin` |

The settings sheet and a pushed file write the same `files/.env`, one line per
key, and neither clobbers what the other put there: a write keeps every other
line, and an import folds the pushed names in over their old values rather than
replacing the file. `dotenvy` leaves variables that are already set alone, so
the app's provider and model pickers beat whatever `.env` says without
rewriting the file that holds the keys.

The provider list comes from `providers.json`, the same catalog the CLI and
desktop pickers read, synced out of the Aster checkout at build time with the
copy under `assets/` as the fallback. The model list comes from the provider's
own `/models` endpoint, the way an IDE picker fills in.

## Troubleshooting

| symptom | what it is | what to do |
| --- | --- | --- |
| the notification never says "Connected" | no token, or no network | check `.env` landed (debug builds): `adb shell run-as dev.aster.probe cat files/.env` |
| the bot answers strangers with an id | no allowed ids yet | paste the id it printed into Allowed ids under the settings icon |
| "This build has no agent" | `libaster.so` is missing from the APK | rerun `./build-agent.sh`, rebuild, reinstall |
| every verb says it cannot reach the service | the accessibility service is off | rerun `./grant.sh`, or turn Aster on in Accessibility settings |
| `type` reports posted and nothing arrives | the field owns its own input (a terminal) | read the screen after typing; there is no way around it from here |
| `serve is not a verb` | a stale `asterctl` | `./build-client.sh`, reinstall, then open the app once so the bin symlinks refresh |
| `/mirror` says the port is held | something else is listening on 7070 | stop it; the mirror will not kill a process it does not own |
| the viewer says capture was not allowed | a PIN or pattern keyguard | unlock the phone once; that is the one lock it cannot answer itself |
| nothing works after a reboot | the agent is not started at boot yet | open the app once |
| the agent tapped nothing and said the screen is locked | the keyguard is up | taps do not reach apps behind it; unlock first |

Logs are under the `ASTEREYES`, `ASTERAGENT`, `ASTERPROBE`, `ASTERINSTALL`,
`ASTERWAKE` and `aster-mirror` tags.

## How it fits together

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

- **The agent** is the real `libaster.so`, run as a child of a foreground
  service that supervises it and restarts it with backoff from 5 s to 5 min,
  tightening only when it fails inside a minute, which means misconfigured
  rather than unlucky.
- **The control client** (`asterctl`, from `client/`) is the agent's body. It
  talks to `AsterA11yService` over an abstract unix socket, so there is no path
  on disk and no permissions to get wrong. Connecting costs about 150
  microseconds, which is why paying it once per verb is fine.
- **The app** holds every permission Android hands out, and adds a notification
  listener, an invisible IME for fields that refuse `ACTION_SET_TEXT`, and the
  mirror.
- **The instructions** (`device-AGENTS.md`, shipped as `assets/AGENTS.md`) and
  the skills (`skills/`) are the load-bearing part: they are the agent's entire
  understanding of what it is. Change behavior by changing those files, not by
  adding code. `Install.kt` refreshes bins, skills and instructions on every
  service start, so an update needs nobody to open the app.

The agent is exec'd from `nativeLibraryDir`, and `Install.binDir` symlinks
`asterctl` and `aster` into the app's private `bin` directory on every start,
because the agent calls both by name. A symlink keeps the SELinux label of its
target, which is why that works where a copy into the data directory would not
be executable at all. Every install lands in a new `nativeLibraryDir`, so the
links are remade rather than trusted.

## The control protocol

One line in, text out, over `@aster-eyes` in the abstract namespace. One
connection per command; the reply is read to EOF. The service binds the name
with ten tries 300 ms apart, then waits two seconds and loops, because a
previous listener unwinding can take longer than any fixed number of tries and
giving up used to leave the service bound and answering nothing.

Three kinds of line are treated differently at the accept loop. `stream …` turns
the connection into the video itself. `live …` is a person's finger on the
mirror, so it runs on its own thread and is never queued behind an agent verb
waiting for the screen to go quiet. Everything else is a verb: wake the screen,
dispatch, answer with a receipt and the new state of the screen.

Every verb starts by waking the display, because a phone left on a desk is off
and there are no windows to read while it is. That is a
`SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` held for two minutes,
then a poll of the active window every 50 ms until it appears or three seconds
pass. When the keyguard is up the reply opens with a note saying so, because a
locked screen reads fine and taps nothing behind it.

### Targets

Every verb that points at something takes the same shapes, which is what lets
the agent fall back from the tree to the grid to the pixels without learning a
new syntax:

| target | comes from | example |
| --- | --- | --- |
| element index | `map` or `find` | `tap 11` |
| pixel pair | anywhere | `tap 540,1200` |
| grid cell | `shot grid` | `tap F7` |
| OCR block | `ocr` | `tap o3` |
| blob | `locate` | `drag b0 b3` |
| rectangle | a map's bounds | `shot 0,452-1080,683` |

A handle that matches the grammar but points at nothing is a stale read, not a
syntax error, so it names the read to re-run: `no ocr block o4; the last ocr had
3; run ocr again`.

## The verbs

`asterctl help` prints this on the device. Read:

| verb | what it does |
| --- | --- |
| `map` (alias `screen`) | numbered, actionable elements; the index is the handle |
| `find <text>` | the same list, filtered by text, content-desc or id |
| `ocr` | read text the tree cannot see (canvas, game, image) |
| `notes` | notifications seen since the last read, newest first |
| `apps [filter]` | installed apps, label first |
| `shot [target]` | PNG of the screen, an element, a rectangle or a cell |
| `shot grid [px]` | PNG with lettered cells; `shot grid <cell\|range>` zooms, pixel-labelled |
| `shot jpeg <1-100> <width>` | the same capture scaled, with the real screen size alongside |
| `marks [off]` | draw the map's indices over the live screen |
| `events` | which packages are keeping the screen busy, over one settle budget |

Act:

| verb | what it does |
| --- | --- |
| `tap <target>` | click; resolves up to the row that owns a label |
| `press <target>` | long press (600 ms): the menus a tap never reaches |
| `swipe <from> <to> [ms]` | a flick (300 ms): scrolls, dismisses, archives |
| `drag <p1> <p2> [p3 …] [ms]` | down, pause, move, pause, up (alias `slide`) |
| `hold <target> <ms>` | a finger kept down that long |
| `pinch <target> in\|out [ms]` | two fingers: maps, photos |
| `finger down\|move\|up` | one touch held across calls, with reads in between |
| `live tap\|press\|down\|move\|up\|key` | the mirror's finger: dispatched and left |
| `scroll [n] up\|down` | scroll an element, or the biggest scroller on screen |

Type:

| verb | what it does |
| --- | --- |
| `text <text>` | set the focused field outright through `ACTION_SET_TEXT` |
| `type <text>` | commit through the keyboard, appending like a person |
| `clear` | empty the focused field, either side of the cursor |
| `key <name>` | `back`, `home`, `recents`, `lock`, `power`, `notifications`, `quicksettings`, and `enter`, `delete`, `tab` into the focused field |

Wait and wake:

| verb | what it does |
| --- | --- |
| `wait <text> [secs]` | block until the text is on screen (default 10 s, capped at 20 s) |
| `later <30s\|2m\|1h\|18:30> <what to do>` | end the turn; a reminder wakes the agent then |

Straight there, because reading and tapping across the phone works and is slow:

| verb | what it does |
| --- | --- |
| `open <app>` | launch by label or package, then map the new screen |
| `restart <app>` | home, kill the background process, reopen |
| `install <app>` | the store page for it, then map it |
| `settings [name]` | `wifi bluetooth airplane data sound display battery apps location storage`, or bare |
| `quicksettings`, `notifications` | the shades, through global actions |
| `dial`, `sms`, `url` (alias `web`), `search`, `place` | hand off to whatever app owns the scheme |
| `alarm HH:MM`, `timer 10m`, `event HH:MM` | the phone's own clock and calendar |
| `wallpaper <path>` | from an image on disk, scaled and cropped without a picker |
| `emergency <number>` | dial, then press the dialer's own call button |
| `volume up\|down\|max\|mute\|<0-100> [stream]` | `media ring alarm notification call` |
| `media pause\|play\|toggle\|next\|prev` | to whichever app holds the media session |

Two of those deserve their reasons written down. `emergency` exists because the
platform refuses `ACTION_CALL` for emergency numbers and only `ACTION_DIAL` may
pre-fill one, so the service dials and then presses the call button itself,
found by view id across the dialers that ship on real phones and never by a
loose word match, since the dialpad container is itself clickable and has "dial"
in its id. `alarm` and `timer` go to the phone's clock because an alarm there
survives the agent being killed, the battery dying and the app being
uninstalled, and a reminder held inside the agent has none of those properties.

`later` is the other half of waiting: it schedules an exact alarm, and the
receiver drops a file the bridge turns into a new turn, so the agent ends its
turn instead of holding the line for an install.

## The receipt model

Every action returns a receipt and then what actually changed:

```txt
receipt: posted ("System" via its row)
changed: +32 -30 pkg=com.android.settings after_ms=713
```

The receipt only means the event was accepted. The `changed` line is the
evidence: a signature of every element (class, id, text, description, top-left)
before and after, counted both ways. An empty diff prints a warning instead of a
success:

```txt
warning: nothing on screen changed; treat as not done
```

`quiesce` is what makes the diff honest. It waits for the accessibility event
count to move (up to 1500 ms), and only then waits for 150 ms of quiet inside a
600 ms budget, because a marquee or a spinner never goes quiet and once the
action has landed a bounded wait for calm is all the extra certainty there is.

On a blind tree the diff is pixels instead. A canvas app exposes the whole view
as one element with nothing inside, so the service keeps a 152 px-wide thumbnail
of the frame from before the action, compares it after with a per-channel RGB
delta threshold, groups what moved into rectangles, and reads the result with
OCR:

```txt
changed: tree blind (0 elements); 6% of pixels changed since the frame 2s before the action
moved: 84,1204-996,1560; 12,96-1068,240
```

Settling is different there too. A blind screen emits no accessibility events,
so waiting for them never returns: `settleBlind` grabs thumbnails every 120 ms
until two in a row differ by 1% or less and hold for 280 ms, bounded by the same
budget, because a spinner never stops.

## Reading the screen

`capture()` walks every window except the bars, prunes, and numbers what is left:

- Non-application windows 200 px or less in either dimension are skipped. Those
  are the status bar, the nav bar and the floating accessibility button: on
  every screen, never the target, and the clock ticks.
- A node is kept when it is visible with real bounds and is either interactive
  or carries text or a content description. A pure layout container carries
  nothing to act on, so it is skipped rather than pruned away with its children.
- The walk stops at 4000 nodes. On API 33 and up each child is fetched with
  `FLAG_PREFETCH_DESCENDANTS_HYBRID`, siblings and uninterruptible, because
  every child is otherwise a round trip to the app being read.
- Each element is one line capped at 160 characters. A terminal hands its whole
  screen over as a single content description, which both breaks the format and
  swamps the context budget.

A map reads:

```txt
pkg=com.android.settings elements=33 capture_ms=8.1
 11 [LinearLayout] 0,452-1080,683 tap
 12 [TextView] #title "Location" 189,506-394,577
```

`pkg` is the app in front. Each row is
`index [Class] #id "text" left,top-right,bottom` then any of `tap`, `edit`,
`scroll`. Tapping a label works: the service walks up as far as six ancestors to
the first clickable, visible row and says so in the receipt.

`wait <text>` is a single call rather than sleep-and-reread. It polls `find`
every 500 ms, and on a blind tree it runs OCR once a second at the rate the
screenshot limiter allows. A timeout prints the near misses before the map:

```txt
error: "Installed" did not appear within 30s
closest on screen: "Install" (12), "Uninstall" (14)
```

Waiting for a word that describes the screen rather than one that is on it is
how most waits are lost, so naming the near miss turns a dead end into the next
tap.

## Acting on the screen

A tap is `ACTION_CLICK` where the node accepts one and a dispatched gesture at
the node's centre where it does not. Everything else is a gesture, and the
gesture is waited out: one stroke is dispatched and blocked on, so the next can
continue it, and a cancellation is reported rather than pretending it played.

- `swipe` is a single 300 ms stroke, which apps read as a fling.
- `drag` is three strokes that continue one touch: down, a pause, the move
  through every point given, a pause, up. A slider, a cue or a card being sorted
  needs the pauses to register the grab and the drop, which is why a swipe "does
  not take" on them.
- `pinch` dispatches two strokes at once, 80 px to 400 px either side of centre.
- `finger down|move|up` holds one touch across separate calls, so the screen can
  be read while the finger is still down and the move adjusted before letting
  go. That is what a power bar needs.
- `live` is the mirror's finger: 16 ms segments, moves capped at 250 ms, nothing
  read before and nothing waited out after. A viewer that closes mid-drag leaves
  a finger on the glass and every later gesture is cancelled by the one still
  down, so the next `down` lifts whatever is held.

`key lock` is the only route to the power button an accessibility service has:
the shell cannot inject `KEYCODE_POWER` without `INJECT_EVENTS` and the power
menu carries no lock item on every device. Locking ends the screen, so there is
nothing to diff and the keyguard is the receipt.

## Typing

There are two paths and they fail differently.

`text` is `ACTION_SET_TEXT` on the focused editable, which replaces the field
outright. Plenty of fields refuse it: Compose editors, WebViews, anything with
its own input handling.

`type` commits through `AsterIme`, an input method that draws nothing and never
shows a window. An IME owns the `InputConnection`, so committing text works
wherever the cursor is, and appends the way a person would. It draws nothing
because even a zero-height view claims the whole screen as touchable, which
blocks every tap and hides the app from the tree the agent is trying to read.

Neither reaches a view that handles its own input and never opens an input
connection. A terminal is the common case: `type` reports posted while nothing
arrives.

## Seeing what the tree cannot

**OCR.** `ocr` runs ML Kit's latin recogniser over one screenshot and returns
numbered blocks with bounds. Those numbers are targets: `tap o4` taps the centre
of block 4 from that read.

**The grid.** `shot grid` draws lettered cells over a screenshot: ten across the
short side, columns `A`, `B`, `C` and rows `1`, `2`, `3`. A cell resolves to its
centre for every gesture. Geometry follows only from the screen size, so the
same name means the same pixel for the picture and the tap after it, and the
grid is only ever in the picture, so it never confuses OCR and never needs
turning off. A cell is coarse, so `shot grid R3` (or a range, `Q2-S4`) crops
that part, upscales it when small, and labels the lines with screen pixels,
which are the numbers a tap takes.

**Blobs.** `locate bright` (or a colour name) masks the frame, finds connected
components on a 900 px-wide working copy, and returns them largest first as
`b0`, `b1`, by true centre. A control the tree cannot see becomes a target by
where it actually is instead of a grid-cell guess.

**Aim.** `aim <target>` closes a control loop on the device instead of asking
the model for a swipe per turn. It erodes the white mask to find the cue ball's
solid core, takes the bright pixels farthest from it as the aim line's far end,
drags the cue, reads again, and corrects, learning the finger-to-aim gain by
secant update, up to six iterations or two degrees of error.

`Vision.kt` holds all of it as pure functions of a screenshot, on a downscaled
copy, with every coordinate scaled back to device pixels before it leaves.

## The mirror

`/mirror` starts `asterctl serve` on the phone and replies with a tappable URL;
`/mirror off` stops it. The page at `/` shows the phone and takes touches; `/ws`
carries H.264 out and touch, key, text and quality JSON in.

- **The video comes off the hardware encoder.** `takeScreenshot` is rate-limited
  to one frame every third of a second, which is why the old mirror ran at 2
  fps. MediaProjection has no such limit: a VirtualDisplay renders straight into
  a `MediaCodec` input Surface, so the pixels never pass through the heap. The
  capture is 1080 px wide, 60 fps, VBR, keyframes ten seconds apart,
  `KEY_LATENCY` 1 so a frame goes out as soon as it is queued, and the previous
  frame repeated every 100 ms so a viewer joining a still screen has something
  to ride on.
- **One capture serves every viewer** and outlives them by 30 idle minutes.
  Android voids a capture grant the moment it is used, so a capture per viewer
  would be a consent dialog per viewer. The size never changes for the same
  reason: since Android 14 a new projection means the dialog again. Presets
  change only the bitrate, which the encoder takes live.
- **A slow viewer only hurts itself.** Each viewer owns the thread that writes
  to it and a 60-frame queue; the encoder's drain loop never blocks on a socket.
  An overflowing viewer has its backlog dropped and restarts from the next
  keyframe, and after five of those it is let go.
- **The wire format is scrcpy's**, because the decoder on the other end is a
  port of one already written against it: eight bytes of pts with the top two
  bits as config and keyframe flags, four bytes of length, then the Annex-B
  payload. The Rust side converts to AVCC and hands WebCodecs a codec string
  from the SPS, then packets whose first byte is the kind.
- **The capture consent answers itself.** `MirrorAutoAccept` presses Start with
  the accessibility service's own thumb, addressing the dialog by view id rather
  than by what it says, because the button reads "Share screen" here, "Record
  screen" elsewhere, and "Next" whenever one app is the chosen mode. It sets the
  mode spinner to the whole screen first, since the dialog opens on a single app
  whose button leads to an app picker rather than a grant.
- **A locked phone used to be where the mirror died.** Android stops a
  projection the instant the device locks. Joining now wakes the screen,
  dismisses the keyguard, and holds a wake lock until the last viewer leaves. A
  PIN or pattern is the one case a person still has to answer.
- **Consent, then the service, in that order.** The `mediaProjection` foreground
  type is only permitted once capture has been granted, and starting the service
  first throws and takes the whole process down, accessibility service and all.

A copy of the viewer at `files/viewer.html` (or `$ASTER_MIRROR_UI`) wins over
the built-in one, so the page can be pushed and reloaded without reinstalling
the app, which would cost the capture consent and the accessibility toggle every
time. Packet formats, quality knobs, Tailscale and cloudflared exposure, and the
pitfalls: `skills/mirror/SKILL.md`.

## The cross-build

Rust's stock Android target keeps native thread-local storage off. That sounds
harmless until you embed a Python interpreter, which Aster does for its `python`
tool: every thread-local then costs one of bionic's 128 pthread keys, and the
interpreter aborts with `out of TLS keys`.

The fix is `target-spec/aarch64-linux-android.json`, whose whole reason for
existing is two lines:

```json
"tls-model": "emulated",
"has-thread-local": true
```

With native TLS on, LLVM lowers thread-locals to emulated TLS, which routes
every one of them through a single key. That lowering calls
`__emutls_get_address`, which lives in the NDK's compiler-rt archive and which
rustc's default link drops, so the build passes the archive explicitly.
`libffi` is the other missing piece: RustPython's `ctypes` wants a system
`libffi`, which Android does not ship, so `build-agent.sh` builds a static one
once from source and links against it. `build-std` and JSON target specs are
still nightly, so the build sets `RUSTC_BOOTSTRAP=1` and needs `rust-src`.

The control client is built the same way but with plain std and no target spec.

## Driving it from a host

`run-as` is how the socket is driven during development, which is why the debug
manifest is debuggable. On a real device the agent execs `asterctl` as its own
child and needs neither.

```sh
adb shell run-as dev.aster.probe files/bin/asterctl map
```

On a release build `run-as` is gone, so a verb goes through a broadcast and the
reply lands in the external files dir:

```sh
adb shell am broadcast -p dev.aster.probe --receiver-foreground -a dev.aster.probe.CTL --es cmd "'shot grid'"
adb shell cat /sdcard/Android/data/dev.aster.probe/files/ctl/reply.txt
adb pull /sdcard/Android/data/dev.aster.probe/files/ctl/shot.png
```

The inner quotes matter: `adb shell` strips the outer pair, so without them the
phone sees `shot` and `grid` as two arguments. `-p` matters too, or the broadcast
is deferred while the app is in the background.

The rest of them: `CAP` logs a map, `BENCH --ei n 20` reruns the capture
benchmark, `EXPORT` copies skills, memory and sessions out for `adb pull`,
`IMPORT` replaces them from a pushed directory, and `CLEAR_FEED` empties the live
feed.

To reach the mirror from the host without Telegram:

```sh
adb shell "asterctl serve >/dev/null 2>&1 &"   # it blocks otherwise
adb forward tcp:7071 tcp:7070                  # then open http://localhost:7071
```

## Permissions

The manifest declares every permission an app can hold. Runtime ones are asked
for at launch, foreground first and then background, because Android 11 and up
drop the whole request when `ACCESS_BACKGROUND_LOCATION` rides along with the
rest. Everything else needs `grant.sh` or a Settings toggle.

The accessibility service asks for interactive windows, view ids, not-important
views, key event filtering and screenshots, declares `isAccessibilityTool`, and
sets `notificationTimeout` to 0 so no event is coalesced away. The flag that
would turn on touch exploration is deliberately left out: it turns every finger
tap into an explore-only touch and makes the phone unusable by hand.

## What the probe established

Measured on a Pixel 7 emulator, Android 15 / API 35, with a software GPU, so the
absolute numbers are pessimistic; the structure holds.

| question | answer |
| --- | --- |
| Can a normal app exec the agent? | yes, from `nativeLibraryDir`, `aster 0.5.0`, exit 0 |
| Can it read another app's screen? | yes, 164 nodes walked to 35 kept, 2.3 KB |
| Can it act, and does the act land? | yes, confirmed by the foreground activity changing |
| How fast, screen already still? | **~8 ms** end to end from the Rust side |
| How fast during a fling? | p50 21 ms, p95 84 ms |
| How fast across an app launch? | 100-600 ms, sometimes worse |
| Same read over `adb uiautomator dump` | **2470 ms**, 27851 bytes |

## Three things worth carrying forward

**Read when the screen is still.** Reading mid-animation costs ~40x and returns
a frame nobody asked for. Events mark the screen dirty and the read waits for
quiet.

**Wait for the action to land before waiting for quiet.** Waiting only for quiet
returns instantly when the app has not reacted yet, reads the stale tree, and
reports a real navigation as no change. A verifier that lies is worse than none.

**Never exec the agent on the main thread.** The activity blocked on the client
while the socket handler posted its capture to the same looper. Same process,
same thread, instant deadlock.

## Known limits

- **Type into a terminal.** Neither `text` nor `type` reaches a view that
  handles its own input and never opens an input connection.
- **See a canvas.** Maps exposes the whole map as a single element with nothing
  inside. The fallback is OCR, which misreads things, so treat what it returns
  as a reading rather than a fact.
- **Reach the lock screen.** The screen must be unlocked for taps to reach apps.
  The service says so when the keyguard is up, but it cannot get past it.
- **Wake the phone from cold.** Every verb wakes the screen, but that is a wake
  lock, not a boot. `RECEIVE_BOOT_COMPLETED` is declared and not used yet, so a
  reboot means opening the app once.
- **One ABI.** Only `arm64-v8a` is built and shipped.
- **The numbers are from an emulator.** A real device is faster.

## Repository layout

```
app/src/main/
  AndroidManifest.xml            every permission, five services, three receivers
  assets/                        AGENTS.md, the flattened skills, providers.json (generated)
  jniLibs/arm64-v8a/             libaster.so (the agent), libclient.so (asterctl)
  kotlin/dev/aster/probe/
    AsterA11yService.kt          capture, gestures, OCR, the socket, the verbs
    AsterAgentService.kt         the supervised agent process
    AsterIme.kt                  the invisible keyboard
    AsterNotifications.kt        the notification listener
    Mirror*.kt                   MediaProjection, the encoder, consent, viewers
    Grid.kt, Vision.kt           lettered cells, blobs, changed regions, the aim read
    MarksOverlay.kt              the map's indices drawn on the live screen
    Shortcuts.kt                 apps, settings, intents, alarms, the store
    Install.kt                   bins, skills, instructions and .env on every start
    Env.kt                       the .env, read and written from the phone
    Models.kt                    the provider catalog, model lists, the child's env
    Sessions.kt, Feed.kt         transcripts and the live feed
    WakeReceiver.kt              `later`, as an alarm and a wakeup file
    MainActivity.kt, ui/         the app
  res/xml/a11y_config.xml        what the accessibility service may do
client/
  src/main.rs                    the CLI, and serve's detached re-exec
  src/socket.rs                  the abstract socket, one line in
  src/server.rs                  the mirror: axum, the websocket, Annex-B to AVCC
  viewer.html                    the mirror page, served from the binary
skills/<name>/SKILL.md           the agent's reference, shipped into assets
device-AGENTS.md                 the standing instructions, shipped as AGENTS.md
docs/ANDROID.md                  the longer architecture write-up
target-spec/                     the custom Android target that turns TLS on
build-agent.sh, build-client.sh  the two cross-builds
grant.sh                         every permission and toggle, over adb
```
