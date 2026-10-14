package inflation.tools

import inflation.ecology.HexHistory
import inflation.ecology.HexSim


import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig

/**
 * Runs the resident plant-eaters on the hexes for some years over weather, water and plants, and
 * prints each kind's numbers year by year with the main causes of death, and the cost of a step.
 *
 * Usage (--history FILE writes the week-by-week history as CSV for the graphs; --save FILE saves the
 * world every five years and at the end, --load FILE carries on a saved one): hex-fauna-check [--seed N]
 * [--width KM] [--height KM] [--years N] [--save FILE] [--load FILE]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var years = 10
    var uniform = false
    var historyFile: java.io.File? = null
    var saveFile: java.io.File? = null
    var loadFile: java.io.File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            "--uniform" -> uniform = true
            "--history" -> historyFile = java.io.File(args[++k])
            "--save" -> saveFile = java.io.File(args[++k])
            "--load" -> loadFile = java.io.File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val sim = HexSim(inflation.ecology.HexSnapshot.WorldCache.load(cfg) { println(it) }, uniformStart = uniform)
    loadFile?.let { f ->
        val t0 = System.nanoTime()
        inflation.ecology.HexSnapshot.load(sim, f)
        println("carrying on the world saved in $f, at ${sim.weather.date} (loaded in %.1f s)".format((System.nanoTime() - t0) / 1e9))
    }
    val hw = sim.hw; val weather = sim.weather; val land = sim.land; val fauna = sim.fauna; val herds = sim.groups
    // Sample places for local swings: every so many places with some plant cover.
    val samplePlaces = (0 until land.n step (land.n / 60).coerceAtLeast(1)).filter { land.herb[it] + land.shrub[it] > 0.05f }.take(40)
    val localSum = Array(samplePlaces.size) { FloatArray(5) }
    val localYears = Array(samplePlaces.size) { ArrayList<FloatArray>() }
    var localWeeks = 0
    var apptDue = 0L; var apptKept = 0L
    // Camp moves this year, of bands with houses and without.
    val movesHouse = LongArray(2); val bandWeeksHouse = LongArray(2)
    fun movesWith(houses: Boolean): Double { val i = if (houses) 1 else 0; val v = movesHouse[i] * 52.0 / bandWeeksHouse[i].coerceAtLeast(1); movesHouse[i] = 0; bandWeeksHouse[i] = 0; return v }
    fun bandHeadsNow(l: List<inflation.ecology.HexGroup>): Double = l.sumOf { it.size.toDouble() }.coerceAtLeast(1.0)
    fun keptShare(): Double { val v = 100.0 * apptKept / apptDue.coerceAtLeast(1); apptDue = 0; apptKept = 0; return v }
    inflation.ecology.Tuning.changed().takeIf { it.isNotEmpty() }?.let { println("tuning: $it") }
    // Bands' ways from a learned network (-Dhex.ways=FILE), else the scaffold's.
    System.getProperty("hex.ways")?.let { herds.ways = inflation.learning.NetWays(inflation.learning.Mlp.load(java.io.File(it).readText())) }
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    println("year " + fauna.kinds.joinToString("") { "%11s".format(it.name.take(10)) })
    println("   0 " + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
    // How well this month's climate suits each kind where its groups are, by head count.
    fun fit() = herds.kinds.joinToString(", ") { s ->
        val gs = herds.groups.filter { it.alive && it.species == s }
        val heads = gs.sumOf { it.size }.coerceAtLeast(1)
        "%s %.2f".format(s.name, gs.sumOf { it.size * fauna.monthFit(s, it.place, weather.date.month).toDouble() } / heads)
    }
    println("     climate fit: " + fit())
    // How much of the land each kind's climate suits at all, and how strongly its groups feel drawn elsewhere.
    fun drawn() = herds.kinds.withIndex().joinToString(", ") { (k, s) ->
        val gs = herds.groups.filter { it.alive && it.species == s }
        val pull = gs.sumOf { herds.bearings.strength(k, hw.placeHex[it.place]).toDouble() } / gs.size.coerceAtLeast(1)
        val good = (0 until fauna.n).sumOf { p -> if (fauna.climateSuits(s, p) > 0.8f) land.areaKm2[p].toDouble() else 0.0 } / land.areaKm2.sum()
        "%s %.0f%% / %.2f".format(s.name, 100 * good, pull)
    }
    // Ways of working and cereals: printed each year and at the end.
    var grainReapedYear = 0.0; var grainReapedNow = 0.0
    fun skillsAndCereals() {
        run {
            // Ways of working: how many bands know each, and how well those that know it do it (skills a band
            // holds are part of its customs; neighbours share them, distant bands differ).
            val bs = herds.groups.filter { it.alive && it.band != null }.map { it.band!! }
            if (bs.isNotEmpty()) println("ways of working (bands knowing, their mean level): " + inflation.ecology.Skill.entries.joinToString(", ") { s ->
                val k = bs.filter { it.knowsSkill[s.ordinal] }
                "%s %.0f%% %.2f".format(s.name.lowercase().replace('_', ' '), 100.0 * k.size / bs.size, if (k.isEmpty()) 0.0 else k.map { it.skills[s.ordinal].toDouble() }.average()) })
        }
        run {
            val f = fauna.food
            val stands = (0 until land.n).count { f.cereal[it] > 0.01f }
            val fields = (0 until land.n).filter { f.domestic[it] > 0.01f }
            println("cereals: wild stands in %d places; places with ears that hold their grain %d, most %.2f, mean there %.2f; grain reaped by people %.0f t in the last year; stored grain %.0f t (bands at home %.0f t); grain standing %.0f t, ripened in the last year %.0f t; bands at home on cereal ground %d of %d".format(
                stands, fields.size, fields.maxOfOrNull { f.domestic[it] } ?: 0f, if (fields.isEmpty()) 0.0 else fields.map { f.domestic[it].toDouble() }.average(), grainReapedYear,
                herds.groups.filter { it.alive && it.band != null }.sumOf { it.stores[inflation.ecology.Store.SEED.ordinal].toDouble() } / 1000,
                herds.groups.filter { it.alive && it.band?.atHome == true }.sumOf { it.stores[inflation.ecology.Store.SEED.ordinal].toDouble() } / 1000,
                (0 until land.n).sumOf { (f.grain[it] * land.areaKm2[it] * 1e6).toDouble() } / 1000, f.grainRipenedLastYear,
                herds.groups.count { it.alive && it.band?.atHome == true && f.cereal[it.place] > 0f }, herds.groups.count { it.alive && it.band?.atHome == true }))
        }
    }
    run {
        val t0 = System.nanoTime(); herds.bearings.month
        val t1 = System.nanoTime(); herds.bearings.update(0); fauna.bearings.update(0)
        println("sense of direction: setting up %.0f ms, a month's update for all kinds %.0f ms".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
    }
    // Where the matter is: each pool over the whole map, thousand tonnes.
    fun budget(): String {
        val sl = fauna.small
        fun perM2(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } // kg/m² × km² = 1e6 kg = 1000 t
        fun perKm2(a: FloatArray) = perM2(a) / 1e6
        val plants = (0 until fauna.n).sumOf { (land.biomass(it) * land.areaKm2[it]).toDouble() }
        val smallAnimals = listOf(sl.insects, sl.rodents, sl.herps, sl.pollinators, sl.hares, sl.hyrax, sl.tortoises, sl.foxes, sl.birds).sumOf { perKm2(it) }
        val residents = fauna.kinds.indices.sumOf { fauna.count(it) * fauna.kinds[it].massKg } / 1e6
        val groups = herds.groups.filter { it.alive }.sumOf { (it.adults + 0.3 * it.young) * it.species.massKg } / 1e6
        return "plants %.0f, litter %.0f, soil organic %.0f, carrion %.2f, small animals %.2f, resident herds %.2f, herds and packs %.2f (thousand t)".format(
            plants, perM2(sl.litter), perM2(sl.organicMatter), perM2(sl.carrion), smallAnimals, residents, groups)
    }
    println("     matter: " + budget())
    println("     land %.0f km²; groups at the start: ".format(land.areaKm2.sum()) + herds.kinds.joinToString(", ") { "${it.name} ${herds.groups.count { g -> g.species == it }} (${herds.count(it)})" })
    val history = HexHistory()
    var ns = 0L
    val causes = java.util.TreeMap<String, Double>()
    val janHex = HashMap<Int, Int>()
    for (y in 1..years) {
        val before = causes.toMap()
        val huntYear = java.util.TreeMap<String, Int>(); val fedSum = HashMap<String, Double>(); val groupWeeks = HashMap<String, Int>()
        // Seasonal movement: where each herd is in January, and how far from there it is in July.
        val julyKm = HashMap<String, ArrayList<Double>>()
        // People, week by week, for the comparison with the record: band sizes, heads, camps moved, ground walked.
        var bandWeeks = 0.0; var bandHeads = 0.0; var childWeeks = 0.0; var adultWeeks = 0.0; var motherWeeks = 0.0; var moves = 0
        val walked = HashMap<Int, HashSet<Int>>(); val lastCamp = HashMap<Int, Int>()
        repeat(stepsPerYear) { w ->
            if (w == stepsPerYear / 2) println("     midsummer, land that suits / pull felt: " + drawn())
            System.getProperty("hex.huntDiag")?.let { kinds ->
                if (w % 13 == 6) {
                    // Packs of the kind that went hungry this week against those that ate, and their ground.
                    for (kind in kinds.split(',')) {
                    val gs = herds.groups.filter { it.alive && it.species.name == kind }
                    val (hungry, fed) = gs.partition { it.fed < 0.5f }
                    fun ground(l: List<inflation.ecology.HexGroup>) = if (l.isEmpty()) "-" else "resident game %.0f kg/km², young per pack %.1f, holding ground %.0f%%, cover %.2f, water %.1f km, band %.1f".format(
                        l.map { g -> fauna.kinds.indices.sumOf { k -> ((fauna.adults[k][g.place] + fauna.young[k][g.place]) * fauna.kinds[k].massKg).toDouble() } }.average(),
                        l.map { it.young.toDouble() }.average(), 100.0 * l.count { it.home >= 0 } / l.size,
                        l.map { land.cover(it.place).toDouble() }.average(), l.map { land.waterKm[it.place].toDouble() }.average(), l.map { hw.placeBand[it.place].toDouble() }.average()) +
                        ", %.0f m, %.1f °C, fed %.2f, larder %.0f days, size %.0f, path %.1f hexes, days without water %.1f".format(l.map { hw.placeElevationM[it.place].toDouble() }.average(), l.map { weather.temperature[it.place].toDouble() }.average(),
                            l.map { it.fed.toDouble() }.average(), l.map { (it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f)).toDouble() }.average(), l.map { it.size.toDouble() }.average(), l.map { it.path.size.toDouble() }.average(), l.map { it.dry.toDouble() }.average())
                    println("     week $w $kind: ${hungry.size} of ${gs.size} packs hungry: ${ground(hungry)} | fed: ${ground(fed)}")
                    val thirsty = gs.filter { it.dry > it.species.daysWithoutWater }
                    if (thirsty.isNotEmpty()) println("     week $w $kind: ${thirsty.size} thirsty: " + ground(thirsty) + "; water within reach %.0f%%, sea %.0f%%".format(100.0 * thirsty.count { land.water[it.place] >= 0.05f } / thirsty.size, 100.0 * thirsty.count { hw.sea[hw.placeHex[it.place]] > 0.3f } / thirsty.size))
                    }
                }
            }
            if (System.getProperty("hex.fedDiag") != null && y == 1 && w % 13 == 6) {
                // How well each resident kind is fed where it lives, and why: weighted by head count.
                println("     week $w, residents: fed / share hungry / tether / access / green / reserve days")
                for ((k, s) in fauna.kinds.withIndex()) {
                    var heads = 0.0; var fed = 0.0; var hungry = 0.0; var teth = 0.0; var acc = 0.0; var gr = 0.0; var res = 0.0
                    for (p in 0 until fauna.n) {
                        val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                        if (h <= 0.0) continue
                        heads += h; fed += h * fauna.fed[k][p]; if (fauna.fed[k][p] < 0.6f) hungry += h
                        teth += h * fauna.tether(s, p); acc += h * inflation.ecology.HexFood.access(s, land.ruggedness[p]); gr += h * land.green[p]; res += h * fauna.reserve[k][p]
                    }
                    if (heads > 0) println("       %-12s %.2f / %.0f%% / %.2f / %.2f / %.2f / %.0f".format(s.name, fed / heads, 100 * hungry / heads, teth / heads, acc / heads, gr / heads, res / heads))
                }
                println("     herds and packs: fed / reserve days / young per adult / days without water / cold degrees")
                for (s in herds.kinds) {
                    val gs = herds.groups.filter { it.alive && it.species == s }
                    val heads = gs.sumOf { it.size }.toDouble().coerceAtLeast(1.0)
                    println("       %-12s %.2f / %.0f / %.2f / %.1f / %.1f".format(s.name, gs.sumOf { it.fed.toDouble() * it.size } / heads, gs.sumOf { it.fat.toDouble() * it.size } / heads,
                        gs.sumOf { it.young }.toDouble() / gs.sumOf { it.adults }.coerceAtLeast(1), gs.sumOf { it.dry.toDouble() * it.size } / heads,
                        gs.sumOf { s.coldC(weather.temperature[it.place], weather.date.month).toDouble() * it.size } / heads))
                }
            }
            val t0 = System.nanoTime()
            // -Dhex.follow=kind: follow one group of that kind week by week, as the viewer's follow mode does.
            System.getProperty("hex.follow")?.let { kind -> if (herds.groups.none { it.id == herds.watched && it.alive }) herds.watched = herds.groups.firstOrNull { it.alive && it.species.name == kind }?.id ?: -1 }
            sim.step()
            grainReapedNow += (herds.hunting["people:grain reaped, kg"] ?: 0) / 1000.0
            for (g in herds.groups) { val b = g.band ?: continue; if (!g.alive) continue; val i = if (b.house >= 0.2f) 1 else 0; bandWeeksHouse[i]++; if (b.campWeeks == 0) movesHouse[i]++ }
            // Appointments kept: of bands in their appointment's month, how many are at its hex.
            for (g in herds.groups) { val b = g.band ?: continue; if (!g.alive || b.appointHex < 0 || b.appointMonth != weather.date.month) continue; apptDue++; if (hw.placeHex[g.place] == b.appointHex) apptKept++ }
            // Local swings: yearly means of small life at fixed sample places.
            for ((j, sp) in samplePlaces.withIndex()) { val sl = fauna.small; localSum[j][0] += sl.rodents[sp]; localSum[j][1] += sl.hares[sp]; localSum[j][2] += sl.foxes[sp]; localSum[j][3] += sl.owls[sp]; localSum[j][4] += sl.birds[sp] }
            localWeeks++
            if (localWeeks == Date.DAYS_PER_YEAR / cfg.daysPerStep) { for (j in samplePlaces.indices) { localYears[j] += localSum[j].map { it / localWeeks }.toFloatArray(); localSum[j].fill(0f) }; localWeeks = 0 }
            if (System.getProperty("hex.follow") != null && herds.watched >= 0) {
                val g = herds.groups.firstOrNull { it.id == herds.watched }
                println("   follow #%d (%s, %d) %s:".format(herds.watched, g?.species?.name ?: "?", g?.size ?: 0, weather.date))
                for (l in herds.watchLog) println("      $l")
            }
            ns += System.nanoTime() - t0
            if (historyFile != null) history.record(sim)
            for ((key, v) in fauna.deaths) causes.merge(key, v, Double::plus)
            for ((key, v) in herds.deaths) causes.merge(key, v.toDouble(), Double::plus)
            for ((key, v) in herds.kills) causes.merge("$key:hunted", v.toDouble(), Double::plus)
            for ((key, v) in herds.births) causes.merge("$key:+born", v.toDouble(), Double::plus)
            for ((key, v) in herds.hunting) huntYear.merge(key, v, Int::plus)
            // -Dhex.migDiag=kind: each month, how far that kind's herds are from the place they remember for the month.
            System.getProperty("hex.migDiag")?.let { kind -> if (weather.date.dayOfMonth <= cfg.daysPerStep) {
                val m = weather.date.month
                val gs = herds.groups.filter { it.alive && it.species.name == kind && it.seasonPlace[m] >= 0 }
                if (gs.isNotEmpty()) println("     month ${m + 1} $kind: %.0f km from this month's place, fed %.2f, remembered worth %.2f".format(
                    gs.map { sim.hex.distanceKm(hw.placeHex[it.place], hw.placeHex[it.seasonPlace[m]]) }.average(), gs.map { it.fed.toDouble() }.average(), gs.map { it.seasonQ[m].toDouble() }.average()))
            } }
            if (weather.date.dayOfMonth <= cfg.daysPerStep) {
                if (weather.date.month == 0) for (g in herds.groups) if (g.alive && !g.species.carnivore) janHex[g.id] = hw.placeHex[g.place]
                if (weather.date.month == 6) for (g in herds.groups) if (g.alive && !g.species.carnivore) janHex[g.id]?.let { julyKm.getOrPut(g.species.name) { ArrayList() } += sim.hex.distanceKm(it, hw.placeHex[g.place]) }
            }
            for (g in herds.groups) if (g.alive && g.band != null) {
                bandWeeks++; bandHeads += g.size; childWeeks += g.young; adultWeeks += g.adults; motherWeeks += (g.adults - g.band!!.elders) / 2
                val h = hw.placeHex[g.place]
                for (q in g.path) walked.getOrPut(g.id) { HashSet() } += q
                lastCamp.put(g.id, h)?.let { if (it != h) moves++ }
            }
            for (g in herds.groups) if (g.alive && g.species.carnivore) { fedSum.merge(g.species.name, g.fed.toDouble(), Double::plus); groupWeeks.merge(g.species.name, 1, Int::plus) }
        }
        println("%4d ".format(y) + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
        println("     small life: " + fauna.small.summary())
        println("     water life: " + fauna.water.summary())
        println("     sea and lake fish this week, t: " + fauna.water.fishFlows.entries.sortedBy { it.key }.joinToString(", ") { "%s %.1f".format(it.key, it.value) })
        println("     eruptions so far: " + sim.eruptions.history.joinToString(", ") { "VEI ${it.vei} in year ${it.date.year}" }.ifEmpty { "none" })
        println("     matter: " + budget())
        println("     climate fit: " + fit())
        println("     land that suits / pull felt: " + drawn())
        println("     groups: " + herds.kinds.joinToString(", ") { "${it.name} ${herds.count(it)}" })
        // This year's deaths of the resident herds, all kinds together, by cause.
        val residentNames = fauna.kinds.map { it.name }.toSet()
        val year = HashMap<String, Double>()
        for ((key, v) in causes) if (key.substringBefore(":") in residentNames) year.merge(key.substringAfter(":"), v - (before[key] ?: 0.0), Double::plus)
        // Hunters this year: born and dead.
        println("     hunters this year, born / died: " + herds.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val born = (causes["${s.name}:+born"] ?: 0.0) - (before["${s.name}:+born"] ?: 0.0)
            val died = causes.entries.filter { it.key.startsWith(s.name + ":") && !it.key.endsWith("+born") }.sumOf { it.value - (before[it.key] ?: 0.0) }
            "%s %.0f / %.0f".format(s.name, born, died)
        })
        // How the hunting went, per hunting party per week.
        println("     hunting, per party a week (chases / herds that saw it first / kills from herds / kills of residents / fed): " + herds.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gw = (groupWeeks[s.name] ?: 0).coerceAtLeast(1).toDouble()
            fun v(k: String) = (huntYear["${s.name}:$k"] ?: 0) / gw
            "%s %.2f / %.2f / %.2f / %.2f / %.2f".format(s.name, v("chases"), v("herds that saw it first"), v("kills from herds"), v("kills of residents"), (fedSum[s.name] ?: 0.0) / gw)
        })
        // What each hunting kind's diet asked for and what each part brought, as shares of what it asked.
        // (What each eats comes from what pays best; meat includes fish.)
        println("     hunters' food (meat / small prey / carrion / plants, as shares of what they need): " + herds.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val asked = (huntYear["${s.name}:diet asked, kg"] ?: 0).toDouble().coerceAtLeast(1.0)
            fun part(k: String) = 100 * (huntYear["${s.name}:diet $k, kg"] ?: 0) / asked
            "%s %.0f/%.0f/%.0f/%.0f%%".format(s.name, part("meat"), part("small prey"), part("carrion"), part("plants"))
        })
        // How far the herds are in July from where they were in January.
        if (julyKm.isNotEmpty()) println("     herds' January to July (mean km / share over 50 km): " + julyKm.entries.joinToString(", ") { (k, v) -> "%s %.0f / %.0f%%".format(k, v.average(), 100.0 * v.count { it > 50.0 } / v.size) })
        // And how far apart the places they remember for January and July are: the migration they mean to make.
        println("     herds' remembered January and July places apart (mean km): " + herds.kinds.filter { !it.carnivore }.joinToString(", ") { s ->
            val d = herds.groups.filter { it.alive && it.species == s && it.seasonPlace[0] >= 0 && it.seasonPlace[6] >= 0 }.map { sim.hex.distanceKm(hw.placeHex[it.seasonPlace[0]], hw.placeHex[it.seasonPlace[6]]) }
            "%s %.0f".format(s.name, if (d.isEmpty()) 0.0 else d.average())
        })
        // How often each kind's groups weighed their choices, and how often that came to moving.
        println("     choices a week per group (kept to ground unweighed / weighed and stayed / moved): " + herds.kinds.joinToString(", ") { s ->
            val c = IntArray(3) { herds.planCounts.getAndSet(s.index * 3 + it, 0) }
            val all = c.sum().coerceAtLeast(1)
            "%s %.0f/%.0f/%.0f%%".format(s.name, 100.0 * c[0] / all, 100.0 * c[1] / all, 100.0 * c[2] / all)
        })
        // The hunters' families: packs, how many hold ground and could breed, lone animals, adults and young.
        println("     hunters' families (packs / holding ground / able to breed / alone / adults / young): " + herds.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gs = herds.groups.filter { it.alive && it.species == s }
            "%s %d / %d / %d / %d / %d / %d".format(s.name, gs.size, gs.count { it.home >= 0 }, gs.count { it.home >= 0 && it.adults >= 2 }, gs.count { it.adults == 1 }, gs.sumOf { it.adults }, gs.sumOf { it.young })
        })
        // Bands: their skills, the tools they know and how full their kit is, how long they keep camp, their larders.
        val bands = herds.groups.filter { it.alive && it.band != null }
        if (bands.isNotEmpty()) {
            val bs = bands.map { it.band!! }
            println("     bands: skills " + inflation.ecology.Skill.entries.joinToString(", ") { f -> "%s %.2f".format(f.name.lowercase(), bs.map { it.skills[f.ordinal] }.average()) } +
                " | tools known (kit) " + inflation.ecology.Tool.entries.joinToString(", ") { t -> "%s %.0f%% (%.2f)".format(t.name.lowercase(), 100.0 * bs.count { it.knows[t.ordinal] } / bs.size, bs.map { it.kit[t.ordinal] }.average()) } +
                " | customs " + inflation.ecology.Tradition.entries.joinToString(", ") { t -> "%s %.0f%%".format(t.name.lowercase(), 100.0 * bs.count { it.holds(t) } / bs.size) } +
                " | elders %.0f%% of adults, children inbred %.3f".format(100.0 * bs.sumOf { it.elders } / bands.sumOf { it.adults }.coerceAtLeast(1), bs.map { it.inbred.toDouble() }.average()) +
                " | kites %d, animals taken in drives this year %d, caches %d".format(herds.kites.count { it >= 1f }, huntYear["people:kills in kite drives"] ?: 0, bs.count { it.cachePlace >= 0 }) +
                " | dogs %.0f in %d bands, wolf packs' boldness %.2f".format(bs.sumOf { it.kept.sum().toDouble() }, bs.count { it.kept.sum() >= 1f }, herds.groups.filter { it.alive && it.species.followsPeople && !it.species.human }.map { inflation.ecology.boldness(it).toDouble() }.average()) +
                " | country known %.0f km²".format(bs.map { b -> b.known.sumOf { java.lang.Long.bitCount(it) }.toDouble() }.average() * sim.hex.hexKm * sim.hex.hexKm * 0.866) +
                " | camp weeks %.1f, larder days %.0f".format(bs.map { it.campWeeks }.average(), bands.map { it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f) }.average()) +
                // Ties and gatherings: bands it knows and is close to; bands camped with two or more others in the same hex.
                " | houses: %.0f%% of bands have them (%.0f%% at home), camps moved a year %.1f with houses vs %.1f without".format(
                    100.0 * bs.count { it.house >= 0.2f } / bs.size.coerceAtLeast(1), 100.0 * bs.count { it.atHome } / bs.size.coerceAtLeast(1),
                    movesWith(true), movesWith(false)) +
                // (By the rain where they are: the mobile test wants houses rarer on dry, poor land.)
                " | with houses by yearly rain: " + listOf(0f to 300f, 300f to 600f, 600f to 1e9f).joinToString(", ") { (lo, hi) ->
                    val inRain = bands.filter { val h = hw.placeHex[it.place]; val r = (0 until 12).sumOf { m -> hw.precip[m][h].toDouble() }; r >= lo && r < hi }
                    "%s %.0f%% of %d".format(if (hi > 1e8f) ">600 mm" else "%.0f-%.0f mm".format(lo, hi), 100.0 * inRain.count { it.band!!.house >= 0.2f } / inRain.size.coerceAtLeast(1), inRain.size) } +
                " | kept herds: " + fauna.kinds.indices.filter { k -> bs.any { it.herd.size > k && it.herd[k] >= 1f } }.joinToString(", ") { k ->
                    val keepers = bs.filter { it.herd.size > k && it.herd[k] >= 1f }
                    "%s %.0f head in %d bands (tame %.2f)".format(fauna.kinds[k].name, keepers.sumOf { it.herd[k].toDouble() }, keepers.size, keepers.map { it.herdTame[k].toDouble() }.average()) }.ifEmpty { "none" } +
                " | ornaments %.1f a person (%.0f%% of shell), shell beads a person inland %.2f, by the sea %.2f; graves with goods %d (richest hold %.0f%% of goods)".format(
                    bs.sumOf { it.ornaments.toDouble() } / bandHeadsNow(bands), 100.0 * bs.sumOf { it.exotic.toDouble() } / bs.sumOf { it.ornaments.toDouble() }.coerceAtLeast(1e-9),
                    bands.filter { !hw.coast[hw.placeHex[it.place]] }.let { l -> l.sumOf { it.band!!.exotic.toDouble() } / bandHeadsNow(l) },
                    bands.filter { hw.coast[hw.placeHex[it.place]] }.let { l -> l.sumOf { it.band!!.exotic.toDouble() } / bandHeadsNow(l) },
                    land.graveGoods.count { it >= 1f }, land.graveGoods.sortedDescending().let { gs -> 100.0 * gs.take((gs.count { it >= 1f } / 10).coerceAtLeast(1)).sum() / gs.sum().coerceAtLeast(1e-9f) }) +
                " | raids on homes, palisades %.0f%% of bands, homes known %.1f a band".format(100.0 * bs.count { it.palisade >= 0.2f } / bs.size.coerceAtLeast(1), bs.map { it.knownHomes.size }.average()) +
                " | appointments %.0f%% of bands, kept %.0f%% of band-weeks due this year; burial places %d; largest gathering %d bands".format(
                    100.0 * bs.count { it.appointHex >= 0 } / bs.size.coerceAtLeast(1),
                    keptShare(),
                    land.burials.count { it >= 1f }, bands.groupBy { hw.placeHex[it.place] }.values.maxOfOrNull { it.size } ?: 0) +
                " | ties %.1f a band (close %.1f), gathered %.0f%%".format(bs.map { it.ties.size }.average(), bs.map { b -> b.ties.values.count { it > 0.3f } }.average(),
                    100.0 * bands.groupBy { hw.placeHex[it.place] }.values.filter { it.size >= 3 }.sumOf { it.size } / bands.size.coerceAtLeast(1)))
        }
        if (bandWeeks > 0) {
            // People against what the ethnographic and archaeological record shows of foragers (ranges in brackets).
            val weeks = stepsPerYear.toDouble()
            fun yearly(cause: String) = (causes["people:$cause"] ?: 0.0) - (before["people:$cause"] ?: 0.0)
            val died = causes.keys.filter { it.startsWith("people:") && !it.endsWith("+born") && !it.endsWith(":hunted") }.sumOf { yearly(it.substringAfter(':')) }
            val childDied = (huntYear["people:children died"] ?: 0).toDouble()
            val children = childWeeks / weeks; val adults = adultWeeks / weeks
            val childQ = childDied / children.coerceAtLeast(1.0)
            val adultQ = ((died - childDied).coerceAtLeast(0.0)) / adults.coerceAtLeast(1.0)
            val meat = (huntYear["people:meat eaten, kg"] ?: 0).toDouble(); val plants = (huntYear["people:plants eaten, kg"] ?: 0).toDouble()
            val violent = yearly("raids") + causes.keys.filter { it.startsWith("people:killed by") }.sumOf { yearly(it.substringAfter(':')) }
            val bandsSeen = walked.size.coerceAtLeast(1)
            println("     people against the record: band size %.0f [15-50, most 20-35] | people per km² %.3f [foragers 0.01-0.4; Mediterranean 0.05-0.2] | camps moved a year %.1f [5-60, median about 15] | ground walked a year %.0f km² [200-2500] | plant share of food %.0f%% [Mediterranean 40-60%%] | years between births %.1f [3-4] | children reaching 15 %.0f%% [about 55-65%%] | years left at 15 %.0f [about 35-40] | violent deaths %.0f%% [2-15%%]".format(
                bandHeads / bandWeeks, bandHeads / weeks / land.areaKm2.sum(), moves.toDouble() / bandsSeen,
                walked.values.map { it.size }.average() * sim.hex.hexKm * sim.hex.hexKm * 0.866, 100 * plants / (meat + plants).coerceAtLeast(1.0),
                (motherWeeks / weeks) / yearly("+born").coerceAtLeast(1.0), 100 * Math.pow((1 - childQ).coerceIn(0.0, 1.0), 15.0), 1 / adultQ.coerceAtLeast(1e-3), 100 * violent / died.coerceAtLeast(1.0)))
        }
        println("     resident deaths this year: " + year.entries.sortedByDescending { it.value }.filter { it.value >= 1.0 }.joinToString(", ") { "%s %.0f".format(it.key, it.value) })
        println("     droughts under way: " + sim.weather.droughts())
        grainReapedYear = grainReapedNow; grainReapedNow = 0.0
        print("     "); skillsAndCereals()
        System.out.flush()
        // The history so far, for the graphs (every five years and at the end).
        historyFile?.let { if (y % 5 == 0 || y == years) history.writeCsv(it) }
        saveFile?.let { if (y % 5 == 0 || y == years) { val t0 = System.nanoTime(); inflation.ecology.HexSnapshot.save(sim, it); println("     saved the world to $it (%.1f s, %.0f MB)".format((System.nanoTime() - t0) / 1e9, it.length() / 1e6)) } }
        println("     this week: kills " + herds.kills.entries.joinToString(", ") { "${it.key} ${it.value}" } + " | meetings " + herds.meetings.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }
    // How much small life swings from year to year in one place (not the map's total, where out-of-step places smooth each other out).
    run {
        val names = listOf("rodents", "hares", "foxes", "owls", "game birds")
        println("local swings at %d places (median over places of the largest year against the smallest / share of place-years with none): ".format(samplePlaces.size) + names.indices.joinToString(", ") { c ->
            val ratios = localYears.mapNotNull { ys -> val v = ys.drop(1).map { it[c] }; val mx = v.maxOrNull() ?: 0f; val mn = v.minOrNull() ?: 0f; if (mx > 0f) (if (mn > 0f) mx / mn else Float.POSITIVE_INFINITY) else null }.sorted()
            val empty = localYears.sumOf { ys -> ys.drop(1).count { it[c] <= 0.001f } }.toDouble() / localYears.sumOf { (it.size - 1).coerceAtLeast(0) }.coerceAtLeast(1)
            "%s %.1fx / %.0f%%".format(names[c], if (ratios.isEmpty()) 0f else ratios[ratios.size / 2], 100 * empty)
        })
    }
    // How unequal the land is for people: food to be had in each land hex (plant foods, game, fish).
    run {
        val value = DoubleArray(sim.hex.size)
        for (pl in 0 until land.n) {
            val h = hw.placeHex[pl]; val a = land.areaKm2[pl].toDouble()
            val f = fauna.food
            value[h] += (f.fruit[pl] + f.roots[pl] + f.seeds[pl] + f.finds[pl]) * 1e6 * a
            for (k in fauna.kinds.indices) value[h] += 0.1 * fauna.adults[k][pl] * fauna.kinds[k].massKg * a
        }
        for (h in 0 until sim.hex.size) if (hw.land[h] > 0f) value[h] += fauna.water.fishWithin(h).toDouble()
        val v = (0 until sim.hex.size).filter { hw.land[it] > 0.5f }.map { value[it] }.sorted()
        if (v.isNotEmpty()) {
            val total = v.sum().coerceAtLeast(1e-9)
            var cum = 0.0; var area = 0.0
            for ((i, x) in v.withIndex()) { cum += x; area += cum }
            val gini = 1.0 - 2.0 * (area / (v.size * total)) + 1.0 / v.size
            val top = v.takeLast((v.size / 20).coerceAtLeast(1)).sum() / total
            println("food for people across the land: unevenness (Gini) %.2f, richest 5%% of hexes hold %.0f%% of it, median hex %.0f t".format(gini, 100 * top, v[v.size / 2] / 1000))
        }
    }
    skillsAndCereals()
    println("deaths over the run, by cause:")
    for (s in fauna.kinds + herds.kinds) {
        val c = causes.entries.filter { it.key.substringBefore(":") == s.name && !it.key.endsWith("+born") }.map { it.key.substringAfter(":") to it.value }.sortedByDescending { it.second }
        val all = c.sumOf { it.second }.coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + c.filter { 100 * it.second / all >= 0.5 }.joinToString(", ") { (k, v) -> "$k %.0f%%".format(100 * v / all) })
    }
    // Where each kind lives at the end: share in each height band.
    println("share of each kind by height band (lowest first):")
    for ((i, s) in fauna.kinds.withIndex()) {
        val byBand = DoubleArray(HexWorld.BANDS_M.size)
        for (p in 0 until fauna.n) byBand[hw.placeBand[p]] += ((fauna.adults[i][p] + fauna.young[i][p]) * land.areaKm2[p]).toDouble()
        val tot = byBand.sum().coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + byBand.joinToString(" ") { "%3.0f%%".format(100 * it / tot) })
    }
    println("where a step's time goes:")
    println(inflation.ecology.Profile.report(years * stepsPerYear))
    if (inflation.ecology.Profile.FINE) { println("finer parts (thread time, summed over cores):"); println(inflation.ecology.Profile.fineReport(years * stepsPerYear)) }
    println("a step of weather, water, plants and all animals: %.1f ms, of it settling meetings %.1f ms, the sense of direction %.1f ms".format(ns / 1e6 / (years * stepsPerYear), herds.meetNs / 1e6 / (years * stepsPerYear), (herds.bearings.updateNs + fauna.bearings.updateNs) / 1e6 / (years * stepsPerYear)))
}
