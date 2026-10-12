package inflation.ecology

import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.max

/**
 * Runs the living world for some years and prints the animal populations each season.
 *
 * Usage: fauna [--seed N] [--size KM] [--height KM] [--years N] [--policies DIR]
 *
 * With --policies, species that have a saved network in DIR (see [inflation.learning.NetworkPolicy]) use it.
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var years = 10
    var policies: java.io.File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            "--policies" -> policies = java.io.File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val sim = Simulation(world)
    val e = sim.ecology
    policies?.let { dir ->
        val nets = inflation.learning.NetworkPolicy.load(dir)
        val policy = inflation.learning.NetworkPolicy(nets, e::noise, e.policies.getValue(Catalogue.GAZELLE))
        for (s in nets.keys) e.policies[s] = policy
        println("networks for " + nets.keys.joinToString { it.name })
    }
    val land = (0 until world.grid.size).count { sim.vegetation.isVegetated(it) } * world.grid.cellKm * world.grid.cellKm
    println("land %.0f km², %d springs and water holes in dry country, %d of them oases".format(land, e.spring.count { it }, e.oasis.count { it }))
    println("%-28s ".format("date") + Catalogue.all.joinToString(" ") { "%9s".format(it.name.take(9)) } + "  kills  fish t/km² shellfish run t")
    val stepsPerSeason = 90 / cfg.daysPerStep
    var kills = 0
    var fires = 0; var burnedKm2 = 0.0
    val t0 = System.nanoTime()
    val water = (0 until world.grid.size).filter { e.plankton.isWater[it] }
    repeat(years * 4) {
        repeat(stepsPerSeason) { sim.step(); kills += e.kills; fires += sim.vegetation.fires.size; burnedKm2 += sim.vegetation.fires.sumOf { it.second.toDouble() } }
        println("%-28s ".format(sim.date.toString()) + Catalogue.all.joinToString(" ") { "%9d".format(e.count(it)) } +
            " %6d %11.2f %9.1f %5.0f".format(kills, water.sumOf { e.fish.fish[it].toDouble() } / water.size / 1000,
                e.fish.shellfish.sumOf { it.toDouble() } / 1e6, e.fish.run.sumOf { it.toDouble() } / 1000))
        kills = 0
        val veg = sim.vegetation
        val landCells = (0 until world.grid.size).filter { veg.isVegetated(it) }
        println("   plants trees %.2f shrubs %.2f herbs %.2f kg/m², grass quality %.2f, browsing %.2f grazing %.2f kg/km²/day, %d fires burned %.0f km²".format(
            landCells.map { veg.tree[it].toDouble() }.average(), landCells.map { veg.shrub[it].toDouble() }.average(), landCells.map { veg.herb[it].toDouble() }.average(),
            landCells.map { e.grassQuality[it].toDouble() }.average(), landCells.map { veg.browsing[it].toDouble() }.average(), landCells.map { veg.grazing[it].toDouble() }.average(), fires, burnedKm2))
        fires = 0; burnedKm2 = 0.0
        println("   game   " + e.game.summary())
        e.groups.filter { it.species.human && it.alive }.takeIf { it.isNotEmpty() }?.let { bands ->
            val people = bands.sumOf { it.size }
            println("   people %d bands, %d people (%.0f%% children, %.0f%% elders), inbreeding %.3f, stores %.0f days, camps kept %.0f weeks (%.0f%% over half a year), hunting craft %.2f, gathering craft %.2f".format(
                bands.size, people, 100.0 * bands.sumOf { it.young } / people, 100.0 * bands.sumOf { it.elders } / people,
                bands.map { it.inbred }.average(), bands.sumOf { it.larder.toDouble() } / max(1e-6, bands.sumOf { it.dailyNeedKg.toDouble() }),
                bands.map { it.campWeeks }.average(), 100.0 * bands.count { it.campWeeks > 26 } / bands.size,
                bands.mapNotNull { b -> b.craft?.let { c -> Catalogue.herbivores.map { c[it.index] }.average() } }.average(),
                bands.mapNotNull { b -> b.forageCraft?.average() }.average()))
            val spoken = bands.filter { it.words != null }
            if (spoken.size > 1) {
                var near = 0.0; var all = 0.0; var pairs = 0
                for (a in spoken) {
                    var best = Double.MAX_VALUE; var bestU = 0f
                    for (b in spoken) {
                        if (a === b) continue
                        val u = Language.understanding(a.words!!, b.words!!)
                        val d = kotlin.math.hypot(a.x - b.x, a.y - b.y)
                        if (d < best) { best = d; bestU = u }
                        all += u; pairs++
                    }
                    near += bestU
                }
                println("   speech neighbours understand each other %.0f%%, any two bands %.0f%%".format(100 * near / spoken.size, 100 * all / max(1, pairs)))
            }
            println("   land   %.0f named places per band (water %.0f, stone %.0f, camps %.0f), furthest known %.0f km away on average".format(
                bands.map { it.places?.size ?: 0 }.average(),
                bands.map { b -> b.places?.count { it.kind == PlaceKind.WATER } ?: 0 }.average(),
                bands.map { b -> b.places?.count { it.kind == PlaceKind.STONE } ?: 0 }.average(),
                bands.map { b -> b.places?.count { it.kind == PlaceKind.CAMP } ?: 0 }.average(),
                bands.map { b -> b.places?.maxOfOrNull { kotlin.math.hypot(it.x - b.x, it.y - b.y) } ?: 0.0 }.average()))
            println("   ways   " + Tradition.entries.joinToString { tr -> "%s %.0f%%".format(tr.name.lowercase(), 100.0 * bands.count { it.holds(tr) } / bands.size) })
            println("   tools  " + Tool.entries.joinToString { tl -> "%s %.2f".format(tl.name.lowercase(), bands.mapNotNull { it.tools?.get(tl.ordinal)?.toDouble() }.average()) } +
                "; goods kg/adult " + Material.entries.joinToString { m -> "%s %.1f".format(m.name.lowercase(), bands.sumOf { (it.goods?.get(m.ordinal) ?: 0f).toDouble() } / max(1, bands.sumOf { it.adults })) })
        }
        val sea = e.marine
        println("   sea    " + SeaCatalogue.all.joinToString { "${it.name} ${sea.count(it)}" } + ", sea birds %.0f, shark kills %d".format(sea.birdCount(), sea.sharkKills))
        sea.sharkKills = 0
        if (System.getenv("FAUNA_DETAIL") != null) {
            println("   sea deaths " + sea.deathsBy.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" })
            sea.deathsBy.clear()
            println("   sea cond " + SeaCatalogue.all.joinToString { s ->
                val gs = sea.groups.filter { it.species == s }
                "%s %.2f/%.2f".format(s.name, if (gs.isEmpty()) 0.0 else gs.map { it.condition }.average(), if (gs.isEmpty()) 0.0 else gs.map { it.lastIntake.toDouble() }.average())
            })
        }
        if (System.getenv("FAUNA_DETAIL") != null) {
            println("   kills  " + Catalogue.herbivores.joinToString(" ") { "%9d".format(e.killsBy[it] ?: 0) })
            println("   hunters " + Catalogue.predators.joinToString { "${it.name} ${e.killsByPredator[it] ?: 0} kills, ${e.stolen[it] ?: 0} stolen" })
            println("   cond   " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.2f".format(if (gs.isEmpty()) 0f else gs.map { it.condition }.average().toFloat()) })
            println("   young% " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.2f".format(gs.sumOf { it.young }.toFloat() / max(1, gs.sumOf { it.size })) })
            println("   intake " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.2f".format(if (gs.isEmpty()) 0f else gs.map { it.lastIntakeRatio }.average().toFloat()) })
            println("   watch  " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.2f".format(if (gs.isEmpty()) 0f else gs.map { it.posture }.average().toFloat()) })
            println("   seasonkm " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s && !it.seasonX[1].isNaN() && !it.seasonX[7].isNaN() }; "%9.0f".format(if (gs.isEmpty()) 0.0 else gs.map { kotlin.math.hypot((it.seasonX[1] - it.seasonX[7]).toDouble(), (it.seasonY[1] - it.seasonY[7]).toDouble()) * world.grid.cellKm }.average()) })
            println("   fat d  " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.1f".format(if (gs.isEmpty()) 0f else gs.map { it.fat }.average().toFloat()) })
            println("   march% " + Catalogue.all.joinToString(" ") { s -> val gs = e.groups.filter { it.species == s }; "%9.2f".format(gs.count { it.marching }.toFloat() / max(1, gs.size)) })
            val cells = (0 until world.grid.size).filter { sim.vegetation.isVegetated(it) }
            println("   water  land with drinking water %.0f%%, within 5 km %.0f%%, mean wetness %.2f".format(
                100f * cells.count { e.waterKm[it] == 0f } / cells.size, 100f * cells.count { e.waterKm[it] <= 5f } / cells.size,
                cells.map { e.wetness[it] }.average()))
            println("   deaths " + e.deathsBy.entries.sortedByDescending { it.value }.filter { System.getenv("FAUNA_SPECIES")?.split(",")?.any { s -> it.key.startsWith(s) } ?: true }.take(12).joinToString { "${it.key} ${it.value}" })
            e.killsBy.clear(); e.deathsBy.clear(); e.killsByPredator.clear(); e.stolen.clear()
        }
    }
    println("%.0f ms per step".format((System.nanoTime() - t0) / 1e6 / (years * 4 * stepsPerSeason)))
    println("time: " + listOf("weather", "eruptions", "water", "vegetation", "ecology").zip(sim.timings.toList())
        .joinToString { (k, ns) -> "%s %.0f ms".format(k, ns / 1e6 / (years * 4 * stepsPerSeason)) } + " per step")
    println("ecology: " + e.timings.entries.joinToString { (k, ns) -> "%s %.0f ms".format(k, ns / 1e6 / (years * 4 * stepsPerSeason)) } + " per step")
    sim.log.filter { "wolf" !in it }.takeLast(10).forEach(::println)
}
