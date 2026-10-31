package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.get
import inflation.world.set

/**
 * What a group remembers of places it has been to (or, for bands, been told of): for each a hex and
 * a season (a quarter of the year), how well it fed there, the water, how dangerous it was, when
 * it was last there, and for bands the rarer materials it found there ([found]). A few slots; the least worth keeping (long unvisited and poor) gives way to a
 * new place. The storage is engineered; what to make of it is for the choosing networks to learn
 * (see [HexFeatures]): the hand-written behaviour does not read it.
 */
class PlaceMemory {
    val hex = HexArray(MEMORY_SLOTS)
    val season = IntArray(MEMORY_SLOTS)
    val fed = FloatArray(MEMORY_SLOTS)
    /**
     * A band's food got a week there as its own record counts it ([Band.weekSupply]: a share of its need before keeping warm,
     * net of its larder), from its own visits only ([noteGot]); NaN where it has none. What a planning band reckons a camp
     * by ([GroupPlanner.planCamp]); [fed], which its larder and fat fill out, is no measure of the place's food.
     */
    val got = FloatArray(MEMORY_SLOTS) { Float.NaN }
    val water = FloatArray(MEMORY_SLOTS)
    val danger = FloatArray(MEMORY_SLOTS)
    val lastWeek = IntArray(MEMORY_SLOTS)
    /** Whether it was there itself (false: only told of it). */
    val own = BooleanArray(MEMORY_SLOTS)
    /** Its dead buried there (a band's ancestors), and how many bands it has met there. */
    val dead = FloatArray(MEMORY_SLOTS)
    val met = FloatArray(MEMORY_SLOTS)
    /** How richly each of the rarer materials ([RARE]) crops out there, 0..1, by slot then material (0: none, or not a band). */
    val found = FloatArray(MEMORY_SLOTS * RARE.size)

    /** What it remembers finding of rare material [k] (index in [RARE]) in slot [i]. */
    fun foundAt(i: Int, k: Int) = found[i * RARE.size + k]

    /** Of all seasons at hex [h]: its dead buried there, and bands met there. */
    fun deadAt(h: Hex): Float { var s = 0f; for (i in 0 until MEMORY_SLOTS) if (hex[i] == h) s += dead[i]; return s }
    fun metAt(h: Hex): Float { var s = 0f; for (i in 0 until MEMORY_SLOTS) if (hex[i] == h) s += met[i]; return s }

    /** Adds [n] to slot of hex [h] in season [s]'s count in [what] (making the slot if need be). */
    fun mark(h: Hex, s: Int, week: Int, perYear: Float, what: FloatArray, n: Float) {
        var i = find(h, s)
        if (i < 0) { record(h, s, 0.5f, 0.5f, 0f, week, perYear); i = find(h, s) }
        if (i >= 0) what[i] += n
    }

    fun find(h: Hex, s: Int): Int { for (i in 0 until MEMORY_SLOTS) if (hex[i] == h && season[i] == s) return i; return -1 }

    /**
     * Records this week ([week], a step, [perYear] of them to a year) at hex [h] in season [s]: blended into what it
     * remembered there, or into a new slot.
     */
    fun record(h: Hex, s: Int, fedNow: Float, waterNow: Float, dangerNow: Float, week: Int, perYear: Float, ownVisit: Boolean = true, trust: Float = 1f,
        finds: FloatArray? = null, findsAt: Int = 0) {
        var i = find(h, s)
        if (i >= 0) {
            val w = if (ownVisit) 0.5f else 0.2f * trust
            if (!ownVisit && own[i]) return // its own memory outweighs hearsay
            fed[i] += w * (fedNow - fed[i]); water[i] += w * (waterNow - water[i]); danger[i] += w * (dangerNow - danger[i])
            if (finds != null) finds.copyInto(found, i * RARE.size, findsAt, findsAt + RARE.size)
            if (ownVisit) { lastWeek[i] = week; own[i] = true }
            return
        }
        // The slot least worth keeping: empty, else the poorest weighed by how long ago it was.
        i = 0; var worst = Float.MAX_VALUE
        for (k in 0 until MEMORY_SLOTS) {
            if (hex[k].none) { i = k; break }
            // (Graves and meeting places are not forgotten.)
            val keep = fed[k] - danger[k] - (week - lastWeek[k]) / perYear + dead[k] + 0.2f * met[k]
            if (keep < worst) { worst = keep; i = k }
        }
        hex[i] = h; season[i] = s; fed[i] = fedNow * (if (ownVisit) 1f else trust); water[i] = waterNow; danger[i] = dangerNow; dead[i] = 0f; met[i] = 0f; got[i] = Float.NaN
        if (finds != null) finds.copyInto(found, i * RARE.size, findsAt, findsAt + RARE.size) else found.fill(0f, i * RARE.size, (i + 1) * RARE.size)
        lastWeek[i] = if (ownVisit) week else week - (perYear / 2f).toInt(); own[i] = ownVisit
    }

    /**
     * Blends [gotNow], this week's food got at hex [h] in season [s] ([got]), into its own memory of the place, as [record]
     * blends what it ate; nothing where it keeps no memory of its own there.
     */
    fun noteGot(h: Hex, s: Int, gotNow: Float) {
        val i = find(h, s)
        if (i < 0 || !own[i] || gotNow.isNaN()) return
        got[i] = if (got[i].isNaN()) gotNow else got[i] + 0.5f * (gotNow - got[i])
    }

    fun copyInto(o: PlaceMemory) {
        hex.copyInto(o.hex); season.copyInto(o.season); fed.copyInto(o.fed); got.copyInto(o.got); water.copyInto(o.water)
        danger.copyInto(o.danger); lastWeek.copyInto(o.lastWeek); own.copyInto(o.own); dead.copyInto(o.dead); met.copyInto(o.met)
        found.copyInto(o.found)
    }

    /** Takes in what [o] remembers that it does not, as hearsay worth [trust] (where it found the rarer materials as told). */
    fun hear(o: PlaceMemory, week: Int, perYear: Float, trust: Float) {
        for (k in 0 until MEMORY_SLOTS) if (!o.hex[k].none && o.own[k]) record(o.hex[k], o.season[k], o.fed[k], o.water[k], o.danger[k], week,
            perYear, ownVisit = false, trust = trust, finds = o.found, findsAt = k * RARE.size)
    }

    companion object {
        const val MEMORY_SLOTS = 16
        /** The rarer materials a band remembers where it found: those that crop out only here and there, and shells on the shore. */
        val RARE = listOf(Material.STONE, Material.SALT, Material.OCHRE, Material.SHELL)
        fun seasonOf(month: Int) = month / 3
    }
}
