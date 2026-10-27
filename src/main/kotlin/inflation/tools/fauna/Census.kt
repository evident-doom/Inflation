package inflation.tools.fauna

import inflation.ecology.set
import inflation.ecology.get
import inflation.ecology.PlantFrom
import inflation.world.Hex
import inflation.StockName
import inflation.Stocks
import inflation.ecology.Activity
import inflation.ecology.Catalogue
import inflation.ecology.DeathCause
import inflation.ecology.Tally
import inflation.ecology.HexGeography
import inflation.ecology.Fed
import inflation.ecology.HexFauna
import inflation.ecology.TallyKey
import inflation.world.get
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.ecology.Species
import inflation.ecology.Store
import inflation.ecology.Stuck
import inflation.world.Biome
import inflation.world.HexWorld
import kotlin.math.abs
import java.util.Random
import inflation.world.get
import inflation.world.set
import inflation.world.Place
import inflation.ecology.seedCornIn
import inflation.ecology.bandLiving
import inflation.ecology.birthSpacing
import kotlin.math.max

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
    /** The named features of the country, told once (they are the land as the world made it). */
    private var featuresTold = false

    /** Each place's main kind of country, as the most of its cells. */
    private val biomeOf: IntArray get() = hw.placeBiome

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
        val good = (0 until fauna.n).sumOf { p -> if (fauna.climateSuits(s, Place(p)) > 0.8f) land.areaKm2[p].toDouble() else 0.0 } / land.areaKm2.sum()
        "%s %.0f%% / %.2f".format(s.name, 100 * good, pull)
    }

    /** The model's stocks, listed once. */
    private val stocks by lazy { sim.stocks() }

    /** Where the matter is: each pool over the whole map, thousand tonnes (as the books count it). */
    fun budget(): String {
        val m = stocks.matter()
        fun v(name: StockName) = m[name.label] ?: 0.0
        return "plants %.0f, litter %.0f, soil organic %.0f, carrion %.2f, small animals %.2f, resident animals %.2f, packs and bands %.2f (thousand t)".format(
            v(StockName.PLANTS), v(StockName.LITTER), v(StockName.SOIL_ORGANIC_MATTER), v(StockName.CARRION), v(StockName.SMALL_ANIMALS), v(StockName.RESIDENT_ANIMALS), v(StockName.GROUPS))
    }

    /** By kind of country: its share of the land, forage, resident animals, hunters and people, per 1000 km². */
    fun printByCountry() {
        val bArea = DoubleArray(Biome.entries.size); val forage = DoubleArray(bArea.size); val res = DoubleArray(bArea.size)
        val huntersB = DoubleArray(bArea.size); val peopleB = DoubleArray(bArea.size)
        for (pl in 0 until land.n) {
            val b = biomeOf[pl]; val a = land.areaKm2[pl].toDouble()
            bArea[b] += a; forage[b] += (land.palatable[pl] * (land.plants.herb[pl] + land.plants.shrub[pl])) * a
            for (k in fauna.kinds.indices) res[b] += residents(k, pl)
        }
        for (gr in groups.groups) if (gr.alive) {
            val b = biomeOf[gr.place]
            if (gr.species.human) peopleB[b] += gr.size.toDouble() else huntersB[b] += gr.size.toDouble()
        }
        val total = bArea.sum()
        println("     by country (share of land: edible forage kg/m², residents / hunters / people per 1000 km²): " +
            Biome.entries.indices.filter { bArea[it] > 0.01 * total }.sortedByDescending { bArea[it] }.joinToString(", ") { b ->
            val k = 1000.0 / bArea[b]
            "%s %.0f%%: %.2f, %.0f / %.1f / %.1f".format(Biome.entries[b].label, 100 * bArea[b] / total,
                forage[b] / bArea[b], res[b] * k, huntersB[b] * k, peopleB[b] * k) })
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
        println("     residents fed from (fresh plants / prey and carrion / reserves, shares of need per animal this year): " +
            fauna.kinds.indices.joinToString(", ") { k ->
            val ff = fauna.fedFrom[k]; val h = ff[Fed.HEADS].sumThenReset().coerceAtLeast(1e-9)
            "%s %.2f/%.2f/%.2f".format(fauna.kinds[k].name, ff[Fed.PLANTS].sumThenReset() / h,
                ff[Fed.ANIMAL].sumThenReset() / h, ff[Fed.RESERVES].sumThenReset() / h)
        })
        println("     residents held hungry with no better place next door (share of head-weeks this year / of those at a closed border / most in one week): " +
            fauna.kinds.indices.filter { fauna.stuck[it][Stuck.HEADS.ordinal] > 0.0 }.joinToString(", ") { k ->
            val st = fauna.stuck[k]; val h = st[Stuck.HEADS.ordinal]
            "%s %.0f%% / %.0f%% / %.0f%%".format(fauna.kinds[k].name, 100 * st[Stuck.STUCK.ordinal] / h,
                100 * st[Stuck.AT_EDGE.ordinal] / st[Stuck.STUCK.ordinal].coerceAtLeast(1e-9), 100 * st[Stuck.PEAK.ordinal])
        })
        for (st in fauna.stuck) st.fill(0.0)
        // What they find there: edible forage, how green, how well the roe deer fed, how far water is.
        fun mean(f: (Int) -> Double, c: Boolean) = (0 until land.n).filter { hw.coast[hw.placeHex[it]] == c }.let { ps ->
            ps.sumOf { f(it) * land.areaKm2[it] } / ps.sumOf { land.areaKm2[it].toDouble() }.coerceAtLeast(1.0) }
        fun both(f: (Int) -> Double) = arrayOf(mean(f, true), mean(f, false))
        val roe = fauna.kinds.indexOf(Catalogue.ROE_DEER)
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
        val worn = hex.all.filter { groups.wear.at(it) > 0.3f }
        val rug = { h: Hex -> hw.firstPlaceIn(h).takeIf { !it.none }?.let { land.ruggedness[it] } ?: 0f }
        val landHexes = hex.all.filter { hw.land[it] > 0.5f }
        println("     paths: %d hexes with a worn path (%.1f%% of land); ruggedness where worn %.2f against %.2f overall; mean wear %.3f".format(
            worn.size, 100.0 * worn.size / landHexes.size.coerceAtLeast(1), worn.map { rug(it).toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
            landHexes.map { rug(it).toDouble() }.average(), groups.wear.paths.average()))
        val (fw, fa) = groups.routes.fordShare()
        println("     routes: %s; worn ways crossing rivers %.1f%% against %.1f%% of all borders".format(groups.routes.summary(), 100 * fw, 100 * fa))
        if (!featuresTold) { featuresTold = true; println("     features: " + HexGeography(hw).summary()) }
    }

    /** How many groups, how big, and how much of the land they cover. */
    fun printGroupSpread() {
        val live = groups.groups.filter { it.alive }
        val landHexes = hex.all.count { hw.land[it] > 0.5f }
        println("     packs and bands (groups, mean size, hexes held of land hexes): " +
            groups.kinds.joinToString(", ") { s ->
            val gs = live.filter { it.species == s }
            "%s %d / %.0f / %.1f%%".format(s.name, gs.size, if (gs.isEmpty()) 0.0 else gs.sumOf { it.size }.toDouble() / gs.size,
                100.0 * gs.map { hw.placeHex[it.place] }.distinct().size / landHexes)
        })
    }

    /** How often each kind's groups weighed their choices, and how often that came to moving (clears the counts). */
    fun printChoices() {
        println("     choices a week per group (unused / weighed and stayed / moved): " + groups.kinds.joinToString(", ") { s ->
            val c = IntArray(3) { groups.planner.planCounts.getAndSet(s.index * 3 + it, 0) }
            val all = c.sum().coerceAtLeast(1)
            "%s %.0f/%.0f/%.0f%%".format(s.name, 100.0 * c[0] / all, 100.0 * c[1] / all, 100.0 * c[2] / all)
        })
    }

    /** The hunters' families: packs, how many hold ground and could breed, lone animals, adults and young. */
    fun printHuntersFamilies() {
        println("     hunters' families (packs / holding ground / able to breed / alone / adults / young): " +
            groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gs = groups.groups.filter { it.alive && it.species == s }
            "%s %d / %d / %d / %d / %d / %d".format(s.name, gs.size, gs.count { !it.home.none }, gs.count { !it.home.none && it.adults >= 2 },
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
            val k = bs.filter { it.knowsSkill[s] }
            "%s %.0f%% %.2f/%.1f".format(s.name.lowercase().replace('_', ' '), 100.0 * k.size / bs.size,
                if (k.isEmpty()) 0.0 else k.map { it.skills[s].toDouble() }.average(),
                if (k.isEmpty()) 0.0 else k.map { it.holders[s].toDouble() }.average()) })
        // How different neighbours' toolkits are against distant bands' (levels of every way, 0 where unknown).
        if (gs.size > 2) {
            val r = Random(7); var nearD = 0.0; var nearN = 0; var farD = 0.0; var farN = 0
            repeat(4000) {
                val x = gs[r.nextInt(gs.size)]; val y = gs[r.nextInt(gs.size)]
                if (x !== y) {
                    val km = hex.distanceKm(hw.placeHex[x.place], hw.placeHex[y.place])
                    val bx = x.band!!; val by = y.band!!
                    val d = Skill.entries.sumOf { s ->
                        val i = s
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
        val fields = (0 until land.n).filter { f.field[it] > 0f || f.fieldGrain[it] > 0f }
        val atHome = groups.groups.filter { it.alive && it.band?.atHome == true }
        println("cereals: wild stands in %d places; places with sown fields (sown or ripe) %d, the share of their ears that hold their grain: most %.2f, mean %.2f, this year's fields bearing %.2f of rested ground, worn ground %.1f km²; grain reaped by people %.0f t in the last year; stored grain %.0f t (bands at home %.0f t); grain standing %.0f t, ripened in the last year %.0f t; bands at home on cereal ground %d of %d".format(
            stands, fields.size, fields.maxOfOrNull { f.fieldDomestic[it] } ?: 0f,
            if (fields.isEmpty()) 0.0 else fields.map { f.fieldDomestic[it].toDouble() }.average(),
            fields.filter { f.field[it] > 0f }.map { f.cropSoil(Place(it)).toDouble() }.average().takeIf { it.isFinite() } ?: 1.0,
            (0 until land.n).sumOf { f.fieldWorn[it] * land.areaKm2[it].toDouble() }, grainReaped,
            gs.sumOf { it.stores[Store.SEED].toDouble() } / 1000,
            atHome.sumOf { it.stores[Store.SEED].toDouble() } / 1000,
            (0 until land.n).sumOf { (f.grain[it] + f.fieldGrain[it]) * land.areaKm2[it] * 1e6 } / 1000, f.grainRipenedLastYear,
            atHome.count { f.cereal[it.place] > 0f }, atHome.size))
        // (Where the ears that hold their grain are kept: in the fields standing, by weight, and in the bands' seed corn and other grain.)
        val sown = (0 until land.n).filter { f.field[it] > 0f }
        val sownKg = sown.sumOf { f.field[it] * land.areaKm2[it] * 1e6 }
        val bands = groups.groups.mapNotNull { g -> g.band?.takeIf { g.alive }?.let { b -> Triple(b, b.seedCornIn(g), g.stores[Store.SEED] - b.seedCornIn(g)) } }
        val cornSum = bands.sumOf { it.second.toDouble() }
        val wild = bands.sumOf { it.third.toDouble() }
        println("cereals seed: sown and standing in %d places, %.1f t, of it from ears that hold their grain %.2f (by weight); bands holding seed corn %d of %d, %.1f t, of it from ears that hold their grain %.2f; their other grain %.1f t, %.2f".format(
            sown.size, sownKg / 1000, if (sownKg > 0) sown.sumOf { f.fieldDomestic[it] * f.field[it] * land.areaKm2[it] * 1e6 } / sownKg else 0.0,
            bands.count { it.second > 0f }, bands.size, cornSum / 1000, if (cornSum > 0) bands.sumOf { it.first.seedDomestic * it.second.toDouble() } / cornSum else 0.0,
            wild / 1000, if (wild > 0) bands.sumOf { it.first.grainDomestic * it.third.toDouble() } / wild else 0.0))
        // (What spaces births: gruel to wean on, and children carried on the band's moves; people-weighted.)
        println("births by how bands live (people; share of weanlings weaned on gruel; camp moves a year; births against a band without weaning food): " + Tally.Living.entries.joinToString(", ") { way ->
            val ofWay = groups.groups.filter { g -> g.alive && g.band?.let { groups.bandLiving(it) == way } == true }
            val people = ofWay.sumOf { it.size.toDouble() }.coerceAtLeast(1.0)
            fun mean(v: (inflation.ecology.Band) -> Float) = ofWay.sumOf { g -> g.band?.let { v(it).toDouble() * g.size } ?: 0.0 } / people
            "%s %d: %.2f; %.1f; %.2f".format(way.label, ofWay.sumOf { it.size }, mean { it.weaningFood },
                mean { groups.stepsPerYear / max(it.stayWeeks, it.campWeeks + 1f) }, mean { groups.birthSpacing(it) })
        })
    }

    /** How unequal the land is for people: food to be had in each land hex (plant foods, game, fish). */
    fun printFoodUnevenness() {
        val value = DoubleArray(hex.size)
        val f = fauna.food
        for (pl in 0 until land.n) {
            val h = hw.placeHex[pl]; val a = land.areaKm2[pl].toDouble()
            value[h] += (f.fruit[pl] + f.roots[pl] + f.mast[pl] + f.seeds[pl] + f.finds[pl]) * 1e6 * a
            for (k in fauna.kinds.indices) value[h] += 0.1 * fauna.adults[k][pl] * fauna.kinds[k].massKg * a
        }
        for (h in hex.all) if (hw.land[h] > 0f) value[h] += fauna.water.fishWithin(h).toDouble()
        val v = hex.all.filter { hw.land[it] > 0.5f }.map { value[it] }.sorted()
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
     * and how far groups went ([movedKm] this year).
     */
    fun printKindByKind(movedKm: Map<Species, Double>) {
        val landB = DoubleArray(Biome.entries.size); for (pl in 0 until land.n) landB[biomeOf[pl]] += land.areaKm2[pl].toDouble()
        fun top(byB: DoubleArray): String {
            val tot = byB.sum().coerceAtLeast(1e-9)
            return byB.indices.sortedByDescending { byB[it] }.take(3).filter { byB[it] > 0 }.joinToString(", ") {
                "%s %.0f%% (land %.0f%%)".format(Biome.entries[it].label, 100 * byB[it] / tot, 100 * landB[it] / landB.sum())
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
            println("  %-14s %8.0f in %d groups | lives in: %s | fed %.2f | moved %.0f km a year".format(s.name, heads, gs.size, top(byB),
                gs.sumOf { it.fed.toDouble() * it.size } / heads.coerceAtLeast(1.0), (movedKm[s] ?: 0.0) / gs.size.coerceAtLeast(1)))
        }
    }

    /** Bands by kind of country over the last year ([lastYear]'s tallies): how many, how they fed and on what, how they lived and died. */
    fun printBandsByCountry(lastYear: Map<TallyKey, Int>, stepsPerYear: Int) {
        fun weeks(c: Biome) = lastYear[Tally.bandsIn(c, Tally.Bands.BAND_WEEKS)] ?: 0
        val cs = lastYear.keys.mapNotNull { Tally.bandsCountry(it, Tally.Bands.BAND_WEEKS) }.sortedByDescending { weeks(it) }
        val allWeeks = cs.sumOf { weeks(it).toDouble() }.coerceAtLeast(1.0)
        println("bands by kind of country, last year (share of band-weeks; food got of food wanted; meat / plants / fish): " + cs.joinToString(", ") { c ->
            fun v(k: Tally.Bands) = (lastYear[Tally.bandsIn(c, k)] ?: 0).toDouble()
            val got = v(Tally.Bands.MEAT) + v(Tally.Bands.PLANTS) + v(Tally.Bands.FISH)
            "%s %.0f%%: %.0f%%, %.0f/%.0f/%.0f%%".format(c.label, 100 * v(Tally.Bands.BAND_WEEKS) / allWeeks, 100 * got / v(Tally.Bands.ASKED).coerceAtLeast(1.0),
                100 * v(Tally.Bands.MEAT) / got.coerceAtLeast(1.0), 100 * v(Tally.Bands.PLANTS) / got.coerceAtLeast(1.0), 100 * v(Tally.Bands.FISH) / got.coerceAtLeast(1.0)) })
        println("bands by kind of country, last year (births / deaths per person a year, foreseen deaths a year ahead as the weeks went, mean perseverance; main causes): " + cs.joinToString(", ") { c ->
            val years = (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() / stepsPerYear
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsDied(c)) }.mapKeys { Tally.diedOf(it.key) }
            val foreseen = (lastYear[Tally.bandsIn(c, Tally.Bands.FORESEEN)] ?: 0) / 1000.0 / (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            val sisu = (lastYear[Tally.bandsIn(c, Tally.Bands.SISU)] ?: 0) / 1000.0 / (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            "%s %.3f / %.3f, foreseen %.3f, perseverance %.2f (%s)".format(c.label, (lastYear[Tally.bandsIn(c, Tally.Bands.BORN)] ?: 0) / years.coerceAtLeast(1e-9), died.values.sum() / years.coerceAtLeast(1e-9), foreseen, sisu,
                died.entries.sortedByDescending { it.value }.take(3).joinToString(" ") { "%s %.3f".format(it.key, it.value / years.coerceAtLeast(1e-9)) }) })
        run {
            val all = (lastYear[Tally.bandTime(Tally.TimeTotal.ALL)] ?: 0).toDouble().coerceAtLeast(1.0)
            println("bands' time, last year (share of their hands' days): " + (Activity.entries.map { it.label to Tally.bandTime(it) } +
                (Tally.TimeTotal.LEFT.label to Tally.bandTime(Tally.TimeTotal.LEFT)) + (Tally.TimeTotal.LOST.label to Tally.bandTime(Tally.TimeTotal.LOST))).joinToString(", ") { (label, key) -> "%s %.0f%%".format(label, 100 * (lastYear[key] ?: 0).toDouble() / all) })
        }
        println("bands by clothing, last year (clad: a kit of at least half; share of people-weeks; deaths per person a year, of them of exposure): " + Tally.Clad.entries.joinToString(", ") { c ->
            val years = (lastYear[Tally.bandsClad(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() / stepsPerYear
            val all = Tally.Clad.entries.sumOf { lastYear[Tally.bandsClad(it, Tally.Bands.PEOPLE_WEEKS)] ?: 0 }
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsCladDied(c)) }
            "%s %.0f%%: %.3f, %.3f".format(c.label, 100.0 * (lastYear[Tally.bandsClad(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0) / all.coerceAtLeast(1),
                died.values.sum() / years.coerceAtLeast(1e-9), (died[Tally.bandsCladDied(c, DeathCause.EXPOSURE)] ?: 0) / years.coerceAtLeast(1e-9)) })
        println("bands by how they live, last year (settled: at home in their houses or at a camp kept half a year or more; share of people-weeks; people a band; births / deaths per 1000 people a year; deaths per 1000 a year by cause, largest first): " + Tally.Living.entries.joinToString(", ") { w ->
            val pw = (lastYear[Tally.bandsLiving(w, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble()
            val all = Tally.Living.entries.sumOf { (lastYear[Tally.bandsLiving(it, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() }.coerceAtLeast(1.0)
            val years = (pw / stepsPerYear).coerceAtLeast(1e-9)
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsLivingDied(w)) }.mapKeys { Tally.diedOf(it.key) }
            "%s %.0f%%, %.1f a band: %.1f / %.1f (%s)".format(w.label, 100 * pw / all, pw / (lastYear[Tally.bandsLiving(w, Tally.Bands.BAND_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0),
                1000 * (lastYear[Tally.bandsLiving(w, Tally.Bands.BORN)] ?: 0) / years, 1000 * died.values.sum() / years,
                died.entries.sortedByDescending { it.value }.joinToString(" ") { "%s %.1f".format(it.key, 1000 * it.value / years) }) })
        println("bands by how well fed through the week, their fat drawn on, last year (share of people-weeks; deaths per person a year of hunger, of exposure, of all; larder, of it meat, and fat at the week's end, days; share of the protein eaten that went to waste; salt held, g a person, sodium short, salt here; share that know of salt, km to the nearest known): " + Tally.Fed.entries.joinToString(", ") { f ->
            val pw = (lastYear[Tally.bandsFed(f, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble()
            val all = Tally.Fed.entries.sumOf { (lastYear[Tally.bandsFed(it, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() }.coerceAtLeast(1.0)
            val years = (pw / stepsPerYear).coerceAtLeast(1e-9); val bw = (lastYear[Tally.bandsFed(f, Tally.Bands.BAND_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsFedDied(f)) }
            "%s %.1f%%: %.3f, %.3f, %.3f; %.1f, %.1f, %.1f; %.0f%%; %.0f, %.0f%%, %.0f%%; %.0f%%, %.0f".format(f.label, 100 * pw / all, (died[Tally.bandsFedDied(f, DeathCause.HUNGER)] ?: 0) / years,
                (died[Tally.bandsFedDied(f, DeathCause.EXPOSURE)] ?: 0) / years, died.values.sum() / years,
                (lastYear[Tally.bandsFed(f, Tally.Bands.LARDER)] ?: 0) / 10.0 / bw, (lastYear[Tally.bandsFed(f, Tally.Bands.LARDER_MEAT)] ?: 0) / 10.0 / bw,
                (lastYear[Tally.bandsFed(f, Tally.Bands.FAT)] ?: 0) / 10.0 / bw, (lastYear[Tally.bandsFed(f, Tally.Bands.LEAN_LOST)] ?: 0).toDouble() / bw,
                (lastYear[Tally.bandsFed(f, Tally.Bands.SALT_HELD)] ?: 0).toDouble() / bw, (lastYear[Tally.bandsFed(f, Tally.Bands.SODIUM_SHORT)] ?: 0).toDouble() / bw,
                (lastYear[Tally.bandsFed(f, Tally.Bands.SALT_HERE)] ?: 0).toDouble() / bw,
                100.0 * (lastYear[Tally.bandsFed(f, Tally.Bands.SALT_KNOWN)] ?: 0) / bw,
                (lastYear[Tally.bandsFed(f, Tally.Bands.SALT_KM)] ?: 0).toDouble() / (lastYear[Tally.bandsFed(f, Tally.Bands.SALT_KNOWN)] ?: 0).coerceAtLeast(1)) })
        run {
            // Meat brought in against meat eaten (each at what it feeds): what a kill beyond the band's hunger leaves to spoil.
            val h = Catalogue.HUMAN
            fun v(k: TallyKey) = (lastYear[k] ?: 0).toDouble()
            val brought = cs.sumOf { v(Tally.bandsIn(it, Tally.Bands.MEAT)) + v(Tally.bandsIn(it, Tally.Bands.FISH)) }
            val eaten = v(Tally.diet(h, Tally.DietPart.MEAT)) + v(Tally.diet(h, Tally.DietPart.SMALL_PREY)) + v(Tally.diet(h, Tally.DietPart.CARRION))
            val asked = cs.sumOf { v(Tally.bandsIn(it, Tally.Bands.ASKED)) }.coerceAtLeast(1.0)
            println("bands' food, last year (of food wanted: meat and fish brought in, of it eaten, plants got): %.0f%%, %.0f%%, %.0f%%".format(
                100 * brought / asked, 100 * eaten / brought.coerceAtLeast(1.0), 100 * cs.sumOf { v(Tally.bandsIn(it, Tally.Bands.PLANTS)) } / asked))
            println("bands' plant food, last year (of food wanted, by what it feeds): " + PlantFrom.entries.joinToString(", ") { f ->
                "%s %.1f%%".format(f.label, 100 * v(Tally.plantsFrom(f)) / asked) })
        }
        println("bands by kind of country, last year (hearth fire fed by the firewood got, share of the days spent fetching it): " + cs.joinToString(", ") { c ->
            val w = weeks(c).toDouble().coerceAtLeast(1.0)
            "%s %.0f%% / %.0f%%".format(c.label, (lastYear[Tally.bandsIn(c, Tally.Bands.HEARTH)] ?: 0) / w, (lastYear[Tally.bandsIn(c, Tally.Bands.FUEL_DAYS)] ?: 0) / w) })
        println("bands by kind of country, last year, in weeks the cold ran them into heat debt through their cover (share of band-weeks: worst debt Wh/m² beyond the bearable; night air °C, clothes clo, fire, shelter or house; share at a new camp, shelter aimed at, share of days spare): " +
            cs.filter { (lastYear[Tally.bandsIn(it, Tally.Cold.WEEKS)] ?: 0) > 0 }.joinToString(", ") { c ->
                fun v(k: Tally.Cold) = (lastYear[Tally.bandsIn(c, k)] ?: 0).toDouble()
                val n = v(Tally.Cold.WEEKS).coerceAtLeast(1.0)
                "%s %.0f%%: %.0f; %.1f, %.1f, %.1f, %.1f; %.0f%%, %.1f, %.0f%%".format(c.label, 10 * 100 * v(Tally.Cold.WEEKS) / (10 * weeks(c)).coerceAtLeast(1) / 10,
                    v(Tally.Cold.SHORT) / n, v(Tally.Cold.AIR) / n, v(Tally.Cold.CLOTHES) / n, v(Tally.Cold.FIRE) / n, v(Tally.Cold.SHELTER) / n,
                    100 * v(Tally.Cold.MOVED) / n, v(Tally.Cold.AIM) / n, 100 * v(Tally.Cold.SPARE) / n) })
    }

    /** Islands: land cut off from the largest land by sea; which kinds live there now. */
    /**
     * Bands' weeks against what their chores asked (stage 0 of the band-choices review, report only): each chore's share of
     * band-weeks it asked days, of them the share it got less than [inflation.ecology.SQUEEZED] of its ask in a week that ran
     * out of days, and the share of its asked days not got; then bands by their spare days over the last season and by
     * whether they keep a herd, with their clothes and deaths by cause; and what stopped their clothes making.
     */
    fun printSqueeze(lastYear: Map<TallyKey, Int>, stepsPerYear: Int) {
        fun v(k: TallyKey) = (lastYear[k] ?: 0).toDouble()
        val weeks = Tally.Spare.entries.sumOf { v(Tally.bandsSpare(it, Tally.Bands.BAND_WEEKS)) }.coerceAtLeast(1.0)
        println("bands' chores against their asks, last year (share of band-weeks it asked days; of them, share it got under 90% of its ask in a week that ran out of days; share of the days asked not got): " +
            Activity.entries.filter { v(Tally.squeeze(it, Tally.Squeeze.ASKED_WEEKS)) > 0 }.joinToString(", ") { a ->
                val asked = v(Tally.squeeze(a, Tally.Squeeze.ASKED_WEEKS))
                "%s %.0f%%, %.1f%%, %.1f%%".format(a.label, 100 * asked / weeks, 100 * v(Tally.squeeze(a, Tally.Squeeze.SHORT_WEEKS)) / asked,
                    100 * v(Tally.squeeze(a, Tally.Squeeze.MISSING_DAYS)) / v(Tally.squeeze(a, Tally.Squeeze.ASKED_DAYS)).coerceAtLeast(1.0))
            })
        fun group(pw: Double, all: Double, bw: Double, clo: Double, died: Map<TallyKey, Int>) = run {
            val years = (pw / stepsPerYear).coerceAtLeast(1e-9)
            "%.0f%%, %.1f a band; %.2f clo; %.1f (%s)".format(100 * pw / all.coerceAtLeast(1.0), pw / bw.coerceAtLeast(1.0), clo / 10 / bw.coerceAtLeast(1.0),
                1000 * died.values.sum() / years, died.entries.sortedByDescending { it.value }.take(6).joinToString(" ") { "%s %.1f".format(Tally.diedOf(it.key), 1000 * it.value / years) })
        }
        val spareAll = Tally.Spare.entries.sumOf { v(Tally.bandsSpare(it, Tally.Bands.PEOPLE_WEEKS)) }
        println("bands by spare days over the last season, last year (share of people-weeks, people a band; clothes; deaths per 1000 people a year, by cause, largest first): " +
            Tally.Spare.entries.joinToString(", ") { sp ->
                sp.label + " " + group(v(Tally.bandsSpare(sp, Tally.Bands.PEOPLE_WEEKS)), spareAll, v(Tally.bandsSpare(sp, Tally.Bands.BAND_WEEKS)),
                    v(Tally.bandsSpare(sp, Tally.Bands.CLOTHES)), lastYear.filterKeys { it.startsWith(Tally.bandsSpareDied(sp)) })
            })
        val herdAll = Tally.HerdKept.entries.sumOf { v(Tally.bandsHerdKept(it, Tally.Bands.PEOPLE_WEEKS)) }
        println("bands by whether they keep a herd, squeeze census, last year (share of people-weeks, people a band; clothes; deaths per 1000 people a year, by cause; mean spare days over the last season): " +
            Tally.HerdKept.entries.joinToString(", ") { h ->
                val bw = v(Tally.bandsHerdKept(h, Tally.Bands.BAND_WEEKS))
                h.label + " " + group(v(Tally.bandsHerdKept(h, Tally.Bands.PEOPLE_WEEKS)), herdAll, bw, v(Tally.bandsHerdKept(h, Tally.Bands.CLOTHES)),
                    lastYear.filterKeys { it.startsWith(Tally.bandsHerdKeptDied(h)) }) + "; spare %.0f%%".format(100 * v(Tally.bandsHerdKept(h, Tally.Bands.SPARE)) / 1000 / bw.coerceAtLeast(1.0))
            })
        val wanted = Tally.ClothesMade.entries.sumOf { v(Tally.clothesMade(it)) }
        println("bands' clothes making, last year (band-weeks clothes were wanted: %.0f%% of band-weeks; by what stopped them): ".format(100 * wanted / weeks) +
            Tally.ClothesMade.entries.joinToString(", ") { c -> "%s %.0f%%".format(c.label, 100 * v(Tally.clothesMade(c)) / wanted.coerceAtLeast(1.0)) })
    }

    fun printIslands() {
        val comp = IntArray(hex.size) { -1 }; var nc = 0; val sizes = ArrayList<Int>()
        for (h0 in hex.all) {
            if (hex.heart[h0] < 0 || comp[h0] >= 0) continue
            val stack = ArrayDeque(listOf(h0)); comp[h0] = nc; var c = 0
            while (stack.isNotEmpty()) {
                val h = stack.removeLast()
                c++
                for (d in 0 until 6) {
                    val b = hex.neighbour(h, d)
                    if (!b.none && hex.heart[b] >= 0 && comp[b] < 0) {
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
        println("islands (%d of 3 hexes or more; hexes / packs and bands / resident kinds above 0.01 a km²): ".format(islands.size) +
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

    /** Where each kind lives at the end: share on low ground (and in unsplit hexes) and on high ground. */
    fun printHeights() {
        println("share of each kind on low ground (and in unsplit hexes), and on high ground:")
        for ((i, s) in fauna.kinds.withIndex()) {
            val byBand = DoubleArray(HexWorld.BANDS)
            for (p in 0 until fauna.n) byBand[hw.placeBand[p]] += residents(i, p)
            val tot = byBand.sum().coerceAtLeast(1.0)
            println("  %-12s ".format(s.name) + byBand.joinToString(" ") { "%3.0f%%".format(100 * it / tot) })
        }
    }
}
