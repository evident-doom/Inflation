package inflation.ecology

/**
 * Settings a balancing run can change without rebuilding (-Dhex.t.NAME=value), each a multiplier on
 * an existing parameter (1 leaves it as it is), or the parameter itself where it is a count.
 */
object Tuning {
    private fun f(name: String, default: Float) = System.getProperty("hex.t.$name")?.toFloat() ?: default
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
    val contentWeeks = System.getProperty("hex.t.contentWeeks")?.toInt() ?: CONTENT_WEEKS
    /** Bands weigh where to go every week ([BAND_CONTENT_WEEKS]); -Dhex.t.bandContentWeeks=2 gives the old every other week when content. */
    val bandContentWeeks = System.getProperty("hex.t.bandContentWeeks")?.toInt() ?: BAND_CONTENT_WEEKS

    /** The settings that differ from the defaults, for the check's header. */
    fun changed(): String = System.getProperties().stringPropertyNames().filter { it.startsWith("hex.t.") }.sorted().joinToString(", ") { "${it.removePrefix("hex.t.")} ${System.getProperty(it)}" }
}
