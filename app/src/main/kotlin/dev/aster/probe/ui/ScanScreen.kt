package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * The camera, for a setup code from the website or a pairing code a computer
 * shows. For when the stock camera will not open links, and the only way to
 * scan a computer's code at all. A pasted link works too, for a phone whose
 * camera is busy or missing.
 */
@Composable
fun ScanScreen(camera: Boolean, problem: String?, onCode: (String) -> Unit, onBack: () -> Unit) {
    var pasted by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = Gutter),
    ) {
        BackRow(title = "Scan a code", onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CardShape)
                    .background(Ink.surface)
                    .border(1.dp, Ink.line, CardShape),
            ) {
                if (camera) {
                    Viewfinder(onCode)
                } else {
                    EmptyState(
                        icon = Glyph.search,
                        title = "The camera is off for Droid",
                        detail = "Allow the camera to scan, or paste the link below.",
                    )
                }
            }
            Text(
                text = "Point the camera at a setup code from droid.withaster.dev/setup, or at the pairing code a " +
                    "computer shows. Droid shows what a code changes before it changes anything.",
                style = Type.secondary,
                color = Ink.dim,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (problem != null) {
                Text(text = problem, style = Type.secondary, color = Ink.red, modifier = Modifier.padding(top = 10.dp))
            }
            SectionLabel("OR PASTE THE LINK", Modifier.padding(top = 24.dp, bottom = 8.dp))
            Field(
                value = pasted,
                onValueChange = { pasted = it.trim() },
                hint = "https://droid.withaster.dev/s#1...",
                uri = true,
                onDone = { if (pasted.isNotEmpty()) onCode(pasted) },
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(text = "Use this link", enabled = pasted.isNotEmpty(), onClick = { onCode(pasted) })
            Spacer(Modifier.height(Gutter))
        }
    }
}

/**
 * The zxing view, QR only, paused with the activity so the camera is let go
 * the moment Droid leaves the screen. The same code read twice in a row is
 * one scan: the view reports every frame it decodes.
 */
@Composable
private fun Viewfinder(onCode: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onCode)
    var last by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val view = remember { mutableStateOf<BarcodeView?>(null) }
    AndroidView(
        factory = { context ->
            BarcodeView(context).apply {
                decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                decodeContinuous { result ->
                    val text = result.text ?: return@decodeContinuous
                    val now = System.currentTimeMillis()
                    val previous = last
                    if (previous != null && previous.first == text && now - previous.second < REPEAT_MS) return@decodeContinuous
                    last = text to now
                    latest(text)
                }
                view.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(lifecycle, view.value) {
        val barcode = view.value
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> barcode?.resume()
                Lifecycle.Event.ON_PAUSE -> barcode?.pause()
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) barcode?.resume()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            barcode?.pause()
        }
    }
}

private const val REPEAT_MS = 3_000L
