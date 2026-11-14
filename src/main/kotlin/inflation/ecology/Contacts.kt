package inflation.ecology

import inflation.Par
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.get
import inflation.world.toHexArray
import inflation.world.Weather

// Contacts between groups whose grounds overlap (docs/design/pack-performance.md, "Contacts"): a pack that holds its ground is about its whole footprint all step ([footprint]), and any
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
 * The contacts of the step among [groups] ([contactsOf]): each pair of which one holds its ground ([HexGroup.footprint]) and
 * whose grounds share a hex, that [contend]; none without a group holding ground.
 */
@inflation.Part("meetings: contacts")
internal fun HexGroups.contacts(): List<Contact> {
    val n = groups.size
    val held = BooleanArray(n) { val g = groups[it]; g.alive && g.rangesFromHome }
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
 * Whether groups [a] and [b] come on each other by chance where their grounds overlap, for the contacts ([contacts]): every
 * pair but two bands that both hold their home hex, whose every contact is their visiting ([neighboursMeet]: the bands
 * about meet as often as foragers' bands are in touch, all of it, Olli 9 Nov 2026). What comes of a contact is the
 * meeting's ([settle]).
 */
internal fun GroupWorld.contend(a: HexGroup, b: HexGroup) = !(staysHome(a) && staysHome(b))

/**
 * Band [g], holding its home hex ([staysHome]): the ground its people range over from its camp ([HexGroup.footprint]), for
 * the step's meetings, laid out as a pack's territory is ([layGround]); it hunts as before. Its ground is its catchment, the
 * land its people work from camp and back within the day: wider for a band on the move than for one living fixed by its
 * houses and fields ([BAND_MOBILE_KM2], [BAND_FIXED_KM2]: Vita-Finzi and Higgs 1970, site catchments of about two hours'
 * walk for hunter-gatherers and one for farmers, from memory). Unlike a pack it does not go about as one: most of its people
 * are at camp most of the time (the young, the old, those at work about the camp), only its foragers going out; the more so
 * for a band living fixed. So its camp's hex holds [BAND_MOBILE_HOME] or [BAND_FIXED_HOME] of its time
 * ([HexGroup.footWeights]: the weights' shares are the time a group is in each hex, [sphereOf]), and the hexes about share
 * the rest by their going.
 */
internal fun HexGroups.bandGround(g: HexGroup, today: Long, weather: Weather) {
    val fixed = g.band?.livesFixed() == true
    layGround(g, hw.placeHex[g.place], if (fixed) BAND_FIXED_KM2 else BAND_MOBILE_KM2, today, weather, g.place)
    val w = g.footWeights ?: return
    var out = 0f; for (i in 1 until w.size) out += w[i]
    // (Home first, [layGround]: its weight set so its share of all is the home share; with nothing about, it is all.)
    val home = if (fixed) BAND_FIXED_HOME else BAND_MOBILE_HOME
    if (out > 0f) w[0] = out * home / (1f - home)
}

/** The catchment of a band on the move ([bandGround]): about two hours' walk, 10 km, about its camp; km². */
internal const val BAND_MOBILE_KM2 = 314f
/** The catchment of a band living fixed by its houses and fields ([bandGround]): about an hour's walk, 5 km; km². */
internal const val BAND_FIXED_KM2 = 79f
/** The share of its time a band on the move spends at its camp's hex, its foragers out by day ([bandGround]); an estimate. */
internal const val BAND_MOBILE_HOME = 0.7f
/** The share of its time a band living fixed spends at its camp's hex, its fields and herds close by ([bandGround]); an estimate. */
internal const val BAND_FIXED_HOME = 0.85f

/**
 * Lays out the hexes group [g] ranges over from home this step ([HexGroup.footprint], [HexGroup.footWeights]): its ground
 * is an area, [areaKm2] of land (a territory's, a catchment's), taken by the ground from hex [home] outward, the easiest
 * going first ([reachOf]: its kind's going, so never across a strait it cannot swim, a cliff it cannot climb or a river it
 * cannot ford), until the land taken comes to the area. Only hexes with ground near [at]'s height and not given way on
 * lately ([gaveWay], those run out forgotten). So a ground keeps its size and takes its shape from the land: round on open
 * ground, along the valley in broken country, short of a barrier. Each hex weighted by its going: 1 at home, falling to a
 * hex's width beyond the farthest taken. [home] first, then the rest, easiest first (of equals, the lower hex). The one way
 * a pack's territory ([footprint]) and a band's ground ([bandGround]) are laid out, whatever the step's length.
 */
internal fun HexGroups.layGround(g: HexGroup, home: Hex, areaKm2: Float, today: Long, weather: Weather, at: inflation.world.Place = g.place) {
    g.yielded = g.yielded?.filter { (it and 0xFFFFFFFFL) > today }?.toLongArray()?.takeIf { it.isNotEmpty() }
    // (Searched as far as three times the area's radius and two hexes more: enough for any ground but one hemmed in.)
    val radius = kotlin.math.sqrt(areaKm2 / Math.PI.toFloat())
    val reach = reachOf(g, home, 3f * radius + 2f * hex.hexKm.toFloat(), weather)
    val order = (0 until reach.hexes.size).filter { i -> val h = reach.hexes[i]
        h != home && !hw.placeNear(at, h).none && !yields(g, h, today) }
        .sortedWith(compareBy<Int> { reach.dist[it] }.thenBy { reach.hexes[it].i })
    val hexes = ArrayList<Hex>(); val dists = ArrayList<Float>()
    hexes += home; dists += 0f
    var land = landKm2(home)
    for (i in order) {
        if (land >= areaKm2) break
        hexes += reach.hexes[i]; dists += reach.dist[i]; land += landKm2(reach.hexes[i])
    }
    val fall = dists.last() + hex.hexKm.toFloat()
    g.footprint = hexes.toHexArray(); g.footWeights = FloatArray(dists.size) { 1f - dists[it] / fall }
}

/**
 * Whether this group spends its step at home, its people ranging out from there ([layGround]), rather than on a way: a pack
 * holding its ground, which ranges as one; a band at its camp, most of its people at home and its foragers going out
 * ([bandGround]). Its [HexGroup.footWeights] are then the shares of its time in each hex ([sphereOf]).
 */
internal val HexGroup.rangesFromHome get() = footprint != null && path.size == 1

/**
 * Pack [g], holding its ground, gave way or lost at a meeting over [contested] (the hexes of the contact, or the meeting's
 * hex alone): it keeps off them, its home apart, for [YIELD_DAYS] from [today]: it does not hunt or mark them, nor is it
 * about them for the meetings ([footprint]). Its home it loses only to a rival that beats it there ([rivalsMeet]).
 * Nothing for a group that does not hold its ground: one on its way turns back instead.
 */
internal fun GroupWorld.gaveWay(g: HexGroup, contested: HexArray, today: Long) {
    // (A band's lair is its camp's hex, [groundOf]: it gives up the hexes about, never its camp.)
    val lair = if (g.band != null) hw.placeHex[g.place] else g.home
    if (g.footprint == null || lair.none) return
    val until = today + YIELD_DAYS
    val kept = g.yielded?.let { y -> y.filter { (it and 0xFFFFFFFFL) > today && contested.indexOf((it ushr 32).toInt()) < 0 } } ?: emptyList()
    val added = ArrayList<Long>()
    for (i in 0 until contested.size) { val h = contested[i]; if (h != lair) added += (h.i.toLong() shl 32) or until }
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
