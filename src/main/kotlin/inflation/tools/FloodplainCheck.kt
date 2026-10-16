package inflation.tools

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.RiverClass
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig

/**
 * Where the world has river plains: how much floodplain world generation makes, along which rivers, in what
 * climate and in what patches; then (with --years) whether those plains flood and stay moist through summer.
 *
 * Usage: floodplain-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1); var years = 0; var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val t0 = System.currentTimeMillis()
    val w = World.generate(cfg, inflation.world.LandformCache.load(cfg) {})
    println("seed ${cfg.seed} ${cfg.widthKm}x${cfg.heightKm}: world in ${(System.currentTimeMillis() - t0) / 1000} s")
    val g = w.grid; val c = w.climate; val fp = w.floodplain
    val land = (0 until g.size).filter { !w.isSea[it] && !w.water.isLake(it) }
    val plain = land.filter { fp.isFloodplain[it] }
    val km2 = g.cellKm * g.cellKm
    fun q(i: Int) = w.water.discharge[fp.riverOf[i]]
    val ariCls = listOf("<0.2" to 0.2f, "0.2-0.45" to 0.45f, "0.45-0.75" to 0.75f, ">=0.75" to 1e9f)
    fun ac(i: Int) = ariCls.indexOfFirst { c.aridity(i) < it.second }
    println("land %d km², floodplain %.0f km² (%.2f%% of land)".format(land.size, plain.size * km2, 100.0 * plain.size / land.size))
    val rc = listOf("stream 3-30" to 3f, "river 30-300" to 30f, "great 300+" to 300f)
    fun rcl(i: Int) = rc.indexOfLast { q(i) >= it.second }
    // Channel length by river class, so floodplain per km of river can be told.
    val chan = IntArray(rc.size); for (i in land) { val d = w.water.discharge[i]; val r = rc.indexOfLast { d >= it.second }; if (r >= 0) chan[r]++ }
    val big = land.filter { w.water.discharge[it] >= 100f }
    println("  largest river %.0f m³/s; channel ≥100 m³/s %d km, of it in aridity <0.75 %d km; ≥300 m³/s in aridity <0.75 %d km".format(
        land.maxOf { w.water.discharge[it] }, big.size, big.count { c.aridity(it) < 0.75f }, big.count { c.aridity(it) < 0.75f && w.water.discharge[it] >= 300f }))
    for ((r, x) in rc.withIndex()) {
        val ps = plain.filter { rcl(it) == r }
        println("  by river %-13s %7.0f km² floodplain, %6d km channel, %.2f km² per km".format(x.first, ps.size * km2, chan[r], ps.size * km2 / chan[r].coerceAtLeast(1)))
    }
    for ((a, x) in ariCls.withIndex()) {
        val all = land.count { ac(it) == a }; val ps = plain.filter { ac(it) == a }
        println("  aridity %-9s land %7d km², floodplain %6.0f km² (%.2f%% of that land); biomes: %s".format(x.first, all, ps.size * km2, 100.0 * ps.size / all.coerceAtLeast(1),
            ps.groupingBy { w.biome[it] }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "%s %.0f%%".format(it.key.name.lowercase(), 100.0 * it.value / ps.size) }))
    }
    val eb = listOf(0f, 200f, 500f, 1000f, 9999f)
    println("  by height: " + (0 until eb.size - 1).joinToString(", ") { b -> "%d-%d m %.0f%%".format(eb[b].toInt(), eb[b + 1].toInt(), 100.0 * plain.count { w.elevation[it] >= eb[b] && w.elevation[it] < eb[b + 1] } / plain.size) })
    // Patches: floodplain cells joined through neighbours.
    val patch = IntArray(g.size) { -1 }; val sizes = ArrayList<Int>(); val stack = IntArray(g.size)
    for (s in plain) {
        if (patch[s] >= 0) continue
        val id = sizes.size; var top = 0; stack[top++] = s; patch[s] = id; var count = 0
        while (top > 0) { val i = stack[--top]; count++; g.forNeighbours(i) { j, _ -> if (patch[j] < 0 && fp.isFloodplain[j] && !w.isSea[j] && !w.water.isLake(j)) { patch[j] = id; stack[top++] = j } } }
        sizes += count
    }
    val members = Array(sizes.size) { ArrayList<Int>() }; for (i in plain) members[patch[i]] += i
    val thresholds = listOf(10, 50, 200, 1000)
    println("  patches: %d; ".format(sizes.size) + thresholds.joinToString(", ") { t -> "≥%d km² %d (%.0f%% of floodplain)".format(t, sizes.count { it >= t }, 100.0 * sizes.filter { it >= t }.sum() / plain.size) })
    // The farming niche: plains in country too dry for crops on rain alone or marginal for them (aridity 0.2-0.75).
    val dryMembers = members.indices.filter { members[it].size >= 50 }.map { p ->
        val ms = members[p]; val ai = ms.map { c.aridity(it).toDouble() }.average()
        val lat = ms.map { c.latitude[g.y(it)] }.average(); val maxQ = ms.maxOf { q(it) }
        val ch = ms.map { fp.riverOf[it] }.toSet().size
        Triple(p, ai, "%5d km², river up to %5.0f m³/s, %4.1f km wide (area/channel), aridity %.2f, %.1f °N, mean %4.0f m, %s".format(ms.size, maxQ, ms.size.toDouble() / ch.coerceAtLeast(1), ai, lat,
            ms.map { w.elevation[it].toDouble() }.average(), ms.groupingBy { w.biome[it] }.eachCount().maxBy { it.value }.key.name.lowercase()))
    }
    val inDry = dryMembers.filter { it.second in 0.2..0.75 }
    println("  patches ≥50 km²: %d; in aridity 0.2-0.75: %d, together %d km²; under 0.2: %d".format(dryMembers.size, inDry.size, inDry.sumOf { members[it.first].size }, dryMembers.count { it.second < 0.2 }))
    for (t in dryMembers.sortedByDescending { members[it.first].size }.take(8)) println("    " + t.third)
    // By latitude, 3° bands.
    val lats = plain.groupBy { (c.latitude[g.y(it)] / 3).toInt() * 3 }.toSortedMap()
    println("  by latitude: " + lats.entries.joinToString(", ") { (l, ps) -> "%d-%d °N %.0f km² (%d in ai 0.2-0.75)".format(l, l + 3, ps.size * km2, ps.count { c.aridity(it) in 0.2f..0.75f }) })
    // Alluvium beyond the floodplain: valley fill, fans and basins (thick sediment), flat or not.
    val allu = land.filter { w.geology.rock[it] == inflation.world.Rock.ALLUVIUM }
    fun flat(i: Int): Boolean { var hi = 0f; g.forNeighbours(i) { j, d -> hi = maxOf(hi, kotlin.math.abs(w.elevation[j] - w.elevation[i]) / d.toFloat()) }; return hi < 0.003f }
    val flatAllu = allu.filter { flat(it) }
    println("  alluvium %d km² (%.1f%% of land), of it flat (<0.3%%) %d km²; flat alluvium in aridity 0.2-0.75: %d km², of it floodplain %d km²".format(allu.size, 100.0 * allu.size / land.size, flatAllu.size,
        flatAllu.count { c.aridity(it) in 0.2f..0.75f }, flatAllu.count { c.aridity(it) in 0.2f..0.75f && fp.isFloodplain[it] }))
    if (years <= 0) return

    // The plains through the seasons, on the hexes' places.
    val hw = HexWorld(w, HexMap(w)); val n = hw.placeCells.size
    val hl = HexLand(hw, cfg.seed); val weather = Weather(hw.weatherPlaces(cfg), cfg)
    val ps = (0 until n).filter { hl.floodShare[it] > 0f }
    val fpArea = DoubleArray(n) { (hl.floodShare[it] * hl.areaKm2[it]).toDouble() }
    val plainAi = FloatArray(n) { p -> hw.placeCells[p].map { c.aridity(it) }.average().toFloat() }
    val stepsY = Date.DAYS_PER_YEAR / cfg.daysPerStep
    for (y in 0 until years) {
        val floodWeeks = IntArray(n); val minWet = FloatArray(n) { 9f }; val dry = BooleanArray(n)
        val augGreenWet = FloatArray(n); val augGreen = FloatArray(n); var augN = 0
        repeat(stepsY) { s ->
            weather.step(); hl.step(weather)
            val month = s * 12 / stepsY
            for (p in ps) {
                if (hl.flood[p] > 0f) floodWeeks[p]++
                val wf = hl.wetFloor(p) / hl.floodShare[p]
                if (wf < minWet[p]) minWet[p] = wf
                if (hl.flow[hw.placeHex[p]] < HexLand.TRICKLE) dry[p] = true
                if (month == 7) { augGreenWet[p] += hl.greenWet[p]; augGreen[p] += hl.green[p] }
            }
            if (month == 7) augN++
        }
        val tot = ps.sumOf { fpArea[it] }
        fun share(f: (Int) -> Boolean) = ps.filter(f).sumOf { fpArea[it] } / tot
        println("== year %d: floodplain on places %.0f km²".format(y, tot))
        println("  flooded at least once %.2f; median weeks flooded %d; river below a trickle at some week %.3f".format(share { floodWeeks[it] > 0 },
            ps.sortedBy { floodWeeks[it] }.let { floodWeeks[it[it.size / 2]] }, share { dry[it] }))
        println("  moist through the driest week (share of the plain with roots on the water table): ≥0.5 %.2f, ≥0.2 %.2f, 0 %.2f".format(share { minWet[it] >= 0.5f }, share { minWet[it] >= 0.2f }, share { minWet[it] <= 0f }))
        for ((a, x) in ariCls.withIndex()) {
            val sub = ps.filter { ac0(plainAi[it], ariCls) == a }; if (sub.isEmpty()) continue
            val st = sub.sumOf { fpArea[it] }
            fun sh(f: (Int) -> Boolean) = sub.filter(f).sumOf { fpArea[it] } / st
            println("  aridity %-9s %6.0f km²: flooded %.2f, moist ≥0.5 in driest week %.2f, river ran dry %.3f; August green: wet floor %.2f, whole place %.2f".format(x.first, st,
                sh { floodWeeks[it] > 0 }, sh { minWet[it] >= 0.5f }, sh { dry[it] },
                sub.sumOf { augGreenWet[it] / augN * fpArea[it] } / st, sub.sumOf { augGreen[it] / augN * fpArea[it] } / st))
        }
    }
}

private fun ac0(a: Float, cls: List<Pair<String, Float>>) = cls.indexOfFirst { a < it.second }
