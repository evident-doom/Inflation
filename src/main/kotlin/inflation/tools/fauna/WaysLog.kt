package inflation.tools.fauna

import inflation.ecology.BandWays
import inflation.ecology.HexSim
import inflation.learning.Schema
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * waysLog=FILE: every band's week, as one row of the CSV FILE (gzipped if its name ends in .gz): the year, month and day,
 * the band's id and size, the numbers it knew of itself as the network reads them ([Schema.WAYS_IN]) and the numbers the
 * network set ([Schema.WAYS_OUT]: 0 is the scaffold's choice, and a way is 2 / (1 + exp(-output)) times it). It only
 * watches: the network is asked once, as it was, and its answer goes on to the band as it came.
 */
class WaysLog(private val sim: HexSim, file: File) {
    private val writer: BufferedWriter
    private val row = StringBuilder()

    init {
        val network = sim.groups.ways
        val stream = FileOutputStream(file).let { if (file.extension == "gz") GZIPOutputStream(it, 1 shl 16) else it }
        writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20)
        writer.write((listOf("year", "month", "day", "band", "size") + Schema.WAYS_IN.map { "in:$it" } + Schema.WAYS_OUT.map { "out:$it" })
            .joinToString(",") { "\"$it\"" } + "\n")
        if (network == null) System.err.println("waysLog: no ways network is set (ways=FILE), so no band's ways are set by one: nothing will be logged")
        else sim.groups.ways = BandWays { g, inputs, out ->
            network.choose(g, inputs, out)
            write(g.id, g.size, inputs, out)
        }
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    @Synchronized private fun write(id: Int, size: Int, inputs: FloatArray, out: FloatArray) {
        val date = sim.weather.date
        row.setLength(0)
        row.append(date.year).append(',').append(date.month + 1).append(',').append(date.dayOfMonth).append(',').append(id).append(',').append(size)
        for (x in inputs) row.append(',').append(x)
        for (x in out) row.append(',').append(x)
        row.append('\n')
        writer.append(row)
    }

    @Synchronized private fun close() { writer.close() }
}
