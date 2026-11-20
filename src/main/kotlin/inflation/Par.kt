package inflation

import inflation.world.Hex
import inflation.world.Place
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.atomic.LongAccumulator
import java.util.function.IntConsumer
import java.util.stream.IntStream

/** One phase of the week, from its start to its end (see [Profile.time]); the time axis the other events are joined to. */
@Name("inflation.Phase") @Label("Phase") @Category("Inflation") @StackTrace(false)
class PhaseEvent : Event() {
    @JvmField @field:Label("Name") var name: String = ""
}

/** One [Par.forEach] call: how many jobs, the sum of their time, the longest, and (by the event's own duration) its wall time. */
@Name("inflation.Par") @Label("Parallel loop") @Category("Inflation") @StackTrace(false)
class ParEvent : Event() {
    @JvmField @field:Label("Name") var name: String = ""
    @JvmField @field:Label("Jobs") var jobs: Long = 0
    @JvmField @field:Label("Job time sum (ns)") var sumNs: Long = 0
    @JvmField @field:Label("Longest job (ns)") var maxNs: Long = 0
}

/** One job of a [Par.forEach] loop; recorded only when it ran longer than the setting's threshold (profile.jfc: 1 ms). */
@Name("inflation.Job") @Label("Slow job") @Category("Inflation") @StackTrace(false)
class JobEvent : Event() {
    @JvmField @field:Label("Loop") var name: String = ""
    @JvmField @field:Label("Index") var index: Int = 0
}

/** A part's time for one kind over a phase ([Profile.kind]): the jobs and the sum of their time, on every thread that ran them. */
@Name("inflation.Kind") @Label("Time by kind") @Category("Inflation") @StackTrace(false)
class KindEvent : Event() {
    @JvmField @field:Label("Part") var part: String = ""
    @JvmField @field:Label("Kind") var kind: String = ""
    @JvmField @field:Label("Jobs") var jobs: Long = 0
    @JvmField @field:Label("Job time sum (ns)") var sumNs: Long = 0
}

/** A job among the slowest of its kind so far ([Profile.slow]): what it was, what it was about, and how long it took. */
@Name("inflation.Slow") @Label("Slowest job") @Category("Inflation") @StackTrace(false)
class SlowEvent : Event() {
    @JvmField @field:Label("What") var what: String = ""
    @JvmField @field:Label("Detail") var detail: String = ""
    @JvmField @field:Label("Time (ns)") var ns: Long = 0
}

/**
 * The model's parallel loops over `0 until n`, in one place so that the profiler can measure them without the model's
 * code carrying any timing. With profile off it is exactly `IntStream.range(0, n).parallel().forEach(body)`, inlined at
 * the call site. With profile=jfr each call also commits a [ParEvent] (jobs, summed and longest job time; its duration is
 * the loop's wall time) and a [JobEvent] for each job over the threshold.
 */
object Par {
    inline fun forEach(name: String, n: Int, crossinline body: (Int) -> Unit) {
        val job = IntConsumer { body(it) }   // (one copy of the body, whichever way it runs)
        if (!Profile.JFR) IntStream.range(0, n).parallel().forEach(job) else measured(name, n, job)
    }

    /** [forEach] over the places `0 until n`, each as a [Place]. */
    inline fun forEachPlace(name: String, n: Int, crossinline body: (Place) -> Unit) = forEach(name, n) { body(Place(it)) }

    /** [forEach] over the hexes `0 until n`, each as a [Hex]. */
    inline fun forEachHex(name: String, n: Int, crossinline body: (Hex) -> Unit) = forEach(name, n) { body(Hex(it)) }

    /** [forEach] over the [items] of a list. */
    inline fun <T> forEach(name: String, items: List<T>, crossinline body: (T) -> Unit) = forEach(name, items.size) { body(items[it]) }

    @PublishedApi internal fun measured(name: String, n: Int, body: IntConsumer) {
        val sum = LongAdder(); val longest = LongAccumulator(java.util.function.LongBinaryOperator { a, b -> maxOf(a, b) }, 0L)
        val loop = ParEvent().also { it.begin() }
        IntStream.range(0, n).parallel().forEach { i ->
            val job = JobEvent().also { it.begin() }
            val t0 = System.nanoTime()
            body.accept(i)
            val ns = System.nanoTime() - t0
            sum.add(ns); longest.accumulate(ns)
            job.end()
            if (job.shouldCommit()) { job.name = name; job.index = i; job.commit() }
        }
        loop.end()
        if (loop.shouldCommit()) { loop.name = name; loop.jobs = n.toLong(); loop.sumNs = sum.sum(); loop.maxNs = longest.get(); loop.commit() }
    }
}

