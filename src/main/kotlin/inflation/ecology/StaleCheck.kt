package inflation.ecology

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * A check, off unless the environment says INFLATION_STALE=1, for reads of the step's who-is-where indexes made after groups
 * moved or were made since the index was built ([moved], [read]): the first step of making one index of who is where
 * (docs/design/consolidation-review.md, 4). Each index is stamped with [moves] when it is built, or, one that finds groups
 * by id (where they are does not matter to it), with [makes]; a read with another count is counted by index and the line that
 * read it, and the counts are printed when the run ends. Changes nothing in the run.
 */
internal object StaleCheck {
    val on = System.getenv("INFLATION_STALE") == "1"

    /** Groups moved into another hex, or made, so far (only counted when [on]). */
    @Volatile var moves = 0L

    /** Groups made so far (only counted when [on]). */
    @Volatile var makes = 0L

    /** A group moved into another hex. */
    fun moved() { if (on) moves++ }

    /** A new group was made: it is somewhere new, too. */
    fun made() { if (on) { moves++; makes++ } }

    /** The count an index of where groups are built now carries. */
    fun stamp(): Long = moves

    /** The count an index of groups by id built now carries. */
    fun madeStamp(): Long = makes

    /** Where an index was read: which, and the file and line that read it. */
    private data class Site(val index: String, val file: String?, val line: Int) {
        override fun toString() = "$index read at $file:$line"
    }
    private val reads = ConcurrentHashMap<Site, LongAdder>()
    private val all = ConcurrentHashMap<Site, LongAdder>()

    /** A read of [index] of where groups are, built at [stamp]: counted as stale where groups have moved or been made since. */
    fun read(index: String, stamp: Long) { if (on) count(index, stamp != moves) }

    /** A read of [index] of groups by id, built at [stamp] ([madeStamp]): counted as stale where groups have been made since. */
    fun readById(index: String, stamp: Long) { if (on) count(index, stamp != makes) }

    /** Counts a read by the line that called [read] or [readById]. */
    private fun count(index: String, stale: Boolean) {
        val f = Throwable().stackTrace.getOrNull(2)
        val at = Site(index, f?.fileName, f?.lineNumber ?: -1)
        all.computeIfAbsent(at) { LongAdder() }.increment()
        if (stale) reads.computeIfAbsent(at) { LongAdder() }.increment()
    }

    init {
        if (on) Runtime.getRuntime().addShutdownHook(Thread {
            println("stale reads of the who-is-where indexes (read after groups moved or were made since it was built: stale / all reads):")
            for ((k, n) in all.entries.sortedBy { it.key.toString() }) println("  $k: ${reads[k]?.sum() ?: 0} / ${n.sum()}")
        })
    }
}
