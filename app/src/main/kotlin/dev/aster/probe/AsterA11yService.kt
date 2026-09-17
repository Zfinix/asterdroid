package dev.aster.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.app.ActivityManager
import android.app.KeyguardManager
import android.media.AudioManager
import android.net.LocalServerSocket
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Observation and action over the accessibility tree, served on a local socket. */
class AsterA11yService : AccessibilityService() {

    private val marked = mutableListOf<AccessibilityNodeInfo?>()
    private val overlay by lazy { MarksOverlay(this) }
    private var socket: LocalServerSocket? = null
    /** True between connect and destroy: the socket is kept alive while this is. */
    @Volatile private var serving = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSig: List<String> = emptyList()
    private var lastPkg = ""

    /** The screen as the caller last had it in full, row for row; what a change-only receipt builds on. */
    private var seenSig: List<String> = emptyList()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val pace by lazy { Pace(this) }
    @Volatile private var lastShotAt = 0L

    /**
     * A receipt that said nothing changed, after a wait sized to how fast this
     * app usually answers. Kept until the next verb, which checks whether the
     * screen moved after all.
     */
    private class Unconfirmed(val sig: List<String>, val pkg: String, val sentAt: Long) {
        @Volatile var movedAt = 0L
    }
    @Volatile private var unconfirmed: Unconfirmed? = null
    private var lastGrid: Grid? = null
    private var lastOcr: List<Block> = emptyList()
    private var lastFrame: Bitmap? = null
    private var lastFrameAt = 0L
    private var lastBlobs: List<Vision.Blob> = emptyList()
    private var finger: GestureDescription.StrokeDescription? = null
    private var fingerAt: PointF? = null

    /**
     * The mirror's own touch, kept apart from the agent's `finger` so a person
     * dragging on the viewer and an agent mid-gesture never take each other's
     * stroke. One thread, so the down, the moves and the up stay in order
     * without ever holding the lock the agent's verbs wait under.
     */
    private val touches = Executors.newSingleThreadExecutor { r -> Thread(r, "aster-live") }
    private val liveLock = Any()
    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveAt: PointF? = null

    @Volatile private var lastEventNanos = 0L
    @Volatile private var eventCount = 0
    private val eventSources = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
        Install.refresh(this)
        serving = true
        // The system can connect the service more than once per process (a
        // settings toggle does it), and a second binder only fights the first
        // for the name. One listener, started once, is enough.
        if (socket == null) serveSocket()
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        "$PKG.CAP" -> Log.i(TAG, map())
                        "$PKG.BENCH" -> bench(i.getIntExtra("n", 20))
                        "$PKG.CTL" -> control(i.getStringExtra("cmd").orEmpty())
                    }
                }
            },
            IntentFilter().apply {
                addAction("$PKG.CAP")
                addAction("$PKG.BENCH")
                addAction("$PKG.CTL")
            },
            Context.RECEIVER_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Our own overlay and notifications are not the screen settling.
        if (event.packageName == PKG) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastEventNanos = System.nanoTime()
                eventCount++
                val pkg = event.packageName?.toString() ?: "?"
                eventSources.merge(pkg, 1, Int::plus)
                unconfirmed?.let { if (it.movedAt == 0L && it.pkg == pkg) it.movedAt = System.currentTimeMillis() }
            }
        }
    }

    /**
     * `adb shell am broadcast -a dev.aster.probe.CTL --es cmd "shot grid"`: drive
     * a verb from the host, since the socket is off limits to the shell user.
     * The reply and any shot land under the external files dir for `adb pull`.
     */
    private fun control(cmd: String) = thread(name = "ctl") {
        val reply = handle(cmd)
        val out = java.io.File(getExternalFilesDir(null), "ctl").apply { mkdirs() }
        java.io.File(out, "reply.txt").writeText(reply)
        Regex("^shot (\\S+)").find(reply)?.groupValues?.get(1)?.let { path ->
            java.io.File(path).copyTo(java.io.File(out, "shot.png"), overwrite = true)
        }
        Log.i(TAG, "ctl: $cmd -> ${reply.lineSequence().firstOrNull()}")
    }

    /** Who is keeping the screen busy, over one settle budget. For tuning. */
    private fun events(): String {
        eventSources.clear()
        SystemClock.sleep(SETTLE_BUDGET_MS.toLong())
        val lines = eventSources.entries.sortedByDescending { it.value }
            .joinToString("") { "  ${it.key} ${it.value}\n" }
        return "events in ${SETTLE_BUDGET_MS}ms:\n$lines"
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serving = false
        // Closing breaks accept(), which ends the thread and frees the name.
        runCatching { socket?.close() }
        super.onDestroy()
    }

    // The abstract namespace keeps the socket off the filesystem, so there is no
    // path to protect and no permissions to get wrong.
    private fun serveSocket() = thread(name = SOCKET) {
        // As long as the service is connected there must be a listener. A name
        // that is busy is a previous listener still unwinding, which can take
        // longer than any fixed number of tries; giving up left the service
        // bound and answering nothing, so this waits it out instead.
        while (serving) {
            var server: LocalServerSocket? = null
            for (attempt in 1..BIND_TRIES) {
                server = runCatching { LocalServerSocket(SOCKET) }.getOrNull()
                if (server != null) break
                Log.i(TAG, "socket busy, retry $attempt")
                SystemClock.sleep(BIND_RETRY_MS)
            }
            val listening = server ?: run {
                Log.w(TAG, "@$SOCKET still busy after $BIND_TRIES tries, waiting")
                SystemClock.sleep(BIND_WAIT_MS)
                null
            } ?: continue
            socket = listening
            Log.i(TAG, "socket listening on @$SOCKET")
            serve(listening)
        }
        Log.i(TAG, "socket thread done")
    }

    /** One listener's whole life: accept until the socket is closed under it. */
    private fun serve(listening: LocalServerSocket) {
        while (true) {
            val client = runCatching { listening.accept() }.getOrNull() ?: break
            val line = runCatching { client.inputStream.bufferedReader().readLine().orEmpty() }
                .getOrNull()
                ?.trim()
            if (line == null) {
                runCatching { client.close() }
                continue
            }
            // The mirror's reply is the video itself, so that connection stays
            // open and is answered by the encoder rather than by a line of text.
            if (line.startsWith("stream")) {
                ActivityLog.verb(this, line)
                MirrorAutoAccept.arm(this)
                Mirror.attach(this, line, client)
                continue
            }
            // A finger on the mirror is a person touching the screen, not an
            // agent asking a question. It skips the wake and the log, and runs
            // off the accept loop so it is never behind an agent verb that is
            // waiting a second and a half for the screen to go quiet.
            if (line.startsWith("live")) {
                touches.execute {
                    client.use {
                        runCatching {
                            it.outputStream.apply { write(dispatch(line).toByteArray()); flush() }
                        }
                    }
                }
                continue
            }
            client.use {
                runCatching {
                    it.outputStream.apply {
                        write(handle(line).toByteArray())
                        flush()
                    }
                }.onFailure { e -> Log.w(TAG, "request failed: $e") }
            }
        }
        // Only clear the field when this instance still owns it, or a newer
        // listener gets wiped out by an older one shutting down.
        if (socket === listening) socket = null
        runCatching { listening.close() }
        Log.i(TAG, "socket closed")
    }

    /**
     * With the display off there are no windows to read and nowhere for a tap
     * to land, and a phone left on a desk is off by default. So this is the
     * first thing every verb needs, not a corner case.
     */
    private fun wake() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        @Suppress("DEPRECATION")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "aster:screen",
        ).acquire(WAKE_MS)
        // The window list is populated as the display comes up, not when the
        // lock is taken, so a read straight after this one still sees nothing.
        val deadline = System.currentTimeMillis() + WAKE_SETTLE_MS
        while (System.currentTimeMillis() < deadline && rootInActiveWindow == null) {
            SystemClock.sleep(FOREGROUND_POLL_MS)
        }
    }

    /** A locked screen reads fine and taps nothing behind it; say so once. */
    private fun lockNote(): String =
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            "note: the screen is locked, so this is the lock screen and taps will " +
                "not reach the app behind it\n"
        } else {
            ""
        }

    private fun handle(line: String): String {
        ActivityLog.verb(this, line)
        wake()
        val verb = line.substringBefore(' ')
        val late = landedLate()
        val reply = lockNote() + when {
            late == null -> dispatch(line)
            verb in READS -> late.first + dispatch(line)
            else -> late.first + held(verb, late.second)
        }
        ActivityLog.agent(this, reply.lineSequence().firstOrNull().orEmpty())
        return reply
    }

    /**
     * A no-change receipt overturned: the app answered after the wait gave up
     * on it. The verb that follows was chosen believing the action failed, and
     * repeating a send or a payment that did go through is the one thing a
     * shorter wait must never cause. The late answer is also the sample that
     * lengthens the wait for this app.
     */
    @Synchronized private fun landedLate(): Pair<String, Snapshot>? {
        val doubt = unconfirmed ?: return null
        unconfirmed = null
        val lateMs = doubt.movedAt - doubt.sentAt
        if (doubt.movedAt == 0L || lateMs > LATE_MS) return null
        val snap = capture()
        val now = snap.signature()
        if (now == doubt.sig) return null
        pace.landed(doubt.pkg, lateMs.toInt())
        val was = doubt.sig.toHashSet()
        val nowSet = now.toHashSet()
        val added = now.count { it !in was }
        val removed = doubt.sig.count { it !in nowSet }
        return "note: the last action did land after all, ${lateMs}ms after it was sent (+$added -$removed); " +
            "the wait for this app is now longer\n" to snap
    }

    /** The screen as it really is now, in place of a verb that was chosen for one that never was. */
    private fun held(verb: String, snap: Snapshot): String {
        remember(snap)
        return "held: `$verb` was not run, because it was chosen when that action looked like it failed. " +
            "Send it again if it is still wanted.\npkg=${snap.pkg} elements=${snap.nodes.size}\n" + show(snap)
    }

    private fun dispatch(line: String): String = try {
        val (verb, rest) = line.split(Regex("\\s+"), limit = 2)
            .let { it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty() }
        when (verb) {
            "map", "screen" -> map()
            "find" -> find(rest.trim())
            "tap" -> tap(rest.trim())
            "do" -> chain(rest)
            "press" -> press(coords(rest))
            "wait" -> waitFor(rest.trim())
            "later" -> WakeReceiver.schedule(this, rest)
            "swipe" -> swipe(rest.trim())
            "drag", "slide" -> drag(rest.trim())
            "hold" -> hold(rest.trim())
            "finger" -> finger(rest.trim())
            "live" -> live(rest.trim())
            "pinch" -> pinch(rest.trim())
            "clear" -> clear()
            "text" -> act(-1, rest)
            "type" -> type(rest)
            "marks" -> marks(rest.trim())
            "notes" -> notes()
            "alerts" -> Alerts.command(this, rest)
            "scroll" -> scroll(rest.trim())
            "ocr" -> ocr()
            "locate" -> locate(rest.trim())
            "aim" -> aim(rest.trim())
            "shot" -> shot(rest.trim())
            "apps" -> Shortcuts.apps(this, rest.trim())
            "open" -> opened(rest.trim())
            "settings" -> handOff { Shortcuts.settings(this, rest) }
            "dial", "sms", "url", "web", "place", "search" ->
                handOff { Shortcuts.intent(this, "$verb $rest") }
            "emergency" -> emergency(rest.trim())
            "install" -> install(rest.trim())
            "wallpaper" -> Wallpaper.set(this, rest.trim())
            "alarm" -> Shortcuts.alarm(this, rest)
            "timer" -> Shortcuts.timer(this, rest)
            "event" -> handOff { Shortcuts.event(this, rest) }
            "quicksettings" -> global(GLOBAL_ACTION_QUICK_SETTINGS, "quick settings")
            "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS, "notification shade")
            "key" -> key(rest.trim())
            "volume" -> volume(rest.trim())
            "media" -> media(rest.trim())
            "restart" -> restart(rest.trim())
            "events" -> events()
            "pace" -> pace(rest.trim())
            "capture" -> capture(rest.trim())
            "help", "--help", "-h" -> HELP
            else -> "error: unknown verb '$verb'; `help` lists them\n"
        }
    } catch (e: StaleRef) {
        "error: ${e.message}\n"
    } catch (t: Throwable) {
        "error: $t\n"
    }

    /** `105 1424` and `105,1424` both mean a pixel. */
    private fun coords(spec: String): String = spec.trim().replace(Regex("\\s+"), ",")

    /** An index, a pixel, a cell, an ocr block or a blob; anything else is the text on the element. */
    private fun tap(spec: String): String {
        val at = coords(spec)
        return when {
            at.toIntOrNull() != null -> act(at.toInt(), null)
            PIXELS.matches(at) || Grid.isCell(at) || OCR_REF.matches(at) || BLOB_REF.matches(at) -> tapAt(at)
            else -> tapLabel(spec.trim('"', '\'', ' '))
        }
    }

    /**
     * Tap by what the element says, resolved on a read taken as the tap runs.
     * An index only holds for the map it came from, so a route planned ahead
     * (`do tap Network; tap Wi-Fi`) can only name its targets this way.
     */
    @Synchronized private fun tapLabel(label: String): String {
        if (label.isEmpty()) return "error: tap wants an element index, x,y, a cell like F7, o3, b0, or the text on the element\n"
        val snap = capture()
        remember(snap)
        val hits = labelled(snap, label)
        return when (hits.size) {
            1 -> act(hits.single(), null)
            0 -> "error: nothing on screen says \"$label\"\n" + nearest(label, snap) +
                "pkg=${snap.pkg} elements=${snap.nodes.size}\n" + show(snap)
            else -> "error: \"$label\" is on ${hits.size} elements; tap one by index\n" +
                hits.joinToString("") { "%3d %s\n".format(it, snap.nodes[it].line()) }
        }
    }

    /**
     * Elements whose text or description is the label, else contains it. A row
     * and the label drawn inside it are one target, and the tap walks up from
     * the label to the row anyway, so only the innermost of a nested pair counts.
     */
    private fun labelled(snap: Snapshot, label: String): List<Int> {
        val says = { i: Int -> listOfNotNull(snap.nodes[i].text, snap.nodes[i].desc).map { it.trim() } }
        val exact = snap.nodes.indices.filter { i -> says(i).any { it.equals(label, true) } }
        val hits = exact.ifEmpty { snap.nodes.indices.filter { i -> says(i).any { it.contains(label, true) } } }
        return hits.filterNot { i ->
            val outer = snap.nodes[i].bounds
            // Equal bounds are one target read twice; the walk reaches the deeper one later.
            hits.any { j -> j != i && outer.contains(snap.nodes[j].bounds) && (j > i || outer != snap.nodes[j].bounds) }
        }
    }

    /**
     * `do tap Network; tap Wi-Fi; wait Connected`: the steps run here, one after
     * another, so a route already known costs one model round instead of one per
     * step. It stops at the first step that fails or changes nothing, because
     * every step after it was planned for a screen that never came.
     */
    private fun chain(script: String): String {
        val steps = script.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return "error: do wants steps separated by ;, like do tap Network; tap Wi-Fi\n"
        val log = StringBuilder()
        for ((i, step) in steps.withIndex()) {
            val verb = step.substringBefore(' ')
            val at = "step ${i + 1}/${steps.size} ($step)"
            if (verb == "do" || verb == "live") return log.append("error: $at: $verb cannot run inside do\n").toString()
            wake()
            if (i == steps.lastIndex) return log.append("$at\n").append(dispatch(step)).toString()
            // Nobody reads this step's screen, so the next one must not diff against it as if they had.
            val seen = seenSig
            val reply = dispatch(step)
            seenSig = seen
            stall(reply)?.let { why ->
                val rest = steps.drop(i + 1)
                return log.append("stopped at $at: $why\n")
                    .append("not run: ").append(rest.joinToString("; ")).append('\n')
                    .append(reply).toString()
            }
            val lines = reply.lineSequence().filter { it.isNotBlank() }
            log.append(at).append(": ")
                .append(lines.firstOrNull { it.startsWith("changed:") } ?: lines.firstOrNull().orEmpty())
                .append('\n')
        }
        return log.toString()
    }

    /** The line that says a step did not land, if one does. A blind screen's pixel diff is left to the caller. */
    private fun stall(reply: String): String? = reply.lineSequence().firstOrNull {
        it.startsWith("error:") || it.startsWith("warning:") ||
            (it.startsWith("receipt:") && !it.startsWith("receipt: posted") && !it.startsWith("receipt: locked"))
    }

    /** `pace [reset]`: how long the waits are for the app in front, and where the figures come from. */
    private fun pace(arg: String): String = when (arg) {
        "reset" -> { pace.reset(); "pace reset: the fixed waits apply until touches are timed again\n" }
        "" -> pace.describe(rootInActiveWindow?.packageName?.toString() ?: lastPkg)
        else -> "error: pace takes nothing, or reset\n"
    }

    /**
     * `capture on|off`: the screen capture the mirror uses, held with nobody
     * watching so canvas reads take frames from it instead of rate-limited
     * screenshots. It shows the phone's recording indicator while it runs.
     */
    private fun capture(arg: String): String = when (arg) {
        "on" -> {
            MirrorAutoAccept.arm(this)
            if (Mirror.ensure(this)) "capture on: canvas reads now come off the screen capture\n"
            else "error: screen capture was not allowed\n"
        }
        "off" -> when {
            !Mirror.capturing -> "capture is already off\n"
            Mirror.viewers() > 0 -> "capture left on: ${Mirror.viewers()} watching the mirror\n"
            else -> { Mirror.stop(); "capture off: canvas reads use screenshots again\n" }
        }
        "" -> if (Mirror.capturing) "capture is on\n" else "capture is off\n"
        else -> "error: capture takes on or off\n"
    }

    /** Block until text is on screen or the budget runs out: one call, not sleep-and-reread. */
    private fun waitFor(arg: String): String {
        val words = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val asked = words.lastOrNull()?.toIntOrNull()
        val needle = (if (asked != null) words.dropLast(1) else words).joinToString(" ")
        if (needle.isEmpty()) return "error: wait needs the text to wait for, then optional seconds\n"
        val secs = (asked ?: WAIT_DEFAULT_SECS).coerceIn(1, WAIT_MAX_SECS)
        val deadline = System.currentTimeMillis() + secs * 1000L
        val started = System.currentTimeMillis()
        var nextOcr = 0L
        while (true) {
            val hit = find(needle)
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            if (!hit.contains("matches=0")) return "found after %.1fs\n".format(elapsed) + hit
            // A blind tree never matches, so read the pixels instead, at a
            // pace the screenshot rate limit allows.
            if (marked.isEmpty() && System.currentTimeMillis() >= nextOcr) {
                nextOcr = System.currentTimeMillis() + WAIT_OCR_MS
                val blocks = grabSync()?.let(::recognize).orEmpty()
                if (blocks.any { it.text.contains(needle, ignoreCase = true) }) {
                    lastOcr = blocks
                    return "found after %.1fs (by ocr; the tree is blind here)\n".format(elapsed) + renderOcr(blocks)
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                val snap = capture()
                remember(snap)
                val head = "error: \"$needle\" did not appear within ${secs}s\n" +
                    if (asked != null && asked > WAIT_MAX_SECS) {
                        "the wait was capped at ${WAIT_MAX_SECS}s; wait again if it is still coming\n"
                    } else ""
                return head + nearest(needle, snap) +
                    "pkg=${snap.pkg} elements=${snap.nodes.size}\n" + show(snap)
            }
            // Nothing can have appeared while the screen is silent, so read
            // again as soon as it moves rather than on a clock.
            val seen = eventCount
            val nextRead = System.currentTimeMillis() + WAIT_POLL_MS
            SystemClock.sleep(WAIT_TICK_MS)
            while (System.currentTimeMillis() < nextRead && eventCount == seen) SystemClock.sleep(WAIT_TICK_MS)
        }
    }

    /** Numbered, actionable elements. The index is the handle the caller acts on. */
    @Synchronized private fun map(): String {
        val started = System.nanoTime()
        val snap = capture()
        remember(snap)
        val head = "pkg=%s elements=%d capture_ms=%.1f\n"
            .format(snap.pkg, snap.nodes.size, (System.nanoTime() - started) / 1e6)
        return head + show(snap)
    }

    /** The same list, filtered by text, content-desc or id. */
    @Synchronized private fun find(needle: String): String {
        if (needle.isEmpty()) return "error: find needs something to look for\n"
        val snap = capture()
        remember(snap)
        val lines = snap.lines()
        val hits = snap.nodes.indices.filter { i ->
            val n = snap.nodes[i]
            "${n.text.orEmpty()} ${n.desc.orEmpty()} ${n.id.orEmpty()}"
                .contains(needle, ignoreCase = true)
        }
        return "pkg=${snap.pkg} matches=${hits.size} for \"$needle\"\n" +
            hits.joinToString("") { "%3d %s\n".format(it, lines[it].trim()) }
    }

    /**
     * What is on screen that the needle nearly matched. Waiting for a word that
     * describes the screen ("Installed" while it says "Install", "results",
     * "Settings") is how most waits are lost, and a timeout that names the near
     * miss turns a dead end into the next tap.
     */
    private fun nearest(needle: String, snap: Snapshot): String {
        val want = needle.lowercase()
        val close = snap.nodes.withIndex().mapNotNull { (i, n) ->
            val label = (n.text ?: n.desc)?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            val have = label.lowercase()
            val related = have.startsWith(want.take(NEAR_PREFIX)) ||
                want.startsWith(have.take(NEAR_PREFIX)) ||
                have.split(' ').any { it.isNotEmpty() && want.startsWith(it) }
            if (related && !have.contains(want)) i to label else null
        }.take(NEAR_MAX)
        if (close.isEmpty()) return ""
        return "closest on screen: " +
            close.joinToString(", ") { (i, label) -> "\"$label\" ($i)" } + "\n"
    }

    /** Act on a mapped element, then re-read and report what actually changed. */
    @Synchronized private fun act(index: Int, textToType: String?): String {
        var node: AccessibilityNodeInfo? = null
        var label = "focused field"
        if (index >= 0) {
            node = marked.getOrNull(index)
                ?: return "error: no element $index; run map first\n"
            node.refresh()
            label = node.describe()
            if (textToType == null && !node.isClickable) {
                node.clickableAncestor()?.let {
                    label = "${node!!.describe()} via its row"
                    node = it
                }
            }
        }
        val before = eventCount
        val posted = if (textToType != null) {
            val target = node ?: focusedEditable() ?: return "error: no text field focused\n"
            label = "type into ${target.describe()}"
            target.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        textToType,
                    )
                },
            )
        } else {
            node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) || node!!.tapCenter()
        }
        return if (posted) settleAndDiff(label, before) else "receipt: refused ($label)\n"
    }

    /**
     * Dial, then press call. The platform refuses ACTION_CALL for emergency
     * numbers, but the dialer's own button is an ordinary clickable element and
     * the person who asked for this is not here to press it.
     */
    @Synchronized private fun emergency(number: String): String {
        val opened = Shortcuts.emergency(this, number)
        if (opened.startsWith("error")) return opened
        awaitForeground(packageName, DIAL_LAUNCH_MS)
            ?: return opened + "the dialer never came to the front\n"
        val before = eventCount
        val snap = capture()
        remember(snap)
        val index = callButton(snap)
            ?: return opened + "no call button on screen. Everything that is:\n" + show(snap)
        val node = marked.getOrNull(index)?.also { it.refresh() }
            ?: return opened + "the call button went stale before it could be pressed\n"
        val pressed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || node.tapCenter()
        return opened + if (pressed) settleAndDiff("press call", before)
        else "element $index is the call button and it refused the press\n"
    }

    /** Open the store on the app, then map it so the Install button is in hand. */
    @Synchronized private fun install(query: String): String {
        val opened = Shortcuts.install(this, query)
        if (opened.startsWith("error")) return opened
        awaitForeground(packageName, STORE_LAUNCH_MS)
            ?: return opened + "the store never came to the front\n"
        return opened + map()
    }

    /**
     * By id first, then by label, and never on a loose word match: the dialpad
     * container is itself clickable and has "dial" in its id, so a substring
     * search over everything presses the keypad instead of the call button.
     */
    private fun callButton(snap: Snapshot): Int? {
        val clickable = snap.nodes.indices.filter { snap.nodes[it].clickable }
        for (hint in CALL_IDS) {
            clickable.firstOrNull { snap.nodes[it].id.orEmpty().contains(hint, true) }
                ?.let { return it }
        }
        val labelled = { i: Int ->
            val n = snap.nodes[i]
            "${n.text.orEmpty()} ${n.desc.orEmpty()}".trim()
        }
        return clickable.firstOrNull { labelled(it).equals("call", true) }
            ?: clickable.firstOrNull { labelled(it).contains("call", true) }
    }

    /** Type through the IME, which reaches fields that refuse ACTION_SET_TEXT. */
    private fun type(text: String): String {
        if (text.isEmpty()) return "error: type needs something to type\n"
        if (!AsterIme.isReady) {
            return "error: the Aster keyboard is not the active input method\n"
        }
        val before = eventCount
        return if (AsterIme.commit(text)) settleAndDiff("type \"$text\"", before)
        else "receipt: refused (no field focused)\n"
    }

    /** Draw the map's indices over the elements, so a screenshot is self-labelling. */
    @Synchronized private fun marks(arg: String): String = when (arg) {
        "off" -> { main.post { overlay.hide() }; "marks off\n" }
        else -> {
            val snap = capture()
            remember(snap)
            paint(snap)
            "marks on: ${snap.nodes.size} drawn\n"
        }
    }

    private fun paint(snap: Snapshot) {
        val marks = snap.nodes.mapIndexed { i, n ->
            MarksOverlay.Mark(i, n.bounds, n.clickable || n.editable || n.scrollable)
        }
        main.post { overlay.show(marks) }
    }

    private fun notes(): String {
        val events = AsterNotifications.drain()
        if (events.isEmpty()) return "no notifications seen (is the listener enabled?)\n"
        return "notifications=${events.size}\n" + events.joinToString("") { "  ${it.render()}\n" }
    }

    /**
     * Reach content below the fold. Without this the agent can only act on what
     * happens to be on screen, which is not enough to finish most tasks.
     */
    @Synchronized private fun scroll(arg: String): String {
        val parts = arg.split(" ").filter { it.isNotEmpty() }
        val direction = parts.lastOrNull() ?: "down"
        val forward = when (direction) {
            "down", "forward" -> true
            "up", "back", "backward" -> false
            else -> return "error: scroll takes up or down\n"
        }
        val index = parts.firstOrNull()?.toIntOrNull()
        val target = if (index != null) {
            marked.getOrNull(index) ?: return "error: no element $index; run map first\n"
        } else {
            biggestScrollable() ?: return "error: nothing on this screen scrolls\n"
        }
        if (!target.isScrollable) return "error: element ${index ?: "?"} does not scroll\n"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val before = eventCount
        return if (target.performAction(action)) settleAndDiff("scroll $direction", before)
        else "receipt: refused (already at the $direction limit)\n"
    }

    /** The scrollable covering the most screen is the one the user means. */
    private fun biggestScrollable(): AccessibilityNodeInfo? =
        marked.filterNotNull()
            .filter { it.isScrollable }
            .maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.let { r -> r.width() * r.height() } }

    /**
     * A PNG on disk, so the run can be shown rather than described. With no
     * argument the whole screen; with an element index or `l,t-r,b`, just that
     * part of it. `grid [px]` draws lettered cells over the whole screen, and
     * `grid <cell|cell-cell|l,t-r,b>` zooms into that part with the lines
     * labelled in screen pixels, for aiming at something smaller than a cell.
     */
    private fun shot(spec: String): String {
        val words = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val gridded = words.firstOrNull() == "grid"
        val args = if (gridded) words.drop(1) else words
        val cellPx = if (gridded) args.firstOrNull()?.toIntOrNull() else null
        // `shot jpeg <1-100> <width>`: a scaled JPEG for the mirror, where the
        // rate limit caps fps and bytes per frame are the only lever left.
        val jpeg = if (!gridded && args.firstOrNull() == "jpeg") {
            val q = args.getOrNull(1)?.toIntOrNull()
                ?: return "error: shot jpeg needs a quality 1-100 and a width, like shot jpeg 60 720\n"
            val w = args.getOrNull(2)?.toIntOrNull()
                ?: return "error: shot jpeg needs a width, like shot jpeg 60 720\n"
            if (q !in 1..100 || w < 100)
                return "error: shot jpeg wants quality 1-100 and a width of at least 100\n"
            q to w
        } else {
            null
        }
        val rest = when {
            jpeg != null -> args.drop(3)
            cellPx != null -> args.drop(1)
            else -> args
        }
        val target = rest.firstOrNull()
        val index = target?.toIntOrNull()
        val crop = when {
            target == null -> null
            index != null -> marked.getOrNull(index)?.let { node ->
                Rect().also { node.getBoundsInScreen(it) }
                    .also { it.inset(-CROP_PADDING, -CROP_PADDING) }
            } ?: return "error: no element $index; run map first\n"
            else -> region(target) ?: cells(target)
                ?: return "error: shot takes an element index, l,t-r,b, or a grid cell like R3 or Q2-S4\n"
        }
        val done = CountDownLatch(1)
        var out = "error: screenshot never returned\n"
        grab { bitmap ->
            out = when {
                bitmap == null -> "error: could not read the screenshot buffer\n"
                jpeg != null -> writeJpeg(bitmap, jpeg.first, jpeg.second)
                gridded && crop != null -> {
                    val clip = Rect(0, 0, bitmap.width, bitmap.height)
                    if (!clip.intersect(crop)) {
                        "error: that region is off screen\n"
                    } else {
                        val (zoomed, legend) = Grid(bitmap.width, bitmap.height).zoom(bitmap, clip)
                        writePng(zoomed, null) + legend
                    }
                }
                gridded -> Grid(bitmap.width, bitmap.height, cellPx).let {
                    lastGrid = it
                    writePng(it.draw(bitmap), null) + it.legend()
                }
                else -> writePng(bitmap, crop)
            }
            done.countDown()
        }
        done.await(OCR_BUDGET_SECONDS, TimeUnit.SECONDS)
        return out
    }

    /** `R3` or `Q2-S4` as a rectangle, with half a cell of margin so a thing on the line is still in it. */
    private fun cells(spec: String): Rect? {
        val ends = spec.split('-', limit = 2)
        val from = ends[0]
        val to = ends.getOrElse(1) { from }
        val grid = currentGrid()
        val a = grid.centre(from) ?: return null
        val b = grid.centre(to) ?: return null
        return Rect(
            (min(a.x, b.x) - grid.cell).toInt(),
            (min(a.y, b.y) - grid.cell).toInt(),
            (max(a.x, b.x) + grid.cell).toInt(),
            (max(a.y, b.y) + grid.cell).toInt(),
        )
    }

    /** The grid a `shot grid` would draw right now, so cell names resolve the same way. */
    private fun screenGrid(): Grid {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }
        return Grid(bounds.width(), bounds.height())
    }

    /** The grid cell names refer to: the last one drawn while the screen is still that shape, else the default. */
    private fun currentGrid(): Grid {
        val screen = screenGrid()
        val last = lastGrid ?: return screen
        return if (last.width == screen.width && last.height == screen.height) last else screen
    }

    private fun region(arg: String): Rect? {
        val m = Regex("(\\d+),(\\d+)-(\\d+),(\\d+)").matchEntire(arg.filterNot { it == ' ' })
            ?: return null
        val (left, top, right, bottom) = m.destructured
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun writePng(bitmap: Bitmap, crop: Rect?): String {
        val clip = Rect(0, 0, bitmap.width, bitmap.height)
        if (crop != null && !clip.intersect(crop)) return "error: that region is off screen\n"
        val framed = if (crop == null) bitmap
        else Bitmap.createBitmap(bitmap, clip.left, clip.top, clip.width(), clip.height())
        val file = java.io.File(cacheDir, "screen-${System.currentTimeMillis()}.png")
        return runCatching {
            file.outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // World-readable: the agent runs as the same uid, but a reader
            // outside it would otherwise get nothing.
            file.setReadable(true, false)
            "shot ${file.absolutePath} (${framed.width}x${framed.height}, ${file.length()} bytes)\n"
        }.getOrElse { "error: could not write the png: $it\n" }
    }

    /** A scaled JPEG for the mirror: same capture, fewer bytes, faster decode. */
    private fun writeJpeg(bitmap: Bitmap, quality: Int, width: Int): String {
        val h = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtMost(bitmap.height)
        val scaled = if (width < bitmap.width) Bitmap.createScaledBitmap(bitmap, width, h, true) else bitmap
        val file = java.io.File(cacheDir, "screen-${System.currentTimeMillis()}.jpg")
        return runCatching {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            file.setReadable(true, false)
            // The frame is scaled but a tap is not, so the mirror needs both sizes.
            "shot ${file.absolutePath} (${scaled.width}x${scaled.height}, ${file.length()} bytes) " +
                "screen ${bitmap.width}x${bitmap.height}\n"
        }.getOrElse { "error: could not write the jpeg: $it\n" }
    }

    /**
     * One screenshot, handed back as a bitmap ML Kit and PNG can both take.
     * The system refuses shots closer together than it allows, and how close
     * that is differs by Android version, so the spacing is learned from the
     * first refusal and kept to after that.
     */
    private fun grab(attempt: Int = 0, then: (Bitmap?) -> Unit) {
        if (attempt == 0 && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            val early = lastShotAt + pace.shotGap - System.currentTimeMillis()
            if (early > 0) SystemClock.sleep(early)
        }
        val askedAt = System.currentTimeMillis()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    lastShotAt = askedAt
                    val raw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    shot.hardwareBuffer.close()
                    then(raw?.copy(Bitmap.Config.ARGB_8888, false))
                }

                override fun onFailure(errorCode: Int) {
                    // The system rate-limits screenshots; a shot right after an
                    // ocr trips it. Wait out the interval rather than fail.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < SHOT_RETRIES) {
                        val since = askedAt - lastShotAt
                        if (lastShotAt > 0 && since < SHOT_GAP_MAX_MS) {
                            pace.shotGap = max(pace.shotGap, since.toInt() + SHOT_GAP_MARGIN_MS)
                        }
                        val retry = (pace.shotGap - since).coerceIn(SHOT_RETRY_MIN_MS, SHOT_GAP_MAX_MS)
                        main.postDelayed({ grab(attempt + 1, then) }, retry)
                        return
                    }
                    Log.w(TAG, "screenshot refused (code $errorCode)")
                    then(null)
                }
            },
        )
    }

    /**
     * Pixels for the parts with no semantics. A canvas map or a game is a single
     * opaque node in the tree; OCR is the only way to read what it draws.
     */
    private fun ocr(): String {
        if (marked.isEmpty()) settleBlind()
        val frame = grabSync() ?: return "error: could not read the screenshot buffer\n"
        val blocks = recognize(frame)
        lastOcr = blocks
        return renderOcr(blocks)
    }

    private class Block(val text: String, val bounds: Rect?)

    /** A handle that matched the grammar but points at nothing: name the read to re-run. */
    private class StaleRef(message: String) : Exception(message)

    /** Text blocks on a frame, waited for. Empty when ML Kit fails, which is logged. */
    private fun recognize(frame: Bitmap): List<Block> {
        val done = CountDownLatch(1)
        var blocks: List<Block> = emptyList()
        recognizer
            .process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { text ->
                blocks = text.textBlocks.map { Block(it.text.oneLine(), it.boundingBox) }
                done.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ocr failed: $e")
                done.countDown()
            }
        done.await(OCR_BUDGET_SECONDS, TimeUnit.SECONDS)
        return blocks
    }

    /** `o<n>` is the handle a tap or drag takes for a block, tied to this read. */
    private fun renderOcr(blocks: List<Block>): String = buildString {
        append("ocr blocks=").append(blocks.size).append(" (tap o0, drag o0 o2, aim o0)\n")
        blocks.forEachIndexed { i, b ->
            append(" o").append(i).append(' ')
            append('"').append(b.text).append('"')
            b.bounds?.let { r ->
                append(' ').append(r.left).append(',').append(r.top)
                append('-').append(r.right).append(',').append(r.bottom)
            }
            append('\n')
        }
    }

    /**
     * One frame for the service's own eyes, waited for: off the screen capture
     * when one is running, which has no rate limit, else a screenshot.
     */
    private fun grabSync(): Bitmap? {
        if (Mirror.capturing) {
            val g = screenGrid()
            Mirror.still(g.width, g.height, STILL_TIMEOUT_MS)?.let { return it }
        }
        val done = CountDownLatch(1)
        var frame: Bitmap? = null
        grab { frame = it; done.countDown() }
        done.await(OCR_BUDGET_SECONDS, TimeUnit.SECONDS)
        return frame
    }

    /**
     * On a blind screen the tree cannot say whether anything moved, so keep a
     * small copy of the last frame and count the pixels that changed instead.
     */
    private fun thumb(frame: Bitmap): Bitmap {
        val w = THUMB_WIDTH
        val h = (frame.height.toLong() * w / frame.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, w, h, true)
    }

    private fun changedPercent(before: Bitmap, after: Bitmap): Int {
        if (before.width != after.width || before.height != after.height) return 100
        val n = before.width * before.height
        val a = IntArray(n).also { before.getPixels(it, 0, before.width, 0, 0, before.width, before.height) }
        val b = IntArray(n).also { after.getPixels(it, 0, after.width, 0, 0, after.width, after.height) }
        var changed = 0
        for (i in 0 until n) {
            val dr = kotlin.math.abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            val dg = kotlin.math.abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            val db = kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF))
            if (dr + dg + db > PIXEL_DELTA) changed++
        }
        return changed * 100 / n
    }

    /** Before acting on a blind screen, keep a frame to compare the outcome against. */
    private fun frameBefore() {
        if (marked.isNotEmpty()) return
        grabSync()?.let { lastFrame = thumb(it); lastFrameAt = System.currentTimeMillis() }
    }

    /** What a blind screen looks like after an action: pixels moved, and what the text says now. */
    private fun observeBlind(): String {
        val frame = grabSync() ?: return "note: the tree is blind here and the screenshot failed; run ocr or shot\n"
        val now = thumb(frame)
        val before = lastFrame
        val age = (System.currentTimeMillis() - lastFrameAt) / 1000
        lastFrame = now
        lastFrameAt = System.currentTimeMillis()
        val blocks = recognize(frame)
        lastOcr = blocks
        val moved = if (before == null) {
            "no earlier frame to compare"
        } else {
            val pct = changedPercent(before, now)
            val g = screenGrid()
            val regions = Vision.changedRegions(before, now, g.width, g.height, PIXEL_DELTA)
            val where = regions.take(4).joinToString("; ") { "${it.left},${it.top}-${it.right},${it.bottom}" }
            "$pct% of pixels changed since the frame ${age}s before the action" +
                if (regions.isEmpty()) "" else "\nmoved: $where"
        }
        return "changed: tree blind (0 elements); $moved\n" + renderOcr(blocks)
    }

    /**
     * A blind screen has no accessibility events, so waiting for them never
     * returns. Wait on the pixels instead: grab thumbnails until two in a row
     * barely differ, so a read or a measurement lands on a still frame, not
     * mid-animation. Bounded, because a spinner never stops.
     */
    private fun settleBlind() {
        val deadline = System.currentTimeMillis() + SETTLE_BUDGET_MS
        var prev = grabSync()?.let(::thumb) ?: return
        var prevAt = System.currentTimeMillis()
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            SystemClock.sleep(STILL_POLL_MS)
            val next = grabSync()?.let(::thumb) ?: return
            if (changedPercent(prev, next) <= STILL_PERCENT) {
                // The pair is only as far apart as frames can be taken, so the
                // calm is counted from the earlier frame, not from this read.
                if (stableSince == 0L) stableSince = prevAt
                if (System.currentTimeMillis() - stableSince >= STILL_HOLD_MS) return
            } else {
                stableSince = 0L
            }
            prev = next
            prevAt = System.currentTimeMillis()
        }
    }

    /** The playfield, with the status bar, tutorial captions and the left-edge controls cut off. */
    private fun tableRoi(): Rect {
        val g = screenGrid()
        return Rect(
            (g.width * 0.16f).toInt(),
            (g.height * 0.12f).toInt(),
            (g.width * 0.97f).toInt(),
            (g.height * 0.96f).toInt(),
        )
    }

    /**
     * Blobs of a colour, numbered `b0 b1 ...`, so a control the tree cannot see
     * (a ball, a coloured button on a canvas) is a target by its true centre
     * instead of a grid-cell guess. `bright` for white, or a colour name.
     */
    private fun locate(arg: String): String {
        val name = arg.ifBlank { "bright" }
        val mask = Vision.colorMask(name) ?: return "error: locate takes bright or a colour (white black red green blue yellow orange)\n"
        if (marked.isEmpty()) settleBlind()
        val frame = grabSync() ?: return "error: could not read the screenshot buffer\n"
        val found = Vision.blobs(Vision.Frame(frame, tableRoi()), 0.00008, mask)
        lastBlobs = found
        if (found.isEmpty()) return "locate $name: nothing found on the table\n"
        return "locate $name: ${found.size} found (tap b0, drag b0 b3, aim b0)\n" +
            found.mapIndexed { i, b ->
                " b$i ${b.cx},${b.cy} area=${b.area} box=${b.bounds.left},${b.bounds.top}-${b.bounds.right},${b.bounds.bottom}\n"
            }.joinToString("")
    }

    /**
     * Point the cue at a target and let the phone do the aiming: read the aim
     * line, drag the cue, read again, and correct, so it converges in a few
     * measured steps instead of the model guessing a swipe per turn. The model
     * gives the target once; the loop closes on the device.
     *
     * With no target it just reports the current reading, for checking the eye.
     */
    @Synchronized private fun aim(spec: String): String {
        settleBlind()
        val read0 = Vision.aim(Vision.Frame(grabSync() ?: return "error: no screenshot\n", tableRoi()))
            ?: return "error: no cue ball and aim line found; this may not be an aiming screen\n"
        if (spec.isBlank()) {
            return "aim reading: ball ${read0.ballX},${read0.ballY} -> tip ${read0.tipX},${read0.tipY} " +
                "(%.0f°)\n".format(Math.toDegrees(read0.angle))
        }
        val target = point(spec) ?: return "error: aim wants a target (x,y, a cell, o#, or b#)\n"
        val cx = read0.ballX.toFloat(); val cy = read0.ballY.toFloat()
        val targetAngle = atan2((target.y - cy).toDouble(), (target.x - cx).toDouble())
        val g = screenGrid()
        val radius = minOf(cx, cy, g.width - cx, g.height - cy, AIM_RADIUS.toFloat()).coerceAtLeast(AIM_MIN_RADIUS.toFloat())
        // The finger sits opposite the aim, so commanding finger angle phi aims
        // the cue at phi. Model says 1:1; the secant update learns the truth.
        fun fingerAt(phi: Double) = PointF(
            (cx - radius * kotlin.math.cos(phi)).toFloat().coerceIn(2f, g.width - 2f),
            (cy - radius * kotlin.math.sin(phi)).toFloat().coerceIn(2f, g.height - 2f),
        )
        val log = StringBuilder("aim at ${target.x.toInt()},${target.y.toInt()} (target %.0f°)\n".format(Math.toDegrees(targetAngle)))
        var phi = targetAngle
        var prevPhi = Double.NaN
        var prevAim = Double.NaN
        var gain = 1.0
        var from = fingerAt(targetAngle + AIM_PROBE)
        var residual = Double.NaN
        for (iter in 1..AIM_ITERS) {
            val to = fingerAt(phi)
            held(listOf(from, to), DRAG_MS)
            from = to
            settleBlind()
            val read = Vision.aim(Vision.Frame(grabSync() ?: break, tableRoi()))
            if (read == null) { log.append(" iter $iter: lost the aim line\n"); continue }
            val aimAngle = atan2((read.tipY - cy).toDouble(), (read.tipX - cx).toDouble())
            val err = wrapAngle(targetAngle - aimAngle)
            residual = err
            log.append(" iter $iter: aim %.0f°, off %.1f°\n".format(Math.toDegrees(aimAngle), Math.toDegrees(err)))
            if (kotlin.math.abs(err) <= AIM_TOL) break
            if (!prevAim.isNaN()) {
                val dA = wrapAngle(aimAngle - prevAim)
                val dP = phi - prevPhi
                if (kotlin.math.abs(dP) > 1e-3 && kotlin.math.abs(dA) > 1e-3) gain = (dA / dP).coerceIn(0.2, 3.0)
            }
            prevPhi = phi; prevAim = aimAngle
            phi = wrapAngle(phi + err / gain)
        }
        val done = if (!residual.isNaN() && kotlin.math.abs(residual) <= AIM_TOL) "on target" else "closest it got"
        return log.append("$done; the aim is set. Take the shot (the power bar) or run `aim` to recheck.\n").toString()
    }

    private fun wrapAngle(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2 * Math.PI
        while (x < -Math.PI) x += 2 * Math.PI
        return x
    }

    /** Launch, then let the new screen settle so the next map is not the old one. */
    private fun opened(query: String): String {
        val before = eventCount
        val result = Shortcuts.open(this, query)
        if (result.startsWith("error")) return result
        quiesce(SETTLE_BUDGET_MS, before)
        return result + map()
    }

    private fun waitForScreen() {
        quiesce(SETTLE_BUDGET_MS, eventCount)
    }

    /**
     * Every verb that starts another app has the same problem: it returns while
     * the target is still launching, so the caller's next map reads this app.
     * Wait for the foreground to change, then say where it landed.
     */
    private fun handOff(launch: () -> String): String {
        val result = launch()
        if (result.startsWith("error")) return result
        val pkg = awaitForeground(packageName, APP_LAUNCH_MS)
            ?: return result + "warning: nothing came to the front; the screen is unchanged\n"
        return result + "now showing $pkg\n"
    }

    /**
     * Cold-starting another app outruns the settle budget, and our own window
     * keeps emitting events while it does, so waiting for quiet is not enough:
     * wait for the foreground to actually leave.
     */
    private fun awaitForeground(away: String, maxMs: Long): String? {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg != null && pkg != away && pkg != SYSTEM_UI) {
                quiesce(SETTLE_BUDGET_MS, eventCount)
                return pkg
            }
            SystemClock.sleep(FOREGROUND_POLL_MS)
        }
        return null
    }

    private fun global(action: Int, label: String): String {
        val before = eventCount
        return if (performGlobalAction(action)) settleAndDiff(label, before)
        else "receipt: refused ($label)\n"
    }

    private fun key(which: String): String {
        val code = when (which) {
            "enter" -> KeyEvent.KEYCODE_ENTER
            "delete" -> KeyEvent.KEYCODE_DEL
            "tab" -> KeyEvent.KEYCODE_TAB
            else -> null
        }
        if (code != null) {
            if (!AsterIme.isReady) return "error: the Aster keyboard is not the active input method\n"
            val before = eventCount
            return if (AsterIme.key(code)) settleAndDiff("key $which", before)
            else "receipt: refused (no field focused)\n"
        }
        val action = when (which) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> return lock()
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "volume_up", "volume-up" -> return volume("up")
            "volume_down", "volume-down" -> return volume("down")
            else -> return "error: key must be back, home, recents, lock, power, " +
                "notifications, quicksettings, enter, delete or tab; " +
                "volume is `volume up|down|max|mute|<0-100>`\n"
        }
        val before = eventCount
        return if (performGlobalAction(action)) settleAndDiff("key $which", before)
        else "receipt: refused (key $which)\n"
    }

    /**
     * The only route to the power button an accessibility service has: the shell
     * cannot inject KEYCODE_POWER without INJECT_EVENTS and the power menu on
     * this device carries no lock item. Locking ends the screen, so there is
     * nothing to diff and the keyguard is the receipt.
     */
    private fun lock(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "error: key lock needs Android 9 or newer\n"
        }
        if (!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) return "receipt: refused (key lock)\n"
        val keyguard = getSystemService(KeyguardManager::class.java)
        val deadline = System.currentTimeMillis() + LOCK_CONFIRM_MS
        while (System.currentTimeMillis() < deadline && !keyguard.isKeyguardLocked) {
            SystemClock.sleep(FOREGROUND_POLL_MS)
        }
        return if (keyguard.isKeyguardLocked) {
            "receipt: locked\nchanged: the screen is off; the next verb wakes it, still locked\n"
        } else {
            "receipt: posted (key lock)\nwarning: the screen did not lock; treat as not done\n"
        }
    }

    /** `volume up|down|max|mute|<0-100> [ring|alarm|notification|call]` via AudioManager: no permission, no screen. */
    private fun volume(arg: String): String {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val words = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val what = words.firstOrNull() ?: return "current " + volumeReport(audio)
        val stream = when (words.getOrNull(1)) {
            null, "media", "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> return "error: stream must be media, ring, alarm, notification or call\n"
        }
        val max = audio.getStreamMaxVolume(stream)
        val percent = what.trimEnd('%').toIntOrNull()
        val result = runCatching {
            when {
                what == "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                what == "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                what == "max" -> audio.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
                what == "mute" || what == "min" -> audio.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                percent != null && percent in 0..100 ->
                    audio.setStreamVolume(stream, (max * percent + 50) / 100, AudioManager.FLAG_SHOW_UI)
                else -> return "error: volume takes up, down, max, mute or a percent 0-100\n"
            }
        }
        result.exceptionOrNull()?.let {
            return "error: the system refused to change that stream ($it); " +
                "ring and notification are blocked while Do Not Disturb is on\n"
        }
        return "receipt: posted (volume $arg)\nnow " + volumeReport(audio)
    }

    /** Playback keys, delivered to whichever app holds the media session. */
    private fun media(arg: String): String {
        val code = when (arg) {
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "error: media takes pause, play, toggle, next or prev\n"
        }
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        SystemClock.sleep(MEDIA_SETTLE_MS)
        val playing = if (audio.isMusicActive) "playing" else "not playing"
        return "receipt: posted (media $arg)\nnow $playing\n"
    }

    private fun volumeReport(audio: AudioManager): String {
        val streams = listOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
        )
        return streams.joinToString(" ") { (name, id) ->
            "$name=${audio.getStreamVolume(id)}/${audio.getStreamMaxVolume(id)}"
        } + "\n"
    }

    /** Leave, kill, reopen, as a person would. The kill only reaches background processes, so home first. */
    private fun restart(query: String): String {
        if (query.isBlank()) return "error: restart needs an app name\n"
        val pkg = Shortcuts.resolve(this, query)
            ?: return "error: no app matching \"$query\"; try `apps` to list them\n"
        performGlobalAction(GLOBAL_ACTION_HOME)
        awaitForeground(pkg, APP_LAUNCH_MS)
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        SystemClock.sleep(RESTART_PAUSE_MS)
        return "restarted $pkg: " + opened(pkg)
    }

    /** A receipt says the event was posted. Only the re-read says it worked. */
    private fun settleAndDiff(what: String, eventsBefore: Int): String {
        val started = System.nanoTime()
        val sentAt = System.currentTimeMillis()
        quiesce(SETTLE_BUDGET_MS, eventsBefore, lastPkg)
        val after = capture()
        val now = after.signature()
        val was = lastSig
        val wasPkg = lastPkg
        val nowSet = now.toHashSet()
        val wasSet = was.toHashSet()
        val added = now.count { it !in wasSet }
        val removed = was.count { it !in nowSet }
        remember(after)
        val head = "receipt: posted (%s)\nchanged: +%d -%d pkg=%s after_ms=%.0f\n"
            .format(what, added, removed, after.pkg, (System.nanoTime() - started) / 1e6)
        if (overlay.isShowing) paint(after)
        if (now.isEmpty()) {
            // No events reach a blind screen to say it settled, so the pixels have to.
            settleBlind()
            val receipt = "receipt: posted (%s)\n".format(what)
            return receipt + observeBlind()
        }
        if (added == 0 && removed == 0) unconfirmed = Unconfirmed(now, wasPkg, sentAt)
        val doubt = if (added == 0 && removed == 0) {
            "warning: nothing on screen changed; treat as not done\n"
        } else {
            ""
        }
        return head + doubt + changes(after, was, wasPkg)
    }

    /**
     * The screen after an action, as little of it as the caller needs. A whole
     * map is thousands of characters resent to the model on every step. When
     * the caller already holds the map this screen grew from, and every row it
     * kept still sits at its old number, only the new rows are printed.
     */
    private fun changes(after: Snapshot, before: List<String>, beforePkg: String): String {
        val now = after.signature()
        val kept = before.toHashSet()
        val inPlace = before.isNotEmpty() && before == seenSig && after.pkg == beforePkg &&
            now.indices.all { i -> now[i] !in kept || before.getOrNull(i) == now[i] }
        val fresh = now.indices.filter { now[it] !in kept }
        if (!inPlace || fresh.size * 2 > now.size) return show(after)
        seenSig = now
        if (fresh.isEmpty() && now.size == before.size) return "screen: the same as the last map\n"
        val lines = after.lines()
        return "screen: elements=${now.size}; rows not listed keep their numbers from the last map\n" +
            fresh.joinToString("") { "%3d %s\n".format(it, lines[it].trim()) }
    }

    /** The whole map, recorded as what the caller now holds. */
    private fun show(snap: Snapshot): String {
        seenSig = snap.signature()
        return snap.render()
    }

    /**
     * Wait for the action to land, then for the screen to stop moving. Waiting
     * only for quiet returns instantly when the app has not reacted yet, which
     * reads the stale tree and reports a change that did happen as no change.
     *
     * Given the app in front, both waits are sized from how fast that app has
     * answered on this phone, and this wait's timings join what is known. A
     * launch passes none and keeps the fixed budget, since how long a cold
     * start takes says nothing about how long a tap does.
     */
    private fun quiesce(maxMs: Int, eventsBefore: Int, pkg: String? = null) {
        val started = System.currentTimeMillis()
        val deadline = started + maxMs
        val landBy = if (pkg == null) deadline else min(deadline, started + pace.landWindow(pkg))
        while (System.currentTimeMillis() < landBy && eventCount == eventsBefore) {
            SystemClock.sleep(POLL_MS)
        }
        if (eventCount == eventsBefore) return
        val landedAt = System.currentTimeMillis()
        pkg?.let { pace.landed(it, (landedAt - started).toInt()) }
        // A marquee or a spinner never goes quiet; once the action has landed,
        // a bounded wait for calm is all the extra certainty there is.
        val budget = pkg?.let { pace.quietBudget(it).toLong() } ?: QUIET_BUDGET_MS
        val quietBy = min(deadline, landedAt + budget)
        while (System.currentTimeMillis() < quietBy) {
            if ((System.nanoTime() - lastEventNanos) / 1_000_000 > QUIET_MS) {
                pkg?.let { pace.settled(it, (System.currentTimeMillis() - landedAt).toInt(), quiet = true) }
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        pkg?.let { pace.settled(it, (System.currentTimeMillis() - landedAt).toInt(), quiet = false) }
    }

    private fun remember(snap: Snapshot) {
        marked.clear()
        snap.nodes.forEach { marked.add(it.ref) }
        lastSig = snap.signature()
        lastPkg = snap.pkg
    }

    /** One full read of the screen, pruned to what a caller could act on or read. */
    private fun capture(): Snapshot {
        val snap = Snapshot()
        val t0 = System.nanoTime()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        windows.forEach { w ->
            if (w.isBar()) return@forEach
            val root = w.root ?: return@forEach
            roots += root
            val focused = w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (w.isActive || w.isFocused)
            if (focused) root.packageName?.let { snap.pkg = it.toString() }
        }
        snap.windows = roots.size
        val t1 = System.nanoTime()
        roots.forEach { walk(it, 0, snap) }
        if (snap.pkg == UNKNOWN_PKG) {
            snap.pkg = roots.firstNotNullOfOrNull { it.packageName }?.toString() ?: "none"
        }
        snap.tWindows = t1 - t0
        snap.tWalk = System.nanoTime() - t1
        return snap
    }

    /** Status bar, nav bar and the floating a11y button: on every screen, never the target, and the clock ticks. */
    private fun AccessibilityWindowInfo.isBar(): Boolean {
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION) return false
        val bounds = Rect().also { getBoundsInScreen(it) }
        return bounds.height() <= BAR_MAX_PX || bounds.width() <= BAR_MAX_PX
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, snap: Snapshot) {
        if (n == null || snap.walked >= MAX_NODES) return
        snap.walked++
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        val visible = n.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
        val interactive = n.isClickable || n.isEditable || n.isScrollable ||
            n.isCheckable || n.isLongClickable
        val text = n.text?.takeIf { it.isNotEmpty() }?.toString()
        val desc = n.contentDescription?.takeIf { it.isNotEmpty() }?.toString()

        // A pure layout container carries nothing to act on; its children still
        // do, so it is skipped rather than pruned away with them.
        if (visible && (interactive || text != null || desc != null)) {
            snap.nodes += Node(
                cls = n.className.shortName(),
                id = n.viewIdResourceName.shortId(),
                text = text,
                desc = desc,
                bounds = bounds,
                depth = depth,
                clickable = n.isClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                ref = n,
            )
        }
        for (i in 0 until n.childCount) walk(child(n, i), depth + 1, snap)
    }

    /** Each child is a round trip to the app unless the subtree is prefetched with it. */
    private fun child(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) n.getChild(i, PREFETCH) else n.getChild(i)

    private fun bench(n: Int) {
        val runs = LongArray(n) {
            val t0 = System.nanoTime()
            capture()
            SystemClock.sleep(30)
            (System.nanoTime() - t0) / 1000
        }
        val sorted = runs.sorted()
        Log.i(
            TAG,
            "bench n=%d min=%.1fms p50=%.1fms p95=%.1fms max=%.1fms".format(
                n,
                sorted.first() / 1000.0,
                sorted[n / 2] / 1000.0,
                sorted[min((n * 0.95).toInt(), n - 1)] / 1000.0,
                sorted.last() / 1000.0,
            ),
        )
    }

    private fun AccessibilityNodeInfo.describe(): String = when {
        !text.isNullOrEmpty() -> "\"$text\""
        contentDescription != null -> "($contentDescription)"
        viewIdResourceName != null -> "#${viewIdResourceName.shortId()}"
        else -> className.shortName()
    }

    /** A label is usually not the tap target; the row wrapping it is. */
    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var cur = parent
        repeat(ANCESTOR_LIMIT) {
            if (cur == null) return null
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.tapCenter(): Boolean {
        val r = Rect().also { getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build(),
            null,
            null,
        )
    }

    /**
     * A canvas view (a game board, a map) is one element with nothing inside,
     * so the only way to reach a spot on it is by the pixel, from its bounds.
     */
    @Synchronized private fun tapAt(spec: String): String {
        val at = point(spec) ?: return "error: tap wants an element index, x,y, a cell like F7, or an ocr block like o3\n"
        frameBefore()
        val before = eventCount
        val label = "tap at ${at.x.toInt()},${at.y.toInt()}"
        stroke(Path().apply { moveTo(at.x, at.y) }, TAP_MS)?.let { return "receipt: $it ($label)\n" }
        return settleAndDiff(label, before)
    }

    /** A long press, on an element or a pixel, for the menus a tap never reaches. */
    @Synchronized private fun press(spec: String): String {
        val at = point(spec) ?: return "error: press wants an element index or x,y\n"
        frameBefore()
        val before = eventCount
        val label = "press at ${at.x.toInt()},${at.y.toInt()}"
        stroke(Path().apply { moveTo(at.x, at.y) }, PRESS_MS)?.let { return "receipt: $it ($label)\n" }
        return settleAndDiff(label, before)
    }

    /** `x1,y1 x2,y2 [ms]`: a drag between two pixels, for carousels, sliders and canvases. */
    @Synchronized private fun swipe(spec: String): String {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val from = parts.getOrNull(0)?.let(::point)
        val to = parts.getOrNull(1)?.let(::point)
        if (from == null || to == null) {
            return "error: swipe wants two targets (x1,y1, a cell, an element, o# or b#), then optional ms\n"
        }
        val ms = parts.getOrNull(2)?.toLongOrNull() ?: SWIPE_MS
        frameBefore()
        val before = eventCount
        val path = Path().apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
        val label = "swipe ${from.x.toInt()},${from.y.toInt()} to ${to.x.toInt()},${to.y.toInt()}"
        stroke(path, ms)?.let { return "receipt: $it ($label)\n" }
        return settleAndDiff(label, before)
    }

    /**
     * `drag p1 p2 [p3 ...] [ms]`: finger down, a pause, the move, a pause, up.
     * A slider, a cue or a card being sorted needs the pauses to register the
     * grab and the drop; `swipe` is the flick that scrolls.
     */
    @Synchronized private fun drag(spec: String): String {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ms = parts.lastOrNull()?.takeIf { parts.size > 2 && it.toLongOrNull() != null }?.toLong()
        val specs = if (ms != null) parts.dropLast(1) else parts
        val points = specs.map { point(it) ?: return "error: drag wants two or more points (x,y, a cell, or an element), then optional ms\n" }
        if (points.size < 2) return "error: drag wants two or more points (x,y, a cell, or an element), then optional ms\n"
        frameBefore()
        val before = eventCount
        val label = "drag " + points.joinToString(" to ") { "${it.x.toInt()},${it.y.toInt()}" }
        held(points, ms ?: DRAG_MS)?.let { return "receipt: $it ($label)\n" }
        return settleAndDiff(label, before)
    }

    /** `hold <n|x,y|cell> <ms>`: a finger kept down for as long as it says. */
    @Synchronized private fun hold(spec: String): String {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val at = parts.getOrNull(0)?.let(::point)
        val ms = parts.getOrNull(1)?.toLongOrNull()
        if (at == null || ms == null) return "error: hold wants a target then milliseconds, e.g. hold B8 1500\n"
        frameBefore()
        val before = eventCount
        val label = "hold at ${at.x.toInt()},${at.y.toInt()} for ${ms}ms"
        stroke(Path().apply { moveTo(at.x, at.y) }, ms)?.let { return "receipt: $it ($label)\n" }
        return settleAndDiff(label, before)
    }

    /** `pinch <x,y|cell> in|out [ms]`: two fingers, for maps and photos. */
    @Synchronized private fun pinch(spec: String): String {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val at = parts.getOrNull(0)?.let(::point)
        val out = when (parts.getOrNull(1)) { "out", "zoom" -> true; "in" -> false; else -> null }
        if (at == null || out == null) return "error: pinch wants a centre then in or out, e.g. pinch K6 out\n"
        val ms = parts.getOrNull(2)?.toLongOrNull() ?: DRAG_MS
        val (near, far) = PINCH_NEAR to PINCH_FAR
        val (from, to) = if (out) near to far else far to near
        val fingers = listOf(-1f, 1f).map { side ->
            Path().apply { moveTo(at.x + side * from, at.y); lineTo(at.x + side * to, at.y) }
        }
        frameBefore()
        val before = eventCount
        val label = "pinch ${if (out) "out" else "in"} at ${at.x.toInt()},${at.y.toInt()}"
        val gesture = GestureDescription.Builder().apply {
            fingers.forEach { addStroke(GestureDescription.StrokeDescription(it, 0, ms)) }
        }.build()
        return if (dispatchGesture(gesture, null, null)) settleAndDiff(label, before) else "receipt: refused ($label)\n"
    }

    /** Down, pause, move through the points, pause, up: three strokes that continue one touch. */
    private fun held(points: List<PointF>, ms: Long): String? {
        val first = points.first()
        val last = points.last()
        val down = GestureDescription.StrokeDescription(Path().apply { moveTo(first.x, first.y) }, 0, HOLD_MS, true)
        perform(down)?.let { return it }
        val move = down.continueStroke(
            Path().apply { moveTo(first.x, first.y); points.drop(1).forEach { lineTo(it.x, it.y) } },
            0,
            ms,
            true,
        )
        perform(move)?.let { return it }
        val up = move.continueStroke(Path().apply { moveTo(last.x, last.y) }, 0, HOLD_MS, false)
        return perform(up)
    }

    /**
     * `finger down <p>` | `finger move <p> [ms]` | `finger up`: one touch held
     * across calls, so the screen can be read while the finger is still down
     * and the move adjusted before letting go. What a power bar needs.
     */
    @Synchronized private fun finger(spec: String): String {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val verb = parts.firstOrNull() ?: return "error: finger wants down <p>, move <p> [ms], or up\n"
        val current = finger
        val at = fingerAt
        frameBefore()
        val before = eventCount
        when (verb) {
            "down" -> {
                if (current != null) return "error: the finger is already down at ${at?.x?.toInt()},${at?.y?.toInt()}; move it or lift it\n"
                val p = parts.getOrNull(1)?.let(::point) ?: return "error: finger down wants a point\n"
                val down = GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, HOLD_MS, true)
                perform(down)?.let { return "receipt: $it (finger down)\n" }
                finger = down
                fingerAt = p
                return settleAndDiff("finger down at ${p.x.toInt()},${p.y.toInt()}, still held", before)
            }
            "move" -> {
                if (current == null || at == null) return "error: no finger is down; finger down <p> first\n"
                val p = parts.getOrNull(1)?.let(::point) ?: return "error: finger move wants a point\n"
                val ms = parts.getOrNull(2)?.toLongOrNull() ?: DRAG_MS
                val move = current.continueStroke(Path().apply { moveTo(at.x, at.y); lineTo(p.x, p.y) }, 0, ms, true)
                perform(move)?.let { finger = null; fingerAt = null; return "receipt: $it (finger move; the touch is lost)\n" }
                finger = move
                fingerAt = p
                return settleAndDiff("finger moved to ${p.x.toInt()},${p.y.toInt()}, still held", before)
            }
            "up" -> {
                if (current == null || at == null) return "error: no finger is down\n"
                val up = current.continueStroke(Path().apply { moveTo(at.x, at.y) }, 0, HOLD_MS, false)
                finger = null
                fingerAt = null
                perform(up)?.let { return "receipt: $it (finger up)\n" }
                return settleAndDiff("finger up at ${at.x.toInt()},${at.y.toInt()}", before)
            }
            else -> return "error: finger wants down <p>, move <p> [ms], or up\n"
        }
    }

    /**
     * `live tap|down|move|up|key`: the mirror's finger. A person watching the
     * live screen needs the touch to land now, so nothing here reads the
     * screen before the gesture or waits for it to settle after, which is the
     * whole difference between this and `tap` or `finger`.
     */
    private fun live(spec: String): String = synchronized(liveLock) {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        when (parts.firstOrNull()) {
            "tap" -> {
                val p = parts.getOrNull(1)?.let(::pixel) ?: return "error: live tap wants x,y\n"
                lift()
                // Nobody is waiting on the outcome, so the gesture is posted and
                // the reply goes back ahead of it.
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path(p), 0, TAP_MS))
                        .build(),
                    null,
                    null,
                )
                "ok\n"
            }
            // A deliberate hold, posted whole: the viewer knows the finger never
            // moved, so there is nothing to track and nothing to wait for.
            "press" -> {
                val p = parts.getOrNull(1)?.let(::pixel) ?: return "error: live press wants x,y\n"
                lift()
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path(p), 0, PRESS_MS))
                        .build(),
                    null,
                    null,
                )
                "ok\n"
            }
            "down" -> {
                val p = parts.getOrNull(1)?.let(::pixel) ?: return "error: live down wants x,y\n"
                lift()
                val down = GestureDescription.StrokeDescription(path(p), 0, LIVE_STEP_MS, true)
                perform(down)?.let { return "receipt: $it (live down)\n" }
                liveStroke = down
                liveAt = p
                "ok\n"
            }
            "move" -> {
                val held = liveStroke
                val at = liveAt
                if (held == null || at == null) return "error: no live touch is down\n"
                val p = parts.getOrNull(1)?.let(::pixel) ?: return "error: live move wants x,y\n"
                val ms = (parts.getOrNull(2)?.toLongOrNull() ?: LIVE_STEP_MS)
                    .coerceIn(LIVE_STEP_MS, LIVE_MOVE_MAX_MS)
                val move = held.continueStroke(path(at, p), 0, ms, true)
                perform(move)?.let {
                    liveStroke = null
                    liveAt = null
                    return "receipt: $it (live move; the touch is lost)\n"
                }
                liveStroke = move
                liveAt = p
                "ok\n"
            }
            "up" -> {
                if (liveStroke == null) return "error: no live touch is down\n"
                lift()
                "ok\n"
            }
            "key" -> {
                val action = when (parts.getOrNull(1)) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    else -> return "error: live key wants back, home, recents, notifications or quicksettings\n"
                }
                if (performGlobalAction(action)) "ok\n" else "receipt: refused (live key)\n"
            }
            else -> "error: live wants tap <p>, down <p>, move <p> [ms], up, or key <name>\n"
        }
    }

    /**
     * Let go of a live touch. A viewer that closes mid-drag leaves a finger on
     * the glass, and every gesture after it is cancelled by the one still down.
     */
    private fun lift() {
        val held = liveStroke ?: return
        val at = liveAt
        liveStroke = null
        liveAt = null
        if (at != null) perform(held.continueStroke(path(at), 0, LIVE_STEP_MS, false))
    }

    private fun path(from: PointF, to: PointF? = null) = Path().apply {
        moveTo(from.x, from.y)
        if (to != null) lineTo(to.x, to.y)
    }

    /**
     * One stroke, waited for, so the next can continue it. Null when it played
     * to the end; otherwise the reason, which is a fact about the system, not
     * about whether the app did anything with it.
     */
    private fun perform(stroke: GestureDescription.StrokeDescription): String? {
        val done = CountDownLatch(1)
        var outcome: String? = "gesture never completed"
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription) { outcome = null; done.countDown() }
                override fun onCancelled(gesture: GestureDescription) {
                    outcome = "gesture cancelled by the system (another touch, or the screen changed under it)"
                    done.countDown()
                }
            },
            null,
        )
        if (!sent) return "refused: the accessibility service could not dispatch a gesture"
        done.await(GESTURE_BUDGET_SECONDS, TimeUnit.SECONDS)
        return outcome
    }

    private fun clear(): String {
        if (!AsterIme.isReady) return "error: the Aster keyboard is not the active input method\n"
        val before = eventCount
        return if (AsterIme.clear()) settleAndDiff("clear the field", before)
        else "receipt: refused (no field focused)\n"
    }

    /** `x,y`, the centre of `l,t-r,b` as a map prints bounds, or a grid cell like `F7`. */
    private fun pixel(spec: String): PointF? {
        if (Grid.isCell(spec)) return currentGrid().centre(spec)
        region(spec)?.let { return PointF(it.exactCenterX(), it.exactCenterY()) }
        val parts = spec.split(',').map { it.trim().toFloatOrNull() }
        if (parts.size != 2) return null
        val (x, y) = parts
        return if (x == null || y == null) null else PointF(x, y)
    }

    /**
     * An element index or a pixel pair, as a point on screen. A handle that
     * matches the grammar but points at nothing is a stale read, not a syntax
     * error, so it says which read to re-run instead of a usage line.
     */
    private fun point(spec: String): PointF? {
        OCR_REF.matchEntire(spec)?.let { m ->
            val i = m.groupValues[1].toInt()
            val r = lastOcr.getOrNull(i)?.bounds ?: throw StaleRef(
                if (lastOcr.isEmpty()) "no ocr block o$i; run ocr first"
                else "no ocr block o$i; the last ocr had ${lastOcr.size}; run ocr again",
            )
            return PointF(r.exactCenterX(), r.exactCenterY())
        }
        BLOB_REF.matchEntire(spec)?.let { m ->
            val i = m.groupValues[1].toInt()
            val b = lastBlobs.getOrNull(i) ?: throw StaleRef(
                if (lastBlobs.isEmpty()) "no blob b$i; run locate first"
                else "no blob b$i; the last locate had ${lastBlobs.size}; run locate again",
            )
            return PointF(b.cx.toFloat(), b.cy.toFloat())
        }
        if (',' in spec || Grid.isCell(spec)) return pixel(spec)
        val n = spec.toIntOrNull() ?: return null
        val node = marked.getOrNull(n) ?: throw StaleRef("no element $n; run map first")
        val r = Rect().also { node.getBoundsInScreen(it) }
        return PointF(r.exactCenterX(), r.exactCenterY())
    }

    /** Null when the system played the whole gesture, else why not. */
    private fun stroke(path: Path, ms: Long): String? =
        perform(GestureDescription.StrokeDescription(path, 0, ms))

    private fun focusedEditable(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private companion object {
        const val TAG = "ASTEREYES"
        const val PKG = "dev.aster.probe"
        const val SOCKET = "aster-eyes"
        const val MAX_NODES = 4000
        const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
            AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
        const val SETTLE_BUDGET_MS = 1500
        const val QUIET_MS = 150
        const val QUIET_BUDGET_MS = 600L
        const val ANCESTOR_LIMIT = 6
        const val TAP_MS = 60L

        /**
         * A live stroke's segment: long enough for the system to take it as a
         * touch, short enough that the finger on the viewer and the finger on
         * the phone are in the same place.
         */
        const val LIVE_STEP_MS = 16L
        const val LIVE_MOVE_MAX_MS = 250L
        const val PRESS_MS = 600L
        const val SWIPE_MS = 300L
        const val DRAG_MS = 600L
        const val HOLD_MS = 150L
        const val PINCH_NEAR = 80f
        const val PINCH_FAR = 400f
        const val GESTURE_BUDGET_SECONDS = 10L
        const val WAIT_OCR_MS = 1_000L
        const val THUMB_WIDTH = 152
        const val PIXEL_DELTA = 48
        const val STILL_POLL_MS = 120L
        const val STILL_PERCENT = 1
        const val STILL_HOLD_MS = 280L
        const val AIM_RADIUS = 520
        const val AIM_MIN_RADIUS = 260
        const val AIM_ITERS = 6
        const val AIM_PROBE = 0.20
        val AIM_TOL = Math.toRadians(2.0)
        val BLOB_REF = Regex("b(\\d+)")
        val OCR_REF = Regex("o(\\d+)")
        val PIXELS = Regex("[\\d.,-]+")
        const val OCR_BUDGET_SECONDS = 10L
        const val CROP_PADDING = 24
        const val BIND_TRIES = 10
        const val BIND_RETRY_MS = 300L
        const val BIND_WAIT_MS = 2_000L
        const val UNKNOWN_PKG = "?"
        const val SYSTEM_UI = "com.android.systemui"
        const val DIAL_LAUNCH_MS = 6_000L
        const val STORE_LAUNCH_MS = 12_000L
        const val APP_LAUNCH_MS = 8_000L
        const val WAKE_MS = 120_000L
        const val WAKE_SETTLE_MS = 3_000L
        const val LOCK_CONFIRM_MS = 2_000L
        const val NEAR_PREFIX = 4
        const val NEAR_MAX = 3
        const val FOREGROUND_POLL_MS = 50L
        const val RESTART_PAUSE_MS = 400L
        const val MEDIA_SETTLE_MS = 300L
        const val WAIT_DEFAULT_SECS = 10
        /** A failed wait is dead air in the chat, and most fail on a word that
         * was never going to appear. Cap it so a bad guess costs seconds. */
        const val WAIT_MAX_SECS = 20
        const val WAIT_POLL_MS = 500L
        const val WAIT_TICK_MS = 80L
        const val POLL_MS = 10L

        /** A screen that moves later than this after a no-change receipt moved for some other reason. */
        const val LATE_MS = 3_000L
        const val STILL_TIMEOUT_MS = 250L
        const val SHOT_GAP_MAX_MS = 1_200L
        const val SHOT_GAP_MARGIN_MS = 25
        const val SHOT_RETRY_MIN_MS = 50L

        /** Verbs that only look. A late landing is reported ahead of them rather than holding them back. */
        val READS = setOf("map", "screen", "find", "ocr", "shot", "notes", "apps", "events", "help", "marks", "pace", "wait")
        const val BAR_MAX_PX = 200
        const val SHOT_RETRIES = 3
        const val HELP = """read:
  map                      numbered, actionable elements; the index is the handle
  find <text>              the same list, filtered by text, desc or id
  ocr                      read text the tree cannot see (canvas, game, image)
  notes                    notifications seen since the last read
  apps [filter]            installed apps
  shot [target]            png of the screen, an element, a rectangle or a cell
  shot grid [px]           png with lettered cells; `shot grid <cell|range>` zooms, pixel-labelled
  shot jpeg <1-100> <width>  scaled jpeg, for the mirror
  marks [off]              draw the map's indices over the live screen
act:
  tap <target>             click. element, x,y, cell F7, ocr block o3, blob b0, or the text on it
  do <step>; <step> ...    run steps in one call; stops at the first that fails or changes nothing
  press <target>           long-press: the menus a tap never reaches
  swipe <from> <to> [ms]   a flick: scrolls, dismisses, archives
  drag <p1> <p2> [p3 ...] [ms]   a held move with pauses: sliders, cues, reordering (alias slide)
  hold <target> <ms>       finger kept down for ms
  pinch <x,y|cell> in|out [ms]   two fingers: maps, photos
  finger down <p> | move <p> [ms] | up   one touch held across calls; read the screen between
  live tap|down|move|up|key <...>  the mirror's finger: dispatched and left, never waited out
  scroll [n] up|down       scroll an element or the biggest scroller
type:
  text <text>              set the focused field's text outright, replacing what is there
  type <text>              commit through the keyboard, appending like a person; reaches fields text cannot
  clear                    empty the focused field
  key back|home|recents|lock|power|notifications|quicksettings
                           system keys; key enter|delete|tab goes to the focused field
wait & wake:
  wait <text> [secs]       block until the text is on screen (default 30s)
  later <30s|2m|1h|18:30> <what to do>   end the turn; a reminder wakes you then
  alerts                   battery warnings and apps whose notifications go to the chat
  alerts battery on|off|20,10 | alerts add <app> | alerts remove <app>
straight there:
  open <app> | restart <app> | install <app> | settings [name]
  dial <number> | sms <number> [text] | url <address> | search <q> | place <q>
  alarm HH:MM [label] | timer 10m [label] | event HH:MM [title]
  wallpaper <path> | emergency <number>
canvas:
  locate bright|<colour>   numbered blobs b0 b1 ... by true centre
  aim <target>             point the cue at a target and self-correct; bare `aim` reads it
other:
  volume [up|down|max|mute|0-100] [media|ring|alarm|notification|call]
  media pause|play|toggle|next|prev
  quicksettings | notifications (the shade) | events | help
  pace [reset]             how long the waits are for the app in front, learned from its touches
  capture on|off           hold the screen capture so canvas reads skip the screenshot rate limit
targets: element n (map) | x,y | cell F7 (shot grid) | ocr block o3 (ocr) | blob b0 (locate) | text (tap)
a stale handle errors with the read to re-run
"""

        // Most specific first, across the dialers that ship on real phones.
        val CALL_IDS = listOf(
            "voice_call_button",
            "call_button",
            "dial_button",
            "dialpad_fab",
            "floating_action_button",
        )
    }

    private data class Node(
        val cls: String,
        val id: String?,
        val text: String?,
        val desc: String?,
        val bounds: Rect,
        val depth: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val ref: AccessibilityNodeInfo,
    ) {
        fun line(): String = buildString {
            append('[').append(cls).append(']')
            id?.let { append(" #").append(it) }
            when {
                text != null -> append(" \"").append(text.oneLine()).append('"')
                desc != null -> append(" (").append(desc.oneLine()).append(')')
            }
            append(' ').append(bounds.left).append(',').append(bounds.top)
            append('-').append(bounds.right).append(',').append(bounds.bottom)
            if (clickable) append(" tap")
            if (editable) append(" edit")
            if (scrollable) append(" scroll")
        }
    }

    private class Snapshot {
        val nodes = mutableListOf<Node>()
        var pkg = UNKNOWN_PKG
        var walked = 0
        var windows = 0
        var tWindows = 0L
        var tWalk = 0L

        fun lines(): List<String> = nodes.map { it.line() }

        fun render(): String =
            lines().withIndex().joinToString("") { (i, l) -> "%3d %s\n".format(i, l.trim()) }

        fun signature(): List<String> =
            nodes.map { "${it.cls}|${it.id}|${it.text}|${it.desc}|${it.bounds.left},${it.bounds.top}" }
    }
}

/**
 * Keep one element on one line and bounded. A terminal hands its whole screen
 * over as a single content-desc, which both breaks the format and swamps the
 * context budget.
 */
private fun String.oneLine(cap: Int = 160): String {
    val flat = replace("\r", "").replace("\n", "\\n")
    return if (flat.length <= cap) flat else flat.take(cap) + "…"
}

private fun CharSequence?.shortName(): String =
    this?.toString()?.substringAfterLast('.') ?: "?"

private fun String?.shortId(): String? = this?.substringAfterLast('/')
