package inflation.ecology

/**
 * A herd, pack or flock: the unit that decides. Individuals are counted, not tracked:
 * adults and young (below maturity). [x], [y] are in cells, fractional.
 */
class AnimalGroup(
    val id: Int,
    val species: Species,
    var x: Double,
    var y: Double,
    var adults: Int,
    var young: Int,
) {
    /** Fat reserves and health, 0..1. Below about 0.2 animals start to die of hunger. */
    var condition = 0.7f
    /** Days' worth of water the group is short of: drinking resets it, moist food slows its rise. */
    var daysWithoutWater = 0f
    /** Share of yesterday's food that was fresh and moist (green growth, roots, meat) rather than dry. */
    var freshShare = 1f
    /** Days the group stays on alert after being attacked: watchful, skittish and hard to approach. */
    var alert = 0
    /** On a march today: covering long distances and eating little (see [Ecology]). */
    var marching = false
    /** Meat a pack has killed but not yet eaten, kg. */
    var stored = 0f
    /** Food gained over need on the last day, 0 = starving, 1 = full. */
    var lastIntakeRatio = 1f
    val size: Int get() = adults + young
    val alive: Boolean get() = size > 0
    /** Body mass of the group, kg; young count as half. */
    val biomassKg: Float get() = species.massKg * (adults + 0.5f * young)
    /** Daily food need, kg; young eat less. */
    val dailyNeedKg: Float get() = species.dailyIntakeKg * (adults + 0.5f * young)
}

/**
 * What a group perceives about one place it could go: the inputs a policy decides from.
 * The same features serve the hand-written baseline and, later, learned policies.
 */
class Option(
    /** Target cell index, or -1 if it cannot go there (sea, lake, off the map). */
    val cell: Int,
    /** Effort to get there, km of flat open ground (0 = stay); terrain, thickets and snow make it more. */
    val km: Float,
    /** Food there: edible plants for herbivores, small prey for predators; kg/km². */
    val forage: Float,
    /** Smell or sight of predators there, normalised 0..1. */
    val danger: Float,
    /** Smell or sight of large prey there, normalised 0..1 (for predators). */
    val prey: Float,
    /** Smell of its own kind there (crowding), normalised 0..1. */
    val kin: Float,
    /** Distance from there to the nearest drinking water, km. */
    val waterKm: Float,
    /** Snow on the ground there, mm water equivalent. */
    val snowMm: Float,
    /** How well broken ground and plants hide things there, 0 (open plain) to 1 (thicket, forest, lava). */
    val cover: Float,
    /** Terrain ruggedness there, 0..1. */
    val rugged: Float,
    /** Wetness there, 0 (dry upland) to 1 (marsh, floodplain). */
    val wet: Float,
)

/** Chooses where a group goes today. Implementations: [BaselinePolicy]; learned networks later. */
fun interface Policy {
    /** Returns the index of the chosen option. [options] always has "stay" first. */
    fun choose(group: AnimalGroup, options: List<Option>): Int
}

/**
 * Hand-written behaviour to calibrate the ecology against and for learned policies to beat:
 * herbivores go to the best forage within reach on the kind of ground they favour, away from
 * predators and deep snow, and head for water when thirsty; predators follow the smell of prey.
 */
class BaselinePolicy(
    /** Random noise in -0.5..0.5 for [group] and option [k], so ties break and herds spread out. */
    private val noise: (group: AnimalGroup, k: Int) -> Float,
) : Policy {
    override fun choose(group: AnimalGroup, options: List<Option>): Int {
        val s = group.species
        val thirsty = group.daysWithoutWater >= s.daysWithoutWater - 1
        val carnivore = s.carnivore
        var best = 0; var bestScore = Float.NEGATIVE_INFINITY
        for ((k, o) in options.withIndex()) {
            if (o.cell < 0) continue
            val h = s.habitat
            var score = if (carnivore) 2f * o.prey + 0.2f * o.forage / (o.forage + 100f) - 1.5f * o.kin
                // Each kind keeps to its own ground: gazelles open plains, ibex cliffs, boar thickets.
                // Big, well-armed animals fear predators less.
                else o.forage / (o.forage + 50f) - 3f * (1f - s.defence) * o.danger - 0.3f * o.kin +
                    0.3f * (h.cover * o.cover + h.rugged * o.rugged + h.wet * o.wet)
            if (o.snowMm > s.snowLimitMm) score -= 1f
            // Keep within a few days' walk of water, more so the longer since the last drink.
            val urgency = group.daysWithoutWater / s.daysWithoutWater
            score -= (0.02f + 0.3f * urgency) * o.waterKm / s.dailyTravelKm
            if (thirsty) score -= 0.2f * o.waterKm
            score -= 0.01f * o.km // moving costs energy
            score += 0.05f * noise(group, k)
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }
}
