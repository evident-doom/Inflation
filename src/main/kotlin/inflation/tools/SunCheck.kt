package inflation.tools

import inflation.world.*

/** Temporary: slopes and sun ratios on high ground. */
fun main() {
    val cfg = WorldConfig(seed = 1, widthKm = 4000, heightKm = 1000)
    val world = inflation.ecology.HexSnapshot.WorldCache.load(cfg) { }
    val hw = HexWorld(world, HexMap(world))
    val s = hw.sunlight
    val hi = (0 until hw.placeCells.size).filter { hw.placeElevationM[it] > 1500 }
    val slopes = hi.flatMap { p -> hw.placeCells[p].map { world.terrain.slope[it].toDouble() } }.sorted()
    println("terrain.slope over 1500 m: median %.3f p90 %.3f".format(slopes[slopes.size / 2], slopes[slopes.size * 9 / 10]))
    for (m in listOf(2, 4, 6)) println("month $m: sunny %.2f shaded %.2f".format(hi.map { s.ratio[0][m][it].toDouble() }.average(), hi.map { s.ratio[1][m][it].toDouble() }.average()))
}
