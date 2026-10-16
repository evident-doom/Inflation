package inflation.ecology

import inflation.Config

/**
 * Settings a balancing run can change without rebuilding (t.NAME=value in the config file, see [inflation.Config]), each a multiplier on
 * an existing parameter (1 leaves it as it is), or the parameter itself where it is a count.
 */
object Tuning {
    private fun f(name: String, default: Float) = Config.float("t.$name", default)
    /** Outbreaks starting among people, against [HexFauna.OUTBREAK_START]. */
    val peopleOutbreak = f("peopleOutbreak", 1f)
    /** People's sickness from crowding, against [PEOPLE_SICK]. */
    val peopleSick = f("peopleSick", 1f)
    /** Time to pick, dig or reap a kilo of plant food, against [PLANT_HANDLING_DAYS_PER_KG] and its kin. */
    val plantHandling = f("plantHandling", 1f)
    /** Chance a much stronger hunter kills another kind's young, against [INTRAGUILD_KILL_YOUNG]. */
    val intraguildYoung = f("intraguildYoung", 1f)
    /** People's deaths of age and ordinary illness, of children, of thirst, and from camp fouling, against their present rates. */
    val humanOther = f("humanOther", 1f)
    val humanYoung = f("humanYoung", 1f)
    val humanThirst = f("humanThirst", 1f)
    val campFouling = f("campFouling", 1f)
    /** Marsh fever among people camped by marshes, against [MARSH_FEVER]. */
    val marshFever = f("marshFever", 1f)
    /** People's births, and the extra births of settled, well-stocked bands ([SETTLED_FERTILITY]), against their present rates. */
    val humanBirths = f("humanBirths", 1f)
    val settledFertility = f("settledFertility", 1f)
    /** The food cost of keeping warm, against [COLD_COST]. */
    val coldCost = f("coldCost", 1f)
    /** Weeks between a content herd's weighing of its places ([CONTENT_WEEKS]). */
    val contentWeeks = Config.int("t.contentWeeks", CONTENT_WEEKS)

    /** The settings that differ from the defaults, for the check's header. */
    fun changed(): String = Config.set("t.")
}
