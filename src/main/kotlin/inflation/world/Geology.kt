package inflation.world

/** Surface rock. [erodibility] scales the stream-power coefficient; [fertility] is the soil it weathers into. */
enum class Rock(val erodibility: Float, val fertility: Float) {
    BASALT(0.7f, 0.8f),
    VOLCANIC(1.1f, 0.75f),
    GRANITE(0.45f, 0.35f),
    METAMORPHIC(0.55f, 0.4f),
    LIMESTONE(0.9f, 0.6f),
    SANDSTONE(1.3f, 0.3f),
    SHALE(1.7f, 0.55f),
    ALLUVIUM(2.5f, 1.0f),
    EVAPORITE(2.0f, 0.05f),
}

/** Materials humans can find at a cell, as bit flags. */
object Mineral {
    const val FLINT = 1 shl 0
    const val CLAY = 1 shl 1
    const val OBSIDIAN = 1 shl 2
    const val OCHRE = 1 shl 3
    const val SALT = 1 shl 4
    const val COPPER = 1 shl 5
    const val TIN = 1 shl 6
    const val BITUMEN = 1 shl 7

    val names = listOf("flint", "clay", "obsidian", "ochre", "salt", "copper", "tin", "bitumen")
}

/**
 * Surface rock and minerals per cell. [sediment] is the thickness of loose sediment over bedrock
 * in metres: alluvium in valleys and basins, deltas, and mud on the sea floor.
 */
class Geology(val rock: Array<Rock>, val minerals: IntArray, val sediment: FloatArray) {
    fun erodibility(): FloatArray = FloatArray(rock.size) { rock[it].erodibility }

    companion object {
        /** Bedrock from tectonic setting; sedimentary cover elsewhere, in bands from low-frequency noise. */
        fun bedrock(grid: Grid, tect: Tectonics, noise: Noise): Geology {
            val n = grid.size
            val rock = Array(n) { Rock.LIMESTONE }
            val minerals = IntArray(n)
            for (i in 0 until n) {
                val x = grid.x(i).toDouble(); val y = grid.y(i).toDouble()
                val plate = tect.plates[tect.plateId[i]]
                val dist = tect.boundaryDistKm[i]
                val conv = tect.convergence[i]
                val strata = noise.fbm(x + 4001, y - 2203, 180.0 / grid.cellKm, 4)
                val local = noise.fbm(x - 777, y + 1555, 15.0 / grid.cellKm, 3)
                rock[i] = when {
                    !plate.continental -> Rock.BASALT
                    tect.isOverriding(i) && dist in 60f..180f && strata > -0.1 -> Rock.VOLCANIC // subduction arc
                    conv < -0.005 && dist < 30 -> if (strata > 0.1) Rock.BASALT else Rock.VOLCANIC // rift flows
                    conv > 0.01 && dist < 50 -> if (strata > 0) Rock.GRANITE else Rock.METAMORPHIC // belt core
                    strata > 0.15 -> Rock.SANDSTONE
                    strata < -0.2 -> Rock.SHALE
                    else -> Rock.LIMESTONE
                }
                var m = 0
                when (rock[i]) {
                    Rock.LIMESTONE -> {
                        if (local > 0.25) m = m or Mineral.FLINT
                        if (conv < 0 && dist < 60 && local < -0.35) m = m or Mineral.BITUMEN
                    }
                    Rock.SHALE -> m = m or Mineral.CLAY
                    Rock.SANDSTONE, Rock.BASALT -> if (local > 0.35) m = m or Mineral.OCHRE
                    Rock.VOLCANIC -> if (local > 0.3) m = m or Mineral.OBSIDIAN
                    Rock.GRANITE -> if (local > 0.5) m = m or Mineral.TIN
                    Rock.METAMORPHIC -> if (local > 0.45) m = m or Mineral.COPPER
                    else -> {}
                }
                minerals[i] = m
            }
            return Geology(rock, minerals, FloatArray(n))
        }
    }
}
