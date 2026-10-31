package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

/** What bands build that stands in the world: houses, a palisade round the houses at a hex, and weirs across its river. */
enum class Built { HOUSES, PALISADE, WEIR, WELL }

/**
 * A thing built that stands at hex [hex], whoever built it: no band owns it. Any band that comes on it empty may use it
 * ([user], the band using it this week, -1 for none), and it weathers away while nobody does. [builder] is the band that
 * began it, kept only as a fact of the bands' dealings. Houses ([Built.HOUSES]) shelter [size] grown people when whole,
 * built in the way [design] (a [Skill]'s ordinal); [built] is how far they stand, 0..1. A palisade's [built] is how much of
 * a ring round the houses at its hex stands. A weir's [size] is its length, m, across the river at its hex; a well's its depth, m. Nothing shelters or guards till it is first [done] (whole); after that it serves
 * as far as it stands, as it weathers and is mended.
 * (All its fields have defaults, so that a saved one loads.)
 */
class Structure(var kind: Built = Built.HOUSES, var hex: Hex = Hex.NONE, var design: Int = -1, var builder: Int = -1, var size: Float = 0f) {
    var built = 0f
    var user = -1
    var done = false
}

/** Whether [g] may use [s]: nobody else is using it this week. */
private fun usable(s: Structure, g: HexGroup) = s.user < 0 || s.user == g.id

/**
 * [HexGroups.built] by hex, in the list's order (so sums over a hex come out as over the list), for [structuresAt]. The list
 * only gains new structures at its end and loses some ([tendStructures]), so its size and last one tell whether it has changed.
 */
internal class StructureIndex(built: List<Structure>, hexes: Int) {
    val size = built.size
    val last = built.lastOrNull()
    val start = IntArray(hexes + 1)
    /** The structures that stand somewhere, by hex (a stable sort, so in the list's order within a hex). */
    val items: List<Structure> = built.filter { !it.hex.none }.sortedBy { it.hex }
    init {
        for (s in items) start[s.hex.i + 1]++
        for (h in 0 until hexes) start[h + 1] += start[h]
    }
    fun current(built: List<Structure>) = size == built.size && last === built.lastOrNull()
}

/** [f] for each structure at hex [h], in [HexGroups.built]'s order. */
private inline fun HexGroups.structuresAt(h: Hex, f: (Structure) -> Unit) {
    if (h.none) return
    val x = bands.builtByHex?.takeIf { it.current(bands.built) } ?: StructureIndex(bands.built, hex.size).also { bands.builtByHex = it }
    for (i in x.start[h.i] until x.start[h.i + 1]) f(x.items[i])
}

/** How far the houses at hex [h] that [g] may use shelter its grown people, 0..1. */
internal fun HexGroups.housesAt(g: HexGroup, h: Hex): Float {
    var room = 0f
    structuresAt(h) { s -> if (s.kind == Built.HOUSES && s.done && usable(s, g)) room += s.built * s.size }
    return min(1f, room / max(1, g.adults))
}

/** How far the houses begun at hex [h] that [g] may use and that are not yet whole would shelter its grown people, 0..1: what it has to show for its work on them. */
internal fun HexGroups.housesBegunAt(g: HexGroup, h: Hex): Float {
    var room = 0f
    structuresAt(h) { s -> if (s.kind == Built.HOUSES && !s.done && usable(s, g)) room += s.built * s.size }
    return min(1f, room / max(1, g.adults))
}

/** How far the houses at hex [h] that [g] may use bring the ground's warmth in ([earthGain]), over the room they give. */
internal fun HexGroups.earthAt(g: HexGroup, h: Hex): Float {
    var room = 0f; var earth = 0f
    structuresAt(h) { s -> if (s.kind == Built.HOUSES && s.done && usable(s, g)) {
        val r = s.built * s.size; room += r; earth += r * earthGain(Skill.entries[s.design.coerceAtLeast(0)])
    } }
    return if (room > 0f) earth / room else 0f
}

/** How well the walls of the houses at hex [h] that [g] may use keep beasts of prey out ([wallGuard]), over the room they give. */
internal fun HexGroups.wallsAt(g: HexGroup, h: Hex): Float {
    var room = 0f; var walls = 0f
    structuresAt(h) { s -> if (s.kind == Built.HOUSES && s.done && usable(s, g)) {
        val r = s.built * s.size; room += r; walls += r * wallGuard(Skill.entries[s.design.coerceAtLeast(0)])
    } }
    return if (room > 0f) walls / room else 0f
}

/** How much of a palisade stands at hex [h] that [g] may use, 0..1. */
internal fun HexGroups.palisadeAt(g: HexGroup, h: Hex): Float {
    var ring = 0f
    structuresAt(h) { s -> if (s.kind == Built.PALISADE && s.done && usable(s, g)) ring = max(ring, s.built) }
    return ring
}

/** How much of a palisade not yet whole stands at hex [h] that [g] may use, 0..1. */
internal fun HexGroups.palisadeBegunAt(g: HexGroup, h: Hex): Float {
    var ring = 0f
    structuresAt(h) { s -> if (s.kind == Built.PALISADE && !s.done && usable(s, g)) ring = max(ring, s.built) }
    return ring
}

/**
 * How much of the river at hex [h] the weirs there that [g] may use stand across, 0..1 ([HexWater.riverWidthM]): their
 * length as far as it stands, over the river's width. Begun ones ([begun]) count only once whole.
 */
internal fun HexGroups.weirAt(g: HexGroup, h: Hex, begun: Boolean = false): Float {
    val width = fauna.water.riverWidthM(h)
    if (width <= 0f) return 0f
    var m = 0f
    structuresAt(h) { s -> if (s.kind == Built.WEIR && (s.done || begun) && usable(s, g)) m += s.built * s.size }
    return min(1f, m / width)
}

/** The well at hex [h] that reaches deepest ([wellReachM]), whole or begun, or null: anyone may use it. */
internal fun HexGroups.wellAt(h: Hex): Structure? {
    var best: Structure? = null; var reach = -1f
    structuresAt(h) { s -> if (s.kind == Built.WELL && wellReachM(s) > reach) { best = s; reach = wellReachM(s) } }
    return best
}

/** The length of a weir a band would build across the river at hex [h], m: as wide as the river, up to [WEIR_MAX_M]. */
internal fun HexGroups.weirLengthM(h: Hex) = min(fauna.water.riverWidthM(h), WEIR_MAX_M)

/** [s] built on by [add] (of the whole); it is [Structure.done] once it first stands whole. */
internal fun buildOn(s: Structure, add: Float) {
    s.built = min(1f, s.built + add)
    if (s.built >= 1f - 1e-4f) { s.built = 1f; s.done = true }
}

/**
 * [g] takes up what stands at hex [h] that nobody else is using this week: houses only as far as it needs them for its grown
 * people, those it already uses first (who is there keeps what it uses); unused houses are nobody's and go to whoever needs
 * them. A palisade or a weir it takes up whole, as before. A well nobody takes up: it is anyone's at its hex.
 */
internal fun HexGroups.useStructures(g: HexGroup, h: Hex) {
    var room = 0f
    structuresAt(h) { s -> if (s.kind == Built.HOUSES && s.user == g.id) room += s.built * s.size }
    structuresAt(h) { s ->
        if (s.user >= 0 || s.kind == Built.WELL) return@structuresAt
        if (s.kind != Built.HOUSES) s.user = g.id
        else if (room < g.adults) { s.user = g.id; room += s.built * s.size }
    }
}

/** [g] gives up what it uses at hex [h]: free to anyone from now. */
internal fun HexGroups.leaveStructures(g: HexGroup, h: Hex) {
    structuresAt(h) { s -> if (s.user == g.id) s.user = -1 }
}

/** What [g] is building of [kind] at hex [h] and has not finished, or a new one begun there (houses for [size] grown people). */
internal fun HexGroups.buildingAt(g: HexGroup, kind: Built, h: Hex, design: Int, size: Float): Structure =
    bands.built.firstOrNull { it.kind == kind && it.hex == h && it.user == g.id && it.built < 1f }
        ?: Structure(kind, h, design, g.id, size).also { it.user = g.id; bands.built += it }

/**
 * Once a week, before the bands' own weeks: what a band left (it is no longer at that hex, or dead) is free to anyone, and
 * what nobody uses weathers away ([HOUSE_DECAY] a year), till nothing stands. Mudbrick wears in the week's [weather]'s rain
 * besides, used or not ([MUD_EROSION]): the band using it mends it as it would build. Wells silt up ([WELL_WEAR]), and then
 * the places they bring water to are found ([wellWater]).
 */
internal fun HexGroups.tendStructures(weather: Weather) {
    if (!bands.structuresSeen) { bands.structuresSeen = true; raiseSavedHouses() }
    if (bands.built.isEmpty()) { fauna.water.weirBlock.fill(0f); land.drink.well.fill(0f); return }
    val where = HashMap<Int, Hex>()
    for (g in groups) if (g.alive && g.band != null) where[g.id] = hw.placeHex[g.place]
    for (s in bands.built) {
        if (s.user >= 0 && where[s.user] != s.hex) s.user = -1
        // (A well nobody holds silts up at its own rate, used or not; whoever wants it mends it.)
        if (s.kind == Built.WELL) s.built = max(0f, s.built - WELL_WEAR * years)
        else if (s.user < 0) s.built = max(0f, s.built - HOUSE_DECAY * years)
        // (A weir stands in the stream: the current and the floods wear it, used or not, and the band using it mends it.)
        if (s.kind == Built.WEIR) s.built = max(0f, s.built - WEIR_WEAR * years)
        if (s.kind == Built.HOUSES && s.design == Skill.MUDBRICK.ordinal) {
            val places = hw.placesOf(s.hex)
            var rain = 0f; for (p in places) rain += weather.rain(p)
            if (places.isNotEmpty()) s.built = max(0f, s.built - MUD_EROSION * rain / places.size / MUD_WALL_MM)
        }
    }
    bands.built.removeAll { it.user < 0 && it.built <= 0f }
    // (How much of each river the weirs that stand whole hold back the run from, whoever uses them: [HexWater.weirBlock].)
    val block = fauna.water.weirBlock
    block.fill(0f)
    val at = HashSet<Hex>()
    for (s in bands.built) if (s.kind == Built.WEIR && s.done) { block[s.hex] += s.built * s.size; at += s.hex }
    for (h in at) block[h] = min(1f, block[h] / max(fauna.water.riverWidthM(h), 1e-3f))
    wellWater()
}

/**
 * A world saved before houses stood in it on their own (model 4.17 and before) kept them as each band's: they are raised
 * where they stood, for the band's grown people, as far as they stood.
 */
private fun HexGroups.raiseSavedHouses() {
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || b.houseHex.none || bands.built.any { it.builder == g.id }) continue
        val h = b.houseHex; val here = hw.placeHex[g.place] == h
        if (b.house > 0f) bands.built += Structure(Built.HOUSES, h, Skill.STONE_FOOTING.ordinal, g.id, g.adults.toFloat()).also {
            it.built = b.house; it.done = true; if (here) it.user = g.id }
        if (b.palisade > 0f) bands.built += Structure(Built.PALISADE, h, -1, g.id, g.adults.toFloat()).also {
            it.built = b.palisade; it.done = true; if (here) it.user = g.id }
    }
}

/**
 * What a whole house would have done for a band's week against the houses it had: the parts of [Band.houseLives] and
 * [Band.houseFood], and what they leave out, for the census only. Person-weeks, and those under a roof (its own houses'
 * share); lives from exposure (against the shelters it had), beasts of prey and raids; for comparison, the exposure lives
 * a whole brush shelter saved over none and a whole house over no cover at all, with the same fire; kg of need from keeping
 * warm, stores kept from spoiling and mice, and stores kept from raiders; that food in lives (the hunger deaths it would
 * have averted that week) and in days of body protein it would have spared the grown against later hunger; and the person-days the house would have given
 * back to the cold, had all the week's days been out at work (warming breaks, [Warmth.pace]) or at crafts in camp
 * ([Warmth.campPace]).
 */
enum class HouseWorth { PEOPLE_WEEKS, ROOFED_WEEKS, EXPOSURE, STALKED, RAIDS, SHELTER_EXPOSURE, BARE_EXPOSURE, WARM_KG, KEPT_KG, RAID_KG,
    HUNGER, PROTEIN_DAYS, WORK_DAYS, CRAFT_DAYS }

/** The week's mean air at a band's camp, °C: the first bin it falls [below]. The report's line. */
enum class HouseAir(val label: String, val below: Float) {
    DEEP("below -10 °C", -10f), FROST("-10 to 0 °C", 0f), COOL("0 to 10 °C", 10f), MILD("10 °C and over", Float.POSITIVE_INFINITY);
    companion object { fun of(c: Float) = entries.firstOrNull { c < it.below } ?: MILD }
}

/**
 * The lives a year houses would save for each of [b]'s grown people camped at place [p], whole and sheltering them all,
 * against none: the deaths of exposure they would avert month by month at the place's usual temperatures
 * ([Weather.usualTemperature]; the wind, rain and snow as this week's there), by the same rules as the week's deaths
 * ([placeHazards]), with its band's clothes, fire and shelters.
 */
internal fun GroupWorld.houseLivesAYear(b: Band, p: Place, weather: Weather): Float {
    val fire = b.fire
    var saved = 0f
    for (m in 0 until Date.MONTHS_PER_YEAR) {
        val air = airAt(p, weather, weather.usualTemperature(m, p))
        saved += (b.warmth(air, fire, 0f, 7).odds - b.warmth(air, fire, 1f, 7).odds) * Date.DAYS_PER_YEAR / 7f / Date.MONTHS_PER_YEAR
    }
    return max(0f, saved)
}

/**
 * The food a year houses would save each of [g]'s grown people camped at place [p], whole and sheltering them all, against
 * none, kg: what keeping warm takes of the day's need month by month at the place's usual temperatures ([warmthNeed]; the
 * wind, rain and snow as this week's there), with its band's clothes, fire and shelters.
 */
internal fun HexGroups.houseFoodAYear(g: HexGroup, b: Band, p: Place, weather: Weather): Float {
    val s = g.species; val fire = b.fire
    var saved = 0f
    for (m in 0 until Date.MONTHS_PER_YEAR) {
        val t = weather.usualTemperature(m, p)
        saved += (warmthNeed(s, b, p, weather, fire, 0f, t) - warmthNeed(s, b, p, weather, fire, 1f, t)) * s.dailyIntakeKg * Date.DAYS_PER_YEAR / Date.MONTHS_PER_YEAR
    }
    return max(0f, saved)
}

