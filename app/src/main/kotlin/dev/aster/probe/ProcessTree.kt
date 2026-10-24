package dev.aster.probe

import java.io.File

/**
 * A child's own children, found through `/proc`. Killing a shell does not kill
 * what it started: a command it forked keeps running, reparented, and holds the
 * output pipe open, so a stopped agent would live on and a restart would run a
 * second copy beside it.
 */
object ProcessTree {

    /** Kill everything [root] started, leaving [root] itself to its owner. */
    fun killBelow(root: Int) {
        below(root, parents()).forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
    }

    /** Every pid that descends from [root], given each pid's parent. */
    fun below(root: Int, parents: Map<Int, Int>): List<Int> {
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val found = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            children[queue.removeFirst()].orEmpty().forEach { child ->
                if (child != root && child !in found) {
                    found += child
                    queue += child
                }
            }
        }
        return found
    }

    /** The parent pid in a `/proc/<pid>/stat` line. The name in parentheses may hold spaces and parentheses itself. */
    fun parentOf(stat: String): Int? =
        stat.substringAfterLast(')', "").trim().split(' ').getOrNull(1)?.toIntOrNull()

    private fun parents(): Map<Int, Int> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val parent = runCatching { File(dir, "stat").readText() }.getOrNull()?.let(::parentOf) ?: return@mapNotNull null
        pid to parent
    }.toMap()
}
