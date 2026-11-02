package inflation

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.LongAdder

/**
 * Where a step's time goes: wall time and the processor time of the whole process for each named phase ([time]), and
 * with profile=jfr a recording of the run that `ProfileReport` reads (docs/profiling.md). The model's code carries no
 * timing of its own beyond its phases: functions are labelled with [Part] and sampled, parallel loops go through [Par],
 * and only what sampling cannot tell apart is timed here: a part's time split by kind ([kind]) and the slowest single
 * jobs with what they were about ([slow]).
 */
object Profile {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val wall = ConcurrentHashMap<String, Long>()
    private val cpu = ConcurrentHashMap<String, Long>()
    /** Phases in the order first timed. */
    private val order = CopyOnWriteArrayList<String>()

    inline fun <T> time(name: String, block: () -> T): T {
        val w0 = System.nanoTime(); val c0 = cpuNow()
        val event = if (JFR) PhaseEvent().also { it.begin() } else null
        val r = block()
        add(name, System.nanoTime() - w0, cpuNow() - c0)
        event?.end()
        if (event != null && event.shouldCommit()) { event.name = name; event.commit() }
        if (JFR) commitKinds()
        return r
    }

    /**
     * profile=jfr: a Java Flight Recording of the run ([profile.jfc]: samples, waits, GC, JIT, load, and the model's own
     * [PhaseEvent], [ParEvent], [JobEvent], [KindEvent] and [SlowEvent]) is written to `rec.jfr` in the run directory when
     * the process exits. Costs about 1-2% of the step and changes no code the JIT compiles; `ProfileReport` reads it.
     */
    val JFR = Config.string(Config.Key.PROFILE).also {
        require(it == null || it == "jfr") { "profile=$it: the only profile is jfr (profile=fine is gone; its parts, kinds and slowest jobs are in the recording)" }
    } == "jfr"
    init { if (JFR) startRecording() }
    private fun startRecording() {
        for (c in listOf(PhaseEvent::class.java, ParEvent::class.java, JobEvent::class.java, KindEvent::class.java, SlowEvent::class.java))
            jdk.jfr.FlightRecorder.register(c)
        val text = Profile::class.java.getResourceAsStream("/profile.jfc")!!.bufferedReader().use { it.readText() }
        val r = jdk.jfr.Recording(jdk.jfr.Configuration.create(java.io.StringReader(text)))
        r.setToDisk(true); r.setDumpOnExit(true); r.destination = java.nio.file.Path.of("rec.jfr")
        r.start()
    }

    fun cpuNow(): Long = os.processCpuTime

    fun add(name: String, wallNs: Long, cpuNs: Long) {
        if (wall.putIfAbsent(name, 0L) == null) order += name
        wall.merge(name, wallNs, Long::plus); cpu.merge(name, cpuNs, Long::plus)
    }

    fun reset() { slowest.clear(); wall.clear(); cpu.clear(); order.clear(); kinds.clear() }

    /** The time now, for a [kindLap] or [slow] to measure from (0 without a recording, which needs none). */
    fun mark(): Long = if (JFR) System.nanoTime() else 0L

    /**
     * [block], its time booked to [part] for [kind] (a kind of animal, say) with profile=jfr: summed over every thread that
     * ran it, and committed as one [KindEvent] for each part and kind as each phase ends. Sampling tells which functions
     * the time went to; this tells which kinds it went on.
     */
    inline fun <T> kind(part: String, kind: String, block: () -> T): T {
        if (!JFR) return block()
        val t = System.nanoTime(); val r = block(); kindTook(part, kind, System.nanoTime() - t)
        return r
    }

    /** As [kind], for a stretch of a loop begun at [since] ([mark]): books it and returns the time now, for the next stretch. */
    @IgnorableReturnValue fun kindLap(part: String, kind: String, since: Long): Long {
        if (!JFR) return 0L
        val now = System.nanoTime(); kindTook(part, kind, now - since)
        return now
    }

    /** Time and jobs booked to a part for a kind since the last [commitKinds]. */
    @PublishedApi internal class KindTally { val ns = LongAdder(); val jobs = LongAdder() }
    private val kinds = ConcurrentHashMap<String, ConcurrentHashMap<String, KindTally>>()

    @PublishedApi internal fun kindTook(part: String, kind: String, ns: Long) {
        // (get first: computeIfAbsent takes the table's bin lock even for an entry already there, and a hundred threads booking
        // the same few would wait on it.)
        val byKind = kinds[part] ?: kinds.computeIfAbsent(part) { ConcurrentHashMap() }
        val t = byKind[kind] ?: byKind.computeIfAbsent(kind) { KindTally() }
        t.ns.add(ns); t.jobs.increment()
    }

    /** One [KindEvent] for each part and kind booked since the last time, as a phase ends (when no job is running). */
    @PublishedApi internal fun commitKinds() {
        for ((part, byKind) in kinds) for ((kind, t) in byKind) {
            val jobs = t.jobs.sumThenReset(); val ns = t.ns.sumThenReset()
            if (jobs == 0L) continue
            val e = KindEvent(); e.begin(); e.end()
            if (e.shouldCommit()) { e.part = part; e.kind = kind; e.jobs = jobs; e.sumNs = ns; e.commit() }
        }
    }

    /**
     * The slowest single jobs, with profile=jfr: after a job begun at [since] ([mark]), one that is among the [SLOW_KEEP]
     * longest of its [what] so far is committed as a [SlowEvent] with the text [detail] gives (built only for one that ranks).
     */
    const val SLOW_KEEP = 8
    /** Jobs of the run's first [WARM_UP_DAYS] days are left out: they carry the JIT's and the class loading's time, not the model's. */
    const val WARM_UP_DAYS = 90L
    @PublishedApi internal val slowest = ConcurrentHashMap<String, java.util.TreeSet<Long>>()
    private val firstDay = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    inline fun slow(what: String, since: Long, day: Long, detail: () -> String) {
        if (JFR) { val ns = System.nanoTime() - since; if (slowRanks(what, ns, day)) slowTook(what, ns, detail()) }
    }
    @PublishedApi internal fun slowRanks(what: String, ns: Long, day: Long): Boolean {
        firstDay.compareAndSet(Long.MIN_VALUE, day)
        if (day >= 0 && day < firstDay.get() + WARM_UP_DAYS) return false
        val m = slowest[what] ?: slowest.computeIfAbsent(what) { java.util.TreeSet() }
        return synchronized(m) {
            val ranks = m.size < SLOW_KEEP || ns > m.first()
            if (ranks) { m += ns; if (m.size > SLOW_KEEP) m.pollFirst() }
            ranks
        }
    }
    @PublishedApi internal fun slowTook(what: String, ns: Long, detail: String) {
        val e = SlowEvent(); e.begin(); e.end()
        if (e.shouldCommit()) { e.what = what; e.detail = detail; e.ns = ns; e.commit() }
    }

    /** A table: each phase's share of the time, ms a step, and how many cores it kept busy on average. */
    fun report(steps: Int): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val total = order.sumOf { wall[it] ?: 0L }.coerceAtLeast(1L)
        return order.joinToString("\n") { k ->
            val w = wall[k] ?: 0L; val c = cpu[k] ?: 0L
            "  %-28s %6.1f ms a step  %4.0f%%  %4.1f of %d cores busy".format(k, w / 1e6 / steps, 100.0 * w / total, c.toDouble() / w.coerceAtLeast(1L), cores)
        }
    }
}
