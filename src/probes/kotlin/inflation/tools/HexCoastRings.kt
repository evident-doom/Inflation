package inflation.tools

import inflation.ecology.set
import inflation.ecology.get
import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.world.get
import inflation.world.set
import inflation.world.Place
import inflation.world.Hex

/**
 * What the land offers people by how far it lies from the sea: land hexes in rings by their
 * distance from the nearest sea hex (1: on the shore), and in each ring the people living there,
 * and the means of what a band weighs in choosing a camp (game, plant food, the camp value made
 * of them, water) and of what it does not weigh there but lives by (fish and shellfish within
 * reach, marsh). After [years] years of the hand-written behaviour.
 *
 * Usage: hex-coast-rings [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-coast-rings [--seed N] [--width KM] [--height KM] [--years N]")
    val base = WorldConfig(widthKm = 1000, heightKm = 1000)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val years = a.int("--years", 5)
    a.done()
    val run = freshSim(cfg, Catalogue.learned)
    val human = Catalogue.learned.first { it.human }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    // Over the last year: each place's yearly odds of a child dying in a flood or a fire, as a camp there would run them.
    val flood = DoubleArray(run.land.n); val fire = DoubleArray(run.land.n)
    repeat(steps) { w ->
        run.step()
        if (w >= steps - perYear) for (p in 0 until run.land.n) {
            val c = run.fauna.calamities(human, Place(p), run.weather, true)
            fire[p] += c.fire.toDouble()
            flood[p] += c.flood.toDouble()
        }
    }
    val hex = run.hex; val hw = run.hw; val land = run.land; val groups = run.groups
    // Rings from the sea.
    val ring = IntArray(hex.size) { -1 }
    var front = hex.all.filter { hex.heart[it] < 0 }
    for (h in front) ring[h] = 0
    var r = 0
    while (front.isNotEmpty() && r < 12) {
        r++
        val next = ArrayList<Hex>()
        for (h in front) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && ring[q] < 0 && hex.heart[q] >= 0) { ring[q] = r; next += q } }
        front = next
    }
    val people = HashMap<Hex, Int>()
    for (g in groups.groups) if (g.alive && g.species.human) people.merge(hw.placeHex[g.place], g.size, Int::plus)
    println("ring (hexes from the sea) | land km² | people per 1000 km² | camp value | game | plants | water km (people) | fish+shellfish kg/week | marsh share | safe-camp ground | child deaths a year in floods / fires")
    for (rr in 1..12) {
        val hs = hex.all.filter { (ring[it] == rr || (rr == 12 && ring[it] >= 12)) && !hw.firstPlaceIn(it).none }
        if (hs.isEmpty()) continue
        val ps = hs.map { hw.firstPlaceIn(it) }
        val km2 = ps.sumOf { land.areaKm2[it].toDouble() }
        fun mean(f: (Hex, Int) -> Double) = hs.indices.sumOf { i -> f(hs[i], (ps[i]).i) * land.areaKm2[ps[i]] } / km2
        val heads = hs.sumOf { people[it] ?: 0 }
        println("%2d%s | %8.0f | %6.1f | %8.0f | %8.0f | %8.0f | %5.1f | %7.0f | %.3f | %.2f | %.3f / %.3f".format(rr, if (rr == 12) "+" else " ", km2,
            1000.0 * heads / km2,
            mean { _, p -> groups.campValue(human, Place(p)).toDouble() },
            mean { _, p -> groups.preyHere(human, Place(p)).toDouble() },
            mean { _, p -> ((run.fauna.food.fruit[p] + run.fauna.food.roots[p] + run.fauna.food.mast[p] + run.fauna.food.seeds[p]) * 1e6f *
                inflation.ecology.PLANT_FOOD_WEIGHT).toDouble() },
            mean { _, p -> land.drink.peopleWaterKm(Place(p)).toDouble() },
            mean { h, _ -> run.fauna.water.fishWithin(h, 1f).toDouble() },
            mean { _, p -> land.waterlogged[p].toDouble() },
            mean { _, p -> land.bestDefence[p].toDouble() }, mean { _, p -> flood[p] }, mean { _, p -> fire[p] }))
    }
}
