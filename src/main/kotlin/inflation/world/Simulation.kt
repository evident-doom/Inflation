package inflation.world

import inflation.ecology.Ecology

/**
 * The living world over time: weather, rivers and floods, eruptions and vegetation, advanced
 * together one step ([WorldConfig.daysPerStep] days) at a time.
 */
class Simulation(val world: World) {
    val weather = Weather(world.grid, world.climate, world.config)
    val water = LiveWater(world)
    val eruptions = Eruptions(world.grid, world.volcanoes, world.config.seed)
    val vegetation = Vegetation(world, world.config.seed)
    /** Animals, from plankton to wolves. */
    val ecology = Ecology(this)
    /** Notable events, newest last, as readable lines. */
    val log = ArrayList<String>()
    private var knownEvents = 0

    val date: Date get() = weather.date

    /** Nanoseconds spent in weather, eruptions, water, vegetation and ecology, summed over all steps. */
    val timings = LongArray(5)

    fun step() {
        val stepDate = weather.date
        var t = System.nanoTime()
        fun lap(k: Int) { val now = System.nanoTime(); timings[k] += now - t; t = now }
        weather.step(); lap(0)
        for (e in eruptions.step(stepDate, world.config.daysPerStep)) {
            weather.volcanicForcing(e.vei, e.volcano.x, e.volcano.y)
            log += "$stepDate: ${e.volcano.kind.name.lowercase().replace('_', ' ')} volcano at (${e.volcano.x}, ${e.volcano.y}) erupts, VEI ${e.vei}"
        }
        lap(1)
        water.step(weather); lap(2)
        vegetation.step(weather, water, eruptions.ash); lap(3)
        for ((cell, km2) in vegetation.fires) if (km2 >= 50f)
            log += "$stepDate: wildfire burns %.0f km² from (%d, %d)".format(km2, world.grid.x(cell), world.grid.y(cell))
        ecology.step(); lap(4)
        if (stepDate.dayOfYear < world.config.daysPerStep) {
            log += "$stepDate: ${ecology.census()}"
        }
        while (knownEvents < weather.events.size) {
            val e = weather.events[knownEvents++]
            val years = (e.endDay - e.startDay) / Date.DAYS_PER_YEAR.toDouble()
            val what = when (e.kind) {
                ClimateEvent.Kind.DROUGHT -> "drought for %.0f years, rain at %.0f%%".format(years, e.strength * 100)
                ClimateEvent.Kind.COLD_WINTER -> "severe winter, %.0f °C below normal for %d days".format(-e.strength, e.endDay - e.startDay)
                ClimateEvent.Kind.HEAT_WAVE -> "heat wave, %.0f °C above normal for %d days".format(e.strength, e.endDay - e.startDay)
                ClimateEvent.Kind.VOLCANIC_WINTER -> "volcanic winter, %.1f °C cooler".format(-e.strength)
            }
            log += "${Date(e.startDay)}: $what around (${e.x.toInt()}, ${e.y.toInt()})"
        }
    }
}
