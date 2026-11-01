package inflation.tools

import inflation.Part
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordingFile
import java.io.File
import java.util.jar.JarFile

/**
 * Reads the Java Flight Recording a `profile=jfr` run wrote (`rec.jfr`, see docs/profiling.md) and tells where the week's
 * time goes: each phase's wall time, each parallel loop's wall time and how well it used the cores, where the samples fell
 * (by [Part] label, then by the loop or phase they fell in), what blocked threads, GC, JIT failures, and whether the
 * machine was quiet enough to trust the run. With `--compare` it puts two arms (each one or more recordings) side by side
 * with their run-to-run spread.
 *
 * Usage: profile-report REC.jfr [--skip-steps N]
 *        profile-report --compare A1.jfr,A2.jfr B1.jfr,B2.jfr [--skip-steps N]
 */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "profile-report REC.jfr [--skip-steps N] | --compare A1.jfr,A2.jfr B1.jfr,B2.jfr [--skip-steps N]")
    val skip = a.int("--skip-steps", 6)
    val compare = a.flag("--compare")
    val words = a.rest()
    a.done()
    if (compare) {
        require(words.size == 2) { "--compare takes two arms, each a comma-separated list of recordings" }
        val arms = words.map { arm -> arm.split(',').filter { it.isNotBlank() }.map { ProfileRead.read(File(it), skip) } }
        println(ProfileText.compare(arms[0], arms[1]))
    } else {
        require(words.size == 1) { "give one recording" }
        println(ProfileText.report(ProfileRead.read(File(words[0]), skip)))
    }
}

/** Time spent in a named thing over the run: how many times, wall ns (summed over its calls), and for loops the jobs and their time. */
internal class Agg {
    var calls = 0L; var wallNs = 0L; var jobs = 0L; var sumNs = 0L; var maxNs = 0L; var slowJobs = 0L
    var samples = 0L; var mainSamples = 0L
}

/** One recording, boiled down. Times are per step after the first [skipped] steps (JIT warm-up) are left out. */
internal class ProfileRun(val file: String) {
    var steps = 0; var skipped = 0
    var seconds = 0.0
    val phases = LinkedHashMap<String, Agg>()          // top-level phases
    val inner = LinkedHashMap<String, Agg>()           // phases timed inside another
    val loops = HashMap<String, Agg>()                 // Par.forEach calls by name
    val labels = HashMap<String, Agg>()                // sample labels
    val phaseSamples = HashMap<String, Agg>()          // every sample, by the top-level phase it fell in
    val frames = HashMap<String, Long>()               // top self frame -> samples
    var samples = 0L; var mainSamples = 0L
    val monitors = HashMap<String, Pair<Long, Long>>() // where threads waited for a monitor -> (count, ns)
    var mainParkNs = 0L
    var gcCount = 0; var gcPauseNs = 0L; var gcMaxNs = 0L
    val jitFailures = ArrayList<String>()
    var cpuSamples = 0; var otherAvg = 0.0; var otherMax = 0.0; var jvmAvg = 0.0
    fun stepMs() = phases.values.sumOf { it.wallNs } / 1e6 / steps.coerceAtLeast(1)
    fun perStepMs(g: Agg) = g.wallNs / 1e6 / steps.coerceAtLeast(1)
}

private class Span(val start: Long, val end: Long, val name: String)

internal object ProfileRead {
    private fun RecordedEvent.s() = startTime.let { it.epochSecond * 1_000_000_000L + it.nano }
    private fun RecordedEvent.e() = endTime.let { it.epochSecond * 1_000_000_000L + it.nano }

    /** The spans not inside an earlier one, in time order (loops and phases run one after another, so these are the outer ones). */
    private fun outermost(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })) {
            if (out.isNotEmpty() && sp.start >= out.last().start && sp.end <= out.last().end) continue
            out += sp
        }
        return out
    }

    private fun containing(spans: List<Span>, starts: LongArray, t: Long): Span? {
        var i = java.util.Arrays.binarySearch(starts, t)
        if (i < 0) i = -i - 2
        return if (i >= 0 && t <= spans[i].end) spans[i] else null
    }

    /** `Class.method` -> its [Part] name, for every labelled method in the classes this program runs with. */
    private val partNames: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        val loc = File(Part::class.java.protectionDomain.codeSource.location.toURI())
        val names = ArrayList<String>()
        if (loc.isDirectory) loc.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { names += it.relativeTo(loc).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        else JarFile(loc).use { jar -> for (en in jar.entries()) if (en.name.endsWith(".class")) names += en.name.removeSuffix(".class").replace('/', '.') }
        for (n in names) {
            val c = try { Class.forName(n, false, Part::class.java.classLoader) } catch (e: Throwable) { continue }
            val ms = try { c.declaredMethods } catch (e: Throwable) { continue }
            for (m in ms) m.getAnnotation(Part::class.java)?.let { out["${c.name}.${m.name}"] = it.name }
        }
        out
    }

    private val frameLabel = HashMap<String, String?>()
    /** The part a stack frame belongs to: a labelled method, or the method a Kotlin lambda or inlined body came out of. */
    private fun labelOf(f: RecordedFrame): String? {
        val m = f.method
        val cls = m.type.name.replace('/', '.')
        val key = cls + "." + m.name
        return frameLabel.getOrPut(key) {
            val outer = cls.substringBefore('$'); val rest = cls.substringAfter('$', "")
            partNames["$outer.${m.name.substringBefore('$')}"]
                ?: (if (rest.isNotEmpty()) partNames["$outer.${rest.substringBefore('$')}"] else null)
        }
    }

    private fun name(m: RecordedMethod) = m.type.name.replace('/', '.') + "." + m.name

    fun read(file: File, skip: Int): ProfileRun {
        val run = ProfileRun(file.name)
        val events = RecordingFile.readAllEvents(file.toPath())
        val phaseEvents = events.filter { it.eventType.name == "inflation.Phase" }.sortedBy { it.s() }
        require(phaseEvents.isNotEmpty()) { "$file has no inflation.Phase events: was it run with profile=jfr?" }
        val firstName = phaseEvents[0].getString("name")
        val stepStarts = phaseEvents.filter { it.getString("name") == firstName }.map { it.s() }
        run.skipped = if (stepStarts.size > skip + 1) skip else 0
        val from = stepStarts[run.skipped]
        run.steps = stepStarts.size - run.skipped
        val last = events.maxOf { it.e() }
        run.seconds = (last - from) / 1e9
        val inRange = events.filter { it.s() >= from }

        // Phases: the outer ones are the step; others are timed inside them.
        val ph = inRange.filter { it.eventType.name == "inflation.Phase" }
        val spans = ph.map { Span(it.s(), it.e(), it.getString("name")) }
        val outer = outermost(spans); val outerSet = outer.toHashSet()
        for (sp in spans.sortedBy { it.start }) {
            val g = (if (sp in outerSet) run.phases else run.inner).getOrPut(sp.name) { Agg() }
            g.calls++; g.wallNs += sp.end - sp.start
        }
        val phaseStarts = LongArray(outer.size) { outer[it].start }

        // Loops.
        val loopEvents = inRange.filter { it.eventType.name == "inflation.Par" }
        for (ev in loopEvents) {
            val g = run.loops.getOrPut(ev.getString("name")) { Agg() }
            g.calls++; g.wallNs += ev.e() - ev.s(); g.jobs += ev.getLong("jobs"); g.sumNs += ev.getLong("sumNs"); g.maxNs = maxOf(g.maxNs, ev.getLong("maxNs"))
        }
        for (ev in inRange) if (ev.eventType.name == "inflation.Job") run.loops.getOrPut(ev.getString("name")) { Agg() }.slowJobs++
        val loopSpans = outermost(loopEvents.map { Span(it.s(), it.e(), it.getString("name")) })
        val loopStarts = LongArray(loopSpans.size) { loopSpans[it].start }

        // Samples: the innermost labelled method on the stack, else the loop it fell in, else the phase.
        for (ev in inRange) when (ev.eventType.name) {
            "jdk.ExecutionSample" -> {
                val th = ev.getThread("sampledThread"); val main = th?.javaName == "main"
                val fr = ev.stackTrace?.frames.orEmpty()
                val t = ev.s()
                val label = fr.firstNotNullOfOrNull { labelOf(it) }
                    ?: containing(loopSpans, loopStarts, t)?.let { "loop: ${it.name}" }
                    ?: containing(outer, phaseStarts, t)?.let { "phase: ${it.name} (no label)" } ?: "outside the phases"
                val g = run.labels.getOrPut(label) { Agg() }
                g.samples++; run.samples++
                if (main) { g.mainSamples++; run.mainSamples++ }
                containing(outer, phaseStarts, t)?.let { sp ->
                    val pg = run.phaseSamples.getOrPut(sp.name) { Agg() }
                    pg.samples++
                    if (main) pg.mainSamples++
                }
                fr.firstOrNull()?.let { f -> run.frames.merge(name(f.method), 1L, Long::plus) }
            }
            "jdk.JavaMonitorEnter" -> {
                val fr = ev.stackTrace?.frames.orEmpty()
                val where = fr.firstOrNull { name(it.method).startsWith("inflation.") }?.let { name(it.method) } ?: fr.firstOrNull()?.let { name(it.method) } ?: "?"
                val on = ev.getClass("monitorClass")?.name?.replace('/', '.') ?: "?"
                val key = "$where  (on $on)"
                val old = run.monitors[key] ?: (0L to 0L)
                run.monitors[key] = (old.first + 1) to (old.second + ev.duration.toNanos())
            }
            "jdk.ThreadPark" -> if (ev.thread?.javaName == "main") run.mainParkNs += ev.duration.toNanos()
            "jdk.GarbageCollection" -> {
                run.gcCount++; run.gcPauseNs += ev.getDuration("sumOfPauses").toNanos(); run.gcMaxNs = maxOf(run.gcMaxNs, ev.getDuration("longestPause").toNanos())
            }
        }

        // JIT: failures, with the method from the compilation of the same id.
        val compiled = HashMap<Long, String>()
        for (ev in events) if (ev.eventType.name == "jdk.Compilation") ev.getValue<RecordedMethod>("method")?.let { compiled[ev.getLong("compileId")] = name(it) }
        for (ev in events) if (ev.eventType.name == "jdk.CompilationFailure")
            run.jitFailures += "${compiled[ev.getLong("compileId")] ?: "(method not recorded)"}: ${ev.getString("failureMessage")}"

        // Machine load: what other processes used.
        val loads = events.filter { it.eventType.name == "jdk.CPULoad" && it.s() >= from }
        if (loads.isNotEmpty()) {
            val other = loads.map { (it.getFloat("machineTotal") - it.getFloat("jvmUser") - it.getFloat("jvmSystem")).toDouble() }
            run.cpuSamples = loads.size; run.otherAvg = other.average(); run.otherMax = other.max()
            run.jvmAvg = loads.map { (it.getFloat("jvmUser") + it.getFloat("jvmSystem")).toDouble() }.average()
        }
        return run
    }
}

internal object ProfileText {
    private fun pct(x: Long, of: Long) = if (of == 0L) 0.0 else 100.0 * x / of
    private fun f1(x: Double) = "%.1f".format(x)

    fun report(r: ProfileRun): String = buildString {
        val step = r.stepMs()
        appendLine("# Profile of ${r.file}")
        appendLine()
        appendLine("${r.steps} steps after skipping the first ${r.skipped} (warm-up), ${f1(r.seconds)} s; step ${f1(step)} ms; ${r.samples} execution samples (${f1(pct(r.mainSamples, r.samples))}% on the main thread).")
        appendLine()
        appendLine("## Reliability")
        val warnings = ArrayList<String>()
        if (r.cpuSamples > 0 && (r.otherAvg > 0.05 || r.otherMax > 0.20))
            warnings += "other processes used the machine: ${f1(100 * r.otherAvg)}% on average, up to ${f1(100 * r.otherMax)}% (the JVM used ${f1(100 * r.jvmAvg)}%): compare only runs made under the same load"
        if (r.skipped == 0) warnings += "the run is too short to skip a warm-up: the first steps carry the JIT's time"
        if (r.steps < 20) warnings += "only ${r.steps} steps: parts under a few percent are noise"
        val realJit = r.jitFailures.count { !it.contains("retry without subsuming loads") && !it.contains("Jvmti state change") }
        if (realJit > 0) warnings += "$realJit JIT compilations failed and left a method uncompiled (below)"
        if (warnings.isEmpty()) appendLine("No warnings.") else warnings.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## The week's phases (wall time; they run one after another)")
        appendLine("| phase | ms/step | % of step | samples % | on main thread % |")
        appendLine("|---|---:|---:|---:|---:|")
        for ((k, g) in r.phases.entries.sortedByDescending { it.value.wallNs }) {
            val s = r.phaseSamples[k]
            appendLine("| $k | ${f1(r.perStepMs(g))} | ${f1(100 * r.perStepMs(g) / step)} | ${if (s == null) "-" else f1(pct(s.samples, r.samples))} | ${if (s == null) "-" else f1(pct(s.mainSamples, s.samples))} |")
        }
        if (r.inner.isNotEmpty()) {
            appendLine()
            appendLine("Timed inside the phases above: " + r.inner.entries.sortedByDescending { it.value.wallNs }.joinToString("; ") { "${it.key} ${f1(r.perStepMs(it.value))} ms/step" })
        }
        appendLine()
        appendLine("(The sample columns say where CPU went, not wall time: samples % is the share of all samples that fell in the phase; on main thread % is the share of those on the main thread, so a high figure means the phase runs mostly serially.)")
        appendLine()
        appendLine("## Parallel loops (`Par.forEach`)")
        appendLine("Parallelism is the jobs' summed time over the loop's wall time: how many cores it kept busy on average. A loop whose wall time is far above its longest job and whose parallelism is low is serial or starved.")
        appendLine("| loop | calls/step | wall ms/step | jobs/step | mean job µs | longest job ms | parallelism | jobs over 1 ms |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
        for ((k, g) in r.loops.entries.filter { it.value.calls > 0 }.sortedByDescending { it.value.wallNs }) {
            val steps = r.steps.coerceAtLeast(1)
            appendLine("| $k | ${f1(g.calls.toDouble() / steps)} | ${f1(r.perStepMs(g))} | ${"%.0f".format(g.jobs.toDouble() / steps)} | ${f1(g.sumNs / 1e3 / g.jobs.coerceAtLeast(1))} | ${f1(g.maxNs / 1e6)} | ${f1(g.sumNs.toDouble() / g.wallNs.coerceAtLeast(1))} | ${g.slowJobs} |")
        }
        appendLine()
        appendLine("## Where the samples fell (innermost `@Part` on the stack, else the loop, else the phase)")
        appendLine("| part | samples % | on main thread % |")
        appendLine("|---|---:|---:|")
        for ((k, g) in r.labels.entries.sortedByDescending { it.value.samples }.take(25))
            appendLine("| $k | ${f1(pct(g.samples, r.samples))} | ${f1(pct(g.mainSamples, g.samples))} |")
        appendLine()
        appendLine("Hottest frames (self): " + r.frames.entries.sortedByDescending { it.value }.take(12).joinToString("; ") { "${it.key} ${f1(pct(it.value, r.samples))}%" })
        appendLine()
        appendLine("## Waits")
        appendLine("Main thread waiting for the workers (joins): ${f1(r.mainParkNs / 1e6 / r.steps.coerceAtLeast(1))} ms/step.")
        if (r.monitors.isEmpty()) appendLine("No monitor waits over 1 ms.") else {
            appendLine("Threads blocked on a monitor (over 1 ms), summed over all threads:")
            appendLine("| where | on | count | total ms | ms/step |")
            appendLine("|---|---|---:|---:|---:|")
            for ((k, v) in r.monitors.entries.sortedByDescending { it.value.second }.take(10)) {
                val where = k.substringBefore("  (on "); val on = k.substringAfter("  (on ").removeSuffix(")")
                appendLine("| $where | $on | ${v.first} | ${f1(v.second / 1e6)} | ${f1(v.second / 1e6 / r.steps.coerceAtLeast(1))} |")
            }
        }
        appendLine()
        appendLine("## GC and JIT")
        appendLine("${r.gcCount} collections, pauses ${f1(r.gcPauseNs / 1e6)} ms in all, longest ${f1(r.gcMaxNs / 1e6)} ms.")
        // ("retry without subsuming loads" is C2 compiling the method again with other settings, which then goes through;
        // "Jvmti state change" is the recording itself changing classes; the others leave the method without that compile.)
        val benign = r.jitFailures.filter { it.contains("retry without subsuming loads") || it.contains("Jvmti state change") }
        val real = r.jitFailures - benign.toSet()
        if (r.jitFailures.isEmpty()) appendLine("No JIT compilation failures.") else {
            appendLine("${benign.size} JIT failures are harmless retries or the recording's own doing; ${real.size} leave a method without a compile:")
            real.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { appendLine("- ${it.value} x ${it.key}") }
            appendLine("(A method that fails in the C1 tier-3 compile with \"out of virtual registers\" may never get a normal C2 compile: split it, see docs on HugeMethodTest.)")
        }
    }

    /** One number per run of an arm: its mean and its spread (largest minus smallest). */
    private class Arm(val values: List<Double>) {
        val mean = values.average()
        val spread = if (values.size > 1) values.max() - values.min() else Double.NaN
    }

    fun compare(a: List<ProfileRun>, b: List<ProfileRun>): String = buildString {
        appendLine("# Comparison: A = ${a.joinToString(", ") { it.file }}; B = ${b.joinToString(", ") { it.file }}")
        appendLine()
        val warn = (a + b).filter { it.cpuSamples > 0 && (it.otherAvg > 0.05 || it.otherMax > 0.20) }
        if (warn.isNotEmpty()) appendLine("Loaded machine in: ${warn.joinToString(", ") { "${it.file} (others ${f1(100 * it.otherAvg)}% avg)" }}. Differences within the spread are noise; so may be larger ones.")
        appendLine("Rows are wall ms per step. A verdict of 'beyond spread' means the arms' means differ by more than the larger run-to-run spread of the two (needs two or more recordings in an arm); with one recording each there is no spread and a difference is not shown to be real.")
        appendLine()
        fun table(title: String, get: (ProfileRun) -> Map<String, Double>) {
            val names = (a + b).flatMap { get(it).keys }.distinct()
            fun arm(runs: List<ProfileRun>, n: String) = Arm(runs.map { get(it)[n] ?: 0.0 })
            appendLine("## $title")
            appendLine("| name | A | B | B - A | % | verdict |")
            appendLine("|---|---:|---:|---:|---:|---|")
            for (n in names.sortedByDescending { maxOf(arm(a, it).mean, arm(b, it).mean) }.take(30)) {
                val x = arm(a, n); val y = arm(b, n)
                val d = y.mean - x.mean
                val sp = listOf(x.spread, y.spread).filter { !it.isNaN() }.maxOrNull()
                val verdict = if (sp == null) "single run" else if (kotlin.math.abs(d) > sp) "beyond spread" else "within spread"
                appendLine("| $n | ${f1(x.mean)}${if (x.spread.isNaN()) "" else " ±${f1(x.spread / 2)}"} | ${f1(y.mean)}${if (y.spread.isNaN()) "" else " ±${f1(y.spread / 2)}"} | ${f1(d)} | ${if (x.mean == 0.0) "-" else f1(100 * d / x.mean)} | $verdict |")
            }
            appendLine()
        }
        table("The step and its phases") { r -> r.phases.mapValues { r.perStepMs(it.value) } + ("whole step" to r.stepMs()) }
        table("Parallel loops") { r -> r.loops.filter { it.value.calls > 0 }.mapValues { r.perStepMs(it.value) } }
    }
}
