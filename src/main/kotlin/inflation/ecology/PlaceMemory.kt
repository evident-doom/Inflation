package inflation.ecology

/**
 * What a group remembers of places it has been to (or, for bands, been told of): for each a hex and
 * a season (a quarter of the year), how well it fed there, the water, how dangerous it was, and when
 * it was last there. A few slots; the least worth keeping (long unvisited and poor) gives way to a
 * new place. The storage is engineered; what to make of it is for the choosing networks to learn
 * (see [HexFeatures]): the hand-written behaviour does not read it.
 */
class PlaceMemory {
    val hex = IntArray(MEMORY_SLOTS) { -1 }
    val season = IntArray(MEMORY_SLOTS)
    val fed = FloatArray(MEMORY_SLOTS)
    val water = FloatArray(MEMORY_SLOTS)
    val danger = FloatArray(MEMORY_SLOTS)
    val lastWeek = IntArray(MEMORY_SLOTS)
    /** Whether it was there itself (false: only told of it). */
    val own = BooleanArray(MEMORY_SLOTS)
    /** Its dead buried there (a band's ancestors), and how many bands it has met there. */
    val dead = FloatArray(MEMORY_SLOTS)
    val met = FloatArray(MEMORY_SLOTS)

    /** Of all seasons at hex [h]: its dead buried there, and bands met there. */
    fun deadAt(h: Int): Float { var s = 0f; for (i in 0 until MEMORY_SLOTS) if (hex[i] == h) s += dead[i]; return s }
    fun metAt(h: Int): Float { var s = 0f; for (i in 0 until MEMORY_SLOTS) if (hex[i] == h) s += met[i]; return s }

    /** Adds [n] to slot of hex [h] in season [s]'s count in [what] (making the slot if need be). */
    fun mark(h: Int, s: Int, week: Int, what: FloatArray, n: Float) {
        var i = find(h, s)
        if (i < 0) { record(h, s, 0.5f, 0.5f, 0f, week); i = find(h, s) }
        if (i >= 0) what[i] += n
    }

    fun find(h: Int, s: Int): Int { for (i in 0 until MEMORY_SLOTS) if (hex[i] == h && season[i] == s) return i; return -1 }

    /** Records this week at hex [h] in season [s]: blended into what it remembered there, or into a new slot. */
    fun record(h: Int, s: Int, fedNow: Float, waterNow: Float, dangerNow: Float, week: Int, ownVisit: Boolean = true, trust: Float = 1f) {
        var i = find(h, s)
        if (i >= 0) {
            val w = if (ownVisit) 0.5f else 0.2f * trust
            if (!ownVisit && own[i]) return // its own memory outweighs hearsay
            fed[i] += w * (fedNow - fed[i]); water[i] += w * (waterNow - water[i]); danger[i] += w * (dangerNow - danger[i])
            if (ownVisit) { lastWeek[i] = week; own[i] = true }
            return
        }
        // The slot least worth keeping: empty, else the poorest weighed by how long ago it was.
        i = 0; var worst = Float.MAX_VALUE
        for (k in 0 until MEMORY_SLOTS) {
            if (hex[k] < 0) { i = k; break }
            // (Graves and meeting places are not forgotten.)
            val keep = fed[k] - danger[k] - (week - lastWeek[k]) / 52f + dead[k] + 0.2f * met[k]
            if (keep < worst) { worst = keep; i = k }
        }
        hex[i] = h; season[i] = s; fed[i] = fedNow * (if (ownVisit) 1f else trust); water[i] = waterNow; danger[i] = dangerNow; dead[i] = 0f; met[i] = 0f
        lastWeek[i] = if (ownVisit) week else week - 26; own[i] = ownVisit
    }

    fun copyInto(o: PlaceMemory) {
        hex.copyInto(o.hex); season.copyInto(o.season); fed.copyInto(o.fed); water.copyInto(o.water)
        danger.copyInto(o.danger); lastWeek.copyInto(o.lastWeek); own.copyInto(o.own); dead.copyInto(o.dead); met.copyInto(o.met)
    }

    /** Takes in what [o] remembers that it does not, as hearsay worth [trust]. */
    fun hear(o: PlaceMemory, week: Int, trust: Float) {
        for (k in 0 until MEMORY_SLOTS) if (o.hex[k] >= 0 && o.own[k]) record(o.hex[k], o.season[k], o.fed[k], o.water[k], o.danger[k], week, ownVisit = false, trust = trust)
    }

    companion object {
        const val MEMORY_SLOTS = 16
        fun seasonOf(month: Int) = month / 3
    }
}
