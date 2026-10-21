package inflation.tools.fauna

import inflation.ecology.BandWays
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.learning.Schema
import inflation.world.Biome
import inflation.world.WorldConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * waysLog=FILE: every band's week, as one row of the CSV FILE (gzipped if its name ends in .gz): the year, month and day,
 * the band's id and size; where it stands (see [PLACE_COLUMNS]); the numbers it knew of itself as the network reads them
 * ([Schema.WAYS_IN]) and the numbers the network set ([Schema.WAYS_OUT]: 0 is the scaffold's choice, and a way is
 * 2 / (1 + exp(-output)) times it). It only watches: the network is asked once, as it was, its answer goes on to the band as it
 * came, and the world is only read.
 */
class WaysLog(private val sim: HexSim, file: File) {
    private val writer: BufferedWriter
    private val row = StringBuilder()
    private val hex = sim.hex
    private val hw = sim.hw
    /** Km from each hex to the nearest hex a river of at least [RIVER_FLOW] m³/s runs through, and to the nearest sea hex (NaN where there is none). */
    private val riverKm = kmFrom { h -> sim.land.hydro.meanFlow[h] >= RIVER_FLOW }
    private val coastKm = kmFrom { h -> hw.sea[h] > SEA_HEX }

    init {
        val network = sim.groups.ways
        val stream = FileOutputStream(file).let { if (file.extension == "gz") GZIPOutputStream(it, 1 shl 16) else it }
        writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20)
        writer.write((listOf("year", "month", "day", "band", "size") + PLACE_COLUMNS + Schema.WAYS_IN.map { "in:$it" } + Schema.WAYS_OUT.map { "out:$it" })
            .joinToString(",") { "\"$it\"" } + "\n")
        if (network == null) System.err.println("waysLog: no ways network is set (ways=FILE), so no band's ways are set by one: nothing will be logged")
        else sim.groups.ways = BandWays { g, inputs, out ->
            network.choose(g, inputs, out)
            write(g, inputs, out)
        }
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    /** The distance in km from each hex to the nearest hex that [isSource], by steps of hexes (the sea and lakes counted as any ground). */
    private fun kmFrom(isSource: (Int) -> Boolean): FloatArray {
        val steps = IntArray(hex.size) { -1 }
        val queue = ArrayDeque<Int>()
        for (h in 0 until hex.size) if (isSource(h)) { steps[h] = 0; queue.addLast(h) }
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && steps[q] < 0) { steps[q] = steps[h] + 1; queue.addLast(q) } }
        }
        return FloatArray(hex.size) { if (steps[it] < 0) Float.NaN else (steps[it] * hex.hexKm).toFloat() }
    }

    @Synchronized private fun write(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val date = sim.weather.date
        row.setLength(0)
        row.append(date.year).append(',').append(date.month + 1).append(',').append(date.dayOfMonth).append(',').append(g.id).append(',').append(g.size)
        // Where it stands now, after this week's move, and the hex it set out from ([HexGroup.path]: the hexes it passed this week, its own last).
        val place = g.place.i
        val here = hw.placeHex[place]
        val from = g.path.firstOrNull() ?: here
        val (x, y) = hex.centreKm(here)
        row.append(',').append(here).append(',').append(place).append(',').append(hw.placeBand[place]).append(',').append(x).append(',').append(y)
            .append(',').append(sim.cfg.northLatitude - y / WorldConfig.KM_PER_DEGREE).append(',').append(hw.placeElevationM[place])
            .append(',').append(Biome.entries[hw.placeBiome[place]].name.lowercase())
            .append(',').append(sim.land.hydro.meanFlow[here]).append(',').append(riverKm[here]).append(',').append(coastKm[here])
            .append(',').append(if (from != here) 1 else 0).append(',').append(from).append(',').append(if (from != here) hex.distanceKm(from, here) else 0.0)
        for (v in inputs) row.append(',').append(v)
        for (v in out) row.append(',').append(v)
        row.append('\n')
        writer.append(row)
    }

    @Synchronized private fun close() { writer.close() }

    companion object {
        /**
         * The columns after the band's size: the hex it stands in and its place (the hex's low or high ground, [inflation.world.HexWorld.placeBand]:
         * 0 low, 1 high), the high-ground flag, the hex centre's x and y (km from the north-west corner) and latitude (degrees), the place's mean
         * elevation (m) and main kind of country, the mean flow of the river in the hex (m³/s, 0 where none), the km to the nearest hex with a river of
         * at least [RIVER_FLOW] and to the nearest sea hex, whether it moved to another hex this week, the hex it set out from and the km between hex centres.
         */
        val PLACE_COLUMNS = listOf("hex", "place", "high_ground", "x_km", "y_km", "lat", "elev_m", "biome", "river_flow_m3s", "km_to_river", "km_to_coast",
            "moved", "from_hex", "km_moved")
        /** A river of at least this mean flow, m³/s, counts for the distance to the nearest river. */
        const val RIVER_FLOW = 10f
        /** A hex that is more than this share sea counts as sea for the distance to the coast. */
        const val SEA_HEX = 0.3f
    }
}
