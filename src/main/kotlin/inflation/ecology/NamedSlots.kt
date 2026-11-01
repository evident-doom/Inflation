package inflation.ecology

/**
 * The numbers a learned network reads or writes, laid out by name: each slot, or block of slots, is named where it is
 * placed, so the name a learned network's `.names` file records for a slot cannot drift from what is written there.
 */
abstract class NamedSlots {
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
object BandInputs : NamedSlots() {
    val YEAR_SIN = one("year sin"); val YEAR_COS = one("year cos")
    val FED = one("fed"); val LARDER = one("larder")
    val STORES = block(Store.entries.map { "store:${it.name.lowercase()}" })
    val ADULTS = one("adults"); val YOUNG = one("young per adult")
    val SKILLS = block(Skill.FORAGING.map { "skill:${it.name.lowercase()}" })
    val TOOLS = block(Tool.entries.map { "tool:${it.name.lowercase()}" })
    val TEMPERATURE = one("temperature"); val CAMP_WEEKS = one("camp weeks"); val SPARE_DAYS = one("spare days")
    // Its camp's ground: thirst, firewood, cover and snow.
    val DAYS_DRY = one("days dry"); val FIREWOOD = one("firewood"); val COVER = one("cover"); val SNOW = one("snow")
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
    // The ecosystem about its camp (names appended, so that nets trained before them give them no weight): the water and
    // what it holds, the plants and their food, the game, and the beasts of prey by whether they take people.
    val NEAR_RIVER = one("near river"); val NEAR_LAKE = one("near lake"); val NEAR_SPRING = one("near spring"); val ON_COAST = one("on the coast")
    val FISH = one("fish and shellfish within reach"); val WATERLOGGED = one("waterlogged")
    val TREES = one("trees"); val SHRUBS = one("shrubs"); val GRASS = one("grass")
    // (Fruit, roots and seed side by side, as [plantFood] writes them.)
    val FRUIT = one("plant food:fruit"); val ROOTS = one("plant food:roots"); val SEED = one("plant food:seed")
    val GAME = one("game within reach"); val SMALL_GAME = one("small game"); val CARRION = one("carrion"); val GAME_TREND = one("game trend")
    val PREDATORS = block(BeastClass.entries.map { "beasts of prey:${it.label}" })
    val ELEVATION = one("elevation")
    // Of the protein it ate last week, the share that went to waste beyond what protein can feed ([Band.leanLost]).
    val LEAN_LOST = one("lean food wasted")
    // What it has to build with and how: the goods it holds (kg against what a person carries), how well it knows each
    // way of building, and the houses and palisade where it is now.
    val GOODS = block(Material.entries.map { "goods:${it.name.lowercase()}" })
    val BUILDING = block(Skill.BUILDING_WAYS.map { "building:${it.name.lowercase()}" })
    val HOUSE_HERE = one("houses here"); val PALISADE_HERE = one("palisade here")
    // This week's move (the ways are set after it, see [hunt]): how far it walked, the days the walk takes all its people, and
    // what an adult would carry in a load ([carriedKg]), so that its loads and its week are weighed knowing where it went.
    val MOVE_KM = one("camp move km"); val MOVE_DAYS = one("camp move days"); val LOAD = one("load an adult carries")
    // What it has begun building here and not finished (houses, as the share of its grown people they would shelter; the
    // palisade's ring): it serves nothing yet ([Structure.done]), so that the work it holds is seen.
    val HOUSES_BEGUN = one("houses begun here"); val PALISADE_BEGUN = one("palisade begun here")
    // Its people's cold this week where it stands, by their body heat ([BodyHeat]): the food keeping warm would take, the odds of dying of
    // exposure, the wind and wet about the camp, and what it has against them (its fire's share of the week, its houses' shelter).
    val COLD_FOOD = one("cold: extra food"); val COLD_ODDS = one("cold: exposure odds"); val WIND = one("wind"); val WET = one("wet")
    val FIRE_SHARE = one("fire share"); val HOME_SHELTER = one("home shelter")
    // Its salt: the share of its people's sodium it lacked last week ([Band.sodiumShort]), and the salt to be had where it stands.
    val SODIUM_SHORT = one("sodium short"); val SALT_HERE = one("salt here")
    // Its last week's plan: the share of its days it held for each activity ([Band.heldShare]), what it set out to do.
    val HELD = block(Activity.entries.map { "held:${it.label}" })
}

/** The slots of a pack's view of itself ([animalInputs]). */
object AnimalInputs : NamedSlots() {
    val YEAR_SIN = one("year sin"); val YEAR_COS = one("year cos")
    val FED = one("fed"); val FAT = one("fat"); val DAYS_DRY = one("days dry"); val OWN_SIZE = one("own size"); val YOUNG = one("young per adult")
    val WARY = one("wary"); val HOLDS_GROUND = one("holds ground"); val WEEKS_HUNGRY = one("weeks hungry"); val TEMPERATURE = one("temperature")
    // What it is: its weight, and what it eats.
    val MASS = one("mass")
    val GRASS = one("diet:grass"); val BROWSE = one("diet:browse"); val FRUIT_AND_ROOTS = one("diet:fruit and roots")
    val MEAT = one("diet:meat"); val SMALL_PREY = one("diet:small prey"); val CARRION = one("diet:carrion")
    val TEMPER = block(Temperament.NAMES.map { "temper:$it" })
}

/** The slots of a band's ways ([setWays]' outputs, [BandWays]), each 0 for the scaffold's choice. */
object WaysOut : NamedSlots() {
    /** Its effort on each food it sets one for ([FOOD_TYPES]: the [Food] kinds but grain, which goes by grass seed's). */
    val EFFORTS = block(List(FOOD_TYPES) { "effort:$it" })
    /** The days of food it aims to keep. */
    val STORE_AIM = one("store aim")
    /** How it ranks making each [Tool] first. */
    val MAKE_FIRST = block(Tool.entries.map { "make first:${it.name.lowercase()}" })
    /** Its [BandWay]s, in their order ([BandWay.slot]). */
    val WAYS = block(BandWay.entries.map { it.label })
}
