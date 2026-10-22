package inflation.ecology

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
 * a ring round the houses at its hex stands.
 * (All its fields have defaults, so that a saved one loads.)
 */
class Structure(var kind: Built = Built.HOUSES, var hex: Int = -1, var design: Int = -1, var builder: Int = -1, var size: Float = 0f) {
    var built = 0f
    var user = -1
}

/** Whether [g] may use [s]: nobody else is using it this week. */
private fun usable(s: Structure, g: HexGroup) = s.user < 0 || s.user == g.id

/** How far the houses at hex [h] that [g] may use shelter its grown people, 0..1. */
internal fun HexGroups.housesAt(g: HexGroup, h: Int): Float {
    var room = 0f
    for (s in built) if (s.kind == Built.HOUSES && s.hex == h && usable(s, g)) room += s.built * s.size
    return min(1f, room / max(1, g.adults))
}

/** How much of a palisade stands at hex [h] that [g] may use, 0..1. */
internal fun HexGroups.palisadeAt(g: HexGroup, h: Int): Float {
    var ring = 0f
    for (s in built) if (s.kind == Built.PALISADE && s.hex == h && usable(s, g)) ring = max(ring, s.built)
    return ring
}

/** [g] takes up what stands at hex [h] that nobody else is using this week. */
internal fun HexGroups.useStructures(g: HexGroup, h: Int) {
    for (s in built) if (s.hex == h && s.user < 0) s.user = g.id
}

/** What [g] is building of [kind] at hex [h] and has not finished, or a new one begun there (houses for [size] grown people). */
internal fun HexGroups.buildingAt(g: HexGroup, kind: Built, h: Int, design: Int, size: Float): Structure =
    built.firstOrNull { it.kind == kind && it.hex == h && it.user == g.id && it.built < 1f }
        ?: Structure(kind, h, design, g.id, size).also { it.user = g.id; built += it }

/**
 * Once a week, before the bands' own weeks: what a band left (it is no longer at that hex, or dead) is free to anyone, and
 * what nobody uses weathers away ([HOUSE_DECAY] a year), till nothing stands.
 */
internal fun HexGroups.tendStructures() {
    if (!structuresSeen) { structuresSeen = true; raiseSavedHouses() }
    if (built.isEmpty()) return
    val where = HashMap<Int, Int>()
    for (g in groups) if (g.alive && g.band != null) where[g.id] = hw.placeHex[g.place]
    for (s in built) {
        if (s.user >= 0 && where[s.user] != s.hex) s.user = -1
        if (s.user < 0) s.built = max(0f, s.built - HOUSE_DECAY * years)
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
        val h = b.houseHex.i; val here = hw.placeHex[g.place] == h
        if (b.house > 0f) built += Structure(Built.HOUSES, h, Skill.STONE_FOOTING.ordinal, g.id, g.adults.toFloat()).also {
            it.built = b.house; if (here) it.user = g.id }
        if (b.palisade > 0f) built += Structure(Built.PALISADE, h, -1, g.id, g.adults.toFloat()).also {
            it.built = b.palisade; if (here) it.user = g.id }
    }
}

/**
 * The lives a year houses would save for each of [g]'s grown people camped at place [p], whole and sheltering them all,
 * against none: the deaths of cold and exposure they would avert month by month at the place's usual temperatures
 * ([Weather.usualTemperature]), by the same rules as the week's deaths ([placeHazards]), with its band's clothes and fire.
 */
internal fun HexGroups.houseLivesAYear(g: HexGroup, b: Band, p: Place, weather: Weather): Float {
    val s = g.species; val fire = b.fire
    var saved = 0f
    for (m in 0 until Date.MONTHS_PER_YEAR) {
        val t = weather.usualTemperature(m, p)
        fun week(home: Float) = min(0.9f, EXPOSURE * b.exposure(t, fire, home)) + Hazards.cold(s, b.feels(t, fire, home), m)
        saved += (week(0f) - week(1f)) * Date.DAYS_PER_YEAR / 7f / Date.MONTHS_PER_YEAR
    }
    return max(0f, saved)
}
