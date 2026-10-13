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
    private var steps = 0
    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** Share of each place that is wetland, where biting insects breed. */
    private val wetland = FloatArray(n) { p -> hw.placeCells[p].count { val b = hw.world.biome[it]; b.name in WETLANDS }.toFloat() / hw.placeCells[p].size }

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
        }
        if (++steps % 5 == 0) { spread(insects); spread(rodents); spread(herps); spread(pollinators) }
    }

    /** Whether reptiles and amphibians are out: warm, and not baking in a drought. */
    fun herpsActive(t: Float, soilWater: Float) = t in 12f..34f && !(t > 28f && soilWater < 10f)

    /** Takes up to [kgPerKm2] of small prey in place [p]: rodents, and reptiles and amphibians when active; returns kg per km² caught. */
    fun catchSmall(p: Int, kgPerKm2: Float, weather: Weather): Float {
        val fromHerps = if (herpsActive(weather.temperature[p], land.soil[p])) min(herps[p] * 0.5f, 0.3f * kgPerKm2) else 0f
        herps[p] -= fromHerps
        val taken = min(rodents[p] * 0.5f, kgPerKm2 - fromHerps)
        rodents[p] -= taken
        return taken + fromHerps
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
    }
}
