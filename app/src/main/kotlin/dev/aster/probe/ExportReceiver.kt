package dev.aster.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/** `adb shell am broadcast -a dev.aster.probe.EXPORT`: copy what the agent learned to where adb can pull it. */
class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val out = File(context.getExternalFilesDir(null), "export")
        out.deleteRecursively()
        var files = 0
        for (name in listOf("skills", "memory", "sessions")) {
            val src = File(context.filesDir, ".local/share/aster/$name")
            if (!src.exists()) continue
            src.copyRecursively(File(out, name), overwrite = true)
            files += src.walkTopDown().count { it.isFile }
        }
        Log.i(TAG, "exported $files files to $out")
        ActivityLog.state(context, "Exported $files files")
    }

    private companion object {
        const val TAG = "ASTEREXPORT"
    }
}
