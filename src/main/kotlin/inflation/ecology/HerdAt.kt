package inflation.ecology

import inflation.Books
import java.util.concurrent.atomic.DoubleAdder
import kotlin.math.min

/**
 * A herd's share of its animals at a place this week, as the residents' hunters (jackals, lynx) find them there
 * alongside the resident game: [adults] and [young] add up the kills they make of it (on all cores), counted out
 * to the herd when their week is done (see [HexFauna.huntResidents], [HexGroups.takeResidentsKills]).
 */
class HerdAt(val g: HexGroup, val share: Float) {
    val adults = DoubleAdder()
    val young = DoubleAdder()
}

/** The plant-eaters' herds about each place this week, each over the ground it grazes ([rangeShares]). */
internal fun HexGroups.herdsByPlace(): Map<Int, List<HerdAt>> {
    val at = HashMap<Int, ArrayList<HerdAt>>()
    for (g in groups) {
        if (!g.alive || g.species.carnivore) continue
        rangeShares(g, hw.placeHex[g.place]) { h, share ->
            val p = hw.sameBandPlace(g.place, h).takeIf { !it.none } ?: g.place
            at.getOrPut(p.i) { ArrayList() } += HerdAt(g, share)
        }
    }
    return at
}

/** The herds' losses to the residents' hunters this week, drawn as whole animals and taken from them. */
internal fun HexGroups.takeResidentsKills(at: Map<Int, List<HerdAt>>) {
    for (list in at.values) for (a in list) {
        val g = a.g
        if (!g.alive) continue
        val dy = min(g.young, poisson(a.young.sum().toFloat(), rnd))
        val da = min(g.adults, poisson(a.adults.sum().toFloat(), rnd))
        if (da + dy == 0) continue
        g.young -= dy; g.adults -= da
        land.books?.flow(Books.Stuff.MATTER, "herds killed by residents (from the groups)", -(da + HexFauna.YOUNG_WEIGHT * dy) * g.species.massKg.toDouble())
        // (Into the residents' week's deaths, as the groups' own tally is cleared when their week begins.)
        fauna.deaths.merge("${g.species.name}:resident hunters", (da + dy).toDouble(), Double::plus)
        herdKilledByResidents[g.species.index] += da + dy
        note(g) { "lost %d to jackals or lynx".format(da + dy) }
    }
}
