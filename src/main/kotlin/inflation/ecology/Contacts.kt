package inflation.ecology

import inflation.Par
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.get
import inflation.world.toHexArray

// Contacts between groups whose grounds overlap (docs/design/pack-performance.md, "Contacts"): with
// [inflation.Tuning.packsStay], a pack that holds its ground is about its whole footprint all step ([footprint]), and any
// other group whose ground for the step shares a hex with it is in contact with it. A contact is one thing between two
// groups, not one per hex they share: how much their grounds overlap, the encounters to expect from it over the step, where
// it is thickest, and the hexes they contest. It is settled once ([meetSettle]: one draw, at most one meeting a tick), and
// what comes of it acts on the ground ([gaveWay]). The same on any number of cores and in any order of the groups, and
// the same however the ground is cut into hexes, as far as the encounters go.
//
// Meant as the pattern for any groups whose spheres of influence meet: packs now; bandits, war parties, scouts later.

/**
 * A group's ground for the step: its [hexes] and the share of the step it is about each ([shares], summing to 1 or less).
 * A pack that holds its ground: its footprint, each hex by its weight's share. A group on its way: the hexes of its path,
 * each by the share of the step it spends there by its timetable.
 */
internal class Sphere(val hexes: HexArray, val shares: FloatArray)

/**
 * Groups [a] and [b] (indices into the spheres) in contact over the step:
 * - [overlap]: the chance both are in the same hex at a moment of the step, the sum over the hexes they share of the
 *   product of their shares there;
 * - [mean]: the encounters to expect over the step, the sum over those hexes of each one's rate of encounter a day of
 *   both being there, times the days both are;
 * - [hex]: the shared hex where the overlap is thickest (of equals, the lowest), where the meeting is held;
 * - [contested]: every hex they share, in index order: what the one that gives way gives up ([gaveWay]).
 */
internal class Contact(val a: Int, val b: Int, val overlap: Float, val mean: Float, val hex: Hex, val contested: HexArray)

/**
 * The contacts among groups with [spheres] (null for a group not in it), [ids] their ids and [held] whether each holds its
 * ground: every pair of which at least one holds its ground and whose spheres share a hex, and that [contend]. (Two groups
 * on their way meet by their timetables instead, [meetSettle].) [rate] is the encounters a day of both being about hex h
 * for the pair, as a pair of the two would meet there ([encounters] with a span of a day). [days] is the step's.
 *
 * Each pair once, found from one side: a group on its way finds the holders in its hexes; a holder finds the holders of a
 * higher id. Worked out group by group on all cores, each its own pairs, its partners in id order; the groups' lists
 * then taken in the groups' order. So the contacts and their order are fixed by the spheres and ids alone.
 */
internal fun contactsOf(spheres: Array<Sphere?>, ids: IntArray, held: BooleanArray, nHexes: Int, days: Float,
                        contend: (Int, Int) -> Boolean, rate: (Int, Int, Hex) -> Float): List<Contact> {
    val n = spheres.size
    // (The holders in each hex, in the groups' order: a count per hex, then each into its hex's run.)
    val start = IntArray(nHexes + 1)
    for (k in 0 until n) { val s = spheres[k] ?: continue; if (held[k]) for (i in 0 until s.hexes.size) start[s.hexes[i].i + 1]++ }
    for (h in 0 until nHexes) start[h + 1] += start[h]
    val holder = IntArray(start[nHexes]); val holderShare = FloatArray(start[nHexes]); val fill = start.copyOf(nHexes)
    for (k in 0 until n) { val s = spheres[k] ?: continue
        if (held[k]) for (i in 0 until s.hexes.size) { val at = fill[s.hexes[i].i]++; holder[at] = k; holderShare[at] = s.shares[i] } }
    val found = arrayOfNulls<List<Contact>>(n)
    Par.forEach("meetings: contacts", n) { k ->
        val s = spheres[k] ?: return@forEach
        class Acc(val j: Int) { var overlap = 0f; var mean = 0f; var best = -1f; var hex = Hex.NONE; val shared = LinkedHashSet<Hex>() }
        var accs: HashMap<Int, Acc>? = null
        for (i in 0 until s.hexes.size) {
            val h = s.hexes[i]; val sk = s.shares[i]
            for (at in start[h.i] until start[h.i + 1]) {
                val j = holder[at]
                if (j == k || (held[k] && ids[j] <= ids[k])) continue
                val acc = (accs ?: HashMap<Int, Acc>().also { accs = it }).getOrPut(j) {
                    if (!contend(k, j)) return@getOrPut Acc(-1)
                    Acc(j) }
                if (acc.j < 0) continue
                val both = sk * holderShare[at]
                acc.overlap += both
                acc.mean += rate(k, j, h) * days * both
                if (both > acc.best || (both == acc.best && h.i < acc.hex.i)) { acc.best = both; acc.hex = h }
                acc.shared += h
            }
        }
        val all = accs ?: return@forEach
        found[k] = all.values.filter { it.j >= 0 && it.overlap > 0f }.sortedBy { ids[it.j] }.map { acc ->
            Contact(k, acc.j, acc.overlap, acc.mean, acc.hex, acc.shared.sortedBy { it.i }.toHexArray()) }
    }
    val out = ArrayList<Contact>()
    for (l in found) if (l != null) out += l
    return out
}

/**
 * The step's ground of group [g], for [contactsOf]: its footprint by its weights' shares if it holds its ground, else the
 * hexes of its path by the share of the step it spends in each ([HexGroup.days], its timetable); null for neither.
 */
internal fun GroupWorld.sphereOf(g: HexGroup): Sphere? {
    val foot = g.footprint; val weights = g.footWeights
    if (foot != null && weights != null && g.path.size == 1) {
        var sum = 0f; for (w in weights) sum += w
        return Sphere(foot, FloatArray(weights.size) { weights[it] / sum })
    }
    if (g.path.size == 0) return null
    val step = days.toFloat(); val last = g.path.size - 1
    return Sphere(g.path, FloatArray(g.path.size) { i -> ((if (i < last) g.days[i + 1] else step) - g.days[i]).coerceAtLeast(0f) / step })
}

/**
 * The contacts of the step among [groups] ([contactsOf]), with [inflation.Tuning.packsStay]: each pair of which one holds its
 * ground ([HexGroup.footprint]) and whose grounds share a hex, that [contend]; none without the switch or a pack holding ground.
 */
internal fun HexGroups.contacts(): List<Contact> {
    if (!tuning.packsStay) return emptyList()
    val n = groups.size
    val held = BooleanArray(n) { val g = groups[it]; g.alive && g.footprint != null && g.path.size == 1 }
    if (held.none { it }) return emptyList()
    val spheres = arrayOfNulls<Sphere>(n)
    for (k in 0 until n) if (groups[k].alive) spheres[k] = sphereOf(groups[k])
    val ids = IntArray(n) { groups[it].id }
    return contactsOf(spheres, ids, held, hex.size, days.toFloat(), { a, b -> contend(groups[a], groups[b]) }) { a, b, h ->
        // (At the holder's ground in that hex, nearest in height to its place: the holder is [b] when [a] is on its way.)
        val at = if (held[a]) groups[a] else groups[b]
        val p = hw.placeNear(at.place, h).takeIf { !it.none } ?: at.place
        encounters(groups[a], groups[b], p, 1f)
    }
}

/**
 * Whether groups [a] and [b] contend where their grounds overlap: the rule of who has dealings with whom, for the contacts
 * ([contacts]). Every pair as at the meetings by timetable, but two bands, which deal with each other by their own
 * meetings ([neighboursMeet]); for groups of people of other sorts later, by their regard for each other.
 */
internal fun contend(a: HexGroup, b: HexGroup) = !(a.species.human && b.species.human)

/**
 * Pack [g], holding its ground, gave way or lost at a meeting over [contested] (the hexes of the contact, or the meeting's
 * hex alone): it keeps off them, its home apart, for [YIELD_DAYS] from [today]: it does not hunt or mark them, nor is it
 * about them for the meetings ([footprint]). Its home it loses only to a rival that beats it there ([rivalsMeet]).
 * Nothing for a group that does not hold its ground: one on its way turns back instead.
 */
internal fun GroupWorld.gaveWay(g: HexGroup, contested: HexArray, today: Long) {
    if (!tuning.packsStay || g.footprint == null || g.home.none) return
    val until = today + YIELD_DAYS
    val kept = g.yielded?.let { y -> y.filter { (it and 0xFFFFFFFFL) > today && contested.indexOf((it ushr 32).toInt()) < 0 } } ?: emptyList()
    val added = ArrayList<Long>()
    for (i in 0 until contested.size) { val h = contested[i]; if (h != g.home) added += (h.i.toLong() shl 32) or until }
    g.yielded = (kept + added).toLongArray().takeIf { it.isNotEmpty() }
    if (added.isNotEmpty()) stats.meeting(Met.HEXES_YIELDED, added.size)
}

/** Whether pack [g] keeps off hex [h] on day [today], having given way there ([gaveWay]). */
internal fun yields(g: HexGroup, h: Hex, today: Long): Boolean {
    val y = g.yielded ?: return false
    for (e in y) if ((e ushr 32).toInt() == h.i && (e and 0xFFFFFFFFL) > today) return true
    return false
}

/** The index of the hex of index [i] in [this], or -1. */
private fun HexArray.indexOf(i: Int): Int { for (k in 0 until size) if (this[k].i == i) return k; return -1 }

/** Days a pack that gave way keeps off the ground it gave up ([gaveWay]): a month; an estimate. */
internal const val YIELD_DAYS = 30L
