package inflation.world

/**
 * The water-bearing rock under a place, by kind of rock. Typical values, from memory of the usual
 * textbook tables (Freeze and Cherry 1979; Heath 1983) and, for the intake, of the base-flow index
 * of rivers on each kind of rock (Boorman and others 1995); within each kind they vary by orders of
 * magnitude, so these are middles, not measurements.
 *
 * @property intake share of the water the soil cannot hold that sinks into the rock rather than
 *   running off over or just under the ground (about the base-flow index of rivers on that rock).
 * @property conductivity how readily water moves through the rock at large, m/day.
 * @property yield share of the rock's volume that drains when the water table falls (specific yield).
 * @property thicknessM depth of rock that holds and passes water, m.
 */
class Aquifer(val intake: Float, val conductivity: Double, val yield: Float, val thicknessM: Float) {
    companion object {
        private val BY_ROCK = mapOf(
            // Valley sand and gravel: soaks up well, passes water fast, drains to the nearby river.
            Rock.ALLUVIUM to Aquifer(0.6f, 10.0, 0.2f, 30f),
            // Porous sandstone: a deep, slow store.
            Rock.SANDSTONE to Aquifer(0.7f, 0.5, 0.1f, 150f),
            // Karst: takes in most of the rain through fissures and sinks; water moves along the
            // fissures at large, but the rock holds little of it.
            Rock.LIMESTONE to Aquifer(0.8f, 1.0, 0.03f, 200f),
            // Jointed lava flows pass water well.
            Rock.BASALT to Aquifer(0.7f, 5.0, 0.05f, 100f),
            Rock.VOLCANIC to Aquifer(0.6f, 0.5, 0.1f, 50f),
            // Granite and old hard rock: water only in the weathered top and the joints.
            Rock.GRANITE to Aquifer(0.45f, 0.05, 0.01f, 30f),
            Rock.METAMORPHIC to Aquifer(0.45f, 0.05, 0.01f, 30f),
            // Shale and clay: almost tight; rain runs off.
            Rock.SHALE to Aquifer(0.25f, 0.001, 0.02f, 20f),
            Rock.EVAPORITE to Aquifer(0.4f, 0.1, 0.02f, 50f),
        )

        fun of(rock: Rock): Aquifer = BY_ROCK.getValue(rock)
    }
}
