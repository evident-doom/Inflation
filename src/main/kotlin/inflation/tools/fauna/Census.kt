package inflation.tools.fauna

import inflation.ecology.set
import inflation.ecology.get
import inflation.ecology.PlantFrom
import inflation.world.Hex
import inflation.StockName
import inflation.Stocks
import inflation.ecology.Activity
import inflation.ecology.Catalogue
import inflation.ecology.Met
import inflation.ecology.Lifeway
import inflation.ecology.CustomsPart
import inflation.ecology.RiteWalk
import inflation.ecology.ritesApart
import inflation.ecology.livelihood
import inflation.ecology.homesApart
import inflation.ecology.Regard
import inflation.ecology.FRIEND_TIE
import inflation.ecology.Mind
import inflation.ecology.PlanTier
import inflation.ecology.ForeseenBin
import inflation.ecology.LarderMove
import inflation.world.Season
import inflation.ecology.DeathCause
import inflation.ecology.Tally
import inflation.ecology.VillageFlow
import inflation.ecology.HexGeography
import inflation.ecology.Fed
import inflation.ecology.HexFauna
import inflation.ecology.TallyKey
import inflation.ecology.MoveTerm
import inflation.ecology.MOVE_TRACE_WEEKS
import inflation.ecology.MOVE_TRACE_MOVES
import inflation.ecology.MOVE_TRACE_SUMS
import inflation.ecology.MOVE_TRACE_LEADS
import inflation.world.get
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.ecology.Built
import inflation.ecology.Tool
import inflation.ecology.wellReachM
import inflation.ecology.Species
import inflation.ecology.Store
import inflation.ecology.Stuck
import inflation.world.Biome
import inflation.world.Date
import inflation.world.HexWorld
import kotlin.math.abs
import java.util.Random
import inflation.world.set
import inflation.world.Place
import inflation.ecology.seedCornIn
import inflation.ecology.bandLiving
import inflation.ecology.birthSpacing
import kotlin.math.max
import kotlin.math.sqrt
import inflation.ecology.GATHER_KM
import inflation.ecology.Language
import inflation.ecology.Tradition
import inflation.ecology.BurialWay

/**
 * The model as it stands, over the whole map: each method prints one or a few lines of a fauna check's
 * report. Some read and clear a ledger the model keeps for checks ([printCoastAndInland] the residents'
 * food sources, [printChoices] the groups' plan counts), so each is called once a year.
 */
class Census(private val sim: HexSim) {
    init { sim.stats.bandSplits = true }

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
            val ff = fauna.fedFrom[k]; val h = ff[Fed.HEADS].sum().coerceAtLeast(1e-9)
            "%s %.2f/%.2f/%.2f".format(fauna.kinds[k].name, ff[Fed.PLANTS].sum() / h, ff[Fed.ANIMAL].sum() / h, ff[Fed.RESERVES].sum() / h)
        })
        println("     residents' plants by food (forage / fruit / roots and fallen mast, shares of need per animal this year): " +
            fauna.kinds.indices.joinToString(", ") { k ->
            val ff = fauna.fedFrom[k]; val h = ff[Fed.HEADS].sum().coerceAtLeast(1e-9)
            val fruit = ff[Fed.FRUIT].sum() / h; val under = ff[Fed.UNDERGROUND].sum() / h
            "%s %.2f/%.2f/%.2f".format(fauna.kinds[k].name, ff[Fed.PLANTS].sum() / h - fruit - under, fruit, under)
        })
        for (ff in fauna.fedFrom) for (a in ff) a.reset()
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
        val banded = groups.kinds.filter { groups.planner.exploreCounts[it.index * 4] > 0 }
        if (banded.isNotEmpty()) println("     bands' moves, share of band-weeks (its best choice would move / moved / moved only by its will to explore's draw): " +
            banded.joinToString(", ") { s ->
                val c = IntArray(4) { groups.planner.exploreCounts.getAndSet(s.index * 4 + it, 0) }
                val all = c[0].coerceAtLeast(1)
                "%s %.1f/%.1f/%.1f%%".format(s.name, 100.0 * c[1] / all, 100.0 * c[2] / all, 100.0 * c[3] / all)
            })
        // (moveTrace=true only: [GroupPlanner.bookMove].)
        val mc = groups.planner.moveCounts
        if (mc[MOVE_TRACE_WEEKS] > 0) {
            val m = LongArray(mc.length()) { mc.getAndSet(it, 0) }
            val moves = m[MOVE_TRACE_MOVES].coerceAtLeast(1).toDouble()
            println("     bands' moves by the hand-written score: %.1f%% of band-weeks; on them, each part of the score where it went less staying's, mean (the moves it did most for): ".format(
                100.0 * m[MOVE_TRACE_MOVES] / m[MOVE_TRACE_WEEKS]) + MoveTerm.entries.joinToString(", ") { t ->
                "%s %+.3f (%.0f%%)".format(t.label, m[MOVE_TRACE_SUMS + t.ordinal] / 1e4 / moves, 100.0 * m[MOVE_TRACE_LEADS + t.ordinal] / moves) })
        }
    }

    /** Kills robbed over the year, from [metYear] (the year's meeting tallies summed over its weeks): by and from each hunting kind, kills and kg. Report only. */
    fun printKillsStolen(metYear: Map<TallyKey, Int>) {
        fun met(m: Met.Of, s: Species) = metYear[m.tally(s.name)] ?: 0
        println("     kills stolen over the year (by: kills, kg / from: kills, kg): " + groups.kinds.filter { it.carnivore && !it.human }.joinToString(", ") { s ->
            "%s %d, %d / %d, %d".format(s.name, met(Met.Of.KILLS_STOLEN_BY, s), met(Met.Of.KG_STOLEN_BY, s), met(Met.Of.KILLS_STOLEN_FROM, s), met(Met.Of.KG_STOLEN_FROM, s))
        })
    }

    /**
     * The hunters' families: packs, how many hold ground and could breed, lone animals, adults and young; and from [metYear]
     * (the year's meeting tallies summed over its weeks) how their packs came and went that year. Report only.
     */
    fun printHuntersFamilies(metYear: Map<TallyKey, Int>) {
        println("     hunters' families (packs / holding ground / able to breed / alone / adults / young): " +
            groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gs = groups.groups.filter { it.alive && it.species == s }
            "%s %d / %d / %d / %d / %d / %d".format(s.name, gs.size, gs.count { !it.home.none }, gs.count { !it.home.none && it.adults >= 2 },
                gs.count { it.adults == 1 }, gs.sumOf { it.adults }, gs.sumOf { it.young })
        })
        fun met(m: Met.Of, s: Species) = metYear[m.tally(s.name)] ?: 0
        println("     packs' year (young left alone / split off / lone pairs / settled ground / ended / of them without ground): " +
            groups.kinds.filter { it.carnivore && it.lifeway == Lifeway.PACK }.joinToString(", ") { s ->
            "%s %d / %d / %d / %d / %d / %d".format(s.name, met(Met.Of.PACK_LEFT_ALONE, s), met(Met.Of.PACK_SPLIT_OFF, s), met(Met.Of.PACK_PAIRED, s),
                met(Met.Of.PACK_SETTLED, s), met(Met.Of.PACK_ENDED, s), met(Met.Of.PACK_ENDED_HOMELESS, s))
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
        println("bands by kind of country, last year (births / deaths per person a year, foreseen deaths a year ahead as the weeks went (of them of hunger / of exposure), mean perseverance; main causes): " + cs.joinToString(", ") { c ->
            val years = (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() / stepsPerYear
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsDied(c)) }.mapKeys { Tally.diedOf(it.key) }
            val foreseen = (lastYear[Tally.bandsIn(c, Tally.Bands.FORESEEN)] ?: 0) / 1000.0 / (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            val sisu = (lastYear[Tally.bandsIn(c, Tally.Bands.SISU)] ?: 0) / 1000.0 / (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            fun per(k: Tally.Bands) = (lastYear[Tally.bandsIn(c, k)] ?: 0) / 1000.0 / (lastYear[Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            "%s %.3f / %.3f, foreseen %.3f (%.3f / %.3f), perseverance %.2f (%s)".format(c.label, (lastYear[Tally.bandsIn(c, Tally.Bands.BORN)] ?: 0) / years.coerceAtLeast(1e-9), died.values.sum() / years.coerceAtLeast(1e-9), foreseen,
                per(Tally.Bands.FORESEEN_HUNGER), per(Tally.Bands.FORESEEN_EXPOSURE), sisu,
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
        println("bands by mind, last year (nets: today's choosing; plans: the planning kind, t.planners; share of people-weeks and of band-weeks; people a band; share of band-weeks at a new camp; births / deaths per 1000 people a year; deaths per 1000 a year by cause, largest first): " + Mind.entries.joinToString(", ") { m ->
            val pw = (lastYear[Tally.bandsMind(m, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble()
            val all = Mind.entries.sumOf { (lastYear[Tally.bandsMind(it, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() }.coerceAtLeast(1.0)
            val bw = (lastYear[Tally.bandsMind(m, Tally.Bands.BAND_WEEKS)] ?: 0).toDouble().coerceAtLeast(1.0)
            val years = (pw / stepsPerYear).coerceAtLeast(1e-9)
            val died = lastYear.filterKeys { it.startsWith(Tally.bandsMindDied(m)) }.mapKeys { Tally.diedOf(it.key) }
            val bwAll = Mind.entries.sumOf { (lastYear[Tally.bandsMind(it, Tally.Bands.BAND_WEEKS)] ?: 0).toDouble() }.coerceAtLeast(1.0)
            "%s %.0f%% (of band-weeks %.0f%%), %.1f a band, moved %.0f%%: %.1f / %.1f (%s)".format(m.label, 100 * pw / all, 100 * (lastYear[Tally.bandsMind(m, Tally.Bands.BAND_WEEKS)] ?: 0) / bwAll,
                pw / bw, 100 * (lastYear[Tally.bandsMind(m, Tally.Bands.MOVES)] ?: 0) / bw,
                1000 * (lastYear[Tally.bandsMind(m, Tally.Bands.BORN)] ?: 0) / years, 1000 * died.values.sum() / years,
                died.entries.sortedByDescending { it.value }.joinToString(" ") { "%s %.1f".format(it.key, 1000 * it.value / years) }) })
        // How bands' forecasts of the year ahead held: in each band of foreseen deaths, the share of people-weeks, the deaths
        // foreseen, and those that came in the year after (all, and of hunger), per person ([bookCalibration]).
        println("bands' forecasts against what came, last year (by mind, then by the share foreseen dead a year ahead: share of people-weeks; foreseen dead / died in the year after / of hunger, per person): " + Mind.entries.joinToString("; ") { m ->
            fun v(b: ForeseenBin, k: Tally.Calib) = (lastYear[Tally.calibration(m, b, k)] ?: 0).toDouble()
            val all = ForeseenBin.entries.sumOf { v(it, Tally.Calib.PEOPLE_WEEKS) }.coerceAtLeast(1.0)
            m.label + ": " + ForeseenBin.entries.joinToString(", ") { b ->
                val pw = v(b, Tally.Calib.PEOPLE_WEEKS).coerceAtLeast(1.0)
                "%s %.0f%%: %.3f / %.3f / %.3f".format(b.label, 100 * v(b, Tally.Calib.PEOPLE_WEEKS) / all, v(b, Tally.Calib.FORESEEN) / 1000 / pw, v(b, Tally.Calib.DIED) / 1000 / pw, v(b, Tally.Calib.HUNGER) / 1000 / pw)
            }
        })
        // Where bands' stores went: what moved their larders, days a person a year by season, and their hunger deaths by what the
        // larder held then and whether they had moved camp in the month before ([Band.larderFlow], [Tally.starvedAt]).
        println("bands' larders, last year (by mind: what moved food in or out, days of a person's need a person a year, the year's net and spring/summer/autumn/winter; hunger deaths per 1000 a year and their share by the larder then, and after a move in the month before, against the share of people-weeks within a month of one): " + Mind.entries.joinToString("; ") { m ->
            val years = ((lastYear[Tally.bandsMind(m, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() / stepsPerYear).coerceAtLeast(1e-9)
            fun f(why: LarderMove, season: Season) = (lastYear[Tally.larderFlow(m, season, why)] ?: 0).toDouble() / years
            val starved = Tally.LarderAt.entries.sumOf { (lastYear[Tally.starvedAt(m, it)] ?: 0).toDouble() }
            m.label + ": " + LarderMove.entries.joinToString(", ") { why ->
                "%s %.1f (%s)".format(why.label, Season.entries.sumOf { f(why, it) }, Season.entries.joinToString("/") { "%.1f".format(f(why, it)) })
            } + "; hunger %.1f: ".format(1000 * starved / years) +
                Tally.LarderAt.entries.joinToString(", ") { "%s %.0f%%".format(it.label, 100 * (lastYear[Tally.starvedAt(m, it)] ?: 0) / starved.coerceAtLeast(1.0)) } +
                ", after a move %.0f%% (of people-weeks %.0f%%)".format(100 * (lastYear[Tally.starvedMoved(m)] ?: 0) / starved.coerceAtLeast(1.0),
                    100 * (lastYear[Tally.movedWeeks(m)] ?: 0) / (years * stepsPerYear).coerceAtLeast(1.0))
        })
        run {
            // A planning band's days beyond its essentials, by tier ([planDays]).
            val pw = (lastYear[Tally.bandsMind(Mind.PLANS, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble()
            if (pw > 0.0) {
                fun v(t: PlanTier) = (lastYear[Tally.planTier(t)] ?: 0).toDouble()
                val all = listOf(PlanTier.OBLIGED, PlanTier.SCORED, PlanTier.UNRECKONED, PlanTier.LEISURE).sumOf { v(it) }.coerceAtLeast(1.0)
                println("planning bands' days beyond their essentials, last year (share of those days by tier; unreckoned asks not given, as a share of those days; lives the days held by lives were reckoned to save, per 1000 people a year; the stores' and houses' days held as a share of their asks, and their asks as a share of those days): " +
                    listOf(PlanTier.OBLIGED, PlanTier.SCORED, PlanTier.UNRECKONED, PlanTier.LEISURE).joinToString(", ") { "%s %.0f%%".format(it.label, 100 * v(it) / all) } +
                    "; %s %.0f%%; %.1f".format(PlanTier.ASKED_BEYOND.label, 100 * v(PlanTier.ASKED_BEYOND) / all, 1000 * v(PlanTier.LIVES) / 1e4 / (pw / stepsPerYear).coerceAtLeast(1e-9)) +
                    "; stores held %.0f%% of asked (asked %.0f%% of those days), houses %.0f%% (%.0f%%)".format(
                        100 * v(PlanTier.STORES_HELD) / v(PlanTier.STORES_ASKED).coerceAtLeast(1.0), 100 * v(PlanTier.STORES_ASKED) / all,
                        100 * v(PlanTier.HOUSES_HELD) / v(PlanTier.HOUSES_ASKED).coerceAtLeast(1.0), 100 * v(PlanTier.HOUSES_ASKED) / all))
            }
        }
        run {
            // Villages: hexes where two or more bands live settled, or one band at home larger than a camp ([villages]).
            val f = groups.bands.villageFlows
            val weeks = f[VillageFlow.WEEKS].toDouble().coerceAtLeast(1.0); val vw = f[VillageFlow.VILLAGE_WEEKS].toDouble()
            val now = groups.bands.villageAge
            println("villages since the census last read them (two or more bands living settled in one hex, or one band at home larger than a camp; nothing chooses by it): standing now %d, the oldest %d weeks; in a week %.1f villages, %.1f bands and %.0f people a village, most bands in one %.0f; people in villages %.0f%% of those settled, %.0f%% of all, %.0f%% of them at home in houses (the rest camped half a year or more); with a palisade standing %.0f%% of village-weeks; %.0f ended, after %.1f weeks on average".format(
                now.size, now.values.maxOrNull() ?: 0, vw / weeks, f[VillageFlow.BANDS] / vw.coerceAtLeast(1.0), f[VillageFlow.PEOPLE] / vw.coerceAtLeast(1.0), f[VillageFlow.MOST_BANDS].toDouble(),
                100 * f[VillageFlow.PEOPLE] / f[VillageFlow.SETTLED_PEOPLE].toDouble().coerceAtLeast(1.0), 100 * f[VillageFlow.PEOPLE] / f[VillageFlow.ALL_PEOPLE].toDouble().coerceAtLeast(1.0), 100 * f[VillageFlow.PEOPLE_AT_HOME] / f[VillageFlow.PEOPLE].toDouble().coerceAtLeast(1.0),
                100 * f[VillageFlow.PALISADED] / vw.coerceAtLeast(1.0), f[VillageFlow.ENDED].toDouble(), f[VillageFlow.ENDED_WEEKS] / f[VillageFlow.ENDED].toDouble().coerceAtLeast(1.0)))
            f.fill(0f)
        }
        run {
            // Wells ([Built.WELL]), as they stand now, and the bands that know them and draw from them.
            val wells = groups.bands.built.filter { it.kind == Built.WELL }
            val whole = wells.filter { it.done }
            val wet = whole.count { s -> hw.placesOf(s.hex).any { land.drink.well[it] >= 0.5f } }
            val bs = groups.groups.filter { it.alive }.mapNotNull { g -> g.band?.let { g to it } }
            println("wells now (nothing chooses by it): %d whole, %d of them reaching water this week, %.1f m deep on average as they stand; %d begun; bands knowing wells %d of %d, with picks %d; bands drawing water from one this week %d, at home with water only from under the ground over the year %.0f%% of bands at home".format(
                whole.size, wet, whole.map { wellReachM(it).toDouble() }.average().takeIf { it.isFinite() } ?: 0.0, wells.size - whole.size,
                bs.count { it.second.knowsSkill[Skill.WELLS] }, bs.size, bs.count { it.second.knows[Tool.PICK] },
                bs.count { land.drink.drawsFromWell(it.first.place, it.first.species.dailyTravelKm) },
                100.0 * bs.filter { it.second.atHome }.let { h -> h.sumOf { it.second.waterShort.toDouble() } / h.size.coerceAtLeast(1) }))
        }
        println("bands by whether they keep a herd and how much of their hands' days it asks, last year (share of people-weeks; people a band; clad, mobile; children a person; fed; air where they stood, °C; births per 1000 people a year; grown deaths per 1000 grown a year, children's per 1000 children, by cause, largest first): " + Tally.Herding.entries.joinToString(", ") { h ->
            fun v(k: Tally.Bands) = (lastYear[Tally.bandsHerding(h, k)] ?: 0).toDouble()
            val pw = v(Tally.Bands.PEOPLE_WEEKS); val cw = v(Tally.Bands.CHILD_WEEKS); val bw = v(Tally.Bands.BAND_WEEKS).coerceAtLeast(1.0)
            val all = Tally.Herding.entries.sumOf { (lastYear[Tally.bandsHerding(it, Tally.Bands.PEOPLE_WEEKS)] ?: 0).toDouble() }.coerceAtLeast(1.0)
            val grownYears = ((pw - cw) / stepsPerYear).coerceAtLeast(1e-9); val childYears = (cw / stepsPerYear).coerceAtLeast(1e-9)
            fun died(young: Boolean) = lastYear.filterKeys { it.startsWith(Tally.bandsHerdingDied(h, young)) }.mapKeys { Tally.diedOf(it.key) }
            fun causes(young: Boolean, years: Double) = died(young).entries.sortedByDescending { it.value }.joinToString(" ") { "%s %.1f".format(it.key, 1000 * it.value / years) }
            "%s %.0f%%, %.1f a band; %.0f%%, %.0f%%; %.2f; %.2f; %.1f; %.1f; grown %.1f (%s); children %.1f (%s)".format(h.label, 100 * pw / all, pw / bw,
                100 * v(Tally.Bands.CLAD_WEEKS) / pw.coerceAtLeast(1.0), 100 * v(Tally.Bands.MOBILE_WEEKS) / bw, cw / pw.coerceAtLeast(1.0),
                v(Tally.Bands.FED) / 1000 / pw.coerceAtLeast(1.0),
                v(Tally.Bands.AIR) / 10 / pw.coerceAtLeast(1.0), 1000 * v(Tally.Bands.BORN) / (pw / stepsPerYear).coerceAtLeast(1e-9),
                1000 * died(false).values.sum() / grownYears, causes(false, grownYears), 1000 * died(true).values.sum() / childYears, causes(true, childYears)) })
        println("bands by whether they keep a herd and how much of their hands' days it asks, last year (foraged of food wanted; hearth fire, share of days fetching firewood; share of their days on each work and lost to cold and hunger; in weeks of heat debt, share of band-weeks: worst debt Wh/m², night air °C, clothes clo, fire, shelter or house, share at a new camp, share of days spare): " + Tally.Herding.entries.joinToString(" | ") { h ->
            fun v(k: Tally.Bands) = (lastYear[Tally.bandsHerding(h, k)] ?: 0).toDouble()
            fun c(k: Tally.Cold) = (lastYear[Tally.bandsHerding(h, k)] ?: 0).toDouble()
            fun t(a: Activity) = (lastYear[Tally.bandsHerdingTime(h, a)] ?: 0).toDouble()
            val bw = v(Tally.Bands.BAND_WEEKS).coerceAtLeast(1.0); val n = c(Tally.Cold.WEEKS).coerceAtLeast(1.0)
            val days = Activity.entries.sumOf { t(it) }.coerceAtLeast(1.0)
            "%s: %.0f%%; %.0f%%, %.0f%%; %s, lost %.0f%%; heat debt %.0f%%: %.0f, %.1f, %.1f, %.2f, %.2f, %.0f%%, %.0f%%".format(h.label,
                100 * (v(Tally.Bands.MEAT) + v(Tally.Bands.PLANTS)) / v(Tally.Bands.ASKED).coerceAtLeast(1.0),
                v(Tally.Bands.HEARTH) / bw, v(Tally.Bands.FUEL_DAYS) / bw,
                Activity.entries.filter { t(it) > 0.0 }.joinToString(" ") { "%s %.0f%%".format(it.label, 100 * t(it) / days) },
                100 * (lastYear[Tally.bandsHerdingTime(h, Tally.TimeTotal.LOST)] ?: 0) / days,
                100 * c(Tally.Cold.WEEKS) / 10 / bw, c(Tally.Cold.SHORT) / n, c(Tally.Cold.AIR) / n, c(Tally.Cold.CLOTHES) / n,
                c(Tally.Cold.FIRE) / n, c(Tally.Cold.SHELTER) / n, 100 * c(Tally.Cold.MOVED) / n, 100 * c(Tally.Cold.SPARE) / n) })
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
            // (A fat kill is worth more than a lean one: when in the year bands kill, and how fat what they bring in is.)
            println("bands' kills of big game by month, last year (kills; fat in the meat brought in, by weight): " + (0 until Date.MONTHS_PER_YEAR).joinToString(", ") { m ->
                val lean = v(Tally.bandKills(m, Tally.KillPart.LEAN_KG)); val fat = v(Tally.bandKills(m, Tally.KillPart.FAT_KG))
                "%d: %.0f; %.1f%%".format(m + 1, v(Tally.bandKills(m, Tally.KillPart.KILLS)), 100 * fat / (lean + fat).coerceAtLeast(1.0)) })
            println("bands' kills of big game by prey kind, last year (kills; meat brought in, kg; fat in it, by weight): " + fauna.kinds
                .map { s -> s.name to Tally.KillPart.entries.map { v(Tally.bandKillsOf(s.name, it)) } }
                .filter { it.second[0] > 0.0 }.sortedByDescending { it.second[1] + it.second[2] }
                .joinToString(", ") { (name, n) -> "%s %.0f; %.0f; %.1f%%".format(name, n[0], n[1] + n[2], 100 * n[2] / (n[1] + n[2]).coerceAtLeast(1.0)) })
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
        println("warmth's days the survival pass asked first, last year (share of all the days each asked): " +
            listOf(Activity.FIREWOOD, Activity.SHELTER).joinToString(", ") { a ->
                "%s %.1f%%".format(a.label, 100 * v(Tally.squeeze(a, Tally.Squeeze.FIRST_DAYS)) / v(Tally.squeeze(a, Tally.Squeeze.ASKED_DAYS)).coerceAtLeast(1.0))
            })
        println("bands by spare days over the last season and how they were kept warm at the week's end, last year (share of the spare bin's people-weeks; mean air °C; share of band-weeks at a new camp; exposure, hunger and all deaths per 1000 people a year): " +
            Tally.Spare.entries.joinToString("; ") { sp ->
                val all = Tally.Warmed.entries.sumOf { v(Tally.bandsWarmed(sp, it, Tally.Bands.PEOPLE_WEEKS)) }
                sp.label + ": " + Tally.Warmed.entries.joinToString(", ") { w ->
                    val pw = v(Tally.bandsWarmed(sp, w, Tally.Bands.PEOPLE_WEEKS)); val bw = v(Tally.bandsWarmed(sp, w, Tally.Bands.BAND_WEEKS)).coerceAtLeast(1.0)
                    val years = (pw / stepsPerYear).coerceAtLeast(1e-9)
                    val died = lastYear.filterKeys { it.startsWith(Tally.bandsWarmedDied(sp, w)) }
                    fun of(c: DeathCause) = died[Tally.bandsWarmedDied(sp, w, c)] ?: 0
                    "%s %.0f%% %.1f °C new %.0f%% exp %.1f hun %.1f all %.1f".format(w.label, 100 * pw / all.coerceAtLeast(1.0),
                        v(Tally.bandsWarmed(sp, w, Tally.Bands.CAMP_AIR)) / 10 / bw, 100 * v(Tally.bandsWarmed(sp, w, Tally.Bands.MOVED)) / bw,
                        1000 * of(DeathCause.EXPOSURE) / years, 1000 * of(DeathCause.HUNGER) / years, 1000 * died.values.sum() / years)
                }
            })
        println("bands' survival pass against their week, by spare days and how they were kept warm, last year (fire planned / got / lit, %; exposure odds planned / got, 1/10000; days left after the walk, %; shelter asked in % of band-weeks, held first % of its ask): " +
            Tally.Spare.entries.joinToString("; ") { sp ->
                sp.label + ": " + Tally.Warmed.entries.filter { v(Tally.bandsWarmed(sp, it, Tally.Bands.BAND_WEEKS)) > 0 }.joinToString(", ") { w ->
                    fun m(what: Tally.Bands, per: Double) = v(Tally.bandsWarmed(sp, w, what)) / per.coerceAtLeast(1.0)
                    val bw = v(Tally.bandsWarmed(sp, w, Tally.Bands.BAND_WEEKS)); val asked = v(Tally.bandsWarmed(sp, w, Tally.Bands.SHELTER_ASKED))
                    "%s fire %.0f/%.0f/%.0f odds %.1f/%.1f left %.0f shelter %.0f%% first %.0f%%".format(w.label,
                        m(Tally.Bands.PLAN_FIRE, bw), m(Tally.Bands.FIRE_GOT, bw), m(Tally.Bands.LIT, bw), m(Tally.Bands.PLAN_ODDS, bw), m(Tally.Bands.ODDS, bw),
                        m(Tally.Bands.LEFT, bw), 100 * asked / bw.coerceAtLeast(1.0), m(Tally.Bands.SHELTER_FIRST, asked))
                }
            })
        println("bands by how far their shelters stood at the week's end, last year (share of people-weeks; mean air °C; share of band-weeks at a new camp; fed %%; keeping warm adds %%; survival pass aimed at; exposure, hunger and all deaths per 1000 people a year): " +
            Tally.Hut.entries.joinToString(", ") { h ->
                val all = Tally.Hut.entries.sumOf { v(Tally.bandsHut(it, Tally.Bands.PEOPLE_WEEKS)) }
                val pw = v(Tally.bandsHut(h, Tally.Bands.PEOPLE_WEEKS)); val bw = v(Tally.bandsHut(h, Tally.Bands.BAND_WEEKS)).coerceAtLeast(1.0)
                val years = (pw / stepsPerYear).coerceAtLeast(1e-9)
                val died = lastYear.filterKeys { it.startsWith(Tally.bandsHutDied(h)) }
                fun of(c: DeathCause) = died[Tally.bandsHutDied(h, c)] ?: 0
                "%s %.0f%% %.1f °C new %.0f%% fed %.0f%% warm +%.1f%% aim %.2f exp %.1f hun %.1f all %.1f".format(h.label, 100 * pw / all.coerceAtLeast(1.0),
                    v(Tally.bandsHut(h, Tally.Bands.CAMP_AIR)) / 10 / bw, 100 * v(Tally.bandsHut(h, Tally.Bands.MOVED)) / bw,
                    100 * v(Tally.bandsHut(h, Tally.Bands.FED)) / 1000 / pw.coerceAtLeast(1.0), 100 * v(Tally.bandsHut(h, Tally.Bands.WARM_EXTRA)) / 1000 / bw,
                    v(Tally.bandsHut(h, Tally.Bands.AIM)) / 100 / bw,
                    1000 * of(DeathCause.EXPOSURE) / years, 1000 * of(DeathCause.HUNGER) / years, 1000 * died.values.sum() / years)
            })
        // (thatchTrace=true only: [bookThatchTrace].)
        if (Tally.Hut.entries.any { v(Tally.thatchTrace(it, Tally.Thatch.WEEKS)) > 0 })
            println("bands' survival pass short of a thatched hut, by how far its shelters reached, last year (band-weeks; what thatching on would have done by the pass's own reckoning, its fire as planned: " +
                "deaths averted per 1000 band-weeks this week of exposure / hunger / through the year after, a negative brings deaths; person-days it would take / the pass left, a band-week; " +
                "weeks expected to stay on; share of band-weeks it would avert deaths and fit, avert them but not fit, avert none): " +
                Tally.Hut.entries.filter { v(Tally.thatchTrace(it, Tally.Thatch.WEEKS)) > 0 }.joinToString(", ") { h ->
                    fun t(k: Tally.Thatch) = v(Tally.thatchTrace(h, k))
                    val bw = t(Tally.Thatch.WEEKS)
                    "%s %.0f exp %.2f hun %.2f year %.2f days %.1f/%.1f stay %.1f worth %.0f%% short %.0f%% not %.0f%%".format(h.label, bw,
                        1000 * t(Tally.Thatch.EXPOSURE) / 1e4 / bw, 1000 * t(Tally.Thatch.HUNGER) / 1e4 / bw, 1000 * t(Tally.Thatch.YEAR) / 1e4 / bw,
                        t(Tally.Thatch.DAYS) / 10 / bw, t(Tally.Thatch.LEFT) / 10 / bw, t(Tally.Thatch.STAY) / 10 / bw,
                        100 * t(Tally.Thatch.WORTH_FITS) / bw, 100 * t(Tally.Thatch.WORTH_SHORT) / bw, 100 * t(Tally.Thatch.NOT_WORTH) / bw)
                })
        fun ft(k: Tally.FoodTime) = v(Tally.foodTime(k))
        val ftWeeks = ft(Tally.FoodTime.WEEKS).coerceAtLeast(1.0)
        println(("bands' foraging against the days its week's plan left it, last year (people-days a band-week): left %.1f, spent %.1f, past what was left %.2f; " +
            "weeks more than 5%% past %.1f%%; kills beyond those expected took %.2f, kills short of them saved %.2f, extra hauling %.2f, water (digging soaks, drawing from wells, carrying it home) %.2f").format(
            ft(Tally.FoodTime.ROOM) / 10 / ftWeeks, ft(Tally.FoodTime.SPENT) / 10 / ftWeeks, ft(Tally.FoodTime.OVER) / 10 / ftWeeks, 100 * ft(Tally.FoodTime.OVER_WEEKS) / ftWeeks,
            ft(Tally.FoodTime.KILLS_OVER) / 10 / ftWeeks, ft(Tally.FoodTime.KILLS_UNDER) / 10 / ftWeeks, ft(Tally.FoodTime.HAUL) / 10 / ftWeeks, ft(Tally.FoodTime.DIG) / 10 / ftWeeks))
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

    /**
     * The identity census, three to six lines: whether bands form regions of one tongue and one set of customs, how alike bands
     * are by distance apart, and how this year's meetings went by how alike and how wary of each other the bands were
     * ([thisYear], the hunting ledger's year so far: see [Tally.meetingsBy]). Bands within [GATHER_KM] of each other are neighbours; neighbours that understand
     * each other at [REGION_UNDERSTOOD] or better and hold at most [REGION_CUSTOMS_APART] of their traditions differently are
     * of one region, and so are the neighbours of those (single linkage). A band bordering another region has a neighbour in
     * it. Neighbour pairs are counted again by how well they understand each other and how wary they are (against the year's
     * meetings, below); and each band's known bands (its ties), how many of another region, and how wide the country it knows. The rites' year, from [metYear] (the year's culture tallies summed over its weeks), counts every rite's month
     * once, held or cut short, and again by how far the band was from the site when it was (see [RiteWalk]); the raids' year counts
     * every raid and what came of it; the bands' year every split and every joining. The lines are the report's: nothing in the model reads them.
     */
    fun printIdentity(thisYear: Map<TallyKey, Int>, metYear: Map<TallyKey, Int>) {
        val bands = groups.groups.mapNotNull { g -> g.band?.takeIf { g.alive }?.let { g to it } }
        val n = bands.size
        if (n < 2) return
        val at = Array(n) { hw.placeHex[bands[it].first.place] }
        val parent = IntArray(n) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        // (Every pair once: by distance class for the means, and the neighbours for the regions.)
        val pairs = LongArray(IDENTITY_KM.size + 1); val understood = DoubleArray(pairs.size); val apart = DoubleArray(pairs.size)
        val sameLineage = LongArray(pairs.size); val sameTotem = LongArray(pairs.size); val sameRite = LongArray(pairs.size)
        val sameLiving = LongArray(pairs.size); val oneGround = LongArray(pairs.size)
        val living = bands.map { it.second.livelihood() }
        val nearA = ArrayList<Int>(); val nearB = ArrayList<Int>()
        // (Neighbours by how well they understand each other and how wary the warier is, as their regard stands now: whether
        // unlike bands live within reach of each other at all, against whom the year's meetings were with.)
        val nearU = LongArray(Tally.Understood.entries.size); val nearW = LongArray(Tally.Wary.entries.size)
        for (i in 0 until n) {
            val bi = bands[i].second; val li = bi.mainLineage()
            for (j in i + 1 until n) {
                val bj = bands[j].second
                val km = hex.distanceKm(at[i], at[j])
                val u = Language.understanding(bi.words, bj.words)
                // (Customs as the bands reckon them, the totem one of them: see [inflation.ecology.Regard.customsApart].)
                val off = Tradition.entries.count { bi.holds(it) != bj.holds(it) } + (if (bi.totem != bj.totem) 1 else 0) +
                    (if (bi.holds(Tradition.BURIAL) && bj.holds(Tradition.BURIAL) && bi.burialWay != bj.burialWay) 1 else 0) +
                    (if (ritesApart(bi, bj)) 1 else 0) + (if (living[i] != living[j]) 1 else 0) + (if (homesApart(bi, bj, hex)) 1 else 0)
                var c = 0
                while (c < IDENTITY_KM.size && km >= IDENTITY_KM[c]) c++
                pairs[c]++; understood[c] += u.toDouble(); apart[c] += off.toDouble()
                if (li == bj.mainLineage()) sameLineage[c]++
                if (!bi.totem.none && bi.totem == bj.totem) sameTotem[c]++
                if (bi.rite.any { it >= 0 && it in bj.rite }) sameRite[c]++
                if (living[i] == living[j]) sameLiving[c]++
                if (!bi.houseHex.none && !bj.houseHex.none && !homesApart(bi, bj, hex)) oneGround[c]++
                if (km <= GATHER_KM) {
                    nearA += i; nearB += j
                    nearU[Tally.Understood.of(u).ordinal]++
                    val w = maxOf(Regard(bi, bands[j].first.id, bj, u, hex).wariness, Regard(bj, bands[i].first.id, bi, u, hex).wariness)
                    nearW[Tally.Wary.of(w).ordinal]++
                    if (u >= REGION_UNDERSTOOD && off <= REGION_CUSTOMS_APART) { val ri = root(i); val rj = root(j); if (ri != rj) parent[ri] = rj }
                }
            }
        }
        val people = DoubleArray(n); val members = IntArray(n)
        for (i in 0 until n) { val r = root(i); people[r] += bands[i].first.size.toDouble(); members[r]++ }
        val border = BooleanArray(n)
        for (k in nearA.indices) if (root(nearA[k]) != root(nearB[k])) { border[nearA[k]] = true; border[nearB[k]] = true }
        val all = people.sum().coerceAtLeast(1.0)
        val regions = (0 until n).filter { members[it] >= 2 }.map { people[it] }.sorted()
        fun q(f: Double) = if (regions.isEmpty()) 0.0 else regions[((regions.size - 1) * f).toInt()]
        println("     identity: %d bands; %d regions of 2 bands or more hold %.0f%% of people (people a region: median %.0f, tenth %.0f, ninetieth %.0f; largest %.0f%% of people); %d bands alone; %.0f%% of bands border another region"
            .format(n, regions.size, 100 * regions.sum() / all, q(0.5), q(0.1), q(0.9), 100 * (regions.lastOrNull() ?: 0.0) / all,
                (0 until n).count { members[root(it)] < 2 }, 100.0 * border.count { it } / n))
        val classes = List(pairs.size) { c ->
            val lo = if (c == 0) 0.0 else IDENTITY_KM[c - 1]
            if (c < IDENTITY_KM.size) "%.0f-%.0f km".format(lo, IDENTITY_KM[c]) else "%.0f km and over".format(lo)
        }
        println("     identity by distance apart (pairs: understood / customs apart / same main lineage / same totem / a rite site in common / same livelihood / homes on one ground): " + pairs.indices.filter { pairs[it] > 0 }.joinToString(", ") { c ->
            "%s %d: %.2f / %.1f / %.0f%% / %.0f%% / %.0f%% / %.0f%% / %.0f%%".format(classes[c], pairs[c], understood[c] / pairs[c], apart[c] / pairs[c], 100.0 * sameLineage[c] / pairs[c],
                100.0 * sameTotem[c] / pairs[c], 100.0 * sameRite[c] / pairs[c],
                100.0 * sameLiving[c] / pairs[c], 100.0 * oneGround[c] / pairs[c]) })
        // (Whom each band knows (its ties, met and not yet forgotten) and how far it knows the country: how wide its world is.)
        val index = HashMap<Int, Int>(n * 2); for (i in 0 until n) index[bands[i].first.id] = i
        val knownBands = IntArray(n); val knownOther = IntArray(n); val friendsOther = IntArray(n); val friends = IntArray(n)
        for (i in 0 until n) for ((id, t) in bands[i].second.ties) {
            val j = index[id] ?: continue
            knownBands[i]++; if (root(j) != root(i)) knownOther[i]++
            if (t > FRIEND_TIE) { friends[i]++; if (root(j) != root(i)) friendsOther[i]++ }
        }
        val hexKm2 = sqrt(3.0) / 2 * hex.hexKm * hex.hexKm
        val reachKm = (0 until n).map { i -> sqrt(bands[i].second.known.sumOf { java.lang.Long.bitCount(it) } * hexKm2 / Math.PI) }.sorted()
        val knownSorted = knownBands.sorted()
        println("     neighbours within %.0f km (pairs, as they stand): by understanding %s; by wariness %s"
            .format(GATHER_KM, Tally.Understood.entries.joinToString(", ") { "%s %d".format(it.label, nearU[it.ordinal]) },
                Tally.Wary.entries.joinToString(", ") { "%s %d".format(it.label, nearW[it.ordinal]) }))
        println("     bands known a band (alive): median %d, ninetieth %d; %.0f%% of them of another region; friends %.1f a band, %.0f%% of them of another region; country known, as a circle: median %.0f km across, ninetieth %.0f km"
            .format(knownSorted[n / 2], knownSorted[(n * 9) / 10], 100.0 * knownOther.sum() / knownBands.sum().coerceAtLeast(1),
                friends.sum().toDouble() / n, 100.0 * friendsOther.sum() / friends.sum().coerceAtLeast(1), 2 * reachKm[n / 2], 2 * reachKm[(n * 9) / 10]))
        val kept = bands.mapNotNull { (_, b) -> b.totem.takeIf { !it.none }?.i }
        val bury = bands.filter { it.second.holds(Tradition.BURIAL) }
        println("     totems: %.0f%% of bands keep one, %d kinds kept, %.0f%% known lately to have eaten of theirs; burial: %.0f%% of bands bury their dead, %.0f%% of those carry them to their ancestors, %.1f person-days a band still owed to burials"
            .format(100.0 * kept.size / n, kept.toSet().size, 100.0 * bands.count { it.second.totemBroken > 0f } / n, 100.0 * bury.size / n,
                if (bury.isEmpty()) 0.0 else 100.0 * bury.count { it.second.burialWay == BurialWay.AT_ANCESTORS } / bury.size,
                if (bury.isEmpty()) 0.0 else bury.sumOf { it.second.burialOwed.toDouble() } / bury.size))
        val now = weather.date.year * 12 + weather.date.month
        val keeping = bands.filter { (_, b) -> b.rite.any { it >= 0 } }
        val bySite = HashMap<Int, Int>()
        for ((_, b) in bands) for (p in b.rite.toSet()) if (p >= 0) bySite.merge(p, 1, Int::plus)
        val shared = bySite.values.sorted()
        val holy = land.sanctity.sortedDescending()
        println("     rites: %.0f%% of bands keep rites (%.1f sites a band), %.0f%% of those held one to the end in the past year, %.1f person-days a band still owed to rites; %d sites, bands a site median %d, largest %d, %d sites kept by 5 bands or more; holiest places' sanctity %.0f / %.0f / %.0f, %d places of 100 or more"
            .format(100.0 * keeping.size / n, if (keeping.isEmpty()) 0.0 else keeping.sumOf { (_, b) -> b.rite.count { it >= 0 } }.toDouble() / keeping.size,
                if (keeping.isEmpty()) 0.0 else 100.0 * keeping.count { (_, b) -> b.riteKept >= 0 && now - b.riteKept < 12 } / keeping.size,
                bands.sumOf { it.second.riteOwed.toDouble() } / n.coerceAtLeast(1),
                shared.size, if (shared.isEmpty()) 0 else shared[shared.size / 2], shared.lastOrNull() ?: 0, shared.count { it >= 5 },
                holy.getOrElse(0) { 0f }, holy.getOrElse(9) { 0f }, holy.getOrElse(99) { 0f }, holy.count { it >= 100f }))
        val ages = keeping.map { it.second.beliefYears }.sorted()
        println("     beliefs: %.0f%% of bands keep none, %.0f%% remember an omen; belief kept a median of %.1f years (ninetieth %.1f); strain %.2f a band against %.2f borne"
            .format(100.0 * (n - keeping.size) / n.coerceAtLeast(1), 100.0 * bands.count { it.second.omenPlace >= 0 } / n.coerceAtLeast(1), ages.getOrElse(ages.size / 2) { 0f }, ages.getOrElse((ages.size * 9) / 10) { 0f },
                keeping.sumOf { it.second.strain.toDouble() } / keeping.size.coerceAtLeast(1), keeping.sumOf { it.second.strainBorne().toDouble() } / keeping.size.coerceAtLeast(1)))
        fun met(m: Met) = metYear[m.key] ?: 0
        val held = met(Met.BANDS_HELD_A_RITE); val cut = met(Met.BANDS_CUT_A_RITE_SHORT)
        println("     rites' year: %d held, %d cut short (%.0f%% held; %d of those held in a lesser form, %d moved to a month of plenty; %d meetings at rites held together, %d of them broken by a raid; %d bands with sickness kept away); omens marked: %d death tolls, %d plenty after want, %d strange signs; %d rites born of them; %d bands split over their belief"
            .format(held, cut, 100.0 * held / (held + cut).coerceAtLeast(1), met(Met.BANDS_HELD_A_RITE_IN_A_LESSER_FORM), met(Met.BANDS_MOVED_A_RITE_TO_A_BETTER_MONTH), met(Met.BANDS_GATHERED_AT_A_RITE), met(Met.RAIDS_ON_HOLY_GROUND), met(Met.SICK_BANDS_KEPT_FROM_A_RITE), met(Met.BANDS_MARKED_A_DEATH_TOLL), met(Met.BANDS_MARKED_PLENTY_AFTER_WANT),
                met(Met.BANDS_MARKED_A_STRANGE_SIGN), met(Met.BANDS_BORE_A_RITE), met(Met.BANDS_SPLIT_OVER_THEIR_BELIEF)))
        println("     raids' year: %d at meetings, %d on homes; %d raided bands slipped away, %d larders taken, %d bands driven from their homes; %d times neighbours stood with the raided"
            .format(met(Met.RAIDS), met(Met.RAIDS_ON_HOMES), met(Met.RAIDED_BANDS_SLIPPED_AWAY), met(Met.LARDERS_RAIDED), met(Met.BANDS_DRIVEN_FROM_HOME),
                met(Met.NEIGHBOURS_DEFENDED)))
        println("     bands' year: %d split, %d small bands joined kin, %d bands at home together became one"
            .format(met(Met.BANDS_SPLIT), met(Met.SMALL_BANDS_JOINED_KIN), met(Met.BANDS_BECAME_ONE)))
        fun of(m: Met.Of, w: RiteWalk) = metYear[m.tally(w.label)] ?: 0
        println("     rites by the band's distance from the site: " + RiteWalk.entries.joinToString("; ") { w ->
            val h = of(Met.Of.RITES_HELD, w); val c = of(Met.Of.RITES_CUT_SHORT, w)
            "%s %d held, %d cut short (%.0f%% held, %.0f%% of their days given)".format(w.label, h, c, 100.0 * h / (h + c).coerceAtLeast(1),
                of(Met.Of.RITES_CUT_SHORT_PERCENT_GIVEN, w).toDouble() / c.coerceAtLeast(1))
        })
        fun days(m: Met.Of, part: CustomsPart) = (metYear[m.tally(part.label)] ?: 0) / 10.0
        val parts = listOf(CustomsPart.BURIALS, CustomsPart.RITES, CustomsPart.CLEANING, CustomsPart.NURSING)
        val planned = days(Met.Of.CUSTOMS_DAYS_TO_HAND, CustomsPart.HELD); val free = days(Met.Of.CUSTOMS_DAYS_TO_HAND, CustomsPart.FREE)
        println("     customs' time this year, person-days got of asked: " + parts.joinToString("; ") { part ->
            val asked = days(Met.Of.CUSTOMS_DAYS_ASKED, part); val got = days(Met.Of.CUSTOMS_DAYS_GIVEN, part)
            "%s %.0f of %.0f (%.0f%%)".format(part.label, got, asked, 100.0 * got / asked.coerceAtLeast(1e-9))
        } + "; to hand for them %.0f, %.0f%% of it held by the plan, the rest free".format(planned + free, 100.0 * planned / (planned + free).coerceAtLeast(1e-9)))
        fun line(label: String, key: (Tally.MeetingEnd) -> TallyKey): String {
            fun v(e: Tally.MeetingEnd) = thisYear[key(e)] ?: 0
            val met = v(Tally.MeetingEnd.MET)
            return "%s %d/%d/%d/%d".format(label, met, v(Tally.MeetingEnd.RAIDED), v(Tally.MeetingEnd.PARTNERS), v(Tally.MeetingEnd.RAIDED_HOME)) +
                (if (met > 0) " (%.0f raids a thousand meetings)".format(1000.0 * v(Tally.MeetingEnd.RAIDED) / met) else "")
        }
        println("     meetings this year by understanding (met / raided at meeting / exchanged partners / raided at home): " +
            Tally.Understood.entries.joinToString(", ") { u -> line(u.label) { e -> Tally.meetingsBy(u, e) } })
        println("     meetings this year by customs (met / raided at meeting / exchanged partners / raided at home): " +
            Tally.CustomsApart.entries.joinToString(", ") { c -> line(c.label) { e -> Tally.meetingsBy(c, e) } })
        println("     meetings this year by wariness (met / raided at meeting / exchanged partners / raided at home): " +
            Tally.Wary.entries.joinToString(", ") { w -> line(w.label) { e -> Tally.meetingsBy(w, e) } })
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

/**
 * The identity census's lines between regions ([Census.printIdentity]): neighbours that understand each other at this or
 * better and hold at most this many traditions differently are of one region. The report's, not measured; and the edges of
 * its distance classes, km (a band's gathering ground, the 150 km blocks founders' words and descent come from, a 450 km
 * region's words, and the 600 km after which founders' lineages repeat).
 */
private const val REGION_UNDERSTOOD = 0.75f
private const val REGION_CUSTOMS_APART = 1
private val IDENTITY_KM = doubleArrayOf(50.0, 150.0, 450.0, 600.0)
