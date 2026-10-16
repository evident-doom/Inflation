package inflation.ecology

/**
 * For the viewer: where things died this week and by what, and how much of the dead matter on the
 * ground went (rotted, eaten, weathered), place by place. It is kept only while something reads it
 * ([HexFauna.record] is null otherwise, at no cost to a run); whoever reads it [clear]s it after.
 */
class PlaceRecord(val n: Int, kinds: Int) {
    enum class Cause(val label: String) { HUNGER("hunger"), THIRST("thirst"), COLD("cold and snow"), ILLNESS("illness"), KILLED("killed"), OTHER("age and other") }

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

    internal fun resident(k: Int, p: Int, c: Cause, kg: Float) { residents[k][p * Cause.entries.size + c.ordinal] += kg }

    internal fun foldResidents() {
        val m = Cause.entries.size
        for (r in residents) for (p in 0 until n) for (c in 0 until m) { val v = r[p * m + c]; if (v != 0f) { animalKg[c][p] += v; r[p * m + c] = 0f } }
    }

    /** A death of a group's member, under the cause the week's outcome named it by. */
    internal fun group(s: Species, p: Int, cause: String, k: Int) {
        val c = cause(cause)
        if (s.human) people[c.ordinal][p] += k.toFloat() else animalKg[c.ordinal][p] += k * s.massKg
    }

    fun clear() {
        for (a in animalKg) a.fill(0f)
        for (a in people) a.fill(0f)
        carrionGone.fill(0f); litterGone.fill(0f); bonesGone.fill(0f); organicGone.fill(0f)
    }

    companion object {
        /** The cause a death named [name] falls under. */
        fun cause(name: String): Cause {
            val s = name.lowercase()
            return when {
                "hunger" in s || "starv" in s || "orphan" in s -> Cause.HUNGER
                "thirst" in s -> Cause.THIRST
                "cold" in s || "snow" in s || "freez" in s || "exposure" in s -> Cause.COLD
                "taken by" in s || "killed" in s || "hunted" in s || "raid" in s || "rival" in s || "fought" in s || "eagle" in s || "crocodile" in s || "hunters" in s -> Cause.KILLED
                "illness" in s || "disease" in s || "fever" in s || "plague" in s || "sick" in s || "worm" in s || "outbreak" in s || "crowd" in s || "foul" in s || "flux" in s || "cough" in s -> Cause.ILLNESS
                else -> Cause.OTHER
            }
        }
    }
}
