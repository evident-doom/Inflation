package inflation.ecology

/**
 * The herds' first wants of each place's food this week, set once they have moved and before anyone
 * eats ([HexGroups.claim]): at each place of a herd's grazing range, its usual shares of grass, browse,
 * fruit and roots, as far as it can get at them. At each place the residents and the herds then take
 * those shares together, the bigger kinds first ([HexFauna.graze]); each herd eats what it was granted
 * when it grazes ([HexGroups.graze]).
 */
internal class HerdClaims(private val n: Int) {
    var size = 0
        private set
    /** Per claim: the group (its index in [HexGroups.groups]), the place, the place's share of the group's need, its kind's body mass. */
    var group = IntArray(256); var place = IntArray(256); var share = FloatArray(256); var mass = FloatArray(256)
    /** Four to a claim (grass, browse, fruit, roots): kg/m² of the place it wants, as far as it can get at it; and the share of that it was granted. */
    var want = FloatArray(4 * 256); var grant = FloatArray(4 * 256)
    /** The claims at each place, biggest kind first: [byPlace] from [first][p] until [first][p + 1]. */
    val first = IntArray(n + 1); var byPlace = IntArray(256)
    private val at = IntArray(n)
    /** The claims of the group with index i run from [groupFirst][i] until [groupFirst][i + 1]; its week's need, kg. */
    var groupFirst = IntArray(257); var groupWantKg = FloatArray(256)

    /** No claims, for a week with [groups] groups. */
    fun clear(groups: Int) {
        size = 0; first.fill(0)
        if (groupFirst.size < groups + 1) { groupFirst = IntArray(groups + 1); groupWantKg = FloatArray(groups) } else groupFirst.fill(0, 0, groups + 1)
    }

    fun add(g: Int, p: Int, sh: Float, massKg: Float, grass: Float, browse: Float, fruit: Float, roots: Float) {
        if (size == group.size) {
            val m = 2 * size
            group = group.copyOf(m); place = place.copyOf(m); share = share.copyOf(m); mass = mass.copyOf(m)
            want = want.copyOf(4 * m); grant = grant.copyOf(4 * m)
        }
        group[size] = g; place[size] = p; share[size] = sh; mass[size] = massKg
        val w = 4 * size
        want[w] = grass; want[w + 1] = browse; want[w + 2] = fruit; want[w + 3] = roots
        grant[w] = 0f; grant[w + 1] = 0f; grant[w + 2] = 0f; grant[w + 3] = 0f
        size++
    }

    /** Indexes the claims by place, the biggest kind first at each. */
    fun index() {
        for (c in 0 until size) first[place[c] + 1]++
        for (p in 0 until n) first[p + 1] += first[p]
        if (byPlace.size < size) byPlace = IntArray(group.size)
        System.arraycopy(first, 0, at, 0, n)
        for (c in 0 until size) byPlace[at[place[c]]++] = c
        for (p in 0 until n) {
            val a = first[p]; val b = first[p + 1]
            for (i in a + 1 until b) {
                val c = byPlace[i]; var j = i - 1
                while (j >= a && mass[byPlace[j]] < mass[c]) { byPlace[j + 1] = byPlace[j]; j-- }
                byPlace[j + 1] = c
            }
        }
    }
}
