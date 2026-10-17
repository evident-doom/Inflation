package inflation.tools.fauna

import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.ecology.Species
import inflation.ecology.Store
import inflation.world.Biome
import inflation.world.HexWorld
import kotlin.math.abs
import java.util.Random

/**
 * The model as it stands, over the whole map: each method prints one or a few lines of a fauna check's
 * report. Some read and clear a ledger the model keeps for checks ([printCoastAndInland] the residents'
 * food sources, [printChoices] the groups' plan counts), so each is called once a year.
 */
class Census(private val sim: HexSim) {
    private val hw = sim.hw
    private val hex = sim.hex
    private val land = sim.land
    private val fauna = sim.fauna
    private val groups = sim.groups
    private val weather get() = sim.weather

    /** Each place's main kind of country, as the most of its cells. */
    private val biomeOf: IntArray by lazy {
        IntArray(land.n) { pl ->
            val c = IntArray(Biome.entries.size)
            for (cell in hw.placeCells[pl]) c[sim.world.biome[cell].ordinal]++
            c.indices.maxBy { c[it] }
        }
    }

    /** Animals of resident kind [k] at place [pl] (adults and young). */
    private fun residents(k: Int, pl: Int) = ((fauna.adults[k][pl] + fauna.young[k][pl]) * land.areaKm2[pl]).toDouble()

    /** How well this month's climate suits each kind where its groups are, by head count. */
    fun fit() = groups.kinds.joinToString(", ") { s ->
        val gs = groups.groups.filter { it.alive && it.species == s }
        val heads = gs.sumOf { it.size }.coerceAtLeast(1)
        "%s %.2f".format(s.name, gs.sumOf { it.size * fauna.monthFit(s, it.place, weather.date.month).toDouble() } / heads)
    }

    /** How much of the land each kind's climate suits at all, and how strongly its groups feel drawn elsewhere. */
    fun drawn() = groups.kinds.withIndex().joinToString(", ") { (k, s) ->
        val gs = groups.groups.filter { it.alive && it.species == s }
        val pull = gs.sumOf { groups.bearings.strength(k, hw.placeHex[it.place]).toDouble() } / gs.size.coerceAtLeast(1)
        val good = (0 until fauna.n).sumOf { p -> if (fauna.climateSuits(s, p) > 0.8f) land.areaKm2[p].toDouble() else 0.0 } / land.areaKm2.sum()
        "%s %.0f%% / %.2f".format(s.name, 100 * good, pull)
    }

    /** Where the matter is: each pool over the whole map, thousand tonnes. */
    fun budget(): String {
        val sl = fauna.small
        fun perM2(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } // kg/m² × km² = 1e6 kg = 1000 t
        fun perKm2(a: FloatArray) = perM2(a) / 1e6
        val plants = (0 until fauna.n).sumOf { (land.plants.biomass(it) * land.areaKm2[it]).toDouble() }
        val smallAnimals = listOf(sl.insects, sl.dormantInsects, sl.rodents, sl.herps, sl.pollinators, sl.dormantPollinators,
            sl.hares, sl.hyrax, sl.tortoises, sl.foxes, sl.birds).sumOf { perKm2(it) }
        val residents = fauna.kinds.indices.sumOf { fauna.count(it) * fauna.kinds[it].massKg } / 1e6
        val inGroups = groups.groups.filter { it.alive }.sumOf { (it.adults + 0.3 * it.young) * it.species.massKg } / 1e6
        return "plants %.0f, litter %.0f, soil organic %.0f, carrion %.2f, small animals %.2f, resident herds %.2f, herds and packs %.2f (thousand t)".format(
            plants, perM2(sl.litter), perM2(sl.organicMatter), perM2(sl.carrion), smallAnimals, residents, inGroups)
    }

    /** By kind of country: its share of the land, forage, resident animals, herds, hunters and people, per 1000 km². */
    fun printByCountry() {
        val bArea = DoubleArray(Biome.entries.size); val forage = DoubleArray(bArea.size); val res = DoubleArray(bArea.size)
        val herdsB = DoubleArray(bArea.size); val huntersB = DoubleArray(bArea.size); val peopleB = DoubleArray(bArea.size)
        for (pl in 0 until land.n) {
            val b = biomeOf[pl]; val a = land.areaKm2[pl].toDouble()
            bArea[b] += a; forage[b] += (land.palatable[pl] * (land.plants.herb[pl] + land.plants.shrub[pl])) * a
            for (k in fauna.kinds.indices) res[b] += residents(k, pl)
        }
        for (gr in groups.groups) if (gr.alive) {
            val b = biomeOf[gr.place]
            if (gr.species.human) peopleB[b] += gr.size.toDouble()
            else if (gr.species.carnivore) huntersB[b] += gr.size.toDouble()
            else herdsB[b] += gr.size.toDouble()
        }
        val total = bArea.sum()
        println("     by country (share of land: edible forage kg/m², residents / herds / hunters / people per 1000 km²): " +
            Biome.entries.indices.filter { bArea[it] > 0.01 * total }.sortedByDescending { bArea[it] }.joinToString(", ") { b ->
            val k = 1000.0 / bArea[b]
            "%s %.0f%%: %.2f, %.0f / %.0f / %.1f / %.1f".format(Biome.entries[b].name.lowercase().replace('_', ' '), 100 * bArea[b] / total,
                forage[b] / bArea[b], res[b] * k, herdsB[b] * k, huntersB[b] * k, peopleB[b] * k) })
    }

    /**
     * Residents by the sea against inland (a kind crowding the coast shows here), where their food came from this
     * year (clearing that ledger), and what they find there.
     */
    fun printCoastAndInland() {
        var coastA = 0.0; var inA = 0.0
        val coastN = DoubleArray(fauna.kinds.size); val inN = DoubleArray(fauna.kinds.size)
        for (pl in 0 until land.n) {
            val c = hw.coast[hw.placeHex[pl]]; val a = land.areaKm2[pl].toDouble()
            if (c) coastA += a else inA += a
            for (k in fauna.kinds.indices) { val v = residents(k, pl); if (c) coastN[k] += v else inN[k] += v }
        }
        println("     residents by the sea / inland, per km² (coast %.0f%% of land): ".format(100 * coastA / (coastA + inA)) +
            fauna.kinds.indices.joinToString(", ") { k -> "%s %.2f / %.2f".format(fauna.kinds[k].name, coastN[k] / coastA.coerceAtLeast(1.0),
                inN[k] / inA.coerceAtLeast(1.0)) })
        println("     residents fed from (fresh plants / straw / prey and carrion / reserves, shares of need per animal this year): " +
            fauna.kinds.indices.joinToString(", ") { k ->
            val ff = fauna.fedFrom[k]; val h = ff[HexFauna.FED_HEADS].sumThenReset().coerceAtLeast(1e-9)
            "%s %.2f/%.2f/%.2f/%.2f".format(fauna.kinds[k].name, ff[HexFauna.FED_PLANTS].sumThenReset() / h, ff[HexFauna.FED_STRAW].sumThenReset() / h,
                ff[HexFauna.FED_ANIMAL].sumThenReset() / h, ff[HexFauna.FED_RESERVES].sumThenReset() / h)
        })
        // What they find there: edible forage, how green, how well the roe deer fed, how far water is.
        fun mean(f: (Int) -> Double, c: Boolean) = (0 until land.n).filter { hw.coast[hw.placeHex[it]] == c }.let { ps ->
            ps.sumOf { f(it) * land.areaKm2[it] } / ps.sumOf { land.areaKm2[it].toDouble() }.coerceAtLeast(1.0) }
        fun both(f: (Int) -> Double) = arrayOf(mean(f, true), mean(f, false))
        val roe = fauna.kinds.indexOfFirst { it.name == "roe deer" }
        val forage = both { (land.palatable[it] * (land.plants.herb[it] + land.plants.shrub[it])).toDouble() }
        val green = both { land.plants.green[it].toDouble() }
        val roeFed = if (roe >= 0) both { fauna.fed[roe][it].toDouble() } else arrayOf(0.0, 0.0)
        val water = both { land.drink.waterKm[it].toDouble() }
        val temperature = both { weather.temperature[it].toDouble() }
        println("     the sea / inland: edible forage %.3f / %.3f kg/m², green %.2f / %.2f, roe deer fed %.2f / %.2f, water %.1f / %.1f km, temperature %.1f / %.1f °C".format(
            forage[0], forage[1], green[0], green[1], roeFed[0], roeFed[1], water[0], water[1], temperature[0], temperature[1]))
    }

    /** Paths: how many ways are worn, and whether they gather where the going is hard (broken ground, rivers). */
    fun printPaths() {
        val worn = (0 until hex.size).filter { groups.wear.at(it) > 0.3f }
        val rug = { h: Int -> hw.placeOf[0][h].takeIf { it >= 0 }?.let { land.ruggedness[it] } ?: 0f }
        val landHexes = (0 until hex.size).filter { hw.land[it] > 0.5f }
        println("     paths: %d hexes with a worn path (%.1f%% of land); ruggedness where worn %.2f against %.2f overall; mean wear %.3f".format(
            worn.size, 100.0 * worn.size / landHexes.size.coerceAtLeast(1), worn.map { rug(it).toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
            landHexes.map { rug(it).toDouble() }.average(), groups.wear.paths.average()))
        val (fw, fa) = groups.routes.fordShare()
        println("     routes: %s; worn ways crossing rivers %.1f%% against %.1f%% of all borders".format(groups.routes.summary(), 100 * fw, 100 * fa))
    }

    /** How many groups, how big, how much of the land they cover, and how far each herd is from the nearest hunting pack. */
    fun printGroupSpread() {
        val live = groups.groups.filter { it.alive }
        val hunterHexes = live.filter { it.species.pack }.map { hw.placeHex[it.place] }.distinct()
        val landHexes = (0 until hex.size).count { hw.land[it] > 0.5f }
        println("     herds and packs (groups, mean size, hexes held of land hexes, herds' median km to the nearest pack): " +
            groups.kinds.joinToString(", ") { s ->
            val gs = live.filter { it.species == s }
            val km = if (s.carnivore || hunterHexes.isEmpty()) "" else gs.map { g ->
                val h = hw.placeHex[g.place]
                hunterHexes.minOf { hex.distanceKm(h, it) }
            }.sorted().let { if (it.isEmpty()) "" else ", %.0f km".format(it[it.size / 2]) }
            "%s %d / %.0f / %.1f%%%s".format(s.name, gs.size, if (gs.isEmpty()) 0.0 else gs.sumOf { it.size }.toDouble() / gs.size,
                100.0 * gs.map { hw.placeHex[it.place] }.distinct().size / landHexes, km)
        })
    }

    /** How often each kind's groups weighed their choices, and how often that came to moving (clears the counts). */
    fun printChoices() {
        println("     choices a week per group (kept to ground unweighed / weighed and stayed / moved): " + groups.kinds.joinToString(", ") { s ->
            val c = IntArray(3) { groups.planCounts.getAndSet(s.index * 3 + it, 0) }
            val all = c.sum().coerceAtLeast(1)
            "%s %.0f/%.0f/%.0f%%".format(s.name, 100.0 * c[0] / all, 100.0 * c[1] / all, 100.0 * c[2] / all)
        })
    }

    /** The hunters' families: packs, how many hold ground and could breed, lone animals, adults and young. */
    fun printHuntersFamilies() {
        println("     hunters' families (packs / holding ground / able to breed / alone / adults / young): " +
            groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gs = groups.groups.filter { it.alive && it.species == s }
            "%s %d / %d / %d / %d / %d / %d".format(s.name, gs.size, gs.count { it.home >= 0 }, gs.count { it.home >= 0 && it.adults >= 2 },
                gs.count { it.adults == 1 }, gs.sumOf { it.adults }, gs.sumOf { it.young })
        })
    }

    /**
     * Ways of working (how many bands know each, how well, how neighbours' toolkits differ from distant bands'), and
     * cereals; [grainReaped] t in the last year.
     */
    fun printSkillsAndCereals(grainReaped: Double) {
        val gs = groups.groups.filter { it.alive && it.band != null }
        val bs = gs.map { it.band!! }
        // (Skills a band holds are part of its customs; neighbours share them, distant bands differ.)
        if (bs.isNotEmpty()) println("ways of working (bands knowing, their mean level/holders): " + Skill.entries.joinToString(", ") { s ->
            val k = bs.filter { it.knowsSkill[s.ordinal] }
            "%s %.0f%% %.2f/%.1f".format(s.name.lowercase().replace('_', ' '), 100.0 * k.size / bs.size,
                if (k.isEmpty()) 0.0 else k.map { it.skills[s.ordinal].toDouble() }.average(),
                if (k.isEmpty()) 0.0 else k.map { it.holders[s.ordinal].toDouble() }.average()) })
        // How different neighbours' toolkits are against distant bands' (levels of every way, 0 where unknown).
        if (gs.size > 2) {
            val r = Random(7); var nearD = 0.0; var nearN = 0; var farD = 0.0; var farN = 0
            repeat(4000) {
                val x = gs[r.nextInt(gs.size)]; val y = gs[r.nextInt(gs.size)]
                if (x !== y) {
                    val km = hex.distanceKm(hw.placeHex[x.place], hw.placeHex[y.place])
                    val bx = x.band!!; val by = y.band!!
                    val d = Skill.entries.sumOf { s ->
                        val i = s.ordinal
                        abs((if (bx.knowsSkill[i]) bx.skills[i] else 0f) - (if (by.knowsSkill[i]) by.skills[i] else 0f)).toDouble()
                    } / Skill.entries.size
                    if (km < 100.0) { nearD += d; nearN++ } else if (km > 300.0) { farD += d; farN++ }
                }
            }
            println("     toolkits: mean difference between bands within 100 km %.3f, beyond 300 km %.3f".format(nearD / nearN.coerceAtLeast(1),
                farD / farN.coerceAtLeast(1)))
        }
        val f = fauna.food
        val stands = (0 until land.n).count { f.cereal[it] > 0.01f }
        val fields = (0 until land.n).filter { f.domestic[it] > 0.01f }
        val atHome = groups.groups.filter { it.alive && it.band?.atHome == true }
        println("cereals: wild stands in %d places; places with ears that hold their grain %d, most %.2f, mean there %.2f; grain reaped by people %.0f t in the last year; stored grain %.0f t (bands at home %.0f t); grain standing %.0f t, ripened in the last year %.0f t; bands at home on cereal ground %d of %d".format(
            stands, fields.size, fields.maxOfOrNull { f.domestic[it] } ?: 0f,
            if (fields.isEmpty()) 0.0 else fields.map { f.domestic[it].toDouble() }.average(), grainReaped,
            gs.sumOf { it.stores[Store.SEED.ordinal].toDouble() } / 1000,
            atHome.sumOf { it.stores[Store.SEED.ordinal].toDouble() } / 1000,
            (0 until land.n).sumOf { f.grain[it] * land.areaKm2[it] * 1e6 } / 1000, f.grainRipenedLastYear,
            atHome.count { f.cereal[it.place] > 0f }, atHome.size))
    }

    /** How unequal the land is for people: food to be had in each land hex (plant foods, game, fish). */
    fun printFoodUnevenness() {
        val value = DoubleArray(hex.size)
        val f = fauna.food
        for (pl in 0 until land.n) {
            val h = hw.placeHex[pl]; val a = land.areaKm2[pl].toDouble()
            value[h] += (f.fruit[pl] + f.roots[pl] + f.seeds[pl] + f.finds[pl]) * 1e6 * a
            for (k in fauna.kinds.indices) value[h] += 0.1 * fauna.adults[k][pl] * fauna.kinds[k].massKg * a
        }
        for (h in 0 until hex.size) if (hw.land[h] > 0f) value[h] += fauna.water.fishWithin(h).toDouble()
        val v = (0 until hex.size).filter { hw.land[it] > 0.5f }.map { value[it] }.sorted()
        if (v.isEmpty()) return
        val total = v.sum().coerceAtLeast(1e-9)
        var cum = 0.0; var area = 0.0
        for (x in v) { cum += x; area += cum }
        val gini = 1.0 - 2.0 * (area / (v.size * total)) + 1.0 / v.size
        val top = v.takeLast((v.size / 20).coerceAtLeast(1)).sum() / total
        println("food for people across the land: unevenness (Gini) %.2f, richest 5%% of hexes hold %.0f%% of it, median hex %.0f t".format(gini,
            100 * top, v[v.size / 2] / 1000))
    }

    /**
     * Kind by kind: where it lives (its three main kinds of country, against the land's), how well fed,
     * what the herds ate ([lastYear]'s tallies), how far groups went ([movedKm] this year), and its catalogue niche.
     */
    fun printKindByKind(lastYear: Map<String, Int>, movedKm: Map<Species, Double>, stepsPerYear: Int) {
        val landB = DoubleArray(Biome.entries.size); for (pl in 0 until land.n) landB[biomeOf[pl]] += land.areaKm2[pl].toDouble()
        fun top(byB: DoubleArray): String {
            val tot = byB.sum().coerceAtLeast(1e-9)
            return byB.indices.sortedByDescending { byB[it] }.take(3).filter { byB[it] > 0 }.joinToString(", ") {
                "%s %.0f%% (land %.0f%%)".format(Biome.entries[it].name.lowercase().replace('_', ' '), 100 * byB[it] / tot, 100 * landB[it] / landB.sum())
            }
        }
        println("kind by kind, at the end:")
        for (k in fauna.kinds.indices) {
            val s = fauna.kinds[k]; val byB = DoubleArray(landB.size); var fedW = 0.0; var n = 0.0
            for (pl in 0 until land.n) { val c = residents(k, pl); byB[biomeOf[pl]] += c; fedW += c * fauna.fed[k][pl]; n += c }
            println("  %-14s %8.0f | lives in: %s | fed %.2f | habitat wanted: cover %+.1f, rugged %+.1f, wet %+.1f".format(s.name, n, top(byB),
                fedW / n.coerceAtLeast(1.0), s.habitat.cover, s.habitat.rugged, s.habitat.wet))
        }
        for (s in groups.kinds) {
            val gs = groups.groups.filter { it.alive && it.species == s }
            val byB = DoubleArray(landB.size); for (gr in gs) byB[biomeOf[gr.place]] += gr.size.toDouble()
            val heads = gs.sumOf { it.size }.toDouble()
            val diet = if (s.carnivore) "" else listOf("grass", "browse", "fruit and roots", "straw").let { ks ->
                val v = ks.map { (lastYear["${s.name}:ate $it, kg"] ?: 0).toDouble() }
                val tot = v.sum().coerceAtLeast(1.0)
                " | ate: " + ks.indices.joinToString(", ") { "%s %.0f%%".format(ks[it], 100 * v[it] / tot) }
            }
            val ranged = if (s.carnivore) "" else " | grazed over %.1f places a week".format((lastYear["${s.name}:ranged over places"] ?: 0).toDouble() /
                (gs.size * stepsPerYear).coerceAtLeast(1))
            println("  %-14s %8.0f in %d groups | lives in: %s | fed %.2f | moved %.0f km a year%s%s".format(s.name, heads, gs.size, top(byB),
                gs.sumOf { it.fed.toDouble() * it.size } / heads.coerceAtLeast(1.0), (movedKm[s] ?: 0.0) / gs.size.coerceAtLeast(1), diet, ranged))
        }
    }

    /** Bands by kind of country over the last year ([lastYear]'s tallies): how many, how they fed and on what, how they lived and died. */
    fun printBandsByCountry(lastYear: Map<String, Int>, stepsPerYear: Int) {
        fun weeks(c: String) = lastYear["bands in $c:band-weeks"] ?: 0
        val cs = lastYear.keys.filter { it.startsWith("bands in ") && it.endsWith(":band-weeks") }
            .map { it.removePrefix("bands in ").removeSuffix(":band-weeks") }.sortedByDescending { weeks(it) }
        val allWeeks = cs.sumOf { weeks(it).toDouble() }.coerceAtLeast(1.0)
        println("bands by kind of country, last year (share of band-weeks; food got of food wanted; meat / plants / fish): " + cs.joinToString(", ") { c ->
            fun v(k: String) = (lastYear["bands in $c:$k"] ?: 0).toDouble()
            val got = v("meat") + v("plants") + v("fish")
            "%s %.0f%%: %.0f%%, %.0f/%.0f/%.0f%%".format(c, 100 * v("band-weeks") / allWeeks, 100 * got / v("asked").coerceAtLeast(1.0),
                100 * v("meat") / got.coerceAtLeast(1.0), 100 * v("plants") / got.coerceAtLeast(1.0), 100 * v("fish") / got.coerceAtLeast(1.0)) })
        println("bands by kind of country, last year (births / deaths per person a year; main causes): " + cs.joinToString(", ") { c ->
            val years = (lastYear["bands in $c:people-weeks"] ?: 0).toDouble() / stepsPerYear
            val died = lastYear.filterKeys { it.startsWith("bands in $c:died of ") }.mapKeys { it.key.substringAfter("died of ") }
            "%s %.3f / %.3f (%s)".format(c, (lastYear["bands in $c:born"] ?: 0) / years.coerceAtLeast(1e-9), died.values.sum() / years.coerceAtLeast(1e-9),
                died.entries.sortedByDescending { it.value }.take(3).joinToString(" ") { "%s %.3f".format(it.key, it.value / years.coerceAtLeast(1e-9)) }) })
    }

    /** Islands: land cut off from the largest land by sea; which kinds live there now. */
    fun printIslands() {
        val comp = IntArray(hex.size) { -1 }; var nc = 0; val sizes = ArrayList<Int>()
        for (h0 in 0 until hex.size) {
            if (hex.heart[h0] < 0 || comp[h0] >= 0) continue
            val stack = ArrayDeque(listOf(h0)); comp[h0] = nc; var c = 0
            while (stack.isNotEmpty()) {
                val h = stack.removeLast()
                c++
                for (d in 0 until 6) {
                    val b = hex.neighbour(h, d)
                    if (b >= 0 && hex.heart[b] >= 0 && comp[b] < 0) {
                        comp[b] = nc
                        stack.addLast(b)
                    }
                }
            }
            sizes += c; nc++
        }
        val main = sizes.indices.maxByOrNull { sizes[it] } ?: -1
        val islands = sizes.indices.filter { it != main && sizes[it] >= 3 }
        if (islands.isEmpty()) return
        println("islands (%d of 3 hexes or more; hexes / herds and packs / resident kinds above 0.01 a km²): ".format(islands.size) +
            islands.sortedByDescending { sizes[it] }.take(8).joinToString("; ") { c ->
            val gs = groups.groups.filter { it.alive && comp[hw.placeHex[it.place]] == c }.map { it.species.name }.distinct()
            val res = fauna.kinds.indices.filter { k ->
                var a = 0.0
                var n = 0.0
                for (pl in 0 until land.n) if (comp[hw.placeHex[pl]] == c) {
                    a += land.areaKm2[pl]
                    n += (fauna.adults[k][pl] + fauna.young[k][pl]) * land.areaKm2[pl]
                }
                a > 0 && n / a > 0.01
            }.map { fauna.kinds[it].name }
            "%d / %s / %s".format(sizes[c], gs.joinToString(",").ifEmpty { "-" }, res.joinToString(",").ifEmpty { "-" }) })
    }

    /** Where each kind lives at the end: share in each height band. */
    fun printHeights() {
        println("share of each kind by height band (lowest first):")
        for ((i, s) in fauna.kinds.withIndex()) {
            val byBand = DoubleArray(HexWorld.BANDS_M.size)
            for (p in 0 until fauna.n) byBand[hw.placeBand[p]] += residents(i, p)
            val tot = byBand.sum().coerceAtLeast(1.0)
            println("  %-12s ".format(s.name) + byBand.joinToString(" ") { "%3.0f%%".format(100 * it / tot) })
        }
    }
}
