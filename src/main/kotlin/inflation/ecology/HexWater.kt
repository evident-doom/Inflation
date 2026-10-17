package inflation.ecology

import inflation.monthsFrom
import inflation.world.Channel
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.RiverClass
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder
import java.util.stream.IntStream
import kotlin.math.sqrt

/**
 * Life in the water of each hex, as in the 1 km model's plankton, fish and sea, on the hexes:
 *
 * - Plankton in the sea and in lakes: phytoplankton grow with light, warmth and nutrients;
 *   zooplankton graze them. Nutrients are rich on shallow shelves and off river mouths, poor over
 *   deep water; cold water mixes them up, warm water layers and starves the surface. Where the wind
 *   drives the surface water away from a coast (the earth's turning bends the drift to the right),
 *   deep water wells up and the plankton blooms. Dead plankton, waste and dead animals sink and half
 *   is recycled near the surface.
 * - Fish in the sea and in lakes live off the zooplankton; river fish by the river's size and
 *   warmth, dying where the bed nearly dries and coming back from downstream. Shellfish on shallow
 *   coasts. In spring, shad-like fish run up the rivers from the sea, about 20 km a day, until a
 *   cascade stops them.
 * - Sea animals as densities per hex of sea: fin whales straining krill, dolphins and porpoises
 *   (on the shelf) and monk seals (along the coast) eating fish, sharks living mostly on fish and
 *   now and then a seal, porpoise or dolphin. Each breeds in its season as it eats and spreads to
 *   the sea around, whales far. Sea-bird colonies on cliff coasts fish within 40 km.
 * - Crocodiles in warm rivers and lakes, as a danger to herds that drink there ([crocodileRisk]).
 *
 * Units: plankton and nutrients g/m², fish and animals kg/km² of water.
 */
class HexWater(private val hw: HexWorld, private val land: HexLand, private val days: Int, private val small: HexSmallLife) {
    private val hex = hw.hex
    private val world = hw.world
    private val size = hex.size
    private val hexKm2 = hex.areaKm2.toFloat()
    private val years = days / Date.DAYS_PER_YEAR.toFloat()

    /** Open water in each hex, km²: sea, and lake (fresh or closed). */
    val seaKm2 = FloatArray(size) { hw.sea[it] * hexKm2 }
    val lakeKm2 = FloatArray(size) { hw.lake[it] * hexKm2 }
    private val waterKm2 = FloatArray(size) { seaKm2[it] + lakeKm2[it] }
    /** Mean depth of the sea in each hex, m; share of the sea within 3 km of land and under 10 m (shellfish beds). */
    private val depthM = FloatArray(size)
    private val shallow = FloatArray(size)
    /** Distance from each hex to land, km (0 for coast). */
    private val shoreKm = FloatArray(size)
    private val closedLake = FloatArray(size)
    /** Water temperature by month, °C (the climate's normals over the water). */
    private val waterTemp = Array(12) { FloatArray(size) }

    /** Nutrient supply the water is mixed towards (0..1), and nutrients, phytoplankton and zooplankton now, g/m². */
    val supply = FloatArray(size)
    private val nutrients = FloatArray(size)
    /**
     * Nutrients in the deep water under the sea, g/m² of surface water's worth: what sinks from the
     * surface (dead plankton, fish and animals) gathers here, the wind brings it up where it blows the
     * surface water off a coast (upwelling), and it is slowly renewed by the deep ocean beyond.
     */
    private val deep = FloatArray(size) { if (seaKm2[it] > 0f) DEEP_BASE * NUTRIENT_SCALE else 0f }
    val phyto = FloatArray(size)
    val zoo = FloatArray(size)
    /** Upwelling now, 0..1; for each coastal sea hex the way out to sea (unit east, south) and the land place that gives its wind. */
    val upwelling = FloatArray(size)
    private val offE = FloatArray(size); private val offS = FloatArray(size)
    private val windPlace = IntArray(size) { -1 }

    /** Fish in sea and lake water, and in rivers (per km² of the hex's land), kg/km²; shellfish; spawning run now. */
    val fish = FloatArray(size)
    val riverFish = FloatArray(size)
    val shellfish = FloatArray(size)
    val run = FloatArray(size)
    /** River distance up from the sea, km; the mouth hex (-1 if none); cascades fish cannot pass. */
    private val upKm = FloatArray(size)
    private val mouth = IntArray(size) { -1 }
    private val cascade = BooleanArray(size)

    /** Sea animals, kg/km² of sea, per kind of [SEA]. */
    val sea = Array(SEA.size) { FloatArray(size) }
    /** Sea-bird colonies: the coast hex, the birds, and the sea hexes within reach. */
    val colonyHex = ArrayList<Int>()
    val colonyBirds = ArrayList<Float>()
    private val colonyReach = ArrayList<IntArray>()
    /** Sea animals and fish that died this week, kg/km²: they sink and feed the nutrients. */
    private val dead = FloatArray(size)

    init {
        measureWater()
        measureShore()
        startWater(riverMouthFlows())
        walkRivers()
        placeColonies()
    }

    /** The open water of each hex from the fine grid: the sea's mean depth and its shallows near land (shellfish beds), the share of lake that is closed, and the water's temperature by month. */
    private fun measureWater() {
        val grid = world.grid
        val depthSum = DoubleArray(size); val seaCells = IntArray(size); val shallowCells = IntArray(size); val closedCells = IntArray(size); val lakeCells = IntArray(size)
        val tSum = Array(12) { DoubleArray(size) }; val wCells = IntArray(size)
        val r3 = max(1, (3 / grid.cellKm).toInt())
        for (i in 0 until grid.size) {
            val h = hex.owner[i]
            val isSea = world.isSea[i]; val isLake = world.water.isLake(i)
            if (!isSea && !isLake) continue
            wCells[h]++
            for (m in 0 until 12) tSum[m][h] += world.climate.monthlyTemp[m][i].toDouble()
            if (isLake) { lakeCells[h]++; if (world.water.lakeClosed[i]) closedCells[h]++; continue }
            seaCells[h]++; depthSum[h] += -world.elevation[i].toDouble()
            if (world.elevation[i] > -10f) {
                val x0 = grid.x(i); val y0 = grid.y(i)
                var near = false
                search@ for (dy in -r3..r3) for (dx in -r3..r3) if (grid.inBounds(x0 + dx, y0 + dy) && !world.isSea[grid.index(x0 + dx, y0 + dy)]) { near = true; break@search }
                if (near) shallowCells[h]++
            }
        }
        for (h in 0 until size) {
            if (seaCells[h] > 0) { depthM[h] = (depthSum[h] / seaCells[h]).toFloat(); shallow[h] = shallowCells[h].toFloat() / seaCells[h] }
            if (lakeCells[h] > 0) closedLake[h] = closedCells[h].toFloat() / lakeCells[h]
            for (m in 0 until 12) waterTemp[m][h] = if (wCells[h] > 0) (tSum[m][h] / wCells[h]).toFloat() else hw.temp[m][h]
        }
    }

    /** Distance from each hex to land, hex by hex. */
    private fun measureShore() {
        val dist = FloatArray(size) { Float.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (h in 0 until size) if (hw.land[h] > 0f) { dist[h] = 0f; queue += h }
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && dist[q] > dist[h] + hex.hexKm) { dist[q] = (dist[h] + hex.hexKm).toFloat(); queue += q } }
        }
        for (h in 0 until size) shoreKm[h] = dist[h]
    }

    /** Rivers into the sea: each land hex whose river leaves the land feeds the sea hexes around its mouth, m³/s. */
    private fun riverMouthFlows(): FloatArray {
        val mouthFlow = FloatArray(size)
        for (h in 0 until size) {
            if (land.meanFlow[h] <= 0f || hw.land[h] <= 0f || land.down[h] >= 0) continue
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && seaKm2[q] > 0f) mouthFlow[q] += land.meanFlow[h] / 6f }
            if (seaKm2[h] > 0f) mouthFlow[h] += land.meanFlow[h]
        }
        return mouthFlow
    }

    /** The water's nutrient supply and its first plankton, fish, shellfish and sea animals; for each coastal sea hex the way out to sea and the land whose wind blows over it ([mouthFlow]: what the rivers bring, m³/s). */
    private fun startWater(mouthFlow: FloatArray) {
        for (h in 0 until size) {
            if (waterKm2[h] <= 0f) continue
            supply[h] = if (seaKm2[h] <= 0f) (if (closedLake[h] > 0.5f) 0.2f else 0.6f)
                else min(1f, 0.15f + 0.45f * exp(-max(0f, depthM[h]) / 150f) + 0.4f * min(1f, mouthFlow[h] / 50f))
            nutrients[h] = supply[h]; phyto[h] = 2f * supply[h]; zoo[h] = 0.5f * supply[h]
            // The way out to sea from a coast, and the land whose wind blows over it.
            if (seaKm2[h] > 0f && shoreKm[h] <= hex.hexKm) {
                var e = 0.0; var s = 0.0
                val (cx, cy) = hex.centreKm(h)
                for (d in 0 until 6) {
                    val q = hex.neighbour(h, d)
                    if (q < 0 || hw.land[q] <= 0f) continue
                    val (qx, qy) = hex.centreKm(q); e += cx - qx; s += cy - qy
                    if (windPlace[h] < 0) windPlace[h] = hw.firstPlaceIn(q)
                }
                if (hw.land[h] > 0f && windPlace[h] < 0) windPlace[h] = hw.firstPlaceIn(h)
                val len = hypot(e, s)
                if (len > 0) { offE[h] = (e / len).toFloat(); offS[h] = (s / len).toFloat() }
            }
            fish[h] = FISH_START * fishCapacity(h)
            if (shallow[h] > 0f) shellfish[h] = 0.7f * shellCapacity(h)
            for ((k, a) in SEA.withIndex()) if (seaKm2[h] > 0f && shoreKm[h] <= a.maxShoreKm) sea[k][h] = a.massKg / a.startKm2
        }
    }

    /** Rivers: distance up from the sea and cascades, down the network from the mouths; the first river fish. */
    private fun walkRivers() {
        val byDist = (0 until size).filter { land.meanFlow[it] >= RiverClass.STREAM.minDischarge && hw.land[it] > 0f }
        fun up(h: Int): Float {
            if (mouth[h] != -1 || upKm[h] < 0f) return upKm[h]
            upKm[h] = -1f // being worked out
            val d = land.down[h]
            if (d < 0) {
                // The river ends here: at the sea it is a mouth; in a closed basin no run comes up it.
                val atSea = hw.coast[h] || (0 until 6).any { val q = hex.neighbour(h, it); q >= 0 && seaKm2[q] > 0f }
                if (atSea) { mouth[h] = h; upKm[h] = 0f; return 0f }
                return -1f
            }
            val below = up(d)
            if (below < 0f || mouth[d] < 0) { upKm[h] = -1f; return -1f }
            mouth[h] = mouth[d]; upKm[h] = below + hex.hexKm.toFloat()
            cascade[h] = cascade[d] || (hw.elevationM[h] - hw.elevationM[d]) / (hex.hexKm.toFloat() * 1000f) > CASCADE_GRADIENT
            return upKm[h]
        }
        for (h in byDist) up(h)
        for (h in byDist) riverFish[h] = 0.7f * riverCapacity(h, 6)
    }

    /** Sea-bird colonies: the most broken coast in each stretch, fishing the sea within reach. */
    private fun placeColonies() {
        val blocks = HashMap<Long, Int>()
        for (h in 0 until size) {
            if (!hw.coast[h] || hw.land[h] <= 0f) continue
            val p = hw.firstPlaceIn(h).takeIf { it >= 0 } ?: continue
            if (land.ruggedness[p] < 0.3f) continue
            val (x, y) = hex.centreKm(h)
            val key = (x / COLONY_SPACING_KM).toLong() * 100000 + (y / COLONY_SPACING_KM).toLong()
            val best = blocks[key]
            val pb = best?.let { b -> hw.firstPlaceIn(b).takeIf { it >= 0 } }
            if (best == null || pb == null || land.ruggedness[p] > land.ruggedness[pb]) blocks[key] = h
        }
        for (c in blocks.values.sorted()) {
            val reach = (0 until size).filter { seaKm2[it] > 0f && hex.distanceKm(it, c) <= BIRD_FORAGE_KM }.toIntArray()
            if (reach.isEmpty()) continue
            colonyHex += c; colonyBirds += 2000f; colonyReach += reach
        }
    }

    /** Fish the sea or lake water of [h] can carry, kg/km²: they follow the plankton. */
    private fun fishCapacity(h: Int) = if (waterKm2[h] > 0f) MAX_FISH * phyto[h] / (phyto[h] + 5f) else 0f
    private fun shellCapacity(h: Int) = MAX_SHELLFISH * phyto[h] / (phyto[h] + 3f)

    /** River fish the hex can carry, kg per km² of its land: per km of river, by its width and warmth. */
    private fun riverCapacity(h: Int, month: Int): Float {
        val q = max(land.flow[h], 0.01f)
        val widthKm = Channel.widthM(q) / 1000f
        val warm = ((hw.temp[month][h] + 5f) / 25f).coerceIn(0.2f, 1.2f)
        val km = max(hex.hexKm.toFloat(), hw.riverKm[h])
        return RIVER_FISH_PER_KM2_WATER * widthKm * km * warm / max(1f, hw.land[h] * hexKm2)
    }

    /** How far danger from crocodiles reaches in hex [h], 0..1: warm rivers and lakes where the coldest month stays above 12 °C. */
    val crocodiles = FloatArray(size) { h ->
        val coldest = (0 until 12).minOf { hw.temp[it][h] }
        val water = land.meanFlow[h] >= RiverClass.RIVER.minDischarge || hw.lake[h] > 0.02f
        if (coldest > CROC_COLDEST && water) 1f else 0f
    }

    /** Yearly share of the young of herds drinking at place [p] taken by crocodiles; nothing in the cold months. */
    fun crocodileRisk(p: Int, weather: Weather): Float {
        val h = hw.placeHex[p]
        if (crocodiles[h] <= 0f || weather.temperature[p] < 16f) return 0f
        return CROC_RATE * min(1f, land.nearRiver[p] + land.nearLake[p])
    }

    /** This week's flows of sea and lake fish, tonnes: grown, died of age or hunger, eaten by each kind, drifted. */
    @Transient val fishFlows = ConcurrentHashMap<String, Double>()
    // (Summed in adders during the parallel loop, without contention, and written to [fishFlows] after it.)
    @Transient private val flowAdders = ConcurrentHashMap<String, DoubleAdder>()
    private fun flow(key: String, kgPerKm2: Float, h: Int) { if (kgPerKm2 != 0f) flowAdders.computeIfAbsent(key) { DoubleAdder() }.add((kgPerKm2 * waterKm2[h] / 1000f).toDouble()) }
    private val eatenBy = SEA.map { "eaten by ${it.name}" }

    fun step(weather: Weather) {
        fishFlows.clear()
        val date = weather.date
        val month = date.month
        val light = (Date(date.absoluteDay).dayLengthHours(38.0) / 12.0).toFloat()
        IntStream.range(0, size).parallel().forEach { h ->
            val pt = Profile.mark()
            if (waterKm2[h] > 0f) {
                // Upwelling: the wind's drift is bent to the right; where it carries water off the coast, deep water wells up.
                val wp = windPlace[h]
                if (wp >= 0 && seaKm2[h] > 0f) {
                    val u = weather.windEast(wp); val vS = weather.windSouth(wp)
                    val driftE = -vS; val driftS = u // the wind turned a quarter to the right
                    val off = max(0f, driftE * offE[h] + driftS * offS[h]) / UPWELL_WIND
                    upwelling[h] += (min(1f, off) - upwelling[h]) / UPWELL_WEEKS
                }
                val t = waterTemp[month][h]
                val mixing = if (t < 14f) 0.25f else if (t < 20f) 0.08f else 0.02f
                val isSea = seaKm2[h] > 0f
                val full = NUTRIENT_SCALE * supply[h]
                var nu = nutrients[h] * NUTRIENT_SCALE + 0.5f * dead[h] / 1000f
                // What does not rot near the surface sinks to the deep; upwelling brings the deep water up.
                var dp = deep[h]
                if (isSea) dp += 0.5f * dead[h] / 1000f / DEEP_VOLUME
                dead[h] = 0f
                val up = if (isSea) UPWELL_MIX * upwelling[h] else 0f
                var p = phyto[h]; var z = zoo[h]
                val warm = ((t + 2f) / 22f).coerceIn(0.1f, 1.2f)
                repeat(days) {
                    val growth = min(nu, 0.9f * light * warm * p * nu / (nu + 0.15f * NUTRIENT_SCALE))
                    val grazing = min(p, 0.6f * z * p / (p + 3f))
                    val pDeath = 0.05f * p; val zDeath = 0.05f * z + 0.15f * z * z
                    val back = pDeath + zDeath + 0.7f * grazing
                    val rise = up * max(0f, dp - nu)
                    nu = max(0f, nu - growth + mixing * (full - nu) + 0.5f * back + rise)
                    if (isSea) dp = max(0f, dp + (0.5f * back - rise) / DEEP_VOLUME)
                    p = max(0f, p + growth - grazing - pDeath)
                    z = max(0f, z + 0.3f * grazing - zDeath)
                }
                nutrients[h] = nu / NUTRIENT_SCALE; phyto[h] = p; zoo[h] = z
                if (isSea) deep[h] = dp + (DEEP_BASE * NUTRIENT_SCALE - dp) * min(1f, DEEP_RENEW * years)
                // Fish: shoaling sea fish breed fast, lake fish slower. They eat about three times their weight a
                // year in zooplankton (g/m2 = t/km2), as far as there is any;
                // they grow as they eat and starve when it fails.
                // They grow by a fifth of what they eat (the rest goes back to the water as waste), so a
                // fish that can double in a year must eat about ten times its weight.
                val growth = if (seaKm2[h] > 0f) SEA_FISH_GROWTH else FISH_GROWTH
                val f0 = fish[h]
                val need = growth / FISH_EFFICIENCY * years * f0 / 1000f
                val got = min(need, 0.5f * zoo[h])
                zoo[h] -= got
                val fed = if (need > 0f) got / need else 1f
                val fishDead = (FISH_DEATHS + 0.5f * (1f - fed)) * years * f0
                val grown = FISH_EFFICIENCY * got * 1000f * (1f - f0 / max(fishCapacity(h), 1f)).coerceAtLeast(0f)
                fish[h] = max(0f, f0 + grown - fishDead)
                // (What they ate and did not grow by, crowded near what the water carries, goes back to the water as waste.)
                dead[h] += fishDead + got * 1000f - grown
                flow("grown", grown, h); flow("died", fishDead, h)
                // Shellfish filter the plankton: they grow by what they take of it, and die back where the water carries fewer.
                if (shallow[h] > 0f) {
                    val s = shellfish[h]; val kS = max(shellCapacity(h), 1f)
                    // (Shellfish are counted per km² of sea, plankton and the waste per km² of all the water.)
                    val perWater = seaKm2[h] / waterKm2[h]
                    val want = SHELL_GROWTH / FISH_EFFICIENCY * years * s * (1f - s / kS).coerceAtLeast(0f) * perWater / 1000f
                    val ate = min(want, 0.5f * p)
                    phyto[h] = p - ate
                    val grew = FISH_EFFICIENCY * ate * 1000f / perWater
                    val died = if (s > kS) min(s, SHELL_GROWTH * years * s * (s / kS - 1f)) else 0f
                    shellfish[h] = max(0f, s + grew - died)
                    dead[h] += ate * 1000f * (1f - FISH_EFFICIENCY) + died * perWater
                }
                Profile.lap("water: plankton, fish, shellfish", pt)
                Profile.part("water: sea animals") { stepSea(h, month) }
            }
            // River fish: by the river's size and warmth; a nearly dry bed keeps only what survives in pools.
            // They live on what the land washes in (leaves, insects, the dead), taken from the litter of the
            // hex's places; those that die, or are stranded as the bed dries, rot on the banks.
            if (land.meanFlow[h] >= RiverClass.STREAM.minDischarge && hw.land[h] > 0f) {
                val k = riverCapacity(h, month)
                val f = riverFish[h]
                val landKm2h = landKm2[h]
                val change = FISH_GROWTH * years * f * (1f - f / max(k, 1e-3f))
                var grew = 0f; var died = 0f
                if (change > 0f) {
                    // Food wanted, kg over the hex, and what the litter can give.
                    val want = change / FISH_EFFICIENCY * landKm2h
                    var litterKg = 0.0
                    for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][h]; if (p >= 0) litterKg += small.litter[p] * land.areaKm2[p] * 1e6 }
                    val ate = min(want, 0.5f * litterKg.toFloat())
                    if (ate > 0f) for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][h]; if (p >= 0) small.litter[p] *= 1f - ate / litterKg.toFloat() }
                    grew = FISH_EFFICIENCY * ate / landKm2h
                } else died = -change
                if (land.flow[h] / max(land.meanFlow[h], 1e-3f) < 0.05f) died += 0.7f * (f + grew - died).coerceAtLeast(0f)
                riverFish[h] = max(0f, f + grew - died)
                if (died > 0f) bankCarrion(h, died * landKm2h)
            }
        }
        // Currents mix the water of neighbouring hexes: plankton and fish drift and spread.
        val before = fishTonnes()
        Profile.part("water: currents") { for (f in listOf(phyto, zoo, fish, deep)) drift(f) }
        // Shoals come inshore to spawn and feed in spring and early summer, and go back out in autumn.
        if (month in 3..6) shoal(true) else if (month in 8..10) shoal(false)
        nesting = month in NESTING
        fishFlows["drift (net)"] = (fishTonnes() - before).toDouble()
        for ((key, a) in flowAdders) { val v = a.sumThenReset(); if (v != 0.0) fishFlows.merge(key, v, Double::plus) }
        // Fish come back up the rivers from below, but not past a cascade.
        for (h in 0 until size) {
            val d = land.down[h]
            // (A hex all lake or sea on the river's course has no river of its own to come up into.)
            if (d < 0 || landKm2[h] <= 0f || riverFish[h] <= 0f && land.meanFlow[h] < RiverClass.STREAM.minDischarge) continue
            val river = hw.land[d] > 0f
            val below = if (river) riverFish[d] else fish[d]
            val diff = below - riverFish[h]
            if (diff > 0f && !(cascade[h] && !cascade[d])) {
                // (They move up: what one hex gains, the one below loses.)
                val belowKm2 = if (river) landKm2[d] else waterKm2[d]
                if (belowKm2 <= 0f) continue
                val kg = min(0.1f * diff * landKm2[h], 0.5f * below * belowKm2)
                riverFish[h] += kg / landKm2[h]
                if (river) riverFish[d] -= kg / belowKm2 else fish[d] -= kg / belowKm2
            }
        }
        Profile.part("water: spawning runs") { spawningRun(date) }
        Profile.part("water: sea birds") { seaBirds() }
        if (date.dayOfMonth <= days) Profile.part("water: sea animals spreading") { spreadSea() }
    }

    /** For each sea hex the sea hexes next to it nearer the shore; and how many lie seaward of each. */
    private val shoreward = Array(size) { h ->
        if (seaKm2[h] <= 0f) IntArray(0) else (0 until 6).map { hex.neighbour(h, it) }.filter { it >= 0 && seaKm2[it] > 0f && shoreKm[it] < shoreKm[h] }.toIntArray()
    }
    private val seaward = IntArray(size).also { c -> for (h in 0 until size) for (q in shoreward[h]) c[q]++ }

    /** Moves a share of the fish a step towards the shore ([inshore]) or back out to sea. */
    private fun shoal(inshore: Boolean) {
        val old = fish.copyOf()
        val share = SHOAL * days / Date.DAYS_PER_MONTH.toFloat()
        for (h in 0 until size) for (q in shoreward[h]) {
            val kg = if (inshore) share * old[h] * seaKm2[h] / shoreward[h].size else share * old[q] * seaKm2[q] / seaward[q]
            val from = if (inshore) h else q; val to = if (inshore) q else h
            fish[from] -= kg / seaKm2[from]; fish[to] += kg / seaKm2[to]
        }
    }

    /** A share of what is in each hex's water mixes with its neighbours' each week. */
    private fun drift(f: FloatArray) {
        // Exchange between neighbouring water of the same kind (sea with sea, lake with lake), as much
        // leaving one as reaches the other: what moves is the density difference over the narrower water.
        val old = f.copyOf()
        IntStream.range(0, size).parallel().forEach { h ->
            if (waterKm2[h] <= 0f) return@forEach
            var delta = 0f
            for (d in 0 until 6) {
                val q = hex.neighbour(h, d)
                if (q < 0 || waterKm2[q] <= 0f || (seaKm2[q] > 0f) != (seaKm2[h] > 0f)) continue
                delta += DRIFT / 6f * (old[q] - old[h]) * min(waterKm2[h], waterKm2[q]) / waterKm2[h]
            }
            f[h] = max(0f, old[h] + delta)
        }
    }


    /** Sea animals in hex [h]: each eats its food, breeds in its season as it ate, and dies back. */
    private fun stepSea(h: Int, month: Int) {
        if (seaKm2[h] <= 0f) return
        for ((k, a) in SEA.withIndex()) {
            val m = sea[k][h]
            if (m <= 0f) continue
            val need = m * a.dailyShare * days
            val got = when (a.food) {
                SeaFood.PLANKTON -> { val z = zoo[h] * 1000f; val t = min(0.5f * z, need * z / (z + ZOO_HALF)); zoo[h] -= t / 1000f; t }
                SeaFood.FISH -> { val t = min(0.5f * fish[h], need * fishResponse(fish[h])); fish[h] -= t; flow(eatenBy[k], t, h); t }
                SeaFood.PREY -> {
                    // Sharks: mostly fish, now and then a seal, porpoise or dolphin.
                    var t = min(0.5f * fish[h], need * 0.7f * fishResponse(fish[h])); fish[h] -= t; flow(eatenBy[k], t, h)
                    for ((j, b) in SEA.withIndex()) {
                        if (b.food != SeaFood.FISH || t >= need) continue
                        // Seals haul out on rocks and beaches and porpoises keep to the shallows: shelter near the shore.
                        val shelter = if (b.maxShoreKm.isFinite() && shoreKm[h] <= hex.hexKm) 0.5f else 0f
                        val take = min(need - t, sea[j][h] * SHARK_ATTACK * years * (1f - b.defence) * (1f - shelter) * min(1f, m / SHARK_KG_KM2))
                        sea[j][h] -= take; t += take
                    }
                    t
                }
            }
            val fed = if (need > 0f) got / need else 1f
            // Turtles lay on warm beaches only.
            val beach = !a.nestsAshore || (shoreKm[h] <= 0f && waterTemp[month][h] >= NEST_TEMP)
            val born = if (beach && monthsFrom(a.birthMonth, month) < a.birthMonths) m * a.youngPerFemale * 0.5f * fed / (a.birthMonths * Date.DAYS_PER_MONTH.toFloat() / days) else 0f
            val crowd = m / (SEA_FULL * a.massKg / a.startKm2)
            val died = m * min(0.9f, a.backgroundMortality * years + 0.6f * years * (1f - fed) + SEA_DISEASE * years * crowd * crowd)
            sea[k][h] = max(0f, m + born - died)
            dead[h] += died
        }
    }

    /** Sea animals spread to the sea around, each as far as it ranges (whales across the sea, seals along the coast). */
    private fun spreadSea() {
        for ((k, a) in SEA.withIndex()) {
            val f = sea[k]
            val old = f.copyOf()
            val share = min(0.5f, a.dailyKm * 30f / hex.hexKm.toFloat() / 50f)
            for (h in 0 until size) {
                if (old[h] <= 0f) continue
                val nb = (0 until 6).map { hex.neighbour(h, it) }.filter { it >= 0 && seaKm2[it] > 0f && shoreKm[it] <= a.maxShoreKm }
                if (nb.isEmpty()) continue
                // Towards the better fed and the less crowded: the richer and emptier the water around, the more go there.
                val usual = a.massKg / a.startKm2
                val food = nb.map { q -> (if (a.food == SeaFood.PLANKTON) zoo[q] + 0.01f else fish[q] + 1f) / (1f + old[q] / usual) }
                val total = food.sum()
                val go = old[h] * share
                f[h] -= go
                for ((i, q) in nb.withIndex()) f[q] += go * food[i] / total * seaKm2[h] / seaKm2[q]
            }
        }
    }

    /** Run fish of each river mouth this spring, kg: taken from the sea off the mouth, given back at the end. */
    private val runStock = FloatArray(size)
    private val runWaiting = FloatArray(size)
    private var running = false

    /**
     * Spring spawning run: at its start a share of the fish off each river mouth gathers and goes up
     * the river (as far as the front has come, while there is water to swim up), where people and
     * bears take what they can; at its end the survivors go back to the sea, and the spent fish die
     * in the river.
     */
    private fun spawningRun(date: Date) {
        val day = date.dayOfYear - RUN_START_DAY
        val on = day in 0 until RUN_DAYS
        if (on && !running) {
            // The run gathers off each mouth.
            runStock.fill(0f)
            val mouths = HashSet<Int>(); for (h in 0 until size) if (mouth[h] >= 0 && !cascade[h]) mouths += mouth[h]
            for (m in mouths) for (d in 0 until 6) {
                val q = hex.neighbour(m, d); if (q < 0 || seaKm2[q] <= 0f) continue
                val taken = RUN_SHARE * fish[q]
                fish[q] -= taken; runStock[m] += taken * waterKm2[q]
            }
            running = true
        } else if (running) {
            // What is left of the run in the rivers, after this week's catch, goes back into each mouth's stock.
            // (Fish that found no water to swim up last week wait off the mouth.)
            for (m in 0 until size) runStock[m] = runWaiting[m]
            for (h in 0 until size) if (mouth[h] >= 0 && run[h] > 0f) runStock[mouth[h]] += run[h] * landKm2[h]
        }
        run.fill(0f)
        if (!running) return
        if (!on) {
            // The run is over: the survivors go back to the sea off the mouth, the spent ones die.
            for (m in 0 until size) {
                if (runStock[m] <= 0f) continue
                val seas = (0 until 6).map { hex.neighbour(m, it) }.filter { it >= 0 && seaKm2[it] > 0f }
                val area = seas.sumOf { waterKm2[it].toDouble() }.toFloat()
                if (area > 0f) for (q in seas) { fish[q] += RUN_SURVIVAL * runStock[m] / area; dead[q] += (1f - RUN_SURVIVAL) * runStock[m] / area }
            }
            runStock.fill(0f); runWaiting.fill(0f); running = false
            return
        }
        // Up the rivers as far as the front has come.
        val front = day * RUN_KM_PER_DAY
        val weight = FloatArray(size); val total = FloatArray(size)
        for (h in 0 until size) {
            val m = mouth[h]
            if (m < 0 || cascade[h] || upKm[h] > front || landKm2[h] <= 0f) continue
            if (land.flow[h] / max(land.meanFlow[h], 1e-3f) < 0.2f) continue // too little water to swim up
            weight[h] = min(1f, land.meanFlow[h] / 30f) * exp(-upKm[h] / 150f); total[m] += weight[h]
        }
        for (h in 0 until size) if (weight[h] > 0f) run[h] = runStock[mouth[h]] * weight[h] / total[mouth[h]] / landKm2[h]
        for (m in 0 until size) runWaiting[m] = if (total[m] > 0f) 0f else runStock[m]
    }

    /** Sea-bird colonies fish the sea within reach; a colony grows when they eat well and shrinks when not. */
    private fun seaBirds() {
        for (c in colonyHex.indices) {
            val need = colonyBirds[c] * BIRD_KG_DAY * days
            var got = 0f
            for (h in colonyReach[c]) {
                if (got >= need) break
                // They can catch a share of the fish near the surface each week; the nearer water first.
                val take = min(need - got, BIRD_CATCH * fish[h] / (fish[h] + FISH_HALF) * fish[h] * seaKm2[h])
                fish[h] -= take / seaKm2[h]; got += take
                fishFlows.merge("eaten by sea birds", take / 1000.0, Double::plus)
            }
            val fed = if (need > 0f) min(1f, got / need) else 1f
            var b = colonyBirds[c]
            b += b * (BIRD_BREED * fed * years) - b * (0.1f * years + 0.5f * years * (1f - fed))
            colonyBirds[c] = if (b < 2f) 0f else b
        }
        // Young birds from thriving colonies settle empty cliffs nearby, once a year.
        if (stepsDone++ % (Date.DAYS_PER_YEAR / days) == 0) for (c in colonyHex.indices) {
            if (colonyBirds[c] > 0f) continue
            val src = colonyHex.indices.filter { colonyBirds[it] > 500f && hex.distanceKm(colonyHex[it], colonyHex[c]) <= RESETTLE_KM }.maxByOrNull { colonyBirds[it] } ?: continue
            val go = 0.05f * colonyBirds[src]
            colonyBirds[src] -= go; colonyBirds[c] += go
        }
    }
    private var stepsDone = 0

    private val landKm2 = FloatArray(size) { hw.land[it] * hexKm2 }

    /** Leaves [kg] of dead fish on the banks of hex [h]: carrion on its places, by their area. */
    private fun bankCarrion(h: Int, kg: Float) {
        var area = 0f
        for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][h]; if (p >= 0) area += land.areaKm2[p] }
        if (area <= 0f) return
        for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][h]; if (p >= 0) small.carrion[p] += kg / area / 1e6f }
    }
    fun fishTonnes() = fish.indices.sumOf { (fish[it] * waterKm2[it]).toDouble() }.toFloat() / 1000f
    fun riverFishTonnes() = riverFish.indices.sumOf { (riverFish[it] * landKm2[it]).toDouble() }.toFloat() / 1000f
    fun shellfishTonnes() = shellfish.indices.sumOf { (shellfish[it] * seaKm2[it]).toDouble() }.toFloat() / 1000f
    /** Head count of sea kind [k]. */
    fun seaHeads(k: Int) = sea[k].indices.sumOf { (sea[k][it] * seaKm2[it]).toDouble() }.toFloat() / SEA[k].massKg
    /** Mean zooplankton at sea, g/m². */
    fun zooMean(): Float {
        var s = 0.0; var a = 0.0
        for (h in 0 until size) if (seaKm2[h] > 0f) { s += zoo[h] * seaKm2[h]; a += seaKm2[h] }
        return if (a > 0) (s / a).toFloat() else 0f
    }

    /**
     * People fishing from hex [h]: the spawning run while it is in the river, the river's fish, fish
     * from lakes and from the sea along the coast (its own water and the water hexes next to it), and
     * shellfish from shallow shores. Takes up to [kg]; returns kg caught. Each source gives at most a
     * share a week, so a band cannot empty a river.
     */
    fun fishFor(h: Int, kg: Float, skill: Float = 1f): Float {
        var left = kg
        val landKm2 = max(1f, hw.land[h] * hexKm2)
        // A week's fishing gets more the more fish there are, levelling off at what the band wants: a
        // stock [search] times what it wants (less for skilled hands) gives it half (Holling).
        fun take(stock: FloatArray, q: Int, areaKm2: Float, search: Float) {
            if (left <= 0f || areaKm2 <= 0f) return
            val s = stock[q] * areaKm2
            if (s <= 0f) return
            val t = min(0.5f * s, left * s / (s + left * search / skill))
            stock[q] -= t / areaKm2; left -= t
        }
        take(run, h, landKm2, RUN_SEARCH)
        // Turtles and their eggs on the beach in the nesting months.
        if (nesting && shoreKm[h] <= 0f) take(sea[TURTLE], h, seaKm2[h], SHELLFISH_SEARCH)
        take(riverFish, h, landKm2, RIVER_SEARCH)
        for (q in listOf(h) + (0 until 6).map { hex.neighbour(h, it) }.filter { it >= 0 }) {
            take(fish, q, waterKm2[q], FISH_SEARCH)
            take(shellfish, q, seaKm2[q] * shallow[q], SHELLFISH_SEARCH)
        }
        return kg - left
    }

    /** What a band fishing from hex [h] with [skill] could catch in a week at most, kg (see [fishFor]). */
    fun fishWithin(h: Int, skill: Float = 1f): Float {
        val landKm2 = max(1f, hw.land[h] * hexKm2)
        fun most(stock: FloatArray, q: Int, areaKm2: Float, search: Float): Float { val s = stock[q] * areaKm2; return if (s > 0f) min(0.5f * s, s * skill / search) else 0f }
        var kg = most(run, h, landKm2, RUN_SEARCH) + most(riverFish, h, landKm2, RIVER_SEARCH)
        if (nesting && shoreKm[h] <= 0f) kg += most(sea[TURTLE], h, seaKm2[h], SHELLFISH_SEARCH)
        // (The hex and the six round it, in that order, without making a list each time: it is asked often.)
        for (d in -1 until 6) { val q = if (d < 0) h else hex.neighbour(h, d); if (q < 0) continue
            kg += most(fish, q, waterKm2[q], FISH_SEARCH) + most(shellfish, q, seaKm2[q] * shallow[q], SHELLFISH_SEARCH) }
        return kg
    }

    /** Share of its needs a fish-eater gets at fish density [f], kg/km²: little where fish are few (shoals are hard to find), most where they are many. */
    private fun fishResponse(f: Float) = f * f / (f * f + FISH_FIND_HALF * FISH_FIND_HALF)

    private var nesting = false

    /** Totals: plankton, fish, shellfish, sea animals (t), river fish (t) and sea birds. */
    fun summary(): String {
        fun t(a: FloatArray, area: FloatArray) = a.indices.sumOf { (a[it] * area[it]).toDouble() } / 1000
        val landKm2 = FloatArray(size) { hw.land[it] * hexKm2 }
        val sea0 = (0 until size).filter { seaKm2[it] > 0f }
        val zooMean = sea0.sumOf { (zoo[it] * seaKm2[it]).toDouble() } / sea0.sumOf { seaKm2[it].toDouble() }.coerceAtLeast(1.0)
        val phytoMean = sea0.sumOf { (phyto[it] * seaKm2[it]).toDouble() } / sea0.sumOf { seaKm2[it].toDouble() }.coerceAtLeast(1.0)
        return "plankton at sea %.2f / %.3f g/m², ".format(phytoMean, zooMean) + "sea and lake fish %.0f t, river fish %.0f t, shellfish %.0f t, %s, sea birds %.0f in %d colonies".format(
            t(fish, waterKm2), t(riverFish, landKm2), t(shellfish, seaKm2),
            SEA.indices.joinToString(", ") { "%s %.0f".format(SEA[it].name, t(sea[it], seaKm2) * 1000 / SEA[it].massKg) },
            colonyBirds.sum(), colonyBirds.count { it > 0f })
    }

    /** A sea animal as a density: its food, how much it eats, how it breeds and dies, how far it ranges. */
    class SeaKind(
        val name: String, val massKg: Float, val food: SeaFood, val dailyShare: Float, val dailyKm: Float,
        val birthMonth: Int, val birthMonths: Int, val youngPerFemale: Float, val backgroundMortality: Float,
        val defence: Float, val startKm2: Float, val maxShoreKm: Float = Float.POSITIVE_INFINITY,
        val nestsAshore: Boolean = false,
    )

    enum class SeaFood { PLANKTON, FISH, PREY }

    companion object {
        val SEA = listOf(
            SeaKind("fin whale", 50_000f, SeaFood.PLANKTON, 0.015f, 100f, 0, 2, 0.4f, 0.03f, 1f, 800f),
            SeaKind("dolphin", 80f, SeaFood.FISH, 0.05f, 60f, 6, 3, 0.4f, 0.06f, 0.7f, 15f),
            SeaKind("porpoise", 55f, SeaFood.FISH, 0.08f, 30f, 6, 2, 0.8f, 0.12f, 0.4f, 20f, maxShoreKm = 25f),
            SeaKind("monk seal", 300f, SeaFood.FISH, 0.05f, 20f, 9, 3, 0.5f, 0.07f, 0.3f, 30f, maxShoreKm = 12f),
            SeaKind("shark", 800f, SeaFood.PREY, 0.01f, 60f, 7, 2, 0.6f, 0.05f, 1f, 2500f),
            // Loggerheads live on jellyfish, crabs and the like (here: the zooplankton) and come ashore to lay in summer.
            SeaKind("loggerhead turtle", 100f, SeaFood.PLANKTON, 0.01f, 30f, 7, 2, 1f, 0.05f, 0.8f, 50f, nestsAshore = true),
        )
        /** Phytoplankton, g/m², that a full nutrient supply can sustain. */
        const val NUTRIENT_SCALE = 10f
        /** Fish on a rich shelf, kg/km²; per km² of river water at summer warmth; shellfish on a rich bed. */
        const val MAX_FISH = 6000f
        const val RIVER_FISH_PER_KM2_WATER = 15_000f
        const val MAX_SHELLFISH = 20_000f
        const val FISH_GROWTH = 0.6f
        /** Shellfish's yearly growth at low numbers (paid for in plankton). */
        const val SHELL_GROWTH = 0.8f
        /** Share of what fish eat that becomes fish. */
        const val FISH_EFFICIENCY = 0.2f
        /** Fish at the start as a share of what the water can carry; share of the water mixing with the neighbours' a week. */
        const val FISH_START = 0.2f
        const val DRIFT = 0.1f
        const val SEA_FISH_GROWTH = 2f
        /** Yearly share of fish dying of age and disease (what nothing eats). */
        const val FISH_DEATHS = 0.3f
        /** Mean gradient between hexes run fish cannot climb. */
        const val CASCADE_GRADIENT = 0.012f
        const val RUN_START_DAY = 120
        const val RUN_DAYS = 60
        const val RUN_KM_PER_DAY = 20f
        /** Share of the fish off a river mouth that run up it in spring; share of the run that lives to go back. */
        const val RUN_SHARE = 0.05f
        const val RUN_SURVIVAL = 0.7f
        /** Upwelling: daily share of the gap to the deep water's nutrients mixed up at full strength; wind for full strength, m/s; weeks to answer a change of wind. */
        const val UPWELL_MIX = 0.1f
        /** The deep water: nutrients it holds when renewed, as a share of a full supply; its volume as a multiple of the surface water's; yearly share renewed by the ocean beyond. */
        const val DEEP_BASE = 1f
        const val DEEP_VOLUME = 5f
        const val DEEP_RENEW = 0.5f
        /** Share of the fish that move a step inshore (or back out) a month in their season. */
        const val SHOAL = 0.1f
        /** Turtles: the months they come ashore to lay; the least sea warmth, °C, for a nesting beach. */
        val NESTING = 5..7
        const val NEST_TEMP = 22f
        val TURTLE = SEA.indexOfFirst { it.nestsAshore }
        const val UPWELL_WIND = 8f
        const val UPWELL_WEEKS = 3f
        /** Sea animals: a full sea as a multiple of the starting density; disease beyond it; shark weight per km² for full danger; share of prey sharks take. */
        const val SEA_FULL = 8f
        /** How hard each kind of fish is for a band to come by: the stock, as a multiple of what it wants, that gives it half of that (a spawning run crowds a river; sea fish are scattered). */
        const val RUN_SEARCH = 2f
        const val RIVER_SEARCH = 20f
        const val FISH_SEARCH = 100f
        const val SHELLFISH_SEARCH = 50f
        /** Fish, kg/km², at which hunters find them half as easily as in a rich sea. */
        const val FISH_HALF = 1000f
        /** Fish (kg/km²) at which a fish-eating sea animal gets half of what it needs. */
        const val FISH_FIND_HALF = 100f
        /** Zooplankton (kg/km²) at which a filter-feeding whale gets half of what it needs. */
        const val ZOO_HALF = 100f
        const val SEA_DISEASE = 0.5f
        const val SHARK_KG_KM2 = 0.5f
        /** Yearly share of seals, porpoises and dolphins sharks take where sharks are at full strength. */
        const val SHARK_ATTACK = 0.1f
        /** Sea birds: reach from the colony, km; food a day, kg; share of the fish within reach a colony takes in a year at most; coast per colony, km; young a year. */
        const val BIRD_FORAGE_KM = 40.0
        const val BIRD_KG_DAY = 0.25f
        /** Share of the fish within reach a colony can catch in a week; how far young birds go to settle an empty cliff, km. */
        const val BIRD_CATCH = 0.01f
        const val RESETTLE_KM = 200.0
        const val COLONY_SPACING_KM = 25.0
        const val BIRD_BREED = 0.4f
        /** Crocodiles: coldest month above which they live, °C; yearly share of drinking herds' young they take. */
        const val CROC_COLDEST = 12f
        const val CROC_RATE = 0.1f
    }
}
