package dev.aster.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/** `adb push … files/import` then `am broadcast -a dev.aster.probe.IMPORT`: replace skills, memory, or sessions with what was pushed; an empty dir clears one. */
class ImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CLEAR_FEED) {
            ActivityLog.clear(context)
            return
        }
        val src = File(context.getExternalFilesDir(null), "import")
        var files = 0
        for (name in listOf("skills", "memory", "sessions")) {
            val from = File(src, name)
            if (!from.isDirectory) continue
            val to = File(context.filesDir, ".local/share/aster/$name")
            to.deleteRecursively()
            from.copyRecursively(to, overwrite = true)
            files += from.walkTopDown().count { it.isFile }
        }
        src.deleteRecursively()
        Log.i(TAG, "imported $files files")
        ActivityLog.state(context, "Imported $files files")
    }

    private companion object {
        const val TAG = "ASTERIMPORT"
        const val CLEAR_FEED = "dev.aster.probe.CLEAR_FEED"
    }
}
