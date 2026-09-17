---
name: android-use
always: true
description: Drive the Android device this agent is running on. Read the screen as a numbered element map, tap, scroll, type, go back, read notifications, send full or cropped screenshots of what happened, and fall back to OCR where the accessibility tree is blind. Use when asked to open an app, change a setting, find something on screen, answer a message, or do anything on the phone itself.
---

# Using the phone this agent runs on

`asterctl` talks to Aster's accessibility service over a local socket. It is on
`PATH` as `asterctl`; every call returns text, and every action returns a
receipt plus what changed.

## The loop

Read, act, then read again. An element index is only valid for the map it
came from, because the numbers are assigned per capture. Every action's
receipt ends with the new screen, so a receipt is a read: tap the next index
straight from it, with no `map` in between.

When the screen only changed in place (a toggle flipped, a row appeared), the
receipt lists just the changed rows under `screen: elements=N; rows not listed
keep their numbers from the last map`. The rows it leaves out are still there
at the numbers you already have. A new screen always comes back as the whole
map.

Every call is a round trip to you, and that round trip is most of the time a
task takes. When you already know the route, name the targets by their text
and send the whole route as one call:

```json
{"command": "asterctl", "args": ["do", "tap Network & internet; tap Internet; tap Wi-Fi"]}
```

`tap <text>` reads the screen as the tap runs and taps the element that says
it (exact match first, then contains), so it works for a screen you have not
mapped yet. `do` runs the steps in order, prints one line per step, and ends
with the screen after the last one. It stops at the first step that errors or
changes nothing and tells you which steps it did not run. Use the `args` form
above, not `sh -c`, because the shell splits on `;`. A dialog chain (pick, OK,
CLOSE, CLOSE) is one `do`, not four calls.

```sh
asterctl map                 # numbered elements on screen now
asterctl find wifi           # the same list, filtered
asterctl tap 11              # act on element 11
asterctl tap Wi-Fi           # act on the element that says Wi-Fi
asterctl do "tap Wi-Fi; wait Connected"  # several steps, one call
asterctl scroll down         # reach what is below the fold
asterctl type "hello"        # type into the focused field
asterctl key back            # back, home, recents, enter, delete, tab
asterctl volume max          # up, down, max, mute, or 0-100; media unless you name ring/alarm/notification/call
asterctl restart spotify     # home, kill, reopen: the fix for an app that stopped responding
asterctl media pause         # pause, play, toggle, next, prev: whatever is playing, no screen needed
asterctl wait Open 20        # block until that text is on screen, up to 20s of waiting
asterctl later 3m check the 2048 install and play it   # anything longer: come back later
asterctl press <n|x,y>       # long press, for menus a tap never reaches
asterctl swipe x1,y1 x2,y2   # drag between pixels: carousels, sliders, canvases (add ms to slow it)
asterctl key enter|delete|tab # into the focused field
asterctl clear               # empty the focused field
asterctl notes               # recent notifications, newest first
asterctl alerts              # battery warnings and apps whose notifications go to the chat
asterctl alerts add <app>    # send its notifications to the chat; `alerts remove <app>`, `alerts battery off|20,10`
asterctl ocr                 # read the screen as pixels
asterctl shot                # the whole screen as a PNG on disk
asterctl shot 11             # just element 11, with a little margin around it
asterctl shot 0,452-1080,683 # just that rectangle
asterctl shot grid           # the screen with lettered cells drawn on it, for canvases
asterctl shot grid R3        # zoom into that cell (or Q2-S4, or l,t-r,b) with pixel-labelled lines
asterctl tap F7              # the centre of that cell; press, swipe, drag and hold take cells too
asterctl drag B8 B3          # finger down, pause, move, pause, up: sliders, cues, power bars, sorting
asterctl drag K6 R3 1200     # slower; add more points for a path (drag A1 C3 F2)
asterctl hold K6 1500        # keep a finger down that long
asterctl pinch K6 out        # two fingers, in or out: maps, photos
asterctl finger down B8      # one touch held across calls: down, then
asterctl finger move B5 700  #   move it (shot grid in between to watch), then
asterctl finger up           #   let go; the app sees one continuous drag
asterctl tap o4              # an ocr block from the last ocr, by its o-number
```

## Going straight there

Reading and tapping your way across the phone works and is slow. Where an
intent exists, it is one call instead of a dozen reads that can each go wrong:

```sh
asterctl quicksettings    # the shade with the toggles: DND, wifi, torch
asterctl notifications    # the notification shade
asterctl apps [filter]    # what is installed, label first
asterctl open <app>       # launch by label or package
asterctl restart <app>    # when it stops answering taps
asterctl volume max       # media volume, no screen needed
asterctl settings [name]  # wifi bluetooth airplane data sound display battery
                          # apps location storage, or bare for the top level
asterctl install <app>    # the store page for it
asterctl dial <number>    # the dialer filled in, not dialled
asterctl sms <number> [text]
asterctl url <address>    # web is the same verb
asterctl search <query>
asterctl place <query>    # a map of somewhere
asterctl alarm 07:30 [label]
asterctl timer 10m [label]
asterctl event 15:00 [title]
asterctl wallpaper <path> # from an image file on disk
```

`map` always means read the screen. A map of a place is `place`.

Volume is `volume`, never the Sound settings screen: `volume max`, `volume
down`, `volume 30`, `volume mute ring`. It reports every stream afterwards.
Stopping or skipping music is `media pause` and `media next`, not a hunt for
the player's buttons.

`settings` and the handoff verbs put a screen in front of you and stop there.
They are a starting position, not a result: read the screen afterwards and
finish the job by tapping.

Installing an app is six calls, not a research project: `install "<name>"`,
tap the result card, tap Install from the list, `wait Installed 20`, `open
"<name>"`, then dismiss the first-run dialog it shows. `open` succeeding is the
proof it installed; `apps` can lag for a few seconds after an install, so it
is not the check.

## Toggles

A setting is not changed because a screen opened. Nothing here flips a switch
for you, and nothing needs to: the switch is an element on a screen, so it is
a `tap` like any other.

```sh
asterctl quicksettings   # the shade, where the common toggles live
asterctl map             # read what is there
asterctl tap <n>         # flip it
asterctl tap x,y         # tap a pixel: for a canvas element with nothing inside, from its bounds
asterctl map             # confirm it moved
```

Quick Settings holds Do Not Disturb, wifi, bluetooth, torch, rotation, data
and airplane mode. For anything else, `asterctl settings <name>` lands on the
screen that owns it, then the same read, tap, read. If neither knows the name,
`asterctl settings` bare opens the top level, which has a search field.

Nothing about this needs a permission. An app calling `NotificationManager`
to set Do Not Disturb needs `ACCESS_NOTIFICATION_POLICY`; tapping the tile
needs nothing, and tapping is what you are doing. Never report a permission as
the reason a toggle did not move. Report what the tile said after you tapped
it.

## When a screen crashes

Settings screens can crash, and on some ROMs they reliably do. The
notification access page is a known one: it dies before drawing, for any app
that opens it, because Settings itself hits a missing permission internally.

You will not see an error. `startActivity` succeeded, and the crash happened
in the other process. What you will see is `pkg=` back at the launcher, or a
`changed:` line pointing somewhere nobody asked for. Read after every handoff
and check `pkg` is the app you expected.

A crashed screen is a fact about the device, not a blocked action. Say which
screen died, then take another route to the same place: the shade instead of
the settings page, a settings screen instead of a deep link, or the search
field at the top of Settings. If they all fail, say which you tried and what
each did.

## Reading a map

```txt
pkg=com.android.settings elements=33 capture_ms=8.1
 11 [LinearLayout] 0,452-1080,683 tap
 12 [TextView] #title "Location" 189,506-394,577
```

`pkg` is the app in front. Each row is `index [Class] #id "text" left,top-right,bottom` and
then any of `tap`, `edit`, `scroll`. Tapping a label works: the tool walks up to
the row that owns it and says so in the receipt.

## Judging whether an action worked

A receipt means the event was accepted, not that anything happened. The line
after it is the evidence:

```txt
receipt: posted ("Location" via its row)
changed: +17 -29 pkg=com.android.settings after_ms=649
```

`changed: +0 -0` prints a warning and means the screen did not move. Treat that
as not done.

The wait before that verdict is sized to how fast this app has answered on
this phone, so a slow answer can arrive after it. When that happens, your next
call comes back with `note: the last action did land after all` and, for
anything that is not a read, `held: ... was not run`. Take the new screen in
that reply as the truth, then decide again. Send the held command again only
if it still makes sense on that screen. Do not repeat the same tap hoping for a different result; read the
screen and pick a different target, or say what is blocking.

The warning can be wrong in one direction: an app that redraws the same tree
(a canvas, a drawing app, a game) can act and still diff to zero. So before
repeating anything that is not safe to do twice, take a `shot` or an `ocr` and
look. A message sent twice, a call placed twice or an order paid twice is worse
than a slow turn.

## Which element to tap

The receipt names what was tapped. A quoted label means it landed on something
that owns text:

```txt
receipt: posted ("SIMs")            <- a real target
receipt: posted (LinearLayout)      <- a wrapper: usually does nothing
receipt: posted (View)              <- the same
```

A bare class name in the receipt, followed by `+0 -0`, almost always means the
index was a container drawn around the thing you wanted rather than the thing
itself. Do not tap it again and do not go to pixels. Look one or two indices
along in the same map for the row that carries the label, and tap that.

## Waiting for a screen

`wait <text>` matches text that is literally in the tree, as a substring, case
insensitive. It is not a description of the screen. Waiting for `results`,
`Settings` or `loaded` times out even when the screen is exactly right, because
no element says that word.

Wait for a string you have already seen in a map, and pick the shortest stable
part of it. Play Store is the standard trap: the button says `Install`, then
`Open`, and never `Installed`, so `wait Installed` always burns its full
timeout.

A timeout prints the closest text on screen before the map:

```txt
error: "Installed" did not appear within 10s
closest on screen: "Install" (12), "Uninstall" (14)
```

That line is the answer most of the time. Take the index it names rather than
waiting again with the same word.

A wait tops out at 20s however many you ask for, because a wait that fails is
silence for the person watching. Something genuinely slow, like a large
install, is two or three waits with a word to the person between them, not one
long one.

## When the tree is blind

Some screens return almost nothing (`pkg=com.android.systemui`, a handful
of elements). Wi-Fi settings is one. Canvas-drawn apps like Maps are another:
the whole map is a single element with nothing inside it. That is not an error
and not an empty screen.

On a screen you will read by pixels more than once or twice (a game, a
drawing, a map you are working in), run `asterctl capture on` first. Blind
reads then come off the screen capture instead of rate-limited screenshots,
which is several times faster on every `ocr`, `locate`, `aim` and blind tap.
It shows the phone's recording indicator; `asterctl capture off` when done.

Run `asterctl ocr`. It reads the pixels and returns text blocks with bounds,
numbered `o0`, `o1`, and those numbers are targets: `asterctl tap o4` taps
the centre of block 4 from that read. They are not map indices; `tap 4`
means element 4 of the last map, which on a blind screen does not exist.
Use it to read, then act on coordinates from the map where you can. Prefer the
tree whenever it has the answer: OCR misreads things, so treat a value it
returns as a reading, not a fact, and cross-check it if the task depends on it.

When the task is about where things are drawn rather than what they say (a
ball on a table, a piece on a board, a slider with no label), `asterctl shot
grid` writes the screen with a lettered grid over it: columns A, B, C across,
rows 1, 2, 3 down, every cell labelled, and the reply says the cell size.
`read_file` that PNG once, name the cells you need, and act on them: `asterctl
tap F7`, `asterctl press F7`, `asterctl drag F7 J3`. A cell is its centre,
and a cell is coarse: a ball or a handle is smaller than one, so aiming at a
cell centre misses by up to half a cell. When the target has to be exact,
zoom: `asterctl shot grid R3` (or a range, `Q2-S4`) crops that part of the
screen and labels the lines with screen pixels, so you read the target's
`x,y` off the picture and use those numbers: `asterctl drag Q6 2467,361`.
When `ocr` already gives a box for the thing, its centre is exact too, so
prefer that over a cell. The grid is only in the picture, never on the
screen, so it never confuses `ocr` and never needs turning off.

When the move has to be judged as it goes (a power bar, a slider with no
scale, a cue that rotates as you pull), hold the touch across calls:
`finger down B8`, `finger move B5`, `shot grid`, look, `finger move B4`,
`finger up`. The app sees one drag; you get to look before you let go, which
a finished swipe never allows. Lift it when done, or the next action fights
a finger that is still down.

Moving something is `drag`, not `swipe`. A swipe is a flick: the finger is
down for a third of a second and the app reads it as a scroll or a fling,
which is why a slider, a power bar or a piece "did not take". `drag` puts the
finger down, waits, moves, waits, lifts, so the app sees a grab and a drop.
Slow it with a trailing ms when the app animates under the finger. A game
that aims where the finger is aims at where the drag ends, so end the drag
on the target, not near it.

Every gridded shot is posted to the person too, and they can direct you in
the same names: "swipe from F7 to J3" means exactly that, so run it as given
and show the result. When a game or drawing has you guessing, send the grid
shot and ask, rather than swiping blind.

## Typing

`type` reaches any field that accepts input, including ones that refuse the
usual accessibility text action. It cannot reach views that handle their own
input and never open an input connection: a terminal is the common case, and
there `type` reports posted while nothing arrives. Check the screen after
typing rather than trusting the receipt.

## First-run screens

A freshly installed app opens on onboarding, not where you asked to go. Chrome
opens `FirstRunActivity`, Maps opens a sign-in wall. Read the screen before
assuming you are where you intended, and dismiss with the obvious decline
("Use without an account", "No thanks", "Skip") before continuing.

## Permission dialogs

When an app asks for a permission, the system dialog (`pkg=` reads
`com.android.permissioncontroller`) is an ordinary screen to you: `find allow`
lists its buttons. Tap "While using the app" or "Allow" for anything the task
needs, "Only this time" if it was a one-off, and read the receipt, because a
second dialog often follows the first. Aster's own app already holds every
permission a phone offers, so no dialog will ever be about you.

## Showing your work

The person cannot see the phone, so a description of the screen is a claim,
not evidence. `asterctl shot` writes a PNG, and every shot you take is posted
to the chat on its own: do not send it again with `send_document`. A shot is
for the person, not for you: you do not see it, and you do not need to. The
map and `ocr` are how you read the screen. A task is
not finished until they have seen it: before you report done, take a shot of
the state you are claiming. Do not say
"verified" about anything you have not shown.

## Looking for yourself

Only when the map is blind and `ocr` returns nothing useful, and the task
cannot move without knowing what is drawn (a game board, a chart, an icon
with no label), `read_file` the PNG that `shot` wrote and you will see it.
Every picture you look at is re-sent on every later step and makes each one
slower, so look once, decide, and act on coordinates from that look. Never
write code to decode a screenshot.

Always send one for:

- the result, at the end of every task that changed something on the phone (a
  swapped app, a booked seat, a filled form, a settings toggle, a finished
  game)
- the screen you are stuck on, when you stop

Also send one when it helps them follow:

- a step that took several actions to reach
- anything you are unsure you read correctly, or that you are about to change
  in a way that is hard to undo

Crop to the part that matters. A full 1080x2400 screen on a phone shows a
confirmation number as a few unreadable pixels; `asterctl shot <index>` on the
element that holds it, or a rectangle from the map's bounds, arrives legible.
Send the whole screen only when the whole screen is the point, such as showing
where you have landed in an app.

`asterctl marks` draws the map's indices over the elements before a shot, so
the picture carries the same numbers you are acting on. Use it when you are
explaining what you are about to tap, and `asterctl marks off` afterwards, or
the overlay stays in every later shot.

Do not narrate a screenshot you did not send, and do not send one every step:
two or three across a task is usually right, and never zero.

## When nothing moves

On a screen with readable elements, two results in a row of `changed: +0 -0`
mean the app has stopped answering, not that your aim is off. A canvas screen
is different: the receipt says `tree blind`, then how many pixels changed
since just before the action and what the text on screen says now. A few
percent is a hint or a handle moving; tens of percent is a new screen or an
animation. `0%` with the same text means the touch did nothing: do not
explain it away, read the screen again (a `shot grid` if the map is blind)
before trying a different target. `wait <text>` reads by ocr on these screens,
so it is still the way to wait for a result. Do what a person does: `asterctl
key back` once (it also drops the keyboard), read again, and if it is still
stuck `asterctl restart <app>` and start the task over from the fresh screen.
That is two calls. Tapping other coordinates, re-reading with `ocr`, or taking
screenshots to study does not unstick anything and burns the turn.

If the restart lands you in the same place twice, stop and say what you see.
Say what is on screen and what you expected instead.

## Doing it like a person

A phone task is a handful of taps. If you are past ten calls without the
screen you wanted, you are on the wrong route: back out and take the direct
verb (`open`, `settings`, `volume`, `url`, `search`) or restart the app.
Never write scripts to read pixels, solve puzzles, or verify a screen; read
the map or the ocr, act, and read again. `aster python` is for the person's
data, not for looking at the phone.

Never sleep and re-read to wait for something. For anything under about
30 seconds, `asterctl wait "Open" 30` returns the moment the word appears,
with the map. For anything longer (an install, a download, a page that says
"try again in a few minutes"), do not wait at all: `asterctl later 3m <what
to do next>`, tell the person you will be back, and end your turn. The
reminder arrives as a new message with your note in it, and the person can
use you for other things in between.

While you are still finding the route, run one command per call, so you can
see which step failed. Once the steps are known (a dialog chain, a settings
toggle you have done before), send them as one `asterctl do "…; …"`: a
seven-step route as one call saves six model rounds, and it stops by itself
where the screen stops matching the plan. A tap that is allowed to change
nothing, like filling a board where a cell may already be set, goes in
`sh -c "asterctl tap …; asterctl tap …"` instead, because `do` stops there.
Read the shot at the end, not every receipt.

There is no `bash`, no `python`, and no `asterctl python` on this phone. A
shell line is `sh -c "…"`. The only Python is `aster python <file>` or
`aster python -c "…"`, and it works. Never call `aster <file>`: that starts a
second agent.
