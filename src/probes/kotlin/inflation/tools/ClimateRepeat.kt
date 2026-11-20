package inflation.tools

import inflation.world.Climate
import inflation.world.LandformCache
import inflation.world.World
import inflation.world.WorldConfig
import java.util.zip.CRC32

/**
 * Whether world-gen's climate comes out the same every time: generates the world [--times] times in this JVM (landform
 * from its cache, climate always made afresh) and prints a CRC of the climate's arrays each time, and the first cell
 * and month where a later one differs from the first. Run it in several JVMs to compare across runs too.
 *
 * Usage: climate-repeat [--seed N] [--width KM] [--height KM] [--times N]
 */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "climate-repeat [--seed N] [--width KM] [--height KM] [--times N]")
    val cfg = a.world(WorldConfig(seed = 1)); val times = a.int("times", 3); a.done()
    val land = LandformCache.load(cfg) {}
    fun crc(c: Climate): Long {
        val x = CRC32()
        fun put(f: FloatArray) { val b = java.nio.ByteBuffer.allocate(4 * f.size); for (v in f) b.putFloat(v); x.update(b.array()) }
        c.monthlyTemp.forEach(::put); c.monthlyPrecip.forEach(::put); put(c.annualPrecip); c.monthlyPet.forEach(::put)
        put(c.annualPet); put(c.annualAet); put(c.runoff)
        return x.value
    }
    var first: Climate? = null
    repeat(times) { t ->
        val c = World.generate(cfg, land).climate
        val f = first
        val diff = if (f == null) "" else {
            var at = ""
            loop@ for (m in 0 until 12) for (i in c.monthlyPrecip[m].indices) if (c.monthlyPrecip[m][i] != f.monthlyPrecip[m][i]) {
                at = " first precip difference: month $m cell $i, ${f.monthlyPrecip[m][i]} vs ${c.monthlyPrecip[m][i]}"; break@loop }
            at
        }
        if (f == null) first = c
        println("climate ${t + 1}: crc %08x%s".format(crc(c), diff))
    }
}
