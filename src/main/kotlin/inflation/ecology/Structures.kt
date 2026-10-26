package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

/** What bands build that stands in the world: houses, and a palisade round the houses at a hex. */
enum class Built { HOUSES, PALISADE }

/**
 * A thing built that stands at hex [hex], whoever built it: no band owns it. Any band that comes on it empty may use it
 * ([user], the band using it this week, -1 for none), and it weathers away while nobody does. [builder] is the band that
 * began it, kept only as a fact of the bands' dealings. Houses ([Built.HOUSES]) shelter [size] grown people when whole,
 * built in the way [design] (a [Skill]'s ordinal); [built] is how far they stand, 0..1. A palisade's [built] is how much of
 * a ring round the houses at its hex stands. Nothing shelters or guards till it is first [done] (whole); after that it serves
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
    val items: Array<Structure?> = arrayOfNulls(built.size)
    init {
        for (s in built) if (!s.hex.none) start[s.hex.i + 1]++
        for (h in 0 until hexes) start[h + 1] += start[h]
        val at = start.copyOf()
        for (s in built) if (!s.hex.none) items[at[s.hex.i]++] = s
    }
    fun current(built: List<Structure>) = size == built.size && last === built.lastOrNull()
}

/** [f] for each structure at hex [h], in [HexGroups.built]'s order. */
private inline fun HexGroups.structuresAt(h: Hex, f: (Structure) -> Unit) {
    if (h.none) return
    val x = builtByHex?.takeIf { it.current(built) } ?: StructureIndex(built, hex.size).also { builtByHex = it }
    for (i in x.start[h.i] until x.start[h.i + 1]) f(x.items[i]!!)
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

/** [s] built on by [add] (of the whole); it is [Structure.done] once it first stands whole. */
internal fun buildOn(s: Structure, add: Float) {
    s.built = min(1f, s.built + add)
    if (s.built >= 1f - 1e-4f) { s.built = 1f; s.done = true }
}

/** [g] takes up what stands at hex [h] that nobody else is using this week. */
internal fun HexGroups.useStructures(g: HexGroup, h: Hex) {
    structuresAt(h) { s -> if (s.user < 0) s.user = g.id }
}

/** What [g] is building of [kind] at hex [h] and has not finished, or a new one begun there (houses for [size] grown people). */
internal fun HexGroups.buildingAt(g: HexGroup, kind: Built, h: Hex, design: Int, size: Float): Structure =
    built.firstOrNull { it.kind == kind && it.hex == h && it.user == g.id && it.built < 1f }
        ?: Structure(kind, h, design, g.id, size).also { it.user = g.id; built += it }

/**
 * Once a week, before the bands' own weeks: what a band left (it is no longer at that hex, or dead) is free to anyone, and
 * what nobody uses weathers away ([HOUSE_DECAY] a year), till nothing stands. Mudbrick wears in the week's [weather]'s rain
 * besides, used or not ([MUD_EROSION]): the band using it mends it as it would build.
 */
internal fun HexGroups.tendStructures(weather: Weather) {
    if (!structuresSeen) { structuresSeen = true; raiseSavedHouses() }
    if (built.isEmpty()) return
    val where = HashMap<Int, Hex>()
    for (g in groups) if (g.alive && g.band != null) where[g.id] = hw.placeHex[g.place]
    for (s in built) {
        if (s.user >= 0 && where[s.user] != s.hex) s.user = -1
        if (s.user < 0) s.built = max(0f, s.built - HOUSE_DECAY * years)
        if (s.kind == Built.HOUSES && s.design == Skill.MUDBRICK.ordinal) {
            val places = hw.placesOf(s.hex)
            var rain = 0f; for (p in places) rain += weather.rain(p)
            if (places.isNotEmpty()) s.built = max(0f, s.built - MUD_EROSION * rain / places.size / MUD_WALL_MM)
        }
    }
    built.removeAll { it.user < 0 && it.built <= 0f }
}

/**
 * A world saved before houses stood in it on their own (model 4.17 and before) kept them as each band's: they are raised
 * where they stood, for the band's grown people, as far as they stood.
 */
private fun HexGroups.raiseSavedHouses() {
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || b.houseHex.none || built.any { it.builder == g.id }) continue
        val h = b.houseHex; val here = hw.placeHex[g.place] == h
        if (b.house > 0f) built += Structure(Built.HOUSES, h, Skill.STONE_FOOTING.ordinal, g.id, g.adults.toFloat()).also {
            it.built = b.house; it.done = true; if (here) it.user = g.id }
        if (b.palisade > 0f) built += Structure(Built.PALISADE, h, -1, g.id, g.adults.toFloat()).also {
            it.built = b.palisade; it.done = true; if (here) it.user = g.id }
    }
}

/**
 * The lives a year houses would save for each of [b]'s grown people camped at place [p], whole and sheltering them all,
 * against none: the deaths of exposure they would avert month by month at the place's usual temperatures
 * ([Weather.usualTemperature]; the wind, rain and snow as this week's there), by the same rules as the week's deaths
 * ([placeHazards]), with its band's clothes, fire and shelters.
 */
internal fun HexGroups.houseLivesAYear(b: Band, p: Place, weather: Weather): Float {
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

