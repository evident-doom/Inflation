package inflation.tools

import inflation.world.Climate
import inflation.world.LandformCache
import inflation.world.Noise
import inflation.world.World
import inflation.world.WorldConfig
import inflation.world.blur

/** Probe (not for merge): rain by height and by place within the local relief, June-August and the year. */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "rain-geometry-probe [--seed N]")
    val cfg = a.world(WorldConfig(seed = 1)); a.done()
    val t0 = System.currentTimeMillis()
    val land = LandformCache.load(cfg) { System.err.println(it) }
    val g = land.grid; val elev = land.elevation
    val sea = World.seaMask(g, elev)
    val t1 = System.currentTimeMillis()
    val c = Climate.generate(g, cfg, elev, sea, Noise(cfg.seed))
    System.err.println("landform %.0f s, climate %.0f s".format((t1 - t0) / 1e3, (System.currentTimeMillis() - t1) / 1e3))
    val local = blur(g, FloatArray(g.size) { maxOf(0f, elev[it]) }, 7)
    val jja = FloatArray(g.size) { (c.monthlyPrecip[5][it] + c.monthlyPrecip[6][it] + c.monthlyPrecip[7][it]) / 3 }
    val landCells = (0 until g.size).filter { !sea[it] }
    fun row(name: String, cs: List<Int>) {
        if (cs.size < 200) return
        val p = cs.map { c.annualPrecip[it].toDouble() }.average(); val s = cs.map { jja[it].toDouble() }.average()
        val pet = cs.map { ((c.monthlyPet[5][it] + c.monthlyPet[6][it] + c.monthlyPet[7][it]) / 3).toDouble() }.average()
        val t = cs.map { c.monthlyTemp[6][it].toDouble() }.average()
        println("  %-28s n=%7d  year %5.0f mm  JJA %5.1f mm/month (%4.1f mm/wk)  JJA PET %4.0f  July %4.1f C".format(name, cs.size, p, s, s * 7 / 30, pet, t))
    }
    val bands = listOf(0f, 500f, 1000f, 1500f, 2500f, 9000f)
    for ((lo, hi) in listOf(42.0 to 99.0, 36.0 to 42.0, 0.0 to 36.0)) {
        println("latitude %.0f-%.0f".format(lo, minOf(hi, 47.0)))
        val inLat = landCells.filter { val l = c.latitude[g.y(it)]; l >= lo && l < hi }
        for (b in 0 until bands.size - 1) row("%4.0f-%4.0f m".format(bands[b], bands[b + 1]), inLat.filter { elev[it] >= bands[b] && elev[it] < bands[b + 1] })
        val high = inLat.filter { elev[it] > 800 }
        row(">800 m, ridge (+300 over 15km)", high.filter { elev[it] - local[it] > 300 })
        row(">800 m, valley (-300)", high.filter { elev[it] - local[it] < -300 })
    }
    println("June-August rain by 3° band, mm/month: 0-500 m | 1000-1500 m | 1500-2500 m | over 2500 m")
    var band = Math.floor(c.latitude.max() / 3) * 3
    while (band >= Math.floor(c.latitude.min() / 3) * 3) {
        val inBand = landCells.filter { val l = c.latitude[g.y(it)]; l >= band && l < band + 3 }
        fun m(lo: Float, hi: Float): String { val cs = inBand.filter { elev[it] >= lo && elev[it] < hi }; return if (cs.size < 200) "    -" else "%5.1f".format(cs.map { jja[it].toDouble() }.average()) }
        println("  %2.0f-%2.0f°N: %s | %s | %s | %s".format(band, band + 3, m(0f, 500f), m(1000f, 1500f), m(1500f, 2500f), m(2500f, 9000f)))
        band -= 3
    }
}
