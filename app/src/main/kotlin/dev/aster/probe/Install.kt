package dev.aster.probe

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Everything the agent needs on disk before it starts. This used to run from
 * the activity, which meant it only happened when someone opened the app: on a
 * phone with the screen off the activity never resumes, so after an update the
 * agent came up pointing at paths that no longer existed. The services run
 * without a UI, so they do it instead.
 */
object Install {

    fun refresh(context: Context) {
        binDir(context)
        skill(context)
        instructions(context)
        env(context)
    }

    /**
     * Keys can also arrive as a `.env` pushed into the app's external files
     * dir, the one place adb can write without run-as; it is folded into the
     * one inside and never kept there. Folded rather than copied over, because
     * the phone's own settings screen writes to the same file and a push that
     * carries one key should not wipe the rest.
     */
    fun env(context: Context) {
        val dropped = File(context.getExternalFilesDir(null) ?: return, ".env")
        if (!dropped.isFile) return
        runCatching {
            Env.merge(context, dropped)
            dropped.delete()
            Log.i(TAG, "imported .env")
        }.onFailure { Log.w(TAG, "could not import .env: $it") }
    }

    /**
     * The agent calls the control client and itself by name, so give both one. A symlink
     * keeps the SELinux label of its target, which is why this works while a
     * copy into the data directory would not be executable at all.
     */
    fun binDir(context: Context): File {
        val bin = File(context.filesDir, "bin").apply { mkdirs() }
        // Every install lands in a new nativeLibraryDir, so a link made by the
        // previous version points at a path that no longer exists.
        for ((name, lib) in listOf("asterctl" to "libclient.so", "aster" to "libaster.so")) {
            val link = File(bin, name)
            runCatching { link.delete() }
            runCatching {
                android.system.Os.symlink(
                    File(context.applicationInfo.nativeLibraryDir, lib).absolutePath,
                    link.absolutePath,
                )
            }.onFailure { Log.w(TAG, "could not link $name: $it") }
        }
        return bin
    }

    /**
     * Ship the skills with the app; copying them by hand is a step that gets
     * forgotten. The gradle syncDocs task decides what is here, so a new skill
     * needs no list to be kept in step with it.
     */
    fun skill(context: Context) {
        val assets = context.assets.list("").orEmpty()
        for (file in assets) {
            if (!file.endsWith(".md") || file == "AGENTS.md") continue
            val name = file.removeSuffix(".md")
            copyAsset(context, file, File(context.filesDir, ".aster/skills/$name/SKILL.md"))
        }
    }

    /** The standing instructions, in the directory the agent starts in. */
    fun instructions(context: Context) {
        copyAsset(context, "AGENTS.md", File(context.filesDir, "AGENTS.md"))
    }

    private fun copyAsset(context: Context, name: String, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(name).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Log.w(TAG, "could not install $name: $it") }
    }

    private const val TAG = "ASTERINSTALL"
}
