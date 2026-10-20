package inflation.ecology

/**
 * For the viewer: where things died this week and by what, and how much of the dead matter on the
 * ground went (rotted, eaten, weathered), place by place. It is kept only while something reads it
 * ([HexFauna.record] is null otherwise, at no cost to a run); whoever reads it [clear]s it after.
 */
class PlaceRecord(val n: Int, kinds: Int) {
    enum class Cause(val label: String) { HUNGER("hunger"), THIRST("thirst"), COLD("cold and snow"), ILLNESS("illness"), KILLED("killed"),
        OTHER("age and other") }

    /** Animals' deaths by cause, kg (heads x their kind's weight) per place. */
    val animalKg = Array(Cause.entries.size) { FloatArray(n) }
    /** People's deaths by cause, heads per place. */
    val people = Array(Cause.entries.size) { FloatArray(n) }
    /** The resident kinds' deaths, kind by kind (each kind lives its week on its own core), place x cause; folded into [animalKg] by [foldResidents]. */
    internal val residents = Array(kinds) { FloatArray(n * Cause.entries.size) }
    /** Dead matter gone this week, kg/m² per place: carrion (rotted or eaten), litter (rotted), bones (weathered), soil organic matter (broken down). */
    val carrionGone = FloatArray(n)
    val litterGone = FloatArray(n)
    val bonesGone = FloatArray(n)
    val organicGone = FloatArray(n)

    internal fun resident(k: Int, p: Place, c: Cause, kg: Float) { residents[k][p.i * Cause.entries.size + c.ordinal] += kg }

    internal fun foldResidents() {
        val m = Cause.entries.size
        for (r in residents) for (p in 0 until n) for (c in 0 until m) { val v = r[p * m + c]; if (v != 0f) { animalKg[c][p] += v; r[p * m + c] = 0f } }
    }

    /** A death of a group's member, under the cause the week's outcome put it down to. */
    internal fun group(s: Species, p: Place, cause: DeathReason, k: Int) {
        val c = cause.shown
        if (s.human) people[c.ordinal][p] += k.toFloat() else animalKg[c.ordinal][p] += k * s.massKg
    }

    fun clear() {
        for (a in animalKg) a.fill(0f)
        for (a in people) a.fill(0f)
        carrionGone.fill(0f); litterGone.fill(0f); bonesGone.fill(0f); organicGone.fill(0f)
    }
}
