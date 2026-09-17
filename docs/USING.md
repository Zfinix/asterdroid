# Using it

Send instructions in plain language. The agent reads the screen, performs actions and can send screenshots of the result.

```txt
you    open whatsapp and tell mum I land at 6
you    what did I miss on twitter
you    install duolingo and do today's lesson
you    turn on do not disturb until 7am
you    lets play wordle and win
```

Use `/help` to see the available commands:

| command | what it does |
| --- | --- |
| `/new` (or `/clear`) | start a fresh conversation |
| `/stop`, `/retry`, `/queue` | cancel the running turn, rerun the last message, see what is waiting |
| `/mirror`, `/mirror off` | share this phone's screen in a browser |
| `/mode`, `/model`, `/effort` | set mode, model and reasoning effort |
| `/status`, `/sessions`, `/resume` | view status, list sessions and resume a conversation |
| `/memory`, `/remember <fact>` | view or add persistent memory |
| `/skills`, `/learn` | browse skills or create one from the last turn |
| `/checkins`, `/cron` | manage check-ins and scheduled runs |

`/mirror` returns a browser URL for viewing and controlling the phone. It uses the phone's Tailscale address when available, otherwise its LAN address. The accessibility service handles the screen-capture dialog.

The app starts the agent in `yolo` mode because the phone has no terminal for approval prompts. Its instructions tell it to announce actions that send information outside the phone, then proceed. This is a notification, not an approval step. Use `/mode` to change the mode for a chat.

The app shows agent status, special permission grants, tool activity, transcripts, skills and memory. Provider, model and effort pickers restart the agent when changed.

## Control protocol

Commands use the abstract socket `@aster-eyes`: one line per connection, with the response read to EOF. The service retries binding ten times at 300 ms intervals, waits two seconds, then repeats if the previous listener still holds the socket.

The accept loop handles three request types:

- `stream …` turns the connection into a video stream.
- `live …` handles mirror input on a separate thread so it does not wait behind agent commands.
- Other commands wake the display, run the action and return a receipt with the updated screen state.

Display wake uses `SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP`, held for two minutes. The service polls for an active window every 50 ms for up to three seconds. Responses identify an active keyguard because taps cannot reach apps behind it.

### Targets

Commands accept the following target formats:

| target | comes from | example |
| --- | --- | --- |
| element index | `map` or `find` | `tap 11` |
| pixel pair | anywhere | `tap 540,1200` |
| grid cell | `shot grid` | `tap F7` |
| OCR block | `ocr` | `tap o3` |
| blob | `locate` | `drag b0 b3` |
| rectangle | a map's bounds | `shot 0,452-1080,683` |
| text | what the element says (`tap` only) | `tap Wi-Fi` |

Stale handles return a message identifying the read to repeat, for example: `no ocr block o4; the last ocr had 3; run ocr again`.

## Command reference

Run `asterctl help` on the device for the command list.

### Read

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
| `pace [reset]` | the learned waits for the app in front |
| `capture on\|off` | hold the screen capture so blind reads skip the screenshot rate limit |

### Gestures

| verb | what it does |
| --- | --- |
| `tap <target>` | click; resolves up to the row that owns a label |
| `tap <text>` | click the element whose text or description is that, read as the tap runs |
| `do <step>; <step> …` | run several verbs in one call; stops at the first that errors or changes nothing |
| `press <target>` | long press (600 ms): opens context menus |
| `swipe <from> <to> [ms]` | a flick (300 ms): scrolls, dismisses, archives |
| `drag <p1> <p2> [p3 …] [ms]` | down, pause, move, pause, up (alias `slide`) |
| `hold <target> <ms>` | hold a touch for the specified duration |
| `pinch <target> in\|out [ms]` | two fingers: maps, photos |
| `finger down\|move\|up` | one touch held across calls, with reads in between |
| `live tap\|press\|down\|move\|up\|key` | mirror input without settling |
| `scroll [n] up\|down` | scroll an element, or the biggest scroller on screen |

### Text input

| verb | what it does |
| --- | --- |
| `text <text>` | set the focused field outright through `ACTION_SET_TEXT` |
| `type <text>` | commit through the keyboard, append through the input connection |
| `clear` | empty the focused field, either side of the cursor |
| `key <name>` | `back`, `home`, `recents`, `lock`, `power`, `notifications`, `quicksettings`, and `enter`, `delete`, `tab` into the focused field |

### Waiting and scheduling

| verb | what it does |
| --- | --- |
| `wait <text> [secs]` | block until the text is on screen (default 10 s, capped at 20 s) |
| `later <30s\|2m\|1h\|18:30> <what to do>` | end the turn; a reminder wakes the agent then |

### App and system shortcuts

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

`emergency` opens the dialer with the number and presses its call button, found by view ID. This avoids loose text matches against other clickable dialer elements.

`alarm` and `timer` use the phone's clock app, so they do not depend on the agent process staying alive.

`later` schedules an exact alarm. Its receiver writes a file that the Telegram bridge turns into a new agent turn. This lets the current turn finish while waiting for a longer task.

## Action receipts

Every action returns a dispatch receipt followed by observed screen changes:

```txt
receipt: posted ("System" via its row)
changed: +32 -30 pkg=com.android.settings after_ms=713
```

`posted` means the event was accepted. The `changed` line compares element signatures before and after the action: class, ID, text, description and top-left position. A screen change does not by itself confirm that the intended task succeeded.

When the caller already holds the map the screen changed from, and every unchanged row kept its index, the receipt lists only the changed rows instead of the whole map:

```txt
receipt: posted ("Wi-Fi" via its row)
changed: +1 -1 pkg=com.android.settings after_ms=412
screen: elements=33; rows not listed keep their numbers from the last map
 14 [Switch] #switch_widget "On" 924,540-1032,600 tap
```

A new screen, or one where rows shifted, returns the whole map.

An empty diff returns:

```txt
warning: nothing on screen changed; treat as not done
```

`quiesce` waits for an accessibility event, then for 150 ms of quiet. Waiting for the first event prevents a read from returning before the app reacts. The quiet budget prevents animations from blocking indefinitely.

Both waits adapt to the phone and the app. The service times every action: how long the first event took, and how long the screen took to go quiet. It keeps the last 32 of each per app and for the whole phone in `SharedPreferences`. After six timed actions, the wait for the first event is 1.5 times the 95th percentile plus 200 ms, between 350 and 1500 ms. The quiet budget is 1.5 times the 90th percentile plus 100 ms, between 250 and 600 ms. An app that fails to go quiet in half of its last 12 actions gets 250 ms. An app with too few samples uses the phone's figures, and a new install uses the old fixed 1500 and 600 ms. App launches keep the fixed budget. `asterctl pace` prints the current figures for the app in front, and `pace reset` forgets them.

A shorter wait can report `+0 -0` for an app that answers late. The service remembers every no-change receipt. If an event arrives from that app within three seconds and the tree differs at the next verb, it records the delay as a sample, which lengthens that app's wait. It also holds the verb instead of running it, because that verb was chosen on the belief that the action failed:

```txt
note: the last action did land after all, 840ms after it was sent (+3 -1); the wait for this app is now longer
held: `tap 14` was not run, because it was chosen when that action looked like it failed. Send it again if it is still wanted.
```

Read-only verbs such as `map`, `find` and `shot` still run, with the note in front.

For screens with no useful accessibility tree, the service compares 152 px-wide thumbnails using an RGB delta threshold, groups changes into rectangles and runs OCR:

```txt
changed: tree blind (0 elements); 6% of pixels changed since the frame 2s before the action
moved: 84,1204-996,1560; 12,96-1068,240
```

`settleBlind` samples thumbnails every 120 ms until frames at least 280 ms apart differ by no more than 1%, within the same settling budget. A blind action runs it before reading the result, since no events arrive to say the screen settled.

Frames for the service's own reads (`ocr`, `locate`, `aim`, blind diffs, `wait` on a blind screen) come from the screen capture when one is running. The virtual display is pointed at an `ImageReader` for one frame and handed back to the encoder, so a mirror viewer sees the picture hold for a frame or two. This has no rate limit. `asterctl capture on` starts the capture with nobody watching, auto-accepting the consent dialog, and it stops after 30 idle minutes or with `capture off`. Without a capture, reads fall back to `takeScreenshot`. The service learns the system's minimum spacing between screenshots from the first refusal and waits that long before asking again. `shot` always uses `takeScreenshot`, because it is the picture sent to the person.

## Reading the screen

`capture()` walks accessibility windows and returns numbered elements:

- Skip non-application windows measuring 200 px or less in either dimension, such as system bars and floating controls.
- Keep visible nodes with valid bounds that are interactive or contain text or a content description. Skip layout-only nodes but continue through their children.
- Stop at 4000 nodes. On API 33+, use descendant and sibling prefetching to reduce cross-process reads.
- Limit each output line to 160 characters so large content descriptions do not overwhelm the response.

Example:

```txt
pkg=com.android.settings elements=33 capture_ms=8.1
 11 [LinearLayout] 0,452-1080,683 tap
 12 [TextView] #title "Location" 189,506-394,577
```

`pkg` identifies the foreground app. Rows use `index [Class] #id "text" left,top-right,bottom`, followed by flags such as `tap`, `edit` or `scroll`.

When tapping a label, the service searches up to six ancestors for a visible, clickable parent and identifies it in the receipt.

`wait <text>` polls `find` every 500 ms. When the tree has no useful elements, it uses OCR once per second. On timeout, it returns close matches and the current map to help identify the next action.

## Gestures in detail

`tap` uses `ACTION_CLICK` when supported, otherwise a gesture at the target's centre. Gesture cancellation is reported to the caller.

- `swipe` uses a single 300 ms stroke for scrolling and flings.
- `drag` continues one touch through a press, pause, movement, pause and release. The pauses help sliders and draggable items register the grab and drop.
- `pinch` dispatches two strokes, ranging from 80 px to 400 px on either side of the centre.
- `finger down|move|up` holds a touch across calls, allowing screen reads and adjustments before release.
- `live` handles mirror input using 16 ms segments, with moves capped at 250 ms and no screen read or settling wait. A new `down` releases any touch left active by a disconnected viewer.

`key lock` locks the device through accessibility. It returns the keyguard state instead of a screen diff.

## Typing

`text` replaces the focused field through `ACTION_SET_TEXT`. Some Compose editors, WebViews and custom fields reject it.

`type` commits text through `AsterIme` and the focused field's `InputConnection`. The IME draws no window, keeping the screen available for taps and accessibility reads.

Neither method reaches a view that handles input without opening an input connection. In those cases, including some terminals, `type` may report `posted` without inserting text. Read the field after typing to verify the result.

## Visual targeting

When accessibility does not expose useful elements, the agent can use OCR, a screenshot grid or colour-based targets.

| Tool | Behaviour |
| --- | --- |
| `ocr` | Runs ML Kit's Latin recogniser on a screenshot and returns numbered text blocks with bounds. `tap o4` targets block 4. |
| `shot grid` | Adds labelled cells to a screenshot, with ten cells across the short side. Gestures target cell centres. |
| `shot grid R3` or `shot grid Q2-S4` | Crops and enlarges a cell or range, with screen-pixel labels for more precise targeting. |
| `locate bright` or a colour name | Finds connected regions on a 900 px-wide working image and returns `b0`, `b1`, etc., largest first, with centre coordinates. |
| `aim <target>` | Adjusts a pool cue using repeated screenshot measurements, up to six iterations or two degrees of error. |

The grid is added only to the returned image, so it does not affect OCR or the live screen. Cell coordinates depend on screen size.

`aim` finds the cue ball's solid core by eroding a white-pixel mask, estimates the aim line from distant bright pixels, then adjusts the drag using a secant update.

`Vision.kt` implements these operations as screenshot functions. Working images are downscaled; returned coordinates are converted back to device pixels.

## Screen mirror

`/mirror` starts `asterctl serve` and returns a browser URL. `/mirror off` stops it. The page at `/` displays the phone and accepts input; `/ws` carries H.264 video out and touch, key, text and quality messages in.

MediaProjection sends a VirtualDisplay directly to a hardware `MediaCodec` input Surface. The encoder is configured for 1080 px width, 60 fps, variable bitrate and ten-second keyframe intervals. `KEY_LATENCY` is set to 1, and the previous frame repeats every 100 ms to support viewers joining a static screen.

One capture serves all viewers and remains active for 30 minutes after the last viewer leaves. Quality presets change bitrate without resizing or requesting a new projection.

Each viewer has its own writer thread and a 60-frame queue. When a queue overflows, its backlog is dropped and playback resumes at the next keyframe. A viewer is disconnected after five overflows, keeping slow connections from blocking the encoder.

The capture protocol follows scrcpy: eight bytes of presentation timestamp and flags, four bytes of payload length, then Annex-B data. The Rust server converts the video to AVCC for WebCodecs and derives the codec string from the SPS.

`MirrorAutoAccept` uses accessibility view IDs to select whole-screen capture and accept the system dialog. Capture consent must be granted before starting the `mediaProjection` foreground service.

Joining wakes the screen, attempts to dismiss the keyguard and holds a wake lock while viewers are connected. A PIN or pattern requires manual unlocking. Locking the device stops the projection.

To change the viewer without reinstalling, place a copy at `files/viewer.html` or set `ASTER_MIRROR_UI`. See `skills/mirror/SKILL.md` for packet formats, quality settings and Tailscale or cloudflared setup.

## Driving it from a host

Use `run-as` to call the client from a host with a debug build. During normal operation, the agent runs `asterctl` directly as a child process.

```sh
adb shell run-as dev.aster.probe files/bin/asterctl map
```

For release builds, send commands through a broadcast and read the response from the external files directory:

```sh
adb shell am broadcast -p dev.aster.probe --receiver-foreground -a dev.aster.probe.CTL --es cmd "'shot grid'"
adb shell cat /sdcard/Android/data/dev.aster.probe/files/ctl/reply.txt
adb pull /sdcard/Android/data/dev.aster.probe/files/ctl/shot.png
```

The inner quotes matter: `adb shell` strips the outer pair, so without them the phone sees `shot` and `grid` as two arguments. `-p` matters too, or the broadcast is deferred while the app is in the background.

Other broadcasts: `CAP` logs a map, `BENCH --ei n 20` reruns the capture benchmark, `EXPORT` copies skills, memory and sessions out for `adb pull`, `IMPORT` replaces them from a pushed directory, and `CLEAR_FEED` empties the live feed.

To reach the mirror from the host without Telegram:

```sh
adb shell "asterctl serve >/dev/null 2>&1 &"   # it blocks otherwise
adb forward tcp:7071 tcp:7070                  # then open http://localhost:7071
```