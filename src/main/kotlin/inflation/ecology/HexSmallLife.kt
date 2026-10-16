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
/**
 * A wild bird as a density field: when it breeds, how fast it grows and dies back, the months it is
 * here at all (migrants), and [domestication] by the same six criteria as the large animals (diet
 * breadth, growth, captive breeding, temperament, calm, hierarchy).
 */
class BirdKind(
    val name: String,
    val breedMonths: IntRange,
    /** Young raised a year per head; yearly share of the birds dying. */
    val growth: Float,
    val deaths: Float,
    /** Months present; null for residents. Outside them the birds are away. */
    val present: Set<Int>?,
    val domestication: FloatArray,
)

class HexSmallLife(private val hw: HexWorld, private val land: HexLand, private val days: Int) {
    val n = land.n
    /** Soil: litter and organic matter, kg/m²; carrion, kg/m²; soil animals, g/m². */
    val litter = FloatArray(n) { p -> 0.5f * (land.herb[p] + 0.2f * land.shrub[p] + 0.04f * land.tree[p]) + 0.3f }
    val organicMatter = FloatArray(n) { p -> 4f * min(1f, land.biomass(p) / 5f) }
    val carrion = FloatArray(n)
    /** Bones left when the meat is gone, kg/m²: slow to weather, food for bone-crackers. */
    val bones = FloatArray(n)
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
    /** Owls, kestrels, buzzards and kites hunting rodents, kg/km². */
    val owls = FloatArray(n)
    /** Desert locusts, kg/km²: swarms that rise in the desert after a wet spring, strip the green and move on. */
    val locusts = FloatArray(n)
    /** Each kind of [BIRDS], and all together, kg/km². */
    val bird = Array(BIRDS.size) { FloatArray(n) }
    val birds = FloatArray(n)
    /** Eagles hunting over each place, 0..1, and as many as the crags allow. */
    val eagles = FloatArray(n) { p -> min(1f, 2f * land.ruggedness[p]) }
    private val eagleCrags = eagles.copyOf()
    /** Vulture colonies: nest place, the birds, and the places within reach, nearest first. */
    val vulturePlace = ArrayList<Int>()
    val vultures = ArrayList<Float>()
    private val vultureReach = ArrayList<IntArray>()
    /** Dung dropped this week by the herds and packs, kg/m²: it goes to the litter. */
    val dung = FloatArray(n)
    /** Small animals that died this week in each place, kg/km²: they go to the litter. */
    private val smallDead = FloatArray(n)
    /** Migrant birds away beyond the map, kg, per kind; and how much ground each kind finds when it comes back. */
    private val away = DoubleArray(BIRDS.size)
    private val returning = DoubleArray(BIRDS.size)
    /** Migrant birds leaving this week from each place, kg (summed in place order after the parallel loop, so the sum is the same on any number of cores). */
    private val leaving = Array(BIRDS.size) { FloatArray(n) }
    private var lastMonth = -1
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
        return when (BIRDS[k].name) {
            "chukar" -> 6f * min(1f, rugged / 0.5f) * (0.3f + 0.7f * open(p)) * (0.3f + 0.7f * shrubby) * grass
            "quail" -> 5f * open(p) * grass
            "sandgrouse" -> 3f * open(p) * dry
            "rock dove" -> 4f * rugged.pow(1.5f) * (0.3f + 0.7f * grass)
            "mallard" -> 6f * waterfowl[p] * (0.5f + 0.5f * grass)
            "greylag goose" -> 10f * waterfowl[p] * grass
            "crane" -> 8f * waterfowl[p] * (0.5f + 0.5f * grass) + 1f * open(p) * grass
            "white stork" -> 3f * waterfowl[p] + 2f * open(p) * grass
            else -> 0f
        }
    }
    private fun tortoiseCapacity(p: Int): Float {
        val warm = ((meanTemp[p] - 12f) / 8f).coerceIn(0f, 1f)
        return 30f * warm * min(1f, (land.shrub[p] + land.herb[p]) / 0.8f)
    }

    fun step(weather: Weather, carcassKg: FloatArray) {
        val month = weather.date.month
        // Migrant birds: those leaving this week go beyond the map; those coming back spread over the
        // ground that draws them, as many as survived the winter away.
        for (k in BIRDS.indices) {
            val pr = BIRDS[k].present ?: continue
            returning[k] = 0.0
            if (lastMonth >= 0 && month in pr && lastMonth !in pr) {
                var cap = 0.0
                for (p in 0 until n) cap += (birdCapacity(k, p) * land.areaKm2[p]).toDouble()
                // Kinds that breed while away (winter visitors such as geese) come back with their young.
                val b = BIRDS[k]
                // (Passage migrants come through twice: only the passage after the breeding months brings young.)
                val awayMonths = generateSequence((month + 11) % 12) { (it + 11) % 12 }.takeWhile { it !in pr }.toSet()
                val bredAway = if (b.breedMonths.any { it in awayMonths }) 1f + b.growth else 1f
                returning[k] = if (cap > 0.0) away[k] * MIGRANT_SURVIVAL * bredAway / cap else 0.0
                away[k] = 0.0
            }
        }
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            var pt = Profile.mark()
            val t = weather.temperature[p]
            val soilWater = land.soil[p]
            // ---- soil: decomposition doubles every 10 °C up to about 30 °C, stops in dry or frozen soil.
            val wet = (soilWater / 75f).coerceIn(0f, 1f)
            val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
            // What fell, what the animals dropped, and the small animals that died.
            litter[p] += land.litterfall[p] + dung[p] + smallDead[p] / 1e6f
            dung[p] = 0f; smallDead[p] = 0f
            val decomposed = litter[p] * min(1f, 1.2f * warm * wet * years)
            litter[p] -= decomposed
            organicMatter[p] += 0.3f * decomposed - 0.05f * years * organicMatter[p]
            land.organicFertility[p] = 0.3f * organicMatter[p] / (organicMatter[p] + 4f)
            soilFauna[p] = 20f * decomposed / years
            // What lay from earlier weeks rots (half in three or four days at 15 °C, faster in the heat);
            // then the week's carcasses are added, fresh for the vultures and scavengers this week.
            if (carrion[p] > 0f) {
                val gone = carrion[p] * (1f - exp(-CARRION_ROT * max(0.1f, warm) * days))
                carrion[p] -= gone; litter[p] += 0.3f * gone - BONE_SHARE * gone; bones[p] += BONE_SHARE * gone
                if (carrion[p] < 1e-9f) carrion[p] = 0f
            }
            carrion[p] += carcassKg[p] / (land.areaKm2[p] * 1e6f)
            // Bones weather away over a couple of years into the soil.
            if (bones[p] > 0f) { val b = bones[p] * min(1f, years / 2f); bones[p] -= b; litter[p] += b }
            val food = land.herb[p] + 0.3f * land.shrub[p]
            val snow = weather.snowpack[p]
            pt = Profile.lap("small life: soil and decay", pt)
            // ---- plant-eating insects
            var ins = insects[p]
            val dry = soilWater < 10f
            val insDead: Float
            if (t > 32f && dry) { insDead = ins * min(0.5f, 2f * years); ins -= insDead }
            else if (t > 10f) {
                val ceiling = 2000f * food / (food + 0.15f)
                val g = 15f * years * ((t - 10f) / 15f).coerceIn(0f, 1f)
                insDead = ins * 2f * years
                ins += ins * g * (1 - ins / ceiling.coerceAtLeast(10f)) - insDead
                // They eat about a quarter of their weight a day, and half of it comes back as frass.
                val eaten = land.consume(p, LifeForm.HERB, ins * 0.2f * days / 1e6f) + land.consume(p, LifeForm.SHRUB, ins * 0.05f * days / 1e6f)
                litter[p] += FRASS * eaten
            } else { insDead = ins * min(0.9f, 6f * years); ins -= insDead }
            smallDead[p] += insDead
            insects[p] = capped(p, ins, 3000f)
            pt = Profile.lap("small life: insects", pt)
            // ---- biting insects: standing water and warmth.
            val standing = land.flood[p] > 0f || wetland[p] > 0.1f || soilWater / LiveWater.SOIL_CAPACITY > 0.9f
            biting[p] = if (t > 15f && standing) min(500f, biting[p] + 60f * days * max(wetland[p], 0.3f)) else biting[p] * 0.7f
            pt = Profile.lap("small life: biting insects", pt)
            // ---- rodents (a female raises some twenty young a year): they eat ~10% of their mass a day, and
            // a mouse or vole lasts only a few days without food, so the share that found none in the week
            // dies. They breed on fresh growth (young shoots and seeds carry the protein a litter needs),
            // logistic on food; losses to cold and deep snow.
            var r = rodents[p]
            val need = r * 0.1f * days / 1e6f
            val eaten = land.consume(p, LifeForm.HERB, need)
            litter[p] += FRASS * eaten
            val fed = if (need > 0f) eaten / need else 1f
            val fresh = food * land.green[p]
            val birth = if (t > 5f) 10f * years * fed * fresh / (fresh + 0.05f) else 0f // (half the most at 0.05 kg/m² of green forage)
            val death = RODENT_DEATH * years + (if (snow > 30f || t < -5f) 3f * years else 0f)
            val starved = r * (1f - fed)
            val ceil = 600f * food / (food + 0.3f)
            r += r * birth * (1 - r / ceil.coerceAtLeast(1f)) - r * death - starved
            smallDead[p] += rodents[p] * death + starved
            rodents[p] = capped(p, r, 3000f)
            pt = Profile.lap("small life: rodents", pt)
            // ---- reptiles and amphibians
            var h = herps[p]
            if (herpsActive(t, soilWater)) {
                val moist = (soilWater / 40f).coerceIn(0.2f, 1f)
                val grow = 4f * years * moist * insects[p] / (insects[p] + 400f)
                val hc = 80f * food / (food + 0.3f)
                smallDead[p] += h * years
                h += h * grow * (1 - h / hc.coerceAtLeast(1f)) - h * years
                insects[p] = max(0f, insects[p] - h * 0.05f * days)
            } else { smallDead[p] += h * 0.3f * years; h -= h * 0.3f * years }
            herps[p] = capped(p, h, 200f)
            pt = Profile.lap("small life: reptiles and amphibians", pt)
            // ---- fire kills what cannot flee, on the share that burned.
            val b = land.burning[p]
            if (b > 0f) {
                smallDead[p] += insects[p] * 0.8f * b + herps[p] * 0.7f * b + rodents[p] * 0.5f * b + pollinators[p] * 0.5f * b
                insects[p] *= 1f - 0.8f * b; herps[p] *= 1f - 0.7f * b; rodents[p] *= 1f - 0.5f * b; pollinators[p] *= 1f - 0.5f * b
            }
            pt = Profile.lap("small life: fire", pt)
            // ---- pollinators on flowers in spring; grazing leaves fewer flowers.
            val flowering = month in 3..7
            val flowers = (land.herb[p] + 0.5f * land.shrub[p]) * (if (flowering) 1f else 0.2f) * (1f - 0.5f * (1f - exp(-land.grazing[p] / 5f)))
            var po = pollinators[p]
            if (t > 12f) {
                val pc = 50f * flowers / (flowers + 0.3f)
                smallDead[p] += po * years
                po += po * 10f * years * (1 - po / pc.coerceAtLeast(0.5f)) - po * years
            } else { smallDead[p] += po * min(0.5f, 2f * years); po -= po * min(0.5f, 2f * years) }
            pollinators[p] = capped(p, po, 100f)
            if (flowering) pollination[p] += 0.2f * (min(1f, pollinators[p] / 20f) - pollination[p])
            pt = Profile.lap("small life: pollinators", pt)
            stepGame(p, t, month, b); Profile.lap("small life: hares, hyrax, birds, tortoises, foxes, owls, eagles", pt)
        }
        Profile.part("small life: locusts") { stepLocusts(weather) }
        for (k in BIRDS.indices) { val l = leaving[k]; var sum = 0.0; for (p in l.indices) if (l[p] != 0f) { sum += l[p]; l[p] = 0f }; away[k] += sum }
        lastMonth = month
        Profile.part("small life: vultures") { stepVultures(weather) }
        // A little every week, not all at once each month: a monthly lump showed as a pulse at the
        // start of each month where the spreaders arrived and then died back over the weeks.
        Profile.part("small life: spreading to neighbours") {
            spread(spreaders)
            spread(eagles) { eagleCrags[it] > 0f }
        }
    }

    /**
     * Desert locusts: in a spring after good rain, where the desert edge has greened, a swarm may rise;
     * it strips the green plants where it is, moves on (mostly with the wind) and dies out by late
     * summer. Birds, foxes, jackals and people eat them (see [catchSmall]).
     */
    private fun stepLocusts(weather: Weather) {
        val month = weather.date.month
        val season = month in 2..7
        val old = locusts.copyOf()
        for (p in 0 until n) {
            var l = old[p]
            // (A swarm is the hoppers that grew up on the green plants there: its weight comes off them.)
            if (season && l <= 0f && annualRain[p] < LOCUST_RAIN_MM && land.green[p] > 0.6f && locustHash(p) < LOCUST_START * years) l = land.consume(p, LifeForm.HERB, LOCUST_SWARM / SMALL_EFFICIENCY / 1e6f) * SMALL_EFFICIENCY * 1e6f
            if (l <= 0f) continue
            // A swarm eats about its weight in green plants a day.
            val m2 = 1e6f
            val ate = land.consume(p, LifeForm.HERB, min(0.5f * land.herb[p], 0.8f * l * days / m2)) + land.consume(p, LifeForm.SHRUB, min(0.3f * land.shrub[p], 0.2f * l * days / m2))
            dung[p] += DUNG * ate
            val dying = l * (if (season) LOCUST_DEATHS else 0.6f)
            smallDead[p] += dying * 1f
            l -= dying
            // Most of a swarm moves on to the neighbours each week.
            val nb = land.neighbours[p]
            if (nb.isNotEmpty() && l > 1f) { val go = 0.6f * l; for (q in nb) locusts[q] += go / nb.size * land.areaKm2[p] / land.areaKm2[q]; l -= go }
            locusts[p] += l - old[p]
        }
        for (p in 0 until n) if (locusts[p] < 0.1f) { smallDead[p] += locusts[p]; locusts[p] = 0f }
    }

    private var locustSalt = 0L
    private fun locustHash(p: Int): Float {
        var h = (p.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (++locustSalt * 0x632BE59BD9B4E019L)
        h = (h xor (h ushr 33)) * -0xae502812aa7333L; h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat()
    }

    /** Small game at place [p] this week: [t] its temperature, [burnt] the share that burned. */
    private fun stepGame(p: Int, t: Float, month: Int, burnt: Float) {
        if (land.biomass(p) <= 0f) { hares[p] = 0f; hyrax[p] = 0f; birds[p] = 0f; for (b in bird) b[p] = 0f; tortoises[p] = 0f; foxes[p] = 0f; return }
        val mild = t > 5f
        // Young raised a year per head, all born in the breeding months (so the rate in them is higher).
        hares[p] = logistic(p, hares[p], if (month in 3..9 && mild) inSeason(4f, 7) else 0f, 0.9f, hareCapacity(p), grazes = true)
        hyrax[p] = logistic(p, hyrax[p], if (month in 4..6) inSeason(1.2f, 3) else 0f, 0.4f, hyraxCapacity(p), grazes = true)
        var all = 0f
        for (k in bird.indices) {
            val b = BIRDS[k]; val f = bird[k]
            val here = b.present?.contains(month) ?: true
            f[p] = when {
                // Migrants leave for beyond the map at the end of their months here, and come back.
                !here -> { if (f[p] > 0f) leaving[k][p] = f[p] * land.areaKm2[p]; 0f }
                returning[k] > 0.0 -> f[p] + (returning[k] * birdCapacity(k, p)).toFloat()
                else -> logistic(p, f[p], if (month in b.breedMonths && mild) inSeason(b.growth, b.breedMonths.count()) else 0f, b.deaths, birdCapacity(k, p), grazes = true)
            }
            all += f[p]
        }
        birds[p] = all
        val active = t in 14f..33f && land.soil[p] > 8f
        tortoises[p] = logistic(p, tortoises[p], if (active && month in 4..8) inSeason(0.3f, 5) else 0f, if (active) 0.08f else 0.03f, tortoiseCapacity(p), grazes = true)
        // They eat what they grow on (a small share of their weight a day; tortoises only when out),
        // and drop half of it again.
        val eaters = hares[p] + hyrax[p] + all + (if (active) tortoises[p] else 0f) // kg/km²
        if (eaters > 0f) {
            val eaten = land.consume(p, LifeForm.HERB, 0.7f * SMALL_INTAKE * eaters * days / 1e6f) + land.consume(p, LifeForm.SHRUB, 0.3f * SMALL_INTAKE * eaters * days / 1e6f)
            dung[p] += DUNG * eaten
        }
        // Foxes and wildcats eat about a seventh of their weight a day, mostly rodents, then hares and
        // birds; they breed in spring as the food allows, and die back when it fails.
        val prey = rodents[p] + hares[p] + birds[p] + 0.03f * insects[p]
        val need = foxes[p] * FOX_INTAKE * days
        val got = takeSwitching(p, min(0.5f * prey, need * prey / (prey + FOX_HALF)), 0.5f, foxPrey)
        val fed = if (need > 0f) got / need else 1f
        // A litter of four to six a year, born in spring: over the three months, six times the stock a year.
        // Owls and small hawks eat about a fifth of their weight a day, mostly rodents.
        run {
            val r = rodents[p]
            if (owls[p] <= 0f && r > 0f) owls[p] = OWL_SEED * r
            val needO = owls[p] * OWL_INTAKE * days
            val gotO = min(0.5f * r, needO * r / (r + OWL_HALF))
            if (gotO > 0f) rodents[p] -= gotO
            val fedO = if (needO > 0f) gotO / needO else 1f
            owls[p] = logistic(p, owls[p], if (month in 3..5) 3f * fedO else 0f, 0.3f + 0.8f * (1f - fedO), 0.02f * r)
        }
        foxes[p] = logistic(p, foxes[p], if (month in 4..6) 6f * fed else 0f, 0.4f + 0.8f * (1f - fed), 0.02f * prey)
        // Eagles: a pair needs about a kilo a day; they take what is out in the open among the rocks.
        if (eagleCrags[p] > 0f) {
            val open = open(p)
            val prey = (hyrax[p] + hares[p] + bird[CHUKAR][p] + bird[ROCK_DOVE][p]) * (0.3f + 0.7f * open)
            val need = eagles[p] * EAGLE_NEED * days
            val got = takeSwitching(p, min(0.5f * prey, need * prey / (prey + EAGLE_HALF)), 0.5f, eaglePrey)
            // Fawns and kids make up much of what the small game does not.
            val fed = if (need > 0f) min(1f, got / need + 0.3f) else 1f
            eagles[p] = logistic(p, eagles[p], if (month in 4..6) 0.6f * fed else 0f, 0.1f + 0.6f * (1f - fed), eagleCrags[p], counted = false) // (an index, not kg: their dead are not counted)
        }
        // Fire: tortoises die where it burns; the rest bolt and many young die.
        if (burnt > 0f) {
            smallDead[p] += tortoises[p] * 0.6f * burnt + hares[p] * 0.3f * burnt + birds[p] * 0.3f * burnt
            tortoises[p] *= 1f - 0.6f * burnt; hares[p] *= 1f - 0.3f * burnt; takeBirds(p, 0.3f * burnt)
        }
    }

    /** Yearly share of the young of small hoofed animals eagles take in place [p]. */
    fun eagleRisk(p: Int) = EAGLE_RATE * eagles[p] * open(p)

    /** Takes share [f] of every kind of bird at [p]. */
    private fun takeBirds(p: Int, f: Float) {
        for (b in bird) b[p] -= b[p] * f
        birds[p] -= birds[p] * f
    }

    /**
     * Logistic growth at [r] a year in season, deaths at [d] a year, toward [k], in place [p]; the
     * dead go to the litter. An empty place stays empty until animals spread in from around it.
     */
    /** The rate in the breeding months that gives [perYear] young a head a year when they last [months] months. */
    private fun inSeason(perYear: Float, months: Int) = perYear * 12f / months

    /**
     * Logistic growth with deaths, kg/km²: what dies (and what crowding beyond [k] takes back) goes to
     * the dead; a kind that [grazes] grows only by the plants it eats, at [SMALL_EFFICIENCY]. [counted]
     * false for an index that is not kg.
     */
    private fun logistic(p: Int, x: Float, r: Float, d: Float, k: Float, grazes: Boolean = false, counted: Boolean = true): Float {
        val kk = max(k, 0.01f)
        val dead = min(x, x * d * years * if (x > kk) 2f else 1f)
        var change = x * r * years * (1f - x / kk)
        if (change > 0f && grazes) change = land.consume(p, LifeForm.HERB, change / SMALL_EFFICIENCY / 1e6f) * SMALL_EFFICIENCY * 1e6f
        val crowded = if (change < 0f) min(x - dead, -change) else 0f
        if (counted) smallDead[p] += dead + crowded
        return max(0f, x - dead + max(0f, change) - crowded)
    }

    /** [x] held to [most], what is beyond it dying. */
    private fun capped(p: Int, x: Float, most: Float): Float {
        if (x > most) { smallDead[p] += x - most; return most }
        return max(0f, x)
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
            if (month in 1..2) b += b * 0.5f * fed * years * 6f // one chick a pair, hatched in late winter
            b -= b * (0.08f * years + 0.4f * years * (1f - fed))
            vultures[k] = if (b < 2f) 0f else min(b, 2000f) // a colony below a pair is gone
        }
    }

    /** Whether reptiles and amphibians are out: warm, and not baking in a drought. */
    fun herpsActive(t: Float, soilWater: Float) = t in 12f..34f && !(t > 28f && soilWater < 10f)

    /**
     * Takes up to [kgPerKm2] of small prey in place [p]: rodents, hares, hyrax and birds, and when
     * they are out, reptiles, amphibians and tortoises, each in proportion to what there is, at most
     * half of all; returns kg per km² caught.
     */
    fun catchSmall(p: Int, kgPerKm2: Float, weather: Weather, most: Float = 0.5f): Float {
        val t = weather.temperature[p]
        val herpsOut = herpsActive(t, land.soil[p])
        val tortOut = t in 14f..33f
        // Desert rodents sleep through the summer heat in their burrows.
        val rodentsOut = !(t > 30f && land.soil[p] < 10f && annualRain[p] < DORMANT_RAIN_MM)
        val total = (if (rodentsOut) rodents[p] else 0f) + hares[p] + hyrax[p] + birds[p] + (if (herpsOut) herps[p] else 0f) + (if (tortOut) tortoises[p] else 0f) + locusts[p]
        if (total <= 0f) return 0f
        val stocks = when {
            rodentsOut && herpsOut && tortOut -> huntedAll
            else -> (listOfNotNull(if (rodentsOut) rodents else null, hares, hyrax, locusts, if (herpsOut) herps else null, if (tortOut) tortoises else null) + bird).toTypedArray()
        }
        return takeSwitching(p, min(kgPerKm2, most * total), most, stocks)
    }

    private val huntedAll by lazy { (listOf(rodents, hares, hyrax, locusts, herps, tortoises) + bird).toTypedArray() }
    private val foxPrey by lazy { (listOf(rodents, hares) + bird).toTypedArray() }
    private val eaglePrey by lazy { arrayOf(hyrax, hares, bird[CHUKAR], bird[ROCK_DOVE]) }

    /**
     * Takes up to [want] kg/km² at place [p] from [stocks], at most [most] of each. Hunters switch
     * to what is common (as with large game): a kind thin on the ground is seldom met and passed over
     * for the common ones, each weighted by how easily it is found. Returns kg/km² taken.
     */
    private fun takeSwitching(p: Int, want: Float, most: Float, stocks: Array<FloatArray>): Float {
        if (want <= 0f) return 0f
        var weight = 0f
        for (s in stocks) { val x = s[p]; weight += x * x / (x + SMALL_FIND_HALF) }
        if (weight <= 0f) return 0f
        var took = 0f
        for (s in stocks) {
            val x = s[p]
            val g = min(most * x, want * x * x / (x + SMALL_FIND_HALF) / weight)
            s[p] = x - g; took += g
        }
        var all = 0f; for (b in bird) all += b[p]; birds[p] = all
        return took
    }

    /**
     * A hunter searching [sweptKm2] of place [p] takes up to [kg] of small prey: at most half of what
     * lives on the ground it searched. What it takes comes off the place's stocks; returns kg taken.
     */
    fun catchSmallIn(p: Int, kg: Float, sweptKm2: Float, weather: Weather): Float {
        val area = land.areaKm2[p]
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return catchSmall(p, kg / area, weather, 0.5f * searched / area) * area
    }

    /** A scavenger searching [sweptKm2] of place [p] takes up to [kg] of carrion, at most half of what lies on the ground it searched; returns kg taken. */
    fun scavengeIn(p: Int, kg: Float, sweptKm2: Float): Float {
        val area = land.areaKm2[p]; val m2 = area * 1e6f
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return scavenge(p, min(kg, 0.5f * carrion[p] * searched * 1e6f) / m2) * m2
    }

    /** The same for bones, for bone-crackers. */
    fun crackBonesIn(p: Int, kg: Float, sweptKm2: Float): Float {
        val area = land.areaKm2[p]; val m2 = area * 1e6f
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return crackBones(p, min(kg, 0.5f * bones[p] * searched * 1e6f) / m2) * m2
    }

    /** Small prey on the ground at [p], kg/km²: rodents, hares, hyrax, game birds, reptiles and tortoises. */
    fun smallPreyKg(p: Int) = rodents[p] + hares[p] + hyrax[p] + birds[p] + herps[p] + tortoises[p]

    /** Totals over the land, tonnes, and vultures in all colonies. */
    fun summary(): String {
        fun t(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } / 1000
        return "rodents %.0f t, hares %.0f t, hyrax %.0f t, birds %.0f t (%s), tortoises %.0f t, foxes %.0f t, owls %.0f t, eagles %.0f pairs, %d vulture colonies %.0f birds".format(
            t(rodents), t(hares), t(hyrax), t(birds), bird.indices.joinToString(", ") { "%s %.0f".format(BIRDS[it].name, t(bird[it])) },
            t(tortoises), t(foxes), t(owls), eagles.indices.sumOf { (eagles[it] * land.areaKm2[it]).toDouble() } / EAGLE_KM2, vultures.size, vultures.sum())
    }

    /** Takes up to [kgPerM2] of carrion in place [p]; returns what was taken. */
    fun scavenge(p: Int, kgPerM2: Float): Float { val t = min(carrion[p], kgPerM2); carrion[p] -= t; return t }

    /** Takes up to [kgPerM2] of bones in place [p] (for bone-crackers); returns what was taken. */
    fun crackBones(p: Int, kgPerM2: Float): Float { val t = min(bones[p], kgPerM2); bones[p] -= t; return t }

    /** The fruit crop's share of what the trees could bear, from this year's pollination. */
    fun fruitSet(p: Int) = 0.3f + 0.7f * pollination[p]

    /**
     * Small animals wander between neighbouring places: as many leave one as arrive in the other, the
     * density difference over the smaller place; [where] keeps a kind to the places it can live in.
     */
    private fun spread(f: FloatArray, where: (Int) -> Boolean) {
        val old = f.copyOf()
        for (p in 0 until n) {
            if (!where(p)) continue
            val nb = land.neighbours[p]
            val w = spreadWeight[p]
            var delta = 0f
            for (i in nb.indices) { val q = nb[i]; if (where(q)) delta += w[i] * (old[q] - old[p]) }
            f[p] = max(0f, old[p] + delta)
        }
    }

    /** [spread] for kinds that can wander anywhere, all in one pass over the places. */
    private fun spread(fs: Array<FloatArray>) {
        val olds = Array(fs.size) { fs[it].copyOf() }
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val nb = land.neighbours[p]
            val w = spreadWeight[p]
            for (k in fs.indices) {
                val old = olds[k]
                var delta = 0f
                for (i in nb.indices) delta += w[i] * (old[nb[i]] - old[p])
                fs[k][p] = max(0f, old[p] + delta)
            }
        }
    }

    private val spreaders by lazy { (listOf(insects, rodents, herps, pollinators, hares, hyrax, tortoises, foxes) + bird).toTypedArray() }
    /**
     * The share of the difference with each neighbour that crosses in a step: [SPREAD] a month, over
     * the larger place's count of neighbours and scaled to the smaller place's area. (The same flow
     * both ways between two places, so what one gains the other loses.)
     */
    private val spreadWeight = Array(n) { p ->
        val nb = land.neighbours[p]
        val rate = SPREAD * days / Date.DAYS_PER_MONTH
        FloatArray(nb.size) { i -> val q = nb[i]; rate / max(nb.size, land.neighbours[q].size) * min(land.areaKm2[p], land.areaKm2[q]) / land.areaKm2[p] }
    }

    companion object {
        val WETLANDS = setOf("MARSH", "SWAMP_FOREST", "BOG", "SALT_MARSH", "MANGROVE")
        /**
         * The wild birds. Months count from the winter solstice (0 about January): quail are here
         * from spring to early autumn, greylag geese from autumn to early spring.
         */
        /** Share of what small plant-eaters eat that becomes their own growth (about a tenth for warm-blooded animals, Lindeman's rule). */
        const val SMALL_EFFICIENCY = 0.1f
        val BIRDS = listOf(
            BirdKind("chukar", 4..6, 4f, 0.9f, null, floatArrayOf(0.7f, 0.7f, 0.5f, 0.5f, 0.6f, 0.6f)),
            BirdKind("quail", 4..7, 6f, 1.2f, (4..9).toSet(), floatArrayOf(0.7f, 0.9f, 0.7f, 0.5f, 0.5f, 0.4f)),
            BirdKind("sandgrouse", 4..7, 1.5f, 0.7f, null, floatArrayOf(0.5f, 0.6f, 0.2f, 0.3f, 0.3f, 0.4f)),
            BirdKind("rock dove", 3..10, 5f, 0.8f, null, floatArrayOf(0.9f, 0.9f, 0.9f, 0.8f, 0.7f, 0.6f)), // became the pigeon
            BirdKind("mallard", 4..6, 4f, 0.8f, null, floatArrayOf(0.9f, 0.8f, 0.8f, 0.7f, 0.6f, 0.6f)), // became the duck
            BirdKind("greylag goose", 5..6, 1.5f, 0.5f, (setOf(10, 11, 0, 1, 2, 3)), floatArrayOf(0.8f, 0.8f, 0.8f, 0.6f, 0.6f, 0.8f)), // became the goose
            // Common cranes winter by the thousand in the rift's marshes and fields; white storks pass
            // through on the way to and from Africa in spring and late summer. Both breed far away.
            BirdKind("crane", 5..6, 0.5f, 0.3f, setOf(10, 11, 0, 1, 2), floatArrayOf(0.5f, 0.3f, 0.3f, 0.4f, 0.3f, 0.5f)),
            BirdKind("white stork", 5..6, 1.2f, 0.3f, setOf(2, 3, 7, 8), floatArrayOf(0.5f, 0.3f, 0.3f, 0.5f, 0.4f, 0.4f)),
        )
        /** How far vultures soar from the colony for carcasses, km; land per colony at most, km. */
        const val VULTURE_RANGE_KM = 80.0
        const val VULTURE_SPACING_KM = 80.0
        /** Eagles: what the eagles over a place eat at full strength (a pair to [EAGLE_KM2]), kg/km² a day; yearly share of young taken in open crag country. */
        const val EAGLE_NEED = 0.01f
        /** Ground a pair hunts over where the eagles are at full strength, km². */
        const val EAGLE_KM2 = 100.0
        const val EAGLE_RATE = 0.15f
        /** Share of what small plant-eaters eat that comes back as frass and droppings; share of migrant birds that come back. */
        const val FRASS = 0.5f
        /** Share of a rotting carcass left as bones. */
        const val BONE_SHARE = 0.2f
        const val MIGRANT_SURVIVAL = 0.7f
        /** Plant food small plant-eaters (hares, hyrax, game birds, tortoises) eat, kg a day per kg of them. */
        const val SMALL_INTAKE = 0.08f
        /** Foxes and wildcats eat about a fourteenth of their weight a day; prey on the ground (kg/km²) at which they get half of it. */
        const val FOX_INTAKE = 0.07f
        const val FOX_HALF = 3f
        /** Locusts: where they swarm (rain under this, mm a year); yearly chance a greened place raises a swarm; a swarm, kg/km²; share dying a week in season. */
        const val LOCUST_RAIN_MM = 250f
        const val LOCUST_START = 0.0005f
        const val LOCUST_SWARM = 5000f
        const val LOCUST_DEATHS = 0.15f
        /** Desert rodents (where rain is under this, mm a year) sleep through hot, dry weeks. */
        const val DORMANT_RAIN_MM = 300f
        /** Owls and small hawks: food a day per kg of them; rodents (kg/km²) at which they get half of it; their share of the rodents where they first appear. */
        const val OWL_INTAKE = 0.2f
        const val OWL_HALF = 3f
        /** Share of a carcass gone a day at 15 °C (maggots, beetles, bacteria, and the birds and dogs not counted here): half in three or four days. */
        const val CARRION_ROT = 0.2f
        /** Small prey of one kind, kg/km², at which hunters find it half as readily as a common kind. */
        const val SMALL_FIND_HALF = 2f
        const val OWL_SEED = 0.002f
        /** Small prey out in the open (kg/km²) at which eagles get half of what they need. */
        const val EAGLE_HALF = 1f
        /** Share of the difference with its neighbours a small animal population evens out in a month. */
        const val SPREAD = 0.2f
        /** Rodents' deaths a year from all causes but predators, cold, snow and hunger. */
        val RODENT_DEATH = inflation.Config.float("t.rodentDeath", 1.2f)
        /** Share of what herds and packs eat that comes back as dung. */
        const val DUNG = 0.5f
        private val CHUKAR = BIRDS.indexOfFirst { it.name == "chukar" }
        private val ROCK_DOVE = BIRDS.indexOfFirst { it.name == "rock dove" }
    }
}
