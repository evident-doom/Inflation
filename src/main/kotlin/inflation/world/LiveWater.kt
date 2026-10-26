package inflation.world

import kotlin.math.max
import kotlin.math.min

/** A dam across the river at [cell], [heightM] tall. Water backs up behind it into a reservoir. */
class Dam(val cell: Int, val heightM: Float) {
    internal var cells = IntArray(0)
    internal var levels = FloatArray(0)
    internal var capacity = FloatArray(0)
    /** Water held behind the dam, m^3. */
    var storage = 0f; internal set
    /** Current water surface elevation, m. */
    var level = 0f; internal set
    val capacityM3: Float get() = if (capacity.isEmpty()) 0f else capacity.last()
    /** Long-term mean inflow per step, m^3. */
    internal var meanInflowM3 = 0f
}

/**
 * A gravity canal taking [fraction] of the flow at [from] along [path] (cells in order, ending
 * where the water rejoins the drainage). Turning a river is a canal with fraction 1.
 */
class Diversion(val from: Int, val path: IntArray, val fraction: Float)

/** Irrigation: up to [mmPerStep] of water drawn from the river at [source] onto each of [fields]. */
class Irrigation(val source: Int, val fields: IntArray, val mmPerStep: Float)

/**
 * Rivers and floods as the weather changes. Each land cell has a soil bucket and a groundwater
 * store. Rain and snowmelt wet the soil; plants and the sun take water back at a rate that falls
 * as the soil dries; water beyond the soil's capacity runs off, part of it at once and part
 * through groundwater, which feeds rivers slowly between rains. Runoff is routed down the river
 * network every step.
 *
 * When a river carries more than bankfull flow it overtops its banks and floods the land that
 * lies within the flood stage of it (see [Floodplain]).
 *
 * People can change the network: dams store water in reservoirs and release it steadily, canals
 * divert part or all of a river elsewhere, and irrigation draws water onto fields. Works are
 * checked for physical sense (canals run downhill and cannot loop back on themselves).
 *
 * Water reaches the river mouth within one step; real flood waves take days to travel, so peaks
 * on the largest rivers come a little early and sharp.
 */
class LiveWater(private val world: World) {
    private val grid = world.grid
    private val n = grid.size
    private val days = world.config.daysPerStep
    private val stepSeconds = days * 86_400.0
    private val cellArea = grid.cellAreaM2.toFloat()

    /** Plant-available water in the soil, mm. */
    val soil = FloatArray(n)
    /** Groundwater, mm. */
    val groundwater = FloatArray(n)
    /** River discharge during the last step, m^3/s, including canal flow. */
    val discharge = FloatArray(n)
    /** Flood water depth on land during the last step, m. */
    val flood = FloatArray(n)
    /** Under a reservoir. */
    val submerged = BooleanArray(n)

    val dams = ArrayList<Dam>()
    val diversions = ArrayList<Diversion>()
    val irrigation = ArrayList<Irrigation>()

    private val receiver = world.water.drainage.receiver.copyOf()
    private var order = world.water.drainage.stack.copyOf()
    private val volume = FloatArray(n)
    private val pendingIrrigation = FloatArray(n)

    init {
        // Start near the long-term state: moist soil, groundwater at its mean level.
        val stepsPerYear = Date.stepsPerYear(days)
        for (i in 0 until n) {
            if (world.isSea[i]) continue
            soil[i] = SOIL_CAPACITY * 0.6f
            groundwater[i] = RECHARGE * world.climate.runoff[i] / stepsPerYear / BASEFLOW
        }
        System.arraycopy(world.water.discharge, 0, discharge, 0, n)
    }

    /** Advances one step using the weather that just happened in [weather]. */
    fun step(weather: Weather) {
        val pet = world.climate.monthlyPet[weather.lastMonth]
        val share = days.toFloat() / Date.DAYS_PER_MONTH
        val water = world.water
        for (i in 0 until n) {
            if (world.isSea[i]) { volume[i] = 0f; continue }
            val rain = weather.rain(i)
            val input = rain + weather.meltwater[i]
            if (water.isLake(i) || submerged[i]) {
                volume[i] = (input - 1.1f * pet[i] * share) / 1000f * cellArea
                continue
            }
            var s = soil[i] + input + pendingIrrigation[i]
            pendingIrrigation[i] = 0f
            val et = min(s, pet[i] * share * min(1f, s / SOIL_CAPACITY))
            s -= et
            val excess = max(0f, s - SOIL_CAPACITY)
            soil[i] = s - excess
            val base = groundwater[i] * BASEFLOW
            groundwater[i] += excess * RECHARGE - base
            volume[i] = (excess * (1 - RECHARGE) + base) / 1000f * cellArea
        }
        route(pet, share)

        // Floods: river stage above bankfull, spread over land lower than that stage.
        val fp = world.floodplain
        for (i in 0 until n) {
            val r = fp.riverOf[i]
            flood[i] = if (r < 0 || submerged[i] || water.discharge[r] < RiverClass.STREAM.minDischarge) 0f
                else max(0f, Channel.floodStageM(discharge[r], water.discharge[r]) - fp.hand[i])
        }
    }

    private val damAt = HashMap<Int, Dam>()
    private val diversionsAt = HashMap<Int, MutableList<Diversion>>()
    private val irrigationAt = HashMap<Int, MutableList<Irrigation>>()

    private val canalVolume = FloatArray(n)

    private fun route(pet: FloatArray, share: Float) {
        canalVolume.fill(0f)
        for (k in n - 1 downTo 0) {
            val i = order[k]
            var v = volume[i]
            damAt[i]?.let { v = operate(it, v, pet, share) }
            irrigationAt[i]?.forEach { v -= withdraw(it, max(v, 0f)) }
            diversionsAt[i]?.forEach { d ->
                val taken = max(v, 0f) * d.fraction
                v -= taken
                for (k2 in 0 until d.path.size - 1) canalVolume[d.path[k2]] += taken
                volume[d.path.last()] += taken
            }
            volume[i] = v
            val r = receiver[i]
            if (r != i) volume[r] += max(v, 0f)
        }
        for (i in 0 until n) {
            discharge[i] = if (world.isSea[i]) 0f else ((max(volume[i], 0f) + canalVolume[i]) / stepSeconds).toFloat()
        }
    }

    /** Reservoir: holds water up to capacity, releases a steady share, spills the excess. */
    private fun operate(dam: Dam, inflow: Float, pet: FloatArray, share: Float): Float {
        val surface = dam.cells.count { submerged[it] }
        val evaporation = surface * 1.1f * pet[dam.cell] * share / 1000f * cellArea
        dam.storage = max(0f, dam.storage + inflow - evaporation)
        // Release about the long-term mean inflow, so the reservoir fills in wet seasons and
        // keeps the river running in dry ones.
        val steady = min(dam.storage, dam.meanInflowM3)
        val spill = max(0f, dam.storage - steady - dam.capacityM3)
        dam.storage -= steady + spill
        setLevel(dam)
        return steady + spill
    }

    private fun setLevel(dam: Dam) {
        var k = 0
        while (k < dam.capacity.size && dam.capacity[k] <= dam.storage) k++
        dam.level = if (k == 0) world.elevation[dam.cell] else dam.levels[k - 1]
        for ((idx, c) in dam.cells.withIndex()) submerged[c] = idx < k
    }

    private fun withdraw(w: Irrigation, available: Float): Float {
        val want = w.fields.size * w.mmPerStep / 1000f * cellArea
        val taken = min(want, available * 0.8f) // some water must stay in the river
        val perField = taken / w.fields.size / cellArea * 1000f
        for (f in w.fields) pendingIrrigation[f] += perField
        return taken
    }

    /**
     * Builds a dam. The reservoir floods the cells draining through the dam site that lie below
     * the crest. Returns the dam, or null if the site is not on land.
     */
    fun addDam(cell: Int, heightM: Float): Dam? {
        if (world.isSea[cell] || world.water.isLake(cell)) return null
        val dam = Dam(cell, heightM)
        dam.meanInflowM3 = (world.water.discharge[cell] * stepSeconds).toFloat()
        val crest = world.elevation[cell] + heightM
        // Cells upstream of the dam (walking donors) that are below the crest.
        val donors = donorLists()
        val found = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(cell)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            for (d in donors[i] ?: continue) if (world.elevation[d] < crest && !world.isSea[d]) { found += d; stack.add(d) }
        }
        val sorted = found.sortedBy { world.elevation[it] }
        dam.cells = sorted.toIntArray()
        dam.levels = FloatArray(sorted.size)
        dam.capacity = FloatArray(sorted.size)
        // Filling to the k-th cell's level submerges cells 0..k; capacity is the volume below that level.
        var sumElev = 0.0
        for ((k, c) in sorted.withIndex()) {
            sumElev += world.elevation[c]
            val nextLevel = if (k + 1 < sorted.size) world.elevation[sorted[k + 1]] else crest
            dam.levels[k] = nextLevel
            dam.capacity[k] = ((nextLevel * (k + 1) - sumElev) * cellArea).toFloat()
        }
        dams += dam
        damAt[cell] = dam
        return dam
    }

    fun removeDam(dam: Dam) {
        dams -= dam; damAt.remove(dam.cell)
        for (c in dam.cells) submerged[c] = false
    }

    /**
     * Digs a canal. The path must run between neighbouring cells, its end must lie lower than its
     * start, and the water must not be able to flow back to where it was taken. Returns null if not.
     */
    fun addDiversion(from: Int, path: IntArray, fraction: Float): Diversion? {
        if (path.isEmpty() || fraction <= 0f) return null
        val e = world.elevation
        if (e[path.last()] >= e[from]) return null
        var prev = from
        for (c in path) { if (!adjacent(prev, c)) return null; prev = c }
        // A loop would form if the canal's outflow drains back through its intake.
        var i = path.last()
        while (true) { if (i == from) return null; val r = receiver[i]; if (r == i) break; i = r }
        val d = Diversion(from, path, fraction.coerceIn(0f, 1f))
        diversions += d
        diversionsAt.getOrPut(from) { ArrayList() } += d
        reorder()
        return d
    }

    fun removeDiversion(d: Diversion) {
        diversions -= d; diversionsAt[d.from]?.remove(d)
        reorder()
    }

    fun addIrrigation(source: Int, fields: IntArray, mmPerStep: Float): Irrigation {
        val w = Irrigation(source, fields, mmPerStep)
        irrigation += w
        irrigationAt.getOrPut(source) { ArrayList() } += w
        return w
    }

    fun removeIrrigation(w: Irrigation) { irrigation -= w; irrigationAt[w.source]?.remove(w) }

    /**
     * Permanently sends the flow of [cell] to the neighbouring [newReceiver], as when a river is
     * dammed with a cut or breaks its banks. Refused if it would make water flow in a circle.
     */
    fun reroute(cell: Int, newReceiver: Int): Boolean {
        if (!adjacent(cell, newReceiver)) return false
        var i = newReceiver
        while (true) { if (i == cell) return false; val r = receiver[i]; if (r == i) break; i = r }
        receiver[cell] = newReceiver
        reorder()
        return true
    }

    private fun adjacent(a: Int, b: Int): Boolean {
        val dx = grid.x(a) - grid.x(b); val dy = grid.y(a) - grid.y(b)
        return a != b && dx in -1..1 && dy in -1..1
    }

    private fun donorLists(): Map<Int, List<Int>> {
        val m = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) if (receiver[i] != i) m.getOrPut(receiver[i]) { ArrayList() } += i
        return m
    }

    /** Topological order (downstream cells first) of receivers plus canal edges, by Kahn's algorithm. */
    private fun reorder() {
        val indeg = IntArray(n)
        for (i in 0 until n) if (receiver[i] != i) indeg[receiver[i]]++
        for (d in diversions) indeg[d.path.last()]++
        // Upstream-first order; reversed afterwards so that index 0 is downstream.
        val up = IntArray(n)
        var head = 0; var tail = 0
        for (i in 0 until n) if (indeg[i] == 0) up[tail++] = i
        while (head < tail) {
            val i = up[head++]
            val r = receiver[i]
            if (r != i && --indeg[r] == 0) up[tail++] = r
            diversionsAt[i]?.forEach { d -> val t = d.path.last(); if (--indeg[t] == 0) up[tail++] = t }
        }
        check(tail == n) { "water network has a loop" }
        order = IntArray(n) { up[n - 1 - it] }
    }

    companion object {
        /** Water a loamy soil holds for plants over the root zone, mm. */
        const val SOIL_CAPACITY = 150f
        /** Share of excess water that soaks through to groundwater rather than running off at once. */
        const val RECHARGE = 0.4f
        /** Share of groundwater released to rivers each step. */
        const val BASEFLOW = 0.04f
    }
}
