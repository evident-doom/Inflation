package inflation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * A mechanism that a diagnostic run can switch to a simpler form, to measure what it contributes against what it costs
 * (design: review/2026-11-04-effect-per-cost-design.md in the project files). [part] is the `@Part` the mechanism lives
 * in (checked by `AblateTest`); [forms] the forms it offers. Each form is a decision about what "without it" or "less
 * often" means for that one mechanism, made where the guard is, not in general.
 */
enum class Ablation(val key: String, val part: String, val forms: Set<Ablate.Form>) {
    /** Bands that meet tell each other nothing of the country, routes, homes, appointments or remembered places. */
    TELL_OF_COUNTRY("tellOfCountry", "meetings: tell of country", setOf(Ablate.Form.OFF)),
    /** Resident hunters of different kinds no longer meet each other where they live (they still meet packs and bands). */
    RESIDENTS_MEET_RESIDENTS("residentsMeetResidents", "meetings: settle", setOf(Ablate.Form.OFF)),
    /** A band's customs week runs every N weeks (each band on its own week of the N), over N weeks' time. */
    CULTURE_WEEK("cultureWeek", "people: cultureWeek", setOf(Ablate.Form.COARSE)),
    /** A party's hunting odds are reused for up to N weeks while its hunting ground is the same; what game is there is read fresh. */
    HUNTING_ODDS("huntingOdds", "foraging: hunting odds", setOf(Ablate.Form.FROZEN)),
    /** Bands no longer keep off the known homes of bands they are not close to (they still keep off where raiders drove them from). */
    KEEP_OFF("keepOff", "choosing: plan one group", setOf(Ablate.Form.OFF)),
}

/**
 * The run's ablations, from the tuning key `t.ablate` (diagnostic only; Olli 2026-11-04): a comma-separated list of
 * `key:form`, the form `off`, `coarseN` or `frozenN` (N weeks, 2 or more), e.g. `t.ablate=tellOfCountry:off,cultureWeek:coarse4`.
 * Empty, nothing changes. An unknown mechanism or a form it does not offer stops the run.
 */
class Ablate private constructor(private val form: Array<Form?>, private val weeks: IntArray) {
    enum class Form { OFF, COARSE, FROZEN }

    /** Whether any mechanism is ablated in this run. */
    val any = form.any { it != null }
    /** Whether [a] is switched off. */
    fun off(a: Ablation) = form[a.ordinal] == Form.OFF
    /** The weeks [a] runs once in: 1 unless it is coarse. */
    fun every(a: Ablation) = if (form[a.ordinal] == Form.COARSE) weeks[a.ordinal] else 1
    /** The weeks [a]'s output is reused for: 0 unless it is frozen. */
    fun frozen(a: Ablation) = if (form[a.ordinal] == Form.FROZEN) weeks[a.ordinal] else 0

    /** A party's hunting odds as last worked out ([Ablation.HUNTING_ODDS]): on the week [week], for the ground [places]. */
    class FrozenOdds(val week: Long, val places: IntArray, val met: FloatArray, val odds: FloatArray, val stalks: FloatArray)
    private val frozenOdds = ConcurrentHashMap<Int, FrozenOdds>()
    private val prunedWeek = AtomicLong(Long.MIN_VALUE)
    /** How often frozen odds were reused and how often worked out afresh, for the run's report. */
    val reused = LongAdder(); val fresh = LongAdder()

    /** Group [id]'s odds still good on [week] for the ground [places] (its first [n]), or null. */
    fun oddsFor(id: Int, week: Long, places: IntArray, n: Int): FrozenOdds? {
        val keep = frozen(Ablation.HUNTING_ODDS)
        if (keep == 0) return null
        // (Odds older than they may be kept are dropped once a week, whoever comes first, so the dead's do not pile up; a
        // group only ever reads its own, so this changes nothing it sees.)
        val last = prunedWeek.get()
        if (week > last && prunedWeek.compareAndSet(last, week)) frozenOdds.values.removeIf { week - it.week >= keep }
        val f = frozenOdds[id]
        if (f == null || week - f.week >= keep || f.places.size != n || !java.util.Arrays.equals(f.places, 0, n, places, 0, n)) { fresh.increment(); return null }
        reused.increment(); return f
    }
    fun keepOdds(id: Int, f: FrozenOdds) { if (frozen(Ablation.HUNTING_ODDS) > 0) frozenOdds[id] = f }

    /** A line for the run's output: what is ablated, and how often frozen odds were reused. */
    fun report(): String = Ablation.entries.filter { form[it.ordinal] != null }.joinToString(", ") { a ->
        a.key + ":" + form[a.ordinal]?.name?.lowercase() + (if (form[a.ordinal] == Form.OFF) "" else "${weeks[a.ordinal]}") +
            if (a == Ablation.HUNTING_ODDS) " (reused %d of %d)".format(reused.sum(), reused.sum() + fresh.sum()) else ""
    }

    companion object {
        val NONE = Ablate(arrayOfNulls(Ablation.entries.size), IntArray(Ablation.entries.size))

        fun parse(text: String?): Ablate {
            if (text.isNullOrBlank()) return NONE
            val form = arrayOfNulls<Form>(Ablation.entries.size); val weeks = IntArray(Ablation.entries.size)
            for (item in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = item.split(':')
                require(parts.size == 2) { "t.ablate: '$item' is not key:form" }
                val a = Ablation.entries.firstOrNull { it.key == parts[0].trim() }
                    ?: throw IllegalArgumentException("t.ablate: no mechanism '${parts[0].trim()}' (known: ${Ablation.entries.joinToString(", ") { it.key }})")
                require(form[a.ordinal] == null) { "t.ablate: ${a.key} given twice" }
                val f = parts[1].trim().lowercase()
                val (kind, n) = when {
                    f == "off" -> Form.OFF to 0
                    f.startsWith("coarse") -> Form.COARSE to (f.removePrefix("coarse").toIntOrNull() ?: -1)
                    f.startsWith("frozen") -> Form.FROZEN to (f.removePrefix("frozen").toIntOrNull() ?: -1)
                    else -> throw IllegalArgumentException("t.ablate: form '$f' of ${a.key} is none of off, coarseN, frozenN")
                }
                require(kind in a.forms) { "t.ablate: ${a.key} offers ${a.forms.joinToString(", ") { it.name.lowercase() }}, not $f" }
                require(kind == Form.OFF || n >= 2) { "t.ablate: $f of ${a.key} needs a number of weeks, 2 or more (e.g. ${kind.name.lowercase()}4)" }
                form[a.ordinal] = kind; weeks[a.ordinal] = n
            }
            return Ablate(form, weeks)
        }
    }
}
