package dev.aster.probe

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The screen-capture dialog and nothing else. It has no window of its own, so
 * all the user ever sees is the system's own prompt, once per grant.
 */
class MirrorConsentActivity : ComponentActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Mirror.onConsent(if (it.resultCode == Activity.RESULT_OK) it.data else null)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        unlocked { consent() }
    }

    /**
     * Android stops a projection the instant the device is locked, so a mirror
     * asked for at a locked phone is granted and then killed before it has sent
     * a frame. The keyguard comes down first, and a swipe one dismisses itself;
     * a PIN is the one case where a person still has to be there.
     */
    private fun unlocked(then: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            then()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = then()
                override fun onDismissError() = then()
                override fun onDismissCancelled() {
                    Mirror.onConsent(null)
                    finish()
                }
            },
        )
    }

    private fun consent() {
        val media = getSystemService(MediaProjectionManager::class.java)
        val intent: Intent = media.createScreenCaptureIntent()
        runCatching { ask.launch(intent) }.onFailure {
            Mirror.onConsent(null)
            finish()
        }
    }
}
