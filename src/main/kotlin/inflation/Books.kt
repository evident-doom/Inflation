package inflation

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder
import kotlin.math.abs

/**
 * The model's books (audit=true in the config): the world is closed, so between any two points of a step its
 * stocks of water and of matter change by exactly what crossed its edge, rain in and evaporation out, plants'
 * growth in and breath and rot out. Each part names its stocks ([stock]) and the flows across the edge as they
 * happen ([flow]); after each part of the step ([check]) the change in the stocks is set against those flows.
 * What is left over was made or lost by that part outside any process: a leak, reported by part ([report]).
 * A leak already known is booked by name where it happens ([leak]), so that the report lists it and only
 * what nobody has found yet is left over.
 *
 * Off by default: then nothing is summed, and a call site costs a test of [ON], a constant to the JIT.
 */
class Books {
    /** What is kept: water, m³; dead and living matter, kg (reported in t). */
    enum class Stuff(val unit: String, val scale: Double) { WATER("m³", 1.0), MATTER("t", 1e-3) }

    private val stocks = ArrayList<Stock>()
    private val flows = ConcurrentHashMap<String, DoubleAdder>()
    /** Each stock at the last check. */
    private var last: DoubleArray? = null
    /** Over the run, by part: the stocks' change, the flows, and what is left over; with the size of the leaks step by step. */
    private class Part {
        val change = DoubleArray(Stuff.entries.size)
        val flows = TreeMap<String, Double>()
        val gap = DoubleArray(Stuff.entries.size)
        val absGap = DoubleArray(Stuff.entries.size)
        /** The stocks that moved by part (summed over the run), for finding where a leak lies. */
        val moved = TreeMap<String, Double>()
    }
    private val parts = LinkedHashMap<String, Part>()
    private var checks = 0

    /** Keeps [all] the model's stocks ([Stocks]). */
    fun keep(all: Stocks) { stocks += all.list }

    /** Something of [stuff] crossing the world's edge: [amount] in (out when below zero), m³ or kg. */
    fun flow(stuff: Stuff, name: String, amount: Double) { adder(stuff, name).add(amount) }
    /** The running sum of a flow, for loops over places run on many threads. */
    fun adder(stuff: Stuff, name: String): DoubleAdder = flows.computeIfAbsent("${stuff.ordinal}:$name") { DoubleAdder() }
    /** A known leak: [amount] of [stuff] made (lost when below zero) outside any process, m³ or kg. */
    fun leak(stuff: Stuff, name: String, amount: Double) { leakAdder(stuff, name).add(amount) }
    fun leakAdder(stuff: Stuff, name: String): DoubleAdder = adder(stuff, "$LEAK$name")

    /** Sets the stocks against the flows since the last check, charging what is left over to [part]. */
    fun check(part: String) {
        val now = DoubleArray(stocks.size) { stocks[it].sum() }
        // (A stock that is no longer a number stops the run at the part that made it so, before it spreads.)
        for ((i, v) in now.withIndex()) kotlin.check(v.isFinite()) { "the stock ${stocks[i].name} is $v after $part" }
        val before = last
        last = now
        if (before == null) { flows.clear(); return }
        checks++
        val p = parts.getOrPut(part) { Part() }
        val change = DoubleArray(Stuff.entries.size)
        for ((i, s) in stocks.withIndex()) {
            val d = now[i] - before[i]
            change[s.stuff.ordinal] += d
            if (d != 0.0) p.moved.merge("${s.stuff.ordinal}:${s.name}", d, Double::plus)
        }
        val crossed = DoubleArray(Stuff.entries.size)
        for ((key, adder) in flows) {
            val x = adder.sumThenReset()
            if (x == 0.0) continue
            crossed[key.substringBefore(':').toInt()] += x
            p.flows.merge(key, x, Double::plus)
        }
        for (k in Stuff.entries.indices) {
            p.change[k] += change[k]
            val g = change[k] - crossed[k]
            p.gap[k] += g; p.absGap[k] += abs(g)
        }
    }

    /** Each part's leaks over the run, with the flows it booked and the stocks it moved; [steps] to give them a step. */
    fun report(steps: Int): String = buildString {
        for (stuff in Stuff.entries) {
            val k = stuff.ordinal
            val total = stocks.filter { it.stuff == stuff }.sumOf { it.sum() }
            append("  %s, %s a step (stocks now %.4g %s):\n".format(stuff.name.lowercase(), stuff.unit, total * stuff.scale, stuff.unit))
            for ((name, p) in parts) {
                if (p.change[k] == 0.0 && p.absGap[k] == 0.0 && p.flows.keys.none { it.startsWith("$k:") }) continue
                fun x(v: Double) = "%.4g".format(v * stuff.scale / steps)
                append("    %-34s left over %10s (%s by step summed whole)  change %10s\n".format(name, x(p.gap[k]), x(p.absGap[k]), x(p.change[k])))
                val (leaks, fl) = p.flows.filterKeys { it.startsWith("$k:") }.entries.partition { it.key.substringAfter(':').startsWith(LEAK) }
                if (fl.isNotEmpty()) append("      across the edge: " + fl.joinToString(", ") { "${it.key.substringAfter(':')} ${x(it.value)}" } + "\n")
                if (leaks.isNotEmpty()) append("      known leaks: " + leaks.joinToString(", ") { "${it.key.substringAfter(':').removePrefix(LEAK)} ${x(it.value)}" } + "\n")
                val mv = p.moved.filterKeys { it.startsWith("$k:") }
                if (mv.isNotEmpty()) append("      stocks moved: " + mv.entries.sortedByDescending { abs(it.value) }.joinToString(", ") { "${it.key.substringAfter(':')} ${x(it.value)}" } + "\n")
            }
        }
    }

    /** Over the run so far, what [part] left over of [stuff], m³ or kg (for tests). */
    fun leftOver(part: String, stuff: Stuff): Double = parts[part]?.gap?.get(stuff.ordinal) ?: 0.0
    /** The parts checked so far. */
    val partNames: Set<String> get() = parts.keys

    companion object {
        /** Whether the books are kept in this run (config key audit). */
        @JvmField val ON: Boolean = Config.flag(Config.Key.AUDIT, false)
        private const val LEAK = "leak: "
        /** The known leak of float stocks: a bite under half a float's step off a big stock leaves it as it was. */
        const val ROUNDING = "bites lost to float rounding"
    }
}

/**
 * A stock of water or matter the model keeps: [stuff], [name]d, summed over the world by [sum] (m³ or kg); [arrays]
 * are the tables it is summed from, so that a table of matter that is no stock can be found (see StocksTest); [group] is
 * the wider pool the reports also count it in ("plants", "small animals"), if any.
 */
class Stock(val stuff: Books.Stuff, val name: String, val arrays: List<Any>, val group: String?, val sum: () -> Double)

/** Every stock of water and matter in the model, as each part names its own: what the books audit and the reports read. */
class Stocks {
    val list = ArrayList<Stock>()
    private var group: String? = null
    /** A stock of [stuff], [name]d, summed by [sum] from the tables [arrays]. */
    fun add(stuff: Books.Stuff, name: String, vararg arrays: Any, sum: () -> Double) { list += Stock(stuff, name, arrays.toList(), group, sum) }
    /** Matter in kg/m² by place, [name]d, over places of [m2] square metres. */
    fun perM2(name: String, a: FloatArray, m2: FloatArray) = add(Books.Stuff.MATTER, name, a) { var s = 0.0; for (p in a.indices) s += a[p] * m2[p].toDouble(); s }
    /** Matter in kg/km² by place, [name]d, over places of [km2] square km. */
    fun perKm2(name: String, a: FloatArray, km2: FloatArray) = add(Books.Stuff.MATTER, name, a) { var s = 0.0; for (p in a.indices) s += a[p] * km2[p].toDouble(); s }
    /** The stocks [block] adds, counted also in the wider pool [name]. */
    fun within(name: String, block: () -> Unit) { group = name; try { block() } finally { group = null } }

    companion object {
        /** The wider pools ([within]), by the names the books, the history and the checks read them by. */
        const val PLANTS = "plants"
        const val SMALL_ANIMALS = "small animals"
        const val RESIDENT_ANIMALS = "resident animals"
        const val GROUPS = "packs and bands"
    }

    /** The matter in each stock and in each wider pool, thousand tonnes, in the order listed. */
    fun matter(): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>(); val pools = LinkedHashMap<String, Double>()
        for (s in list) if (s.stuff == Books.Stuff.MATTER) {
            val kt = s.sum() / 1e6
            out[s.name] = kt
            s.group?.let { pools.merge(it, kt, Double::plus) }
        }
        for ((k, v) in pools) { require(k !in out) { "pool $k shares its name with a stock" }; out[k] = v }
        return out
    }
}
