package inflation.ecology

import inflation.KgM2
import inflation.world.HexDrinkingWater
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The bulbs and roots that can be dug, and the mast, as rooting animals and bands find them ([HexFood.underground]). */
class HexFoodTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `frozen ground keeps its roots, and what is taken comes off the roots and the mast`() {
        val sim = HexSim(World.generate(cfg))
        repeat(30) { sim.step() }
        val food = sim.fauna.food
        var mast = 0f
        for (i in 0 until food.n) {
            val p = Place(i)
            mast += food.mast[p]
            if (sim.weather.temperature[p] <= HexDrinkingWater.THAW_C) assertTrue(food.diggable(p) == 0f, "roots dug from frozen ground at $i")
            assertTrue(abs(food.underground(p) - food.diggable(p) - food.mast[p]) < 1e-9f, "underground at $i is not its roots and mast")
            val roots0 = food.roots[p]; val mast0 = food.mast[p]; val all = food.underground(p)
            val want = 0.5f * all
            val took = food.takeUnderground(p, KgM2(want)).v
            assertTrue(abs(took - want) <= 1e-6f * (1f + want), "took $took of $want at $i")
            assertTrue(abs((roots0 - food.roots[p]) + (mast0 - food.mast[p]) - took) <= 1e-6f * (1f + took), "taken at $i is not what left the stocks")
        }
        assertTrue(mast > 0f, "no mast fell anywhere, so nothing was checked of it")
    }
}
