package inflation.ecology

import inflation.world.Biome
import java.io.File
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * The history of a hex world, week by week, for finding imbalances: every number worth watching as
 * a named series, recorded after each step.
 *
 * Series names are paths, "group/name", and the groups are:
 * - "animals": head counts of every resident kind and every kind in herds and packs;
 * - "biomass": thousand tonnes in each pool: plants, litter, soil organic matter, carrion, food
 *   stocks, small life, resident herds, herds and packs, fish;
 * - "small life" and "water life": the fields of each, in tonnes or head counts;
 * - "deaths/<kind>": deaths of that kind in the week by cause; "births": young born in herds and
 *   packs; "kills": animals killed by hunters, by prey kind;
 * - "weather": mean temperature, rain, snow lying, droughts under way;
 * - "biome/<biome>": for each kind of country, plants per km² and the head count of each kind.
 *
 * Counts of things that happen (deaths, births, kills, rain) add up over a month or year; states
 * (head counts, biomass, temperature) are averaged. The history can be written to and read from a
 * CSV file, so a long run in the check can be looked at in the viewer.
 */
class HexHistory {
    /** Each series' weekly values, in the order first recorded. */
    val series = LinkedHashMap<String, FloatList>()
    /** Year and month of each recorded week. */
    val year = FloatList(); val month = FloatList()
    val weeks get() = year.size

    /** A growable list of floats. */
    class FloatList {
        var data = FloatArray(64); private set
        var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
        fun pad(to: Int) { while (size < to) add(Float.NaN) }
    }

    /** Whether a series counts events in each week (summed over a period) rather than a state (averaged). */
    fun isFlow(key: String) = key.startsWith("deaths/") || key.startsWith("births/") || key.startsWith("kills/") || key.startsWith("fish flows/") ||
        key == "weather/rain mm"

    private fun put(key: String, v: Float) {
        val s = series.getOrPut(key) { FloatList().also { it.pad(weeks - 1) } }
        s.pad(weeks - 1)
        s.add(v)
    }

    /** Records the week just stepped. */
    fun record(sim: HexSim) = synchronized(this) { recordWeek(sim) }

    private fun recordWeek(sim: HexSim) {
        val fauna = sim.fauna; val groups = sim.groups; val land = sim.land; val hw = sim.hw; val w = sim.weather
        year.add(w.date.year.toFloat()); month.add(w.date.month.toFloat())
        val n = land.n
        val area = land.areaKm2
        // Animals.
        // (A kind in both forms, in the form-swap experiment, counts in both.)
        val animals = LinkedHashMap<String, Double>()
        for ((k, s) in fauna.kinds.withIndex()) animals.merge(s.name, fauna.count(k), Double::plus)
        for (s in groups.kinds) animals.merge(s.name, groups.count(s).toDouble(), Double::plus)
        for ((name, v) in animals) put("animals/$name", v.toFloat())
        // How well fed each plant-eating kind was this week, by heads (residents by place, herds by group), and the young born to residents.
        val fedSum = HashMap<String, DoubleArray>()
        for ((k, s) in fauna.kinds.withIndex()) if (!s.carnivore) {
            val a = fedSum.getOrPut(s.name) { DoubleArray(2) }
            for (p in 0 until land.n) { val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); a[0] += h * fauna.fed[k][p]; a[1] += h }
        }
        for (g in groups.groups) if (g.alive && !g.species.carnivore) fedSum.getOrPut(g.species.name) { DoubleArray(2) }.let { it[0] += g.size * g.fed.toDouble(); it[1] += g.size.toDouble() }
        for ((name, a) in fedSum) if (a[1] > 0.0) put("fed/$name", (a[0] / a[1]).toFloat())
        // Migration: for each migratory kind, the share of its heads above 1000 m, their mean height, and how far
        // its herds stand from where each stood half a year before (km, by heads; herds younger than that left out).
        recordMigration(sim)
        // Biomass, thousand tonnes.
        fun perM2(a: FloatArray) = a.indices.sumOf { (a[it] * area[it]).toDouble() }.toFloat()
        fun perKm2(a: FloatArray) = perM2(a) / 1e6f
        val sl = fauna.small
        put("biomass/herbs", perM2(land.plants.herb)); put("biomass/shrubs", perM2(land.plants.shrub)); put("biomass/trees", perM2(land.plants.tree))
        put("biomass/litter", perM2(sl.litter)); put("biomass/soil organic matter", perM2(sl.organicMatter))
        put("biomass/carrion", perM2(sl.carrion)); put("biomass/bones", perM2(sl.bones))
        put("biomass/fruit", perM2(fauna.food.fruit)); put("biomass/roots and mast", perM2(fauna.food.roots)); put("biomass/straw", perM2(fauna.food.straw))
        put("biomass/small animals", listOf(sl.insects, sl.dormantInsects, sl.rodents, sl.herps, sl.pollinators, sl.dormantPollinators, sl.hares,
            sl.hyrax, sl.tortoises, sl.foxes, sl.birds).sumOf { perKm2(it).toDouble() }.toFloat())
        put("biomass/resident herds", fauna.kinds.indices.sumOf { fauna.count(it) * fauna.kinds[it].massKg }.toFloat() / 1e6f)
        put("biomass/herds and packs", groups.groups.filter { it.alive }.sumOf { (it.adults + 0.3 * it.young) * it.species.massKg }.toFloat() / 1e6f)
        val wl = fauna.water
        put("biomass/sea and lake fish", wl.fishTonnes() / 1000f)
        // Small life, tonnes.
        for ((name, f) in listOf("insects" to sl.insects, "insects waiting as eggs and larvae" to sl.dormantInsects, "biting insects" to sl.biting,
            "biting insects waiting as eggs and larvae" to sl.dormantBiting, "rodents" to sl.rodents, "reptiles and amphibians" to sl.herps,
            "pollinators" to sl.pollinators, "pollinators waiting as eggs and larvae" to sl.dormantPollinators, "hares" to sl.hares, "hyrax" to sl.hyrax,
            "tortoises" to sl.tortoises, "foxes and wildcats" to sl.foxes, "wild birds" to sl.birds))
            put("small life/$name", perKm2(f) * 1000f)
        put("small life/eagle pairs", sl.eagles.indices.sumOf { (sl.eagles[it] * area[it]).toDouble() }.toFloat() / HexSmallLife.EAGLE_KM2.toFloat())
        put("small life/vultures", sl.vultures.sum())
        // Water life.
        for ((k, a) in HexWater.SEA.withIndex()) put("water life/${a.name}", wl.seaHeads(k))
        put("water life/fish, tonnes", wl.fishTonnes()); put("water life/river fish, tonnes", wl.riverFishTonnes())
        put("water life/shellfish, tonnes", wl.shellfishTonnes()); put("water life/sea birds", wl.colonyBirds.sum())
        put("water life/zooplankton, g per m²", wl.zooMean())
        for ((key, v) in wl.fishFlows) if (!key.startsWith("hungry")) put("fish flows/$key, t", v.toFloat())
        // Deaths, births and kills this week.
        for ((key, v) in fauna.deaths) put("deaths/${key.substringBefore(":")}/${key.substringAfter(":")}", v.toFloat())
        for ((key, v) in groups.stats.deaths) put("deaths/${key.substringBefore(":")}/${key.substringAfter(":")}", v.toFloat())
        for ((key, v) in groups.stats.kills) put("deaths/$key/hunted", v.toFloat())
        val births = HashMap<String, Double>()
        for ((key, v) in groups.stats.births) births.merge(key, v.toDouble(), Double::plus)
        for ((k, s) in fauna.kinds.withIndex()) if (fauna.bornThisWeek[k] > 0.0) births.merge(s.name, fauna.bornThisWeek[k], Double::plus)
        for ((key, v) in births) put("births/$key", v.toFloat())
        for ((key, v) in groups.stats.kills) put("kills/$key", v.toFloat())
        // Weather, means over the land.
        val total = area.sum()
        fun mean(f: (Int) -> Float) = (0 until n).sumOf { (f(it) * area[it]).toDouble() }.toFloat() / total
        put("weather/temperature °C", mean { w.temperature[it] })
        put("weather/rain mm", mean { w.precipitation[it] })
        put("weather/snow lying mm", mean { w.snowpack[it] })
        put("weather/droughts under way", w.droughtCount().toFloat())
        put("weather/soil water mm", mean { land.hydro.soil[it] })
        // By kind of country: its area, plants per km² and head counts (and the whole land's area, to set worlds of other sizes beside each other).
        val biomeArea = DoubleArray(Biome.entries.size)
        for (p in 0 until n) biomeArea[biomeOf(sim, p)] += area[p].toDouble()
        put("land/area km²", biomeArea.sum().toFloat())
        val plants = DoubleArray(Biome.entries.size)
        for (p in 0 until n) plants[biomeOf(sim, p)] += (land.plants.biomass(p) * area[p]).toDouble()
        val heads = HashMap<String, DoubleArray>()
        for ((k, s) in fauna.kinds.withIndex()) {
            val a = heads.getOrPut(s.name) { DoubleArray(Biome.entries.size) }
            for (p in 0 until n) a[biomeOf(sim, p)] += ((fauna.adults[k][p] + fauna.young[k][p]) * area[p]).toDouble()
        }
        for (g in groups.groups) if (g.alive) heads.getOrPut(g.species.name) { DoubleArray(Biome.entries.size) }[biomeOf(sim, g.place.i)] += g.size.toDouble()
        for (b in Biome.entries) {
            if (biomeArea[b.ordinal] <= 0.0) continue
            val name = b.name.lowercase().replace('_', ' ')
            put("biome/$name/area km²", biomeArea[b.ordinal].toFloat())
            put("biome/$name/plants kg per m²", (plants[b.ordinal] / biomeArea[b.ordinal]).toFloat())
            for ((kind, a) in heads) put("biome/$name/$kind", a[b.ordinal].toFloat())
        }
        // Keep every series as long as the record.
        for (s in series.values) s.pad(weeks)
    }

    /** Each herd's place over the last half year, week by week (a ring), for [recordMigration]. */
    private val pastPlaces = HashMap<Int, IntArray>()
    private var migrationWeek = 0

    private fun recordMigration(sim: HexSim) {
        val hw = sim.hw; val hex = sim.hex
        val slot = migrationWeek % HALF_YEAR_WEEKS
        val full = migrationWeek >= HALF_YEAR_WEEKS
        val heads = HashMap<Species, DoubleArray>()
        val alive = HashSet<Int>()
        for (g in sim.groups.groups) {
            if (!g.alive || g.species.carnivore) continue
            alive += g.id
            // heads, heads above 1000 m, heads x height, heads with a place half a year back, heads x km from it
            val a = heads.getOrPut(g.species) { DoubleArray(5) }
            val n = g.size.toDouble(); val m = hw.placeElevationM[g.place]
            a[0] += n; if (m > 1000f) a[1] += n; a[2] += n * m
            val ring = pastPlaces.getOrPut(g.id) { IntArray(HALF_YEAR_WEEKS) { -1 } }
            val then = ring[slot]
            if (full && then >= 0) { a[3] += n; a[4] += n * hex.distanceKm(hw.placeHex[then], hw.placeHex[g.place]) }
            ring[slot] = g.place.i
        }
        pastPlaces.keys.retainAll(alive)
        migrationWeek++
        for (s in sim.groups.kinds) {
            if (s.carnivore) continue
            val a = heads[s] ?: DoubleArray(5)
            put("migration/${s.name}/share above 1000 m", if (a[0] > 0) (a[1] / a[0]).toFloat() else Float.NaN)
            put("migration/${s.name}/mean height m", if (a[0] > 0) (a[2] / a[0]).toFloat() else Float.NaN)
            put("migration/${s.name}/km from half a year before", if (a[3] > 0) (a[4] / a[3]).toFloat() else Float.NaN)
        }
    }

    private var biomes: IntArray? = null
    /** The kind of country most of place [p] is. */
    private fun biomeOf(sim: HexSim, p: Int): Int {
        val b = biomes ?: IntArray(sim.land.n) { q ->
            val counts = IntArray(Biome.entries.size)
            for (c in sim.hw.placeCells[q]) counts[sim.world.biome[c].ordinal]++
            counts.indices.maxBy { counts[it] }
        }.also { biomes = it }
        return b[p]
    }

    /** How the weeks are grouped: by week, month or year. */
    enum class Period(val label: String) { WEEK("week"), MONTH("month"), YEAR("year") }

    /** The values of [key] grouped by [period]: x (in years from the start) and y. */
    fun values(key: String, period: Period): Pair<FloatArray, FloatArray> = synchronized(this) { valuesOf(key, period) }

    private fun valuesOf(key: String, period: Period): Pair<FloatArray, FloatArray> {
        val s = series[key] ?: return FloatArray(0) to FloatArray(0)
        val flow = isFlow(key)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var i = 0
        while (i < weeks) {
            var j = i + 1
            while (j < weeks && when (period) {
                    Period.WEEK -> false
                    Period.MONTH -> year[j] == year[i] && month[j] == month[i]
                    Period.YEAR -> year[j] == year[i]
                }) j++
            var sum = 0.0; var c = 0
            for (k in i until j) { val v = s[k]; if (!v.isNaN()) { sum += v; c++ } }
            // A last month or year not yet over would read as a sudden fall in the counts: left out.
            val full = when (period) { Period.WEEK -> 1; Period.MONTH -> 4; Period.YEAR -> 50 }
            if (j == weeks && j - i < full) break
            xs += (year[i] - year[0]) + month[i] / 12f + (if (period == Period.WEEK) (i - firstOfMonth(i)) / 60f else 0f)
            ys += if (c == 0) Float.NaN else if (flow) sum.toFloat() else (sum / c).toFloat()
            i = j
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun firstOfMonth(i: Int): Int { var k = i; while (k > 0 && month[k - 1] == month[i] && year[k - 1] == year[i]) k--; return k }

    /** The names of all series, as recorded so far. */
    fun keys(): List<String> = synchronized(this) { series.keys.toList() }

    fun writeCsv(file: File) = synchronized(this) {
        file.bufferedWriter().use { out ->
            out.write("year,month," + series.keys.joinToString(",") { "\"$it\"" }); out.newLine()
            val cols = series.values.toList()
            for (i in 0 until weeks) {
                out.write("${year[i].toInt()},${month[i].toInt()}")
                for (c in cols) { out.write(","); val v = c[i]; if (!v.isNaN()) out.write(v.toString()) }
                out.newLine()
            }
        }
    }

    companion object {
        /** Half a year in weeks (steps). */
        val HALF_YEAR_WEEKS = Date.DAYS_PER_YEAR / WorldConfig().daysPerStep / 2

        fun readCsv(file: File): HexHistory {
            val h = HexHistory()
            file.bufferedReader().use { r ->
                val header = splitCsv(r.readLine())
                val keys = header.drop(2)
                val lists = keys.map { k -> HexHistory.FloatList().also { h.series[k] = it } }
                while (true) {
                    val line = r.readLine() ?: break
                    val f = line.split(',')
                    h.year.add(f[0].toFloat()); h.month.add(f[1].toFloat())
                    for ((i, l) in lists.withIndex()) l.add(f.getOrNull(i + 2)?.takeIf { it.isNotEmpty() }?.toFloat() ?: Float.NaN)
                }
            }
            return h
        }

        private fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>(); val b = StringBuilder(); var q = false
            for (ch in line) when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += b.toString(); b.clear() }
                else -> b.append(ch)
            }
            out += b.toString()
            return out
        }
    }
}
