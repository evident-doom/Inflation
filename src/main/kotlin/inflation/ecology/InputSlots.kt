package inflation.ecology

/**
 * The numbers of a group's view of itself, laid out by name: each slot, or block of slots, is named where it is
 * placed, so the name a learned network's `.names` file records for a slot cannot drift from what is written there.
 */
abstract class InputSlots {
    private val list = ArrayList<String>()
    /** One slot, [name]d; its index. */
    protected fun one(name: String): Int { list += name; return list.size - 1 }
    /** A block of slots, one for each of [names]; the index of the first. */
    protected fun block(names: List<String>): Int { val at = list.size; list += names; return at }
    /** How many numbers there are. */
    val size get() = list.size
    /** The name of each slot, in order, as a learned network's `.names` file records them (the strings must not change). */
    val names: List<String> get() = list.toList()
}

/** The slots of a band's view of itself ([bandInputs]). */
object BandInputs : InputSlots() {
    val YEAR_SIN = one("year sin"); val YEAR_COS = one("year cos")
    val FED = one("fed"); val LARDER = one("larder")
    val STORES = block(Store.entries.map { "store:${it.name.lowercase()}" })
    val ADULTS = one("adults"); val YOUNG = one("young per adult")
    val SKILLS = block(Skill.FORAGING.map { "skill:${it.name.lowercase()}" })
    val TOOLS = block(Tool.entries.map { "tool:${it.name.lowercase()}" })
    val TEMPERATURE = one("temperature"); val CAMP_WEEKS = one("camp weeks"); val SPARE_DAYS = one("spare days")
    // Its health, the dangers and the bands about it.
    val SICK = block(Illness.entries.map { "sick:${it.name.lowercase()}" })
    val WOUNDED = one("wounded"); val LAMED = one("lamed"); val ELDERS = one("elders"); val TAKEN_BY_BEASTS = one("taken by beasts")
    val BEASTS = one("beasts about")
    val FRIENDS = one("friendly bands about"); val STRANGERS = one("strange bands about"); val FOES = one("hostile bands about")
    val THEIR_SICKNESS = one("sickness of bands about")
    val RAIDED = one("raided lately"); val ON_A_RAID = one("set on a raid"); val INBRED = one("inbreeding")
    val OWED = one("favours owed to it"); val OWES = one("favours it owes")
    val CUSTOMS = block(Tradition.entries.map { "custom:${it.name.lowercase()}" })
    val TEMPER = block(Temperament.NAMES.map { "temper:$it" })
}

/** The slots of a herd's or pack's view of itself ([animalInputs]). */
object AnimalInputs : InputSlots() {
    val YEAR_SIN = one("year sin"); val YEAR_COS = one("year cos")
    val FED = one("fed"); val FAT = one("fat"); val DAYS_DRY = one("days dry"); val OWN_SIZE = one("own size"); val YOUNG = one("young per adult")
    val WARY = one("wary"); val HOLDS_GROUND = one("holds ground"); val WEEKS_HUNGRY = one("weeks hungry"); val TEMPERATURE = one("temperature")
    // What it is: its weight, and what it eats.
    val MASS = one("mass")
    val GRASS = one("diet:grass"); val BROWSE = one("diet:browse"); val FRUIT_AND_ROOTS = one("diet:fruit and roots")
    val MEAT = one("diet:meat"); val SMALL_PREY = one("diet:small prey"); val CARRION = one("diet:carrion")
    val TEMPER = block(Temperament.NAMES.map { "temper:$it" })
}
