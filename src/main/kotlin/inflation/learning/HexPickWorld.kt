package inflation.learning

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Picks small worlds that push herds to migrate: the ones where the land that feeds them in one
 * season starves them in another, and better land lies within a few days' walk. For each land
 * sample (every [STEP_KM] km) and month, forage is how wet the month is against the evaporation it
 * could bear (rain over potential evaporation, at most 1), and nothing while the mean is under 5 °C;
 * the pull to move is how much more forage the best place within [REACH_KM] has than here, averaged
 * over the land and the year. Also reports the seasonal swing in place.
 *
 * Usage: hex-pick-world [--seeds 1-40] [--size KM] [--lats 30,36,42]
 */
object HexPickWorld {
    const val STEP_KM = 10
    const val REACH_KM = 100

    @JvmStatic
    fun main(args: Array<String>) {
        var seeds = (1L..40L).toList(); var size = 600; var lats = listOf(30.0, 36.0, 42.0)
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--seeds" -> seeds = args[++k].split("-").let { (a, b) -> (a.toLong()..b.toLong()).toList() }
                "--size" -> size = args[++k].toInt()
                "--lats" -> lats = args[++k].split(",").map { it.toDouble() }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        val jobs = seeds.flatMap { s -> lats.map { s to it } }
        val results = jobs.parallelStream().map { (seed, lat) ->
            val w = World.generate(WorldConfig(seed = seed, widthKm = size, heightKm = size, centreLatitude = lat))
            Triple(seed, lat, score(w))
        }.toList().sortedByDescending { it.third.first }
        println("seed  lat   pull  swing  land")
        for ((seed, lat, s) in results.take(15)) println("%4d  %4.0f  %.3f  %.3f  %.0f%%".format(seed, lat, s.first, s.second, 100 * s.third))
    }

    /** (pull to move, seasonal swing in place, share of land) for [w]. */
    fun score(w: World): Triple<Double, Double, Double> {
        val g = w.grid
        val step = max(1, (STEP_KM / g.cellKm).toInt())
        val xs = (0 until g.width step step).toList(); val ys = (0 until g.height step step).toList()
        val land = ArrayList<Int>()
        for (y in ys) for (x in xs) { val i = g.index(x, y); if (!w.isSea[i]) land += i }
        if (land.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val forage = Array(12) { m -> FloatArray(land.size) { j -> val i = land[j]
            if (w.climate.monthlyTemp[m][i] < 5f) 0f else min(1f, w.climate.monthlyPrecip[m][i] / max(1f, w.climate.monthlyPet[m][i])) } }
        val r = REACH_KM / g.cellKm
        val near = Array(land.size) { a -> val ax = g.x(land[a]); val ay = g.y(land[a])
            land.indices.filter { b -> val dx = g.x(land[b]) - ax; val dy = g.y(land[b]) - ay; dx * dx + dy * dy <= r * r }.toIntArray() }
        var pull = 0.0; var swing = 0.0
        for (j in land.indices) {
            var lo = 1f; var hi = 0f
            for (m in 0 until 12) {
                val here = forage[m][j]; lo = min(lo, here); hi = max(hi, here)
                var best = here; for (b in near[j]) best = max(best, forage[m][b])
                pull += (best - here) / 12.0
            }
            swing += (hi - lo).toDouble()
        }
        return Triple(pull / land.size, swing / land.size, land.size.toDouble() / (xs.size * ys.size))
    }
}
