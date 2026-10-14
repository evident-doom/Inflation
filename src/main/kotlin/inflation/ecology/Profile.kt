package inflation.ecology

import java.lang.management.ManagementFactory

/**
 * Where a step's time goes: wall time and the processor time of the whole process for each named
 * part, so that parts run on one core show up beside those that use them all.
 */
object Profile {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val wall = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cpu = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Parts in the order first timed. */
    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

    inline fun <T> time(name: String, block: () -> T): T {
        val w0 = System.nanoTime(); val c0 = cpuNow()
        val r = block()
        add(name, System.nanoTime() - w0, cpuNow() - c0)
        return r
    }

    fun cpuNow(): Long = os.processCpuTime

    fun add(name: String, wallNs: Long, cpuNs: Long) {
        if (wall.putIfAbsent(name, 0L) == null) order += name
        wall.merge(name, wallNs, Long::plus); cpu.merge(name, cpuNs, Long::plus)
    }

    fun reset() { wall.clear(); cpu.clear(); order.clear(); parts.clear(); partOrder.clear() }

    /**
     * Finer parts (with -Dhex.profile=fine): time spent in each, summed over all the threads that ran
     * it (so a part inside a loop over places run on every core counts every core's time). Use as
     * `var t = Profile.mark(); ...; t = Profile.lap("part", t)`.
     */
    val FINE = System.getProperty("hex.profile") == "fine"
    private val parts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>()
    private val partOrder = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun mark(): Long = if (FINE) System.nanoTime() else 0L
    fun lap(name: String, since: Long): Long {
        if (!FINE) return 0L
        val now = System.nanoTime()
        parts.computeIfAbsent(name) { partOrder += it; java.util.concurrent.atomic.LongAdder() }.add(now - since)
        return now
    }
    inline fun <T> part(name: String, block: () -> T): T { val t = mark(); val r = block(); lap(name, t); return r }

    /** The finer parts, by thread time a step (ms) and share of all of it, most first. */
    fun fineReport(steps: Int): String {
        val total = parts.values.sumOf { it.sum() }.coerceAtLeast(1L)
        return partOrder.sortedByDescending { parts.getValue(it).sum() }.joinToString("\n") { k ->
            val v = parts.getValue(k).sum()
            "  %-44s %7.1f thread-ms a step  %4.1f%%".format(k, v / 1e6 / steps, 100.0 * v / total)
        }
    }

    /** A table: each part's share of the time, ms a step, and how many cores it kept busy on average. */
    fun report(steps: Int): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val total = order.sumOf { wall[it] ?: 0L }.coerceAtLeast(1L)
        return order.joinToString("\n") { k ->
            val w = wall[k] ?: 0L; val c = cpu[k] ?: 0L
            "  %-28s %6.1f ms a step  %4.0f%%  %4.1f of %d cores busy".format(k, w / 1e6 / steps, 100.0 * w / total, c.toDouble() / w.coerceAtLeast(1L), cores)
        }
    }
}
