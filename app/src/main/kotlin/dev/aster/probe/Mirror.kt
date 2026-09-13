package dev.aster.probe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The live screen as H.264. `takeScreenshot` is rate-limited to one frame every
 * third of a second, which is why the old mirror ran at 2fps; MediaProjection
 * has no such limit and hands the encoder a Surface, so the frames never pass
 * through the heap.
 *
 * There is one capture for the phone, not one per viewer. Android voids a
 * capture grant the moment it is used, so a capture per viewer is a consent
 * dialog per viewer; one capture that outlives its viewers is answered once.
 */
object Mirror {

    private const val TAG = "aster-mirror"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val CONSENT_WAIT_SECONDS = 30L
    private const val DRAIN_TIMEOUT_US = 100_000L
    /**
     * Keyframes are the expensive ones, and a viewer joining mid-stream asks
     * for its own, so the periodic ones only guard against a stream that has
     * drifted. Rare is the right rate for them; scrcpy uses the same.
     */
    private const val KEYFRAME_SECONDS = 10
    private const val FGS_SETTLE_MS = 300L
    private const val PRIORITY_REALTIME = 0

    /**
     * How long the capture outlives its last viewer. A whole session of coming
     * and going should cost one dialog; only a long idle lets it go, since a
     * capture that never stops is a battery that never rests.
     */
    private const val IDLE_MS = 30 * 60 * 1000L

    /**
     * A viewer may fall about a second behind before its backlog is thrown away
     * and it is started again from the next keyframe. Catching up on stale
     * frames is not worth the delay it adds to the live ones.
     */
    private const val QUEUE_DEPTH = 60
    private const val MAX_OVERFLOWS = 5

    /**
     * The capture never changes size. A MediaProjection gives out one
     * VirtualDisplay, so a new size means a new projection, which since
     * Android 14 means the consent dialog again and a black viewer until it
     * is answered. Presets change the bitrate instead, which the encoder takes
     * live; the browser scales the picture.
     */
    private const val CAPTURE_WIDTH = 1080
    private const val CAPTURE_FPS = 60
    private const val REPEAT_FRAME_US = 100_000L

    /**
     * scrcpy's packet header, because the decoder on the other end is a port of
     * one already written against it: 8 bytes of pts with the two top bits as
     * flags, then 4 bytes of length, then the Annex-B payload.
     */
    private const val FLAG_CONFIG = 1L shl 63
    private const val FLAG_KEY = 1L shl 62

    /**
     * One viewer. It sees nothing until a keyframe gives it somewhere to start,
     * and it owns the thread that writes to it: a socket that stops draining
     * must never be something the encoder waits on, or one viewer on a bad link
     * stops the capture for everyone.
     */
    private class Viewer(val socket: LocalSocket) {
        val out = DataOutputStream(socket.outputStream.buffered())
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)
        @Volatile var started = false
        @Volatile var alive = true
        var overflows = 0
    }

    /** The running capture: encoder, display, and whoever is watching. */
    private class Capture(
        val width: Int,
        val height: Int,
        val fps: Int,
        /** The real display, since a tap is aimed in its pixels, not the frame's. */
        val screen: Pair<Int, Int>,
        val projection: MediaProjection,
        val display: VirtualDisplay,
        val codec: MediaCodec,
        val surface: Surface,
    ) {
        val viewers = CopyOnWriteArrayList<Viewer>()
        /** SPS and PPS, kept so a viewer joining mid-stream can configure. */
        @Volatile var config: ByteArray? = null
        @Volatile var running = true
    }

    @Volatile private var capture: Capture? = null
    /** Held while anyone is watching, so the phone cannot lock the capture away. */
    private var awake: PowerManager.WakeLock? = null
    @Volatile private var granted: Intent? = null
    /** True while the consent dialog is up and unanswered; the auto-accept watches it. */
    @Volatile var consentPending: Boolean = false
        private set
    private var pending: CountDownLatch? = null
    private val lock = Any()
    private val consentLock = Any()
    private val awakeLock = Any()
    private val idle = Handler(Looper.getMainLooper())
    private val teardown = Runnable { stop() }

    /** The consent activity's result, routed back to whoever asked for it. */
    fun onConsent(result: Intent?) {
        granted = result
        synchronized(consentLock) { pending?.countDown() }
    }

    /** Let the capture go; the next viewer has to ask for consent again. */
    fun stop() {
        synchronized(lock) {
            val live = capture ?: return
            capture = null
            live.running = false
            for (viewer in live.viewers) {
                viewer.alive = false
                runCatching { viewer.socket.close() }
            }
            live.viewers.clear()
            runCatching { live.display.release() }
            runCatching { live.codec.stop() }
            runCatching { live.codec.release() }
            runCatching { live.surface.release() }
            runCatching { live.projection.stop() }
        }
        letSleep()
    }

    /**
     * `stream h264 <width> <fps> <kbps>`: the connection becomes the video, so
     * the caller holds it open and reads frames until it hangs up.
     */
    fun attach(ctx: Context, spec: String, client: LocalSocket) {
        val args = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }.drop(1)
        if (args.firstOrNull() != "h264") {
            fail(client, "error: stream takes h264, like stream h264 720 30 4000\n")
            return
        }
        // Width and fps are accepted for the verb's shape but the capture has
        // one geometry; only the bitrate is the caller's to choose.
        val kbps = (args.getOrNull(3)?.toIntOrNull() ?: 4_000).coerceIn(200, 20_000)
        thread(name = "aster-mirror-join") {
            runCatching { join(ctx, kbps, client) }.onFailure {
                Log.w(TAG, "could not attach a viewer: $it")
                fail(client, "error: ${it.message}\n")
            }
        }
    }

    private fun fail(client: LocalSocket, message: String) {
        runCatching {
            client.outputStream.write(message.toByteArray())
            client.outputStream.flush()
            client.close()
        }
    }

    /** Put a viewer on the capture, starting one if this is the first. */
    private fun join(ctx: Context, kbps: Int, client: LocalSocket) {
        keepAwake(ctx)
        // The dialog is about to go up on the phone; say so, or the viewer
        // sits black with no idea why.
        if (capture == null && granted == null) {
            runCatching {
                client.outputStream.write("note: approve screen capture on the phone\n".toByteArray())
                client.outputStream.flush()
            }
        }
        val live = synchronized(lock) {
            idle.removeCallbacks(teardown)
            capture ?: start(ctx, kbps)
        }
        if (live == null) {
            fail(client, "error: screen capture was not allowed\n")
            return
        }

        val viewer = Viewer(client)
        // Written before the viewer's own thread exists, so nothing interleaves.
        viewer.out.write(
            "stream ${live.width} ${live.height} ${live.fps} screen ${live.screen.first}x${live.screen.second}\n"
                .toByteArray(),
        )
        live.config?.let { viewer.out.write(frame(FLAG_CONFIG, it)) }
        viewer.out.flush()
        live.viewers.add(viewer)
        // The capture can go away while a viewer is being set up, and one added
        // after the teardown cleared the list is never closed again: no frames,
        // no error, a page waiting forever.
        if (!live.running) {
            drop(live, viewer)
            return
        }
        thread(name = "aster-mirror-out") { pump(live, viewer) }
        // The newest viewer's preset sets the bitrate for everyone: one encoder,
        // one stream, and the last person to pick is the one looking.
        bitrate(live, kbps)
        // A mid-stream joiner needs a frame to start from, not the middle of a GOP.
        keyframe(live)

        // This thread parks on the viewer's own socket, so the drain loop is
        // never the thing blocking on a reader that has walked away.
        runCatching { client.inputStream.read() }
        drop(live, viewer)
    }

    /** One viewer's own writes, so a slow socket blocks only its own thread. */
    private fun pump(live: Capture, viewer: Viewer) {
        while (viewer.alive) {
            val frame = runCatching { viewer.queue.take() }.getOrNull() ?: break
            val sent = runCatching {
                viewer.out.write(frame)
                viewer.out.flush()
            }
            if (sent.isFailure) break
        }
        drop(live, viewer)
    }

    private fun drop(live: Capture, viewer: Viewer) {
        viewer.alive = false
        live.viewers.remove(viewer)
        runCatching { viewer.socket.close() }
        synchronized(lock) {
            if (capture === live && live.viewers.isEmpty()) {
                idle.removeCallbacks(teardown)
                idle.postDelayed(teardown, IDLE_MS)
            }
        }
        if (live.viewers.isEmpty()) letSleep()
    }

    /**
     * The screen on and lit for as long as someone is watching. Android stops a
     * projection the moment the device locks, so a mirror that lets the phone
     * fall asleep is a mirror that goes black and cannot bring itself back.
     */
    private fun keepAwake(ctx: Context) {
        synchronized(awakeLock) {
            if (awake?.isHeld == true) return
            @Suppress("DEPRECATION")
            val held = ctx.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "aster:mirror",
            )
            runCatching { held.acquire() }.onSuccess { awake = held }
        }
    }

    private fun letSleep() {
        synchronized(awakeLock) {
            runCatching { awake?.takeIf { it.isHeld }?.release() }
            awake = null
        }
    }

    private fun bitrate(live: Capture, kbps: Int) {
        runCatching {
            live.codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1_000) },
            )
        }
    }

    private fun keyframe(live: Capture) {
        runCatching {
            live.codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }
    }

    /** The encoder, the display it draws from, and the thread that drains it. */
    private fun start(ctx: Context, kbps: Int): Capture? {
        val media = ctx.getSystemService(MediaProjectionManager::class.java)
        val screen = screen(ctx)
        val (w, h) = sized(screen, CAPTURE_WIDTH)
        val fps = CAPTURE_FPS
        val projection = projection(ctx, media) ?: return null

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            // Left alone, the encoder holds frames back to fill a pipeline, which
            // on a mirror is delay bought with quality nobody asked for. One in,
            // one out, and the screen is scheduled like the live thing it is.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
            // A Surface-fed encoder only emits when the screen changes, so a
            // viewer joining a still screen would wait forever for the keyframe
            // it asked for. Repeating the last frame gives it one to ride on;
            // a repeated frame costs almost nothing to encode or send.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_US)
            // A phone screen is mostly still, so let the encoder spend its bits
            // on the moments that move rather than a flat rate per frame.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val display = runCatching {
            projection.createVirtualDisplay(
                "aster-mirror",
                w,
                h,
                ctx.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "virtual display refused: $it") }.getOrNull()

        if (display == null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            runCatching { projection.stop() }
            MirrorService.stop(ctx)
            return null
        }

        val live = Capture(w, h, fps, screen, projection, display, codec, surface)
        capture = live
        thread(name = "aster-mirror-drain") { drain(live) }
        return live
    }

    /** Encoded frames out to everyone watching, until the capture is let go. */
    private fun drain(live: Capture) {
        val info = MediaCodec.BufferInfo()
        while (live.running) {
            val index = runCatching { live.codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US) }
                .getOrElse { return }
            if (index < 0) continue
            val buffer = live.codec.getOutputBuffer(index)
            if (buffer == null) {
                runCatching { live.codec.releaseOutputBuffer(index, false) }
                continue
            }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val payload = ByteArray(info.size)
            buffer.get(payload)
            runCatching { live.codec.releaseOutputBuffer(index, false) }
            if (payload.isEmpty()) continue

            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (isConfig) live.config = payload

            var header = info.presentationTimeUs and (FLAG_CONFIG or FLAG_KEY).inv()
            if (isConfig) header = header or FLAG_CONFIG
            if (isKey) header = header or FLAG_KEY

            val frame = frame(header, payload)
            for (viewer in live.viewers) {
                // Everything before a viewer's first keyframe is undecodable, so
                // it waits rather than being handed a stream it cannot open.
                if (!viewer.started && !isConfig && !isKey) continue
                if (isKey) viewer.started = true
                // Never block here: this loop is the encoder's only reader, and
                // waiting on one viewer's socket stops the picture for all of them.
                if (viewer.queue.offer(frame)) continue
                viewer.queue.clear()
                viewer.started = false
                viewer.overflows++
                if (viewer.overflows > MAX_OVERFLOWS) {
                    Log.w(TAG, "a viewer could not keep up; letting it go")
                    drop(live, viewer)
                } else {
                    keyframe(live)
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** One packet on the wire: the header, the length, then the payload. */
    private fun frame(header: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(12 + payload.size)
        for (i in 0..7) out[i] = ((header shr (56 - 8 * i)) and 0xFF).toByte()
        val n = payload.size
        out[8] = (n ushr 24).toByte()
        out[9] = (n ushr 16).toByte()
        out[10] = (n ushr 8).toByte()
        out[11] = n.toByte()
        payload.copyInto(out, 12)
        return out
    }

    /**
     * The live projection, asking for consent only when there is none. Consent
     * first, then the service: the mediaProjection foreground type is only
     * permitted once capture has been granted, and starting the service before
     * asking throws SecurityException and takes the whole process down,
     * accessibility service and all.
     */
    private fun projection(ctx: Context, media: MediaProjectionManager): MediaProjection? {
        val token = consent(ctx) ?: return null
        MirrorService.start(ctx)
        // Being in the foreground is a round trip through the system server,
        // and the projection is refused until it has landed.
        Thread.sleep(FGS_SETTLE_MS)
        // The token dies on use, whether or not it worked, so it is never
        // offered twice: a second attempt is refused as a re-use.
        granted = null
        val fresh = runCatching { media.getMediaProjection(Activity.RESULT_OK, token) }
            .onFailure { Log.w(TAG, "capture refused: $it") }
            .getOrNull()
        if (fresh == null) {
            MirrorService.stop(ctx)
            return null
        }
        fresh.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                    MirrorService.stop(ctx)
                }
            },
            Handler(Looper.getMainLooper()),
        )
        return fresh
    }

    /**
     * The consent dialog, raised once however many viewers are asking. Two
     * requests used to mean two dialogs, and the second one's CLEAR_TASK
     * cancelled the first, so nobody was ever granted anything.
     */
    private fun consent(ctx: Context): Intent? {
        granted?.let { return it }
        val latch = synchronized(consentLock) {
            pending ?: CountDownLatch(1).also {
                pending = it
                consentPending = true
                val intent = Intent(ctx, MirrorConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ctx.startActivity(intent)
            }
        }
        latch.await(CONSENT_WAIT_SECONDS, TimeUnit.SECONDS)
        synchronized(consentLock) {
            if (pending === latch) pending = null
            consentPending = false
        }
        return granted
    }

    private fun screen(ctx: Context): Pair<Int, Int> {
        val window = ctx.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.maximumWindowMetrics.bounds
        } else {
            null
        }
        val metrics = ctx.resources.displayMetrics
        val w = bounds?.width()?.takeIf { it > 0 } ?: metrics.widthPixels
        val h = bounds?.height()?.takeIf { it > 0 } ?: metrics.heightPixels
        return w to h
    }

    /** The capture size: the asked-for width, never upscaled, aligned for H.264. */
    private fun sized(screen: Pair<Int, Int>, width: Int): Pair<Int, Int> {
        val (screenW, screenH) = screen
        // H.264 wants even dimensions, and encoders are happiest on 16s.
        val w = align(minOf(width, screenW))
        return w to align(screenH * w / screenW)
    }

    private fun align(value: Int): Int = (value / 16) * 16
}
