package inflation.ecology

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.LiveWater
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The life in the soil and the small animals of each place (height band of a hex), as in the 1 km
 * model's soil life and small fauna:
 *
 * - Soil: dead plant matter falls as litter and rots, faster in warm, moist soil, building organic
 *   matter that enriches the soil; soil animals live on the rotting. Carcasses rot too, half gone in
 *   about ten days in summer heat and far slower in the cold, and scavengers take what they find.
 * - Plant-eating insects (grasshoppers, caterpillars, beetles): boom in warm weeks on green plants,
 *   wait out cold and hot drought as eggs.
 * - Biting insects (mosquitoes, midges): breed in standing water when it is warm.
 * - Rodents and other small mammals: breed fast on seeds and herbs in good years, crash in drought,
 *   cold and deep snow.
 * - Reptiles and amphibians: active and breeding on insects in warm, moist weeks, torpid in cold
 *   winters and hot, dry summers.
 * - Pollinators: build up on flowers in spring, fewer where grazing is hard; how well the flowers
 *   were visited sets the summer and autumn fruit crop.
 * - Small game, as in the 1 km model's small game: hares on open grass, rock hyrax in broken
 *   country, six wild birds each on its own ground (quail and greylag geese come and go with the
 *   seasons), tortoises in warm scrub, and foxes and wildcats living on the rest.
 * - Griffon vulture colonies on the most broken ground, one to a block of [VULTURE_SPACING_KM],
 *   soaring up to [VULTURE_RANGE_KM] for carcasses, nearest first.
 * - Golden eagles nesting on crags, as a share of each place's ground they hunt over (0..1): they
 *   fly over any ground, take hyrax, hares and rock birds, and the young of the smaller hoofed
 *   animals in the open; they thrive where the crags and the game are, and dwindle where food fails.
 * - Fire kills what cannot flee. Every month each kind spreads a little to the places around.
 */
class HexSmallLife(private val hw: HexWorld, private val land: HexLand, private val days: Int) {
    val n = land.n
    /** Soil: litter and organic matter, kg/m²; carrion, kg/m²; soil animals, g/m². */
    val litter = FloatArray(n) { p -> 0.5f * (land.herb[p] + 0.2f * land.shrub[p] + 0.04f * land.tree[p]) + 0.3f }
    val organicMatter = FloatArray(n) { p -> 4f * min(1f, land.biomass(p) / 5f) }
    val carrion = FloatArray(n)
    val soilFauna = FloatArray(n)
    /** Small animals, kg/km². */
    val insects = FloatArray(n) { p -> 300f * min(1f, land.herb[p] / 0.4f) }
    val biting = FloatArray(n)
    val rodents = FloatArray(n) { p -> 150f * min(1f, land.herb[p] / 0.4f) }
    val herps = FloatArray(n) { p -> 20f * min(1f, land.herb[p] / 0.4f) }
    val pollinators = FloatArray(n) { p -> 10f * min(1f, land.herb[p] / 0.4f) }
    /** How well this year's flowers were pollinated, 0..1: sets the fruit crop. */
    val pollination = FloatArray(n) { 0.6f }
    /** Small game, kg/km². */
    val hares = FloatArray(n)
    val hyrax = FloatArray(n)
    val tortoises = FloatArray(n)
    val foxes = FloatArray(n)
    /** Each kind of [SmallGame.BIRDS], and all together, kg/km². */
    val bird = Array(SmallGame.BIRDS.size) { FloatArray(n) }
    val birds = FloatArray(n)
    /** Eagles hunting over each place, 0..1, and as many as the crags allow. */
    val eagles = FloatArray(n) { p -> min(1f, 2f * land.ruggedness[p]) }
    private val eagleCrags = eagles.copyOf()
    /** Vulture colonies: nest place, the birds, and the places within reach, nearest first. */
    val vulturePlace = ArrayList<Int>()
    val vultures = ArrayList<Float>()
    private val vultureReach = ArrayList<IntArray>()
    private var steps = 0
    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** Share of each place that is wetland, where biting insects breed. */
    val wetland = FloatArray(n) { p -> hw.placeCells[p].count { val b = hw.world.biome[it]; b.name in WETLANDS }.toFloat() / hw.placeCells[p].size }
    /** Marsh, bog and floodplain, where ducks and geese feed. */
    private val waterfowl = FloatArray(n) { p -> min(1f, wetland[p] + land.floodShare[p]) }
    /** The year's mean temperature and rain at each place. */
    private val meanTemp = FloatArray(n) { p -> (0 until 12).map { hw.placeTemp(p, it) }.average().toFloat() }
    private val annualRain = FloatArray(n) { p -> (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat() }

    init {
        for (p in 0 until n) {
            if (land.biomass(p) <= 0f) continue
            hares[p] = 0.5f * hareCapacity(p); hyrax[p] = 0.5f * hyraxCapacity(p)
            for (k in bird.indices) bird[k][p] = 0.5f * birdCapacity(k, p)
            birds[p] = bird.sumOf { it[p].toDouble() }.toFloat()
            tortoises[p] = 0.5f * tortoiseCapacity(p); foxes[p] = 0.3f
        }
        // Vultures: the most broken ground with plants in each block, if broken enough for cliffs.
        val hex = hw.hex
        val blocks = HashMap<Long, Int>()
        for (p in 0 until n) {
            if (land.biomass(p) <= 0f || land.ruggedness[p] <= 0.5f) continue
            val (x, y) = hex.centreKm(hw.placeHex[p])
            val key = (x / VULTURE_SPACING_KM).toLong() * 100000 + (y / VULTURE_SPACING_KM).toLong()
            val best = blocks[key]
            if (best == null || land.ruggedness[p] > land.ruggedness[best]) blocks[key] = p
        }
        for (c in blocks.values.sorted()) {
            val (cx, cy) = hex.centreKm(hw.placeHex[c])
            fun dist(q: Int): Double { val (x, y) = hex.centreKm(hw.placeHex[q]); return kotlin.math.hypot(x - cx, y - cy) }
            val near = (0 until n).filter { dist(it) <= VULTURE_RANGE_KM }.sortedBy { dist(it) }
            vulturePlace += c; vultures += 40f; vultureReach += near.toIntArray()
        }
    }

    private fun open(p: Int) = 1f - min(1f, land.canopy(p) + 0.5f * min(1f, land.shrub[p] / 1.5f))
    private fun hareCapacity(p: Int) = 40f * land.herb[p] / (land.herb[p] + 0.3f) * open(p)
    private fun hyraxCapacity(p: Int) = 80f * land.ruggedness[p].pow(1.5f) * min(1f, land.shrub[p] / 0.5f + 0.2f)
    private fun birdCapacity(k: Int, p: Int): Float {
        val grass = land.herb[p] / (land.herb[p] + 0.2f)
        val shrubby = min(1f, land.shrub[p])
        val rugged = land.ruggedness[p]
        val dry = (1f - annualRain[p] / 500f).coerceIn(0f, 1f)
        return when (SmallGame.BIRDS[k].name) {
            "chukar" -> 6f * min(1f, rugged / 0.5f) * (0.3f + 0.7f * open(p)) * (0.3f + 0.7f * shrubby) * grass
            "quail" -> 5f * open(p) * grass
            "sandgrouse" -> 3f * open(p) * dry
            "rock dove" -> 4f * rugged.pow(1.5f) * (0.3f + 0.7f * grass)
            "mallard" -> 6f * waterfowl[p] * (0.5f + 0.5f * grass)
            "greylag goose" -> 10f * waterfowl[p] * grass
            else -> 0f
        }
    }
    private fun tortoiseCapacity(p: Int): Float {
        val warm = ((meanTemp[p] - 12f) / 8f).coerceIn(0f, 1f)
        return 30f * warm * min(1f, (land.shrub[p] + land.herb[p]) / 0.8f)
    }

    fun step(weather: Weather, carcassKg: FloatArray) {
        val month = weather.date.month
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val t = weather.temperature[p]
            val soilWater = land.soil[p]
            // ---- soil: decomposition doubles every 10 °C up to about 30 °C, stops in dry or frozen soil.
            val wet = (soilWater / 75f).coerceIn(0f, 1f)
            val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
            litter[p] += land.litterfall[p]
            val decomposed = litter[p] * min(1f, 1.2f * warm * wet * years)
            litter[p] -= decomposed
            organicMatter[p] += 0.3f * decomposed - 0.05f * years * organicMatter[p]
            land.organicFertility[p] = 0.3f * organicMatter[p] / (organicMatter[p] + 4f)
            soilFauna[p] = 20f * decomposed / years
            // Carcasses of the week, then rotting.
            carrion[p] += carcassKg[p] / (land.areaKm2[p] * 1e6f)
            if (carrion[p] > 0f) {
                val gone = carrion[p] * min(1f, 0.07f * max(0.1f, warm) * days)
                carrion[p] -= gone; litter[p] += 0.3f * gone
                if (carrion[p] < 1e-9f) carrion[p] = 0f
            }
            val food = land.herb[p] + 0.3f * land.shrub[p]
            val snow = weather.snowpack[p]
            // ---- plant-eating insects
            var ins = insects[p]
            val dry = soilWater < 10f
            if (t > 32f && dry) ins -= ins * min(0.5f, 2f * years)
            else if (t > 10f) {
                val ceiling = 2000f * food / (food + 0.15f)
                val g = 15f * years * ((t - 10f) / 15f).coerceIn(0f, 1f)
                ins += ins * g * (1 - ins / ceiling.coerceAtLeast(10f)) - ins * 2f * years
                val eaten = ins * 0.25f * days / 1e6f
                land.consume(p, LifeForm.HERB, eaten * 0.8f); land.consume(p, LifeForm.SHRUB, eaten * 0.2f)
            } else ins -= ins * min(0.9f, 6f * years)
            insects[p] = ins.coerceIn(5f, 3000f)
            // ---- biting insects: standing water and warmth.
            val standing = land.flood[p] > 0f || wetland[p] > 0.1f || soilWater / LiveWater.SOIL_CAPACITY > 0.9f
            biting[p] = if (t > 15f && standing) min(500f, biting[p] + 60f * days * max(wetland[p], 0.3f)) else biting[p] * 0.7f
            // ---- rodents: logistic on food, losses to cold and deep snow; they eat ~10% of their mass a day.
            var r = rodents[p]
            val birth = if (t > 5f) 5f * years * food / (food + 0.2f) else 0f
            val death = 1.2f * years + if (snow > 30f || t < -5f) 3f * years else 0f
            val ceil = 600f * food / (food + 0.3f)
            r += r * birth * (1 - r / ceil.coerceAtLeast(1f)) - r * death
            rodents[p] = r.coerceIn(1f, 3000f)
            land.consume(p, LifeForm.HERB, rodents[p] * 0.1f * days / 1e6f)
            // ---- reptiles and amphibians
            var h = herps[p]
            if (herpsActive(t, soilWater)) {
                val moist = (soilWater / 40f).coerceIn(0.2f, 1f)
                val grow = 4f * years * moist * insects[p] / (insects[p] + 400f)
                val hc = 80f * food / (food + 0.3f)
                h += h * grow * (1 - h / hc.coerceAtLeast(1f)) - h * years
                insects[p] = max(5f, insects[p] - h * 0.05f * days)
            } else h -= h * 0.3f * years
            herps[p] = h.coerceIn(0.5f, 200f)
            // ---- fire kills what cannot flee, on the share that burned.
            val b = land.burning[p]
            if (b > 0f) { insects[p] *= 1f - 0.8f * b; herps[p] *= 1f - 0.7f * b; rodents[p] *= 1f - 0.5f * b; pollinators[p] *= 1f - 0.5f * b }
            // ---- pollinators on flowers in spring; grazing leaves fewer flowers.
            val flowering = month in 0..4
            val flowers = (land.herb[p] + 0.5f * land.shrub[p]) * (if (flowering) 1f else 0.2f) * (1f - 0.5f * (1f - exp(-land.grazing[p] / 5f)))
            var po = pollinators[p]
            if (t > 12f) {
                val pc = 50f * flowers / (flowers + 0.3f)
                po += po * 10f * years * (1 - po / pc.coerceAtLeast(0.5f)) - po * years
            } else po -= po * min(0.5f, 2f * years)
            pollinators[p] = po.coerceIn(0.2f, 100f)
            if (flowering) pollination[p] += 0.2f * (min(1f, pollinators[p] / 20f) - pollination[p])
            stepGame(p, t, month, b)
        }
        stepVultures(weather)
        if (++steps % 5 == 0) for (f in listOf(insects, rodents, herps, pollinators, hares, foxes) + bird) spread(f)
    }

    /** Small game at place [p] this week: [t] its temperature, [burnt] the share that burned. */
    private fun stepGame(p: Int, t: Float, month: Int, burnt: Float) {
        if (land.biomass(p) <= 0f) { hares[p] = 0f; hyrax[p] = 0f; birds[p] = 0f; for (b in bird) b[p] = 0f; tortoises[p] = 0f; foxes[p] = 0f; return }
        val mild = t > 5f
        hares[p] = logistic(hares[p], if (month in 0..6 && mild) 4f else 0f, 0.9f, hareCapacity(p))
        hyrax[p] = logistic(hyrax[p], if (month in 1..3) 2.5f else 0f, 0.4f, hyraxCapacity(p))
        var all = 0f
        for (k in bird.indices) {
            val b = SmallGame.BIRDS[k]; val f = bird[k]
            val here = b.present?.contains(month) ?: true
            f[p] = when {
                // Migrants arrive with the season, as many as the ground draws, and leave.
                !here -> f[p] * 0.3f
                b.present != null && !b.present.contains((month + 11) % 12) -> max(f[p], 0.4f * birdCapacity(k, p))
                else -> logistic(f[p], if (month in b.breedMonths && mild) b.growth else 0f, b.deaths, birdCapacity(k, p))
            }
            all += f[p]
        }
        birds[p] = all
        val active = t in 14f..33f && land.soil[p] > 8f
        tortoises[p] = logistic(tortoises[p], if (active && month in 1..5) 0.8f else 0f, if (active) 0.15f else 0.05f, tortoiseCapacity(p))
        // Foxes and wildcats eat about a seventh of their weight a day, mostly rodents, then hares and
        // birds; they breed in spring as the food allows, and die back when it fails.
        val prey = rodents[p] + hares[p] + birds[p] + 0.03f * insects[p]
        val need = foxes[p] * 0.15f * days
        val got = min(need, 0.1f * prey)
        if (got > 0f && prey > 0f) {
            val f = got / prey
            rodents[p] -= rodents[p] * f; hares[p] -= hares[p] * f; takeBirds(p, f)
        }
        val fed = if (need > 0f) got / need else 1f
        foxes[p] = logistic(foxes[p], if (month in 1..3) 1.5f * fed else 0f, 0.4f + 0.8f * (1f - fed), 0.02f * prey).coerceAtLeast(0.01f)
        // Eagles: a pair needs about a kilo a day; they take what is out in the open among the rocks.
        if (eagleCrags[p] > 0f) {
            val open = open(p)
            val prey = (hyrax[p] + hares[p] + bird[CHUKAR][p] + bird[ROCK_DOVE][p]) * (0.3f + 0.7f * open)
            val need = eagles[p] * EAGLE_NEED * days
            val got = min(need, 0.1f * prey)
            val total = hyrax[p] + hares[p] + bird[CHUKAR][p] + bird[ROCK_DOVE][p]
            if (got > 0f && total > 0f) {
                val f = got / total
                hyrax[p] -= hyrax[p] * f; hares[p] -= hares[p] * f
                birds[p] -= bird[CHUKAR][p] * f + bird[ROCK_DOVE][p] * f
                bird[CHUKAR][p] -= bird[CHUKAR][p] * f; bird[ROCK_DOVE][p] -= bird[ROCK_DOVE][p] * f
            }
            // Fawns and kids make up much of what the small game does not.
            val fed = if (need > 0f) min(1f, got / need + 0.3f) else 1f
            eagles[p] = logistic(eagles[p], if (month in 1..3) 0.6f * fed else 0f, 0.1f + 0.6f * (1f - fed), eagleCrags[p]).coerceAtLeast(0.02f * eagleCrags[p])
        }
        // Fire: tortoises die where it burns; the rest bolt and many young die.
        if (burnt > 0f) { tortoises[p] *= 1f - 0.6f * burnt; hares[p] *= 1f - 0.3f * burnt; takeBirds(p, 0.3f * burnt) }
    }

    /** Yearly share of the young of small hoofed animals eagles take in place [p]. */
    fun eagleRisk(p: Int) = EAGLE_RATE * eagles[p] * open(p)

    /** Takes share [f] of every kind of bird at [p]. */
    private fun takeBirds(p: Int, f: Float) {
        for (b in bird) b[p] -= b[p] * f
        birds[p] -= birds[p] * f
    }

    /** Logistic growth at [r] a year in season, deaths at [d] a year, toward [k]. */
    private fun logistic(x: Float, r: Float, d: Float, k: Float): Float {
        val kk = max(k, 0.01f)
        return max(0.001f, x + x * r * years * (1f - x / kk) - x * d * years * if (x > kk) 2f else 1f)
    }

    /**
     * Vultures soar out from their colony each day and find the carcasses within reach, nearest
     * first, before most other scavengers; a colony grows when they eat well and shrinks when not.
     */
    private fun stepVultures(weather: Weather) {
        val month = weather.date.month
        for (k in vulturePlace.indices) {
            val need = vultures[k] * 0.5f * days
            var got = 0f
            for (q in vultureReach[k]) {
                if (got >= need) break
                if (carrion[q] > 0f) { val m2 = land.areaKm2[q] * 1e6f; got += scavenge(q, (need - got) / m2) * m2 }
            }
            val fed = if (need > 0f) min(1f, got / need) else 1f
            var b = vultures[k]
            if (month in 1..2) b += b * 0.5f * fed * years * 6f // one chick a pair, in late winter
            b -= b * (0.08f * years + 0.4f * years * (1f - fed))
            vultures[k] = b.coerceIn(2f, 2000f)
        }
    }

    /** Whether reptiles and amphibians are out: warm, and not baking in a drought. */
    fun herpsActive(t: Float, soilWater: Float) = t in 12f..34f && !(t > 28f && soilWater < 10f)

    /**
     * Takes up to [kgPerKm2] of small prey in place [p]: rodents, hares, hyrax and birds, and when
     * they are out, reptiles, amphibians and tortoises, each in proportion to what there is, at most
     * half of all; returns kg per km² caught.
     */
    fun catchSmall(p: Int, kgPerKm2: Float, weather: Weather): Float {
        val t = weather.temperature[p]
        val herpsOut = herpsActive(t, land.soil[p])
        val tortOut = t in 14f..33f
        val total = rodents[p] + hares[p] + hyrax[p] + birds[p] + (if (herpsOut) herps[p] else 0f) + (if (tortOut) tortoises[p] else 0f)
        if (total <= 0f) return 0f
        val take = min(kgPerKm2, 0.5f * total)
        val f = take / total
        rodents[p] -= rodents[p] * f; hares[p] -= hares[p] * f; hyrax[p] -= hyrax[p] * f; takeBirds(p, f)
        if (herpsOut) herps[p] -= herps[p] * f
        if (tortOut) tortoises[p] -= tortoises[p] * f
        return take
    }

    /** Totals over the land, tonnes, and vultures in all colonies. */
    fun summary(): String {
        fun t(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } / 1000
        return "rodents %.0f t, hares %.0f t, hyrax %.0f t, birds %.0f t (%s), tortoises %.0f t, foxes %.0f t, eagles %.0f pairs, %d vulture colonies %.0f birds".format(
            t(rodents), t(hares), t(hyrax), t(birds), bird.indices.joinToString(", ") { "%s %.0f".format(SmallGame.BIRDS[it].name, t(bird[it])) },
            t(tortoises), t(foxes), eagles.indices.sumOf { (eagles[it] * land.areaKm2[it]).toDouble() } / EAGLE_KM2, vultures.size, vultures.sum())
    }

    /** Takes up to [kgPerM2] of carrion in place [p]; returns what was taken. */
    fun scavenge(p: Int, kgPerM2: Float): Float { val t = min(carrion[p], kgPerM2); carrion[p] -= t; return t }

    /** The fruit crop's share of what the trees could bear, from this year's pollination. */
    fun fruitSet(p: Int) = 0.3f + 0.7f * pollination[p]

    private fun spread(f: FloatArray) {
        val old = f.copyOf()
        for (p in 0 until n) {
            val nb = land.neighbours[p]
            if (nb.isEmpty()) continue
            var s = 0f; for (q in nb) s += old[q]
            f[p] = 0.8f * old[p] + 0.2f * s / nb.size
        }
    }

    companion object {
        val WETLANDS = setOf("MARSH", "SWAMP_FOREST", "BOG", "SALT_MARSH", "MANGROVE")
        /** How far vultures soar from the colony for carcasses, km; land per colony at most, km. */
        const val VULTURE_RANGE_KM = 80.0
        const val VULTURE_SPACING_KM = 80.0
        /** Eagles: what the eagles over a place eat at full strength (a pair to [EAGLE_KM2]), kg/km² a day; yearly share of young taken in open crag country. */
        const val EAGLE_NEED = 0.01f
        /** Ground a pair hunts over where the eagles are at full strength, km². */
        const val EAGLE_KM2 = 100.0
        const val EAGLE_RATE = 0.15f
        private val CHUKAR = SmallGame.BIRDS.indexOfFirst { it.name == "chukar" }
        private val ROCK_DOVE = SmallGame.BIRDS.indexOfFirst { it.name == "rock dove" }
    }
}
