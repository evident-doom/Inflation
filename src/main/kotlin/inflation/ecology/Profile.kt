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

    fun reset() { wall.clear(); cpu.clear(); order.clear() }

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
