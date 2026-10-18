package inflation.tools.fauna

import inflation.ecology.HexHistory
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Profile
import inflation.world.Date

/**
 * A fauna check's run: the start, the weeks with what is tallied after each, a report at each year's
 * end and one at the end of the run. The reports read the model through [Census] and what was counted
 * week by week through the tallies; [Diagnostics] adds the lines the config asks for.
 */
class FaunaCheck(private val sim: HexSim, private val options: FaunaCheckOptions) {
    private val stepsPerYear = Date.DAYS_PER_YEAR / options.cfg.daysPerStep
    private val census = Census(sim)
    private val deaths = DeathTally(sim)
    private val hunting = HuntTally(sim)
    private val movement = MovementTally(sim)
    private val people = PeopleTally(sim, stepsPerYear)
    private val swings = LocalSwings(sim, stepsPerYear)
    private val diagnostics = Diagnostics(sim, options.cfg.daysPerStep)
    private val history = HexHistory()
    /** Time spent in the steps themselves, ns. */
    private var stepNs = 0L

    fun run() {
        // No years: the world only read and written again (scripts/faunacmp.sh compares saves so, when their layout changes).
        if (options.years == 0) { options.saveFile?.let { HexSnapshot.save(sim, it) }; return }
        printStart()
        for (y in 1..options.years) {
            deaths.startYear(); hunting.startYear(); movement.startYear(); people.startYear()
            repeat(stepsPerYear) { w -> week(y, w) }
            yearEnd(y)
        }
        printEnd()
    }

    private fun printStart() {
        val fauna = sim.fauna
        println("year " + fauna.kinds.joinToString("") { "%11s".format(it.name.take(10)) })
        println("   0 " + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
        println("     climate fit: " + census.fit())
        val groups = sim.groups
        run {
            val t0 = System.nanoTime(); groups.bearings.month
            val t1 = System.nanoTime(); groups.bearings.update(0); fauna.bearings.update(0)
            println("sense of direction: setting up %.0f ms, a month's update for all kinds %.0f ms".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
        }
        println("     matter: " + census.budget())
        println("     land %.0f km²; groups at the start: ".format(sim.land.areaKm2.sum()) +
            groups.kinds.joinToString(", ") { "${it.name} ${groups.groups.count { g -> g.species == it }} (${groups.count(it)})" })
    }

    private fun week(y: Int, w: Int) {
        if (w == stepsPerYear / 2) println("     midsummer, land that suits / pull felt: " + census.drawn())
        diagnostics.beforeStep(y, w)
        // Only the step itself.
        val t0 = System.nanoTime()
        sim.step()
        stepNs += System.nanoTime() - t0
        hunting.week(); movement.week(w); people.week(); swings.week(); deaths.week()
        diagnostics.afterStep()
        if (options.historyFile != null) history.record(sim)
    }

    private fun yearEnd(y: Int) {
        val fauna = sim.fauna; val groups = sim.groups
        println("%4d ".format(y) + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
        println("     small life: " + fauna.small.summary())
        println("     water life: " + fauna.water.summary())
        println("     sea and lake fish this week, t: " +
            fauna.water.fishFlows.entries.sortedBy { it.key }.joinToString(", ") { "%s %.1f".format(it.key, it.value) })
        println("     eruptions so far: " + sim.eruptions.history.joinToString(", ") { "VEI ${it.vei} in year ${it.date.year}" }.ifEmpty { "none" })
        println("     matter: " + census.budget())
        println("     lakes: " + sim.land.hydro.lakeSummary())
        println("     climate fit: " + census.fit())
        println("     land that suits / pull felt: " + census.drawn())
        println("     groups: " + groups.kinds.joinToString(", ") { "${it.name} ${groups.count(it)}" })
        census.printByCountry()
        census.printCoastAndInland()
        census.printPaths()
        census.printGroupSpread()
        deaths.printHuntersBornAndDied()
        hunting.printHunting()
        hunting.printHuntersFood()
        hunting.printPeoplesHides()
        hunting.printTriesOnHerds()
        hunting.printGathering()
        movement.printSeasons()
        census.printChoices()
        census.printHuntersFamilies()
        people.printBands()
        people.printAgainstTheRecord(deaths, hunting)
        deaths.printResidentDeathsThisYear()
        println("     droughts under way: " + sim.weather.droughts())
        hunting.endYear()
        print("     "); census.printSkillsAndCereals(hunting.grainReapedLastYear)
        System.out.flush()
        // The history so far, for the graphs, and the saved world (every five years and at the end).
        val last = y == options.years
        options.historyFile?.let { if (y % 5 == 0 || last) history.writeCsv(it) }
        options.saveFile?.let {
            if (y % 5 == 0 || last) {
                val t0 = System.nanoTime()
                HexSnapshot.save(sim, it)
                println("     saved the world to $it (%.1f s, %.0f MB)".format((System.nanoTime() - t0) / 1e9, it.length() / 1e6))
            }
        }
        println("     this week: kills " + groups.stats.kills.entries.joinToString(", ") { "${it.key} ${it.value}" } + " | meetings " +
            groups.stats.meetings.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }

    private fun printEnd() {
        swings.print()
        census.printFoodUnevenness()
        census.printSkillsAndCereals(hunting.grainReapedLastYear)
        deaths.printHerdDeathsBySize(stepsPerYear)
        census.printKindByKind(hunting.lastYear, movement.movedKm, stepsPerYear)
        census.printBandsByCountry(hunting.lastYear, stepsPerYear)
        census.printIslands()
        deaths.printRunDeaths()
        census.printHeights()
        val steps = options.years * stepsPerYear
        println("where a step's time goes:")
        println(Profile.report(steps))
        if (Profile.FINE) { println("finer parts (thread time, summed over cores):"); println(Profile.fineReport(steps)) }
        sim.books?.let { println("the books: what each part of the step left over of water and matter, beyond the flows booked across the world's edge"); print(it.report(steps)) }
        val groups = sim.groups
        println("a step of weather, water, plants and all animals: %.1f ms, of it settling meetings %.1f ms, the sense of direction %.1f ms"
            .format(stepNs / 1e6 / steps, groups.meetNs / 1e6 / steps, (groups.bearings.updateNs + sim.fauna.bearings.updateNs) / 1e6 / steps))
    }
}
