package inflation

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.LongAdder

/**
 * Where a step's time goes: wall time and the processor time of the whole process for each named
 * part, so that parts run on one core show up beside those that use them all.
 */
object Profile {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val wall = ConcurrentHashMap<String, Long>()
    private val cpu = ConcurrentHashMap<String, Long>()
    /** Parts in the order first timed. */
    private val order = CopyOnWriteArrayList<String>()

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

    fun reset() { slowest.clear(); slowCount.clear(); wall.clear(); cpu.clear(); order.clear(); parts.clear(); partOrder.clear() }

    /**
     * Finer parts (with profile=fine): time spent in each, summed over all the threads that ran
     * it (so a part inside a loop over places run on every core counts every core's time). Use as
     * `var t = Profile.mark(); ...; t = Profile.lap("part", t)`.
     */
    val FINE = Config.string(Config.Key.PROFILE) == "fine"
    private val parts = ConcurrentHashMap<String, LongAdder>()
    private val partOrder = CopyOnWriteArrayList<String>()
    fun mark(): Long = if (FINE) System.nanoTime() else 0L
    @IgnorableReturnValue fun lap(name: String, since: Long): Long {
        if (!FINE) return 0L
        val now = System.nanoTime()
        // (get first: computeIfAbsent takes the table's bin lock even for a part already there, and with a hundred threads timing
        // the same few parts the wait for it was booked to the part timed next.)
        (parts[name] ?: parts.computeIfAbsent(name) { partOrder += it; LongAdder() }).add(now - since)
        return now
    }
    inline fun <T> part(name: String, block: () -> T): T { val t = mark(); val r = block(); lap(name, t); return r }
    /** Part [name], split by [kind] with profile=fine (a kind of animal, say); else [name] alone, built only when fine. */
    fun byKind(name: String, kind: String) = if (FINE) "$name / $kind" else name

    /**
     * The slowest single jobs (with profile=fine): after a job begun at [since] ([mark]), keeps the [SLOW_KEEP] longest of
     * each [what] with the text [detail] gives (built only for one that ranks). Printed with [fineReport].
     */
    const val SLOW_KEEP = 8
    @PublishedApi internal val slowest = ConcurrentHashMap<String, java.util.TreeMap<Long, String>>()
    @PublishedApi internal val slowCount = ConcurrentHashMap<String, LongAdder>()
    /** Jobs of the run's first [WARM_UP_DAYS] days are left out: they carry the JIT's and the class loading's time, not the model's. */
    const val WARM_UP_DAYS = 90L
    private val firstDay = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    inline fun slow(what: String, since: Long, day: Long, detail: () -> String) { if (FINE) { val ns = System.nanoTime() - since; if (slowRanks(what, ns, day)) slowTook(what, ns, detail()) } }
    @PublishedApi internal fun slowRanks(what: String, ns: Long, day: Long): Boolean {
        firstDay.compareAndSet(Long.MIN_VALUE, day)
        if (day >= 0 && day < firstDay.get() + WARM_UP_DAYS) return false
        (slowCount[what] ?: slowCount.computeIfAbsent(what) { LongAdder() }).increment()
        val m = slowest[what] ?: slowest.computeIfAbsent(what) { java.util.TreeMap() }
        return synchronized(m) { m.size < SLOW_KEEP || ns > m.firstKey() }
    }
    @PublishedApi internal fun slowTook(what: String, ns: Long, detail: String) {
        val m = slowest.computeIfAbsent(what) { java.util.TreeMap() }
        synchronized(m) {
            m[ns] = detail
            if (m.size > SLOW_KEEP) m.pollFirstEntry()
        }
    }
    fun slowReport(): String = slowest.keys.sorted().joinToString("\n") { k ->
        val m = slowest.getValue(k)
        "  slowest $k (of ${slowCount.getValue(k).sum()}):\n" + synchronized(m) { m.descendingMap().entries.joinToString("\n") { "    %7.2f ms  %s".format(it.key / 1e6, it.value) } }
    }

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
