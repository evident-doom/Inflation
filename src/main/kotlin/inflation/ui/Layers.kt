package inflation.ui

import inflation.world.Render
import inflation.world.Simulation
import inflation.world.SurfaceWater
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Map layers the viewer can show. Live layers change as time runs. */
enum class Layer(val label: String) {
    TERRAIN("Terrain, rivers, floods, snow"),
    VEGETATION("Vegetation (live)"),
    BIOMES("Potential biomes"),
    TEMPERATURE("Temperature (live)"),
    PRECIPITATION("Rain and snowfall (live)"),
    SOIL_MOISTURE("Soil moisture (live)"),
    RIVER_FLOW("River flow vs normal (live)"),
    GEOLOGY("Rock"),
    SEDIMENT("Sediment thickness"),
    RUGGEDNESS("Ruggedness"),
    TRAVEL_COST("Travel effort (live)"),
    VISIBILITY("Visibility on the ground (live)"),
    VIEWSHED("View from each place"),
    DEFENSIBILITY("Defensibility"),
    WETNESS("Ground wetness and drinking water (live)"),
    GRAZING("Grazing: palatable young growth (live)"),
    PLANKTON("Plankton (live)"),
    FISH("Fish, shellfish and spawning runs (live)"),
    UPWELLING("Upwelling and sea animals (live)"),
    INSECTS("Plant-eating insects (live)"),
    RODENTS("Rodents and small mammals (live)"),
    SOIL_LIFE("Soil: litter and organic matter (live)"),
}

/** Turns simulation state into pixels, one per cell. */
class LayerPainter(private val sim: Simulation) {
    private val world = sim.world
    private val n = world.grid.size
    private val t = world.terrain
    private val terrain = IntArray(n) { shadedLand(it) }
    private val biomes = IntArray(n) { Render.withRivers(world, it, Render.shade(world, it, world.biome[it].rgb)) }
    private val geology = IntArray(n) { Render.withRivers(world, it, Render.shade(world, it, Render.rockColour(world.geology.rock[it], world.isSea[it]))) }
    private val sediment = IntArray(n) { Render.shade(world, it, Render.ramp(ln(1 + world.geology.sediment[it]), 0f, ln(1 + 500f), SEDIMENT)) }

    fun paint(layer: Layer, out: IntArray) {
        when (layer) {
            Layer.TERRAIN -> for (i in 0 until n) out[i] = liveTerrain(i)
            Layer.VEGETATION -> for (i in 0 until n) out[i] = vegetation(i)
            Layer.BIOMES -> System.arraycopy(biomes, 0, out, 0, n)
            Layer.GEOLOGY -> System.arraycopy(geology, 0, out, 0, n)
            Layer.SEDIMENT -> System.arraycopy(sediment, 0, out, 0, n)
            Layer.TEMPERATURE -> for (i in 0 until n) out[i] = Render.ramp(sim.weather.temperature[i], -20f, 40f, Render.COLD_HOT)
            Layer.PRECIPITATION -> for (i in 0 until n) out[i] = Render.ramp(ln(1 + sim.weather.precipitation[i]), 0f, ln(1 + 150f), Render.DRY_WET)
            Layer.SOIL_MOISTURE -> for (i in 0 until n) out[i] = if (world.isSea[i]) SEA else Render.ramp(sim.water.soil[i], 0f, 150f, Render.DRY_WET)
            Layer.RIVER_FLOW -> for (i in 0 until n) out[i] = flow(i)
            Layer.RUGGEDNESS -> live(out) { Render.shade(world, it, Render.ramp(t.ruggedness[it], 0f, 1f, RUGGED)) }
            Layer.TRAVEL_COST -> live(out) { Render.ramp(ln(t.travelCost(it, sim.vegetation, sim.weather.snowpack[it], dischargeNow = sim.water.discharge[it])), 0f, ln(20f), EASY_HARD) }
            Layer.VISIBILITY -> live(out) { Render.ramp(ln(t.sightKm(it, sim.vegetation) * 20f), 0f, ln(20f * 10f), BLIND_CLEAR) }
            Layer.VIEWSHED -> live(out) { Render.shade(world, it, Render.ramp(ln(1 + t.viewAreaKm2[it]), 0f, ln(1 + 2000f), BLIND_CLEAR)) }
            Layer.DEFENSIBILITY -> live(out) { Render.shade(world, it, Render.ramp(t.defensibility[it], 0f, 1f, RUGGED)) }
            Layer.WETNESS -> live(out) {
                // Drinkable water bright blue; elsewhere parched brown to damp green.
                if (sim.ecology.waterKm[it] == 0f) 0x2a6fdb else Render.ramp(sim.ecology.wetness[it], 0f, 1f, DRY_WET)
            }
            Layer.GRAZING -> live(out) { Render.ramp(sim.ecology.graze[it] + sim.ecology.browse[it], 0f, 250f, FAUNA) }
            Layer.PLANKTON -> for (i in 0 until n) out[i] =
                if (sim.ecology.plankton.isWater[i]) Render.ramp(ln(1 + sim.ecology.plankton.phyto[i]), 0f, ln(1 + 15f), PLANKTON) else terrain[i]
            Layer.FISH -> for (i in 0 until n) out[i] = fishColour(i)
            Layer.UPWELLING -> for (i in 0 until n) out[i] =
                if (world.isSea[i]) Render.ramp(sim.ecology.marine.upwelling[i], 0f, 1f, PLANKTON) else terrain[i]
            Layer.INSECTS -> live(out) { Render.ramp(ln(1 + sim.ecology.small.insects[it]), 0f, ln(1 + 3000f), FAUNA) }
            Layer.RODENTS -> live(out) { Render.ramp(sim.ecology.small.rodents[it], 0f, 500f, FAUNA) }
            Layer.SOIL_LIFE -> live(out) { Render.ramp(sim.ecology.soil.organicMatter[it] + sim.ecology.soil.litter[it], 0f, 10f, SOIL) }
        }
    }

    private fun fishColour(i: Int): Int {
        val f = sim.ecology.fish
        return when {
            f.run[i] > 1f -> Render.ramp(ln(1 + f.run[i]), 0f, ln(1 + 4000f), RUN)
            f.shellfish[i] > 100f -> Render.ramp(f.shellfish[i], 0f, 20000f, SHELL)
            f.fish[i] > 0f -> Render.ramp(ln(1 + f.fish[i]), 0f, ln(1 + 6000f), PLANKTON)
            else -> if (sim.vegetation.isVegetated(i)) Render.lerp(terrain[i], 0x808080, 0.5f) else terrain[i]
        }
    }

    /** Paints land cells with [colour] and water as it looks on the terrain map. */
    private inline fun live(out: IntArray, colour: (Int) -> Int) {
        for (i in 0 until n) out[i] = if (sim.vegetation.isVegetated(i)) colour(i) else terrain[i]
    }

    private fun shadedLand(i: Int): Int {
        val h = world.elevation[i]
        val base = when {
            world.isSea[i] -> Render.relief(world, i)
            world.water.isLake(i) -> if (world.water.lakeClosed[i]) 0x7fb5c8 else 0x3f7fbf
            h < 200 -> Render.lerp(0x5f9f4f, 0x9fbf5f, h / 200f)
            h < 1000 -> Render.lerp(0x9fbf5f, 0xbf9f5f, (h - 200) / 800f)
            h < 2500 -> Render.lerp(0xbf9f5f, 0x8f7f6f, (h - 1000) / 1500f)
            else -> Render.lerp(0x8f7f6f, 0xb0a8a0, min(1f, (h - 2500) / 1500f))
        }
        return if (world.isSea[i] || world.water.isLake(i)) base else Render.shade(world, i, base)
    }

    private fun liveTerrain(i: Int): Int {
        var c = terrain[i]
        if (world.isSea[i] || world.water.isLake(i)) return c
        if (sim.water.submerged[i]) return 0x2f6faf
        val snow = sim.weather.snowpack[i]
        if (snow > 2f) c = Render.lerp(c, 0xf8f8ff, min(1f, 0.4f + snow / 60f))
        val ash = sim.eruptions.ash[i]
        if (ash > 5f) c = Render.lerp(c, 0x6a6a6a, min(0.9f, ash / 200f))
        val q = sim.water.discharge[i]
        if (q >= SurfaceWater.RIVER_THRESHOLD) {
            val s = (ln(q / SurfaceWater.RIVER_THRESHOLD + 1) / ln(500.0)).toFloat().coerceIn(0.3f, 1f)
            c = Render.lerp(c, 0x1f5fbf, s)
        }
        val f = sim.water.flood[i]
        if (f > 0.05f) c = Render.lerp(c, 0x3f8fdf, min(0.85f, 0.4f + f))
        return c
    }

    private fun vegetation(i: Int): Int {
        if (world.isSea[i]) return SEA
        if (world.water.isLake(i) || sim.water.submerged[i]) return 0x3f7fbf
        val v = sim.vegetation
        val tree = min(1f, v.tree[i] / 15f); val shrub = min(1f, v.shrub[i] / 2f); val herb = min(1f, v.herb[i] / 0.5f)
        var c = 0xd8c8a0 // bare ground
        c = Render.lerp(c, 0xa8c860, herb)
        c = Render.lerp(c, 0x7a9a40, shrub * 0.8f)
        c = Render.lerp(c, 0x1f5a2a, tree)
        if (v.burnt[i] > 0f) c = Render.lerp(c, 0x2a2018, v.burnt[i] * 0.8f)
        if (v.trails[i] > 0f) c = Render.lerp(c, 0xc09060, v.trails[i])
        if (sim.weather.snowpack[i] > 5f) c = Render.lerp(c, 0xf8f8ff, 0.6f)
        return Render.shade(world, i, c)
    }

    private fun flow(i: Int): Int {
        if (world.isSea[i]) return SEA
        val mean = world.water.discharge[i]
        val q = sim.water.discharge[i]
        if (mean < SurfaceWater.RIVER_THRESHOLD && q < SurfaceWater.RIVER_THRESHOLD) return terrain[i].let { Render.lerp(it, 0xffffff, 0.6f) }
        val ratio = ln(max(q, 1e-3f) / max(mean, 1e-3f)) // 0 = normal
        return Render.ramp(ratio, -2f, 2f, LOW_HIGH)
    }

    companion object {
        const val SEA = 0x1f3f6f
        private val SEDIMENT = intArrayOf(0x606060, 0xc8b88a, 0xe8d8a0, 0xffffe0)
        private val RUGGED = intArrayOf(0xf0ecd8, 0xd8c890, 0xb07840, 0x703020, 0x301010)
        private val EASY_HARD = intArrayOf(0xf0f0d0, 0xe0c060, 0xc06030, 0x802020, 0x200808)
        private val BLIND_CLEAR = intArrayOf(0x102010, 0x305030, 0x80a060, 0xd0e0a0, 0xffffff)
        private val FAUNA = intArrayOf(0x202020, 0x604080, 0xc06040, 0xf0d040, 0xffffc0)
        private val RUN = intArrayOf(0x804000, 0xff8000, 0xffff40)
        private val SHELL = intArrayOf(0x606080, 0xc080c0, 0xffd0ff)
        private val PLANKTON = intArrayOf(0x0a1a40, 0x1f3f6f, 0x2f7f7f, 0x5fbf5f, 0xbfef6f)
        private val SOIL = intArrayOf(0xe8d8a8, 0xb08850, 0x704020, 0x301808)
        private val DRY_WET = intArrayOf(0xc89858, 0xe0d090, 0xa0c070, 0x408050, 0x205848)
        private val LOW_HIGH = intArrayOf(0xb03020, 0xe0a060, 0xf0f0f0, 0x60a0e0, 0x1030b0)
    }
}
