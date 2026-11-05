package inflation.ecology

import inflation.world.Place
import inflation.world.get
import inflation.world.set
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min

// Beasts of prey and the bands: the man-eaters about a camp, the share they take, and what keeps them off (fire, dogs, the night watch, houses, a palisade).

/**
 * The yearly-scaled share of a band taken by beasts of prey this week, and the beast most to blame:
 * lions, leopards, bears, wolves and hyenas that range its hex now and then take one who is alone
 * at the gathering, a child, an old one. The thicker the cover the likelier; a fire at night, dogs,
 * lookouts, company and an eye for sign (tracking) keep them off. No set battle: the beasts take
 * the unwary, not armed men.
 */
internal fun HexGroups.stalkedBy(g: HexGroup, b: Band, kinNow: HashMap<Long, Int>): Stalking {
    val h = hw.placeHex[g.place]; val p = g.place
    val hexKm2 = hex.areaKm2.toFloat()
    var pressure = 0f; var worst: Species? = null; var worstP = 0f
    for (k in kinds) {
        val w = k.takesPeople; if (w <= 0f) continue
        val heads = kinNow[k.index.toLong() * hex.size + h.i] ?: continue
        val v = w * heads * k.massKg / hexKm2
        pressure += v; if (v > worstP) { worstP = v; worst = k }
    }
    eachResidentManEater(p) { k, v -> pressure += v; if (v > worstP) { worstP = v; worst = k } }
    if (pressure <= 0f) return Stalking.NONE
    // (Where one was taken the band remembers the danger.)
    return Stalking(stalkRate(g, b, p, pressure), worst)
}

/** The man-eaters' flesh in resident animals at place [p], kg/km², weighted by how readily each takes people ([Species.takesPeople]). */
internal fun GroupWorld.residentManEaters(p: Place): Float {
    var v = 0f
    eachResidentManEater(p) { _, f -> v += f }
    return v
}

/** Calls [each] with every resident kind that takes people at place [p] and its flesh there, kg/km², weighted by [Species.takesPeople]. */
private inline fun GroupWorld.eachResidentManEater(p: Place, each: (Species, Float) -> Unit) {
    for ((ki, k) in fauna.kinds.withIndex()) {
        val w = k.takesPeople; if (w <= 0f) continue
        each(k, w * (fauna.adults[ki][p] + fauna.young[ki][p]) * k.massKg)
    }
}

/**
 * The share of the loss to beasts of prey band [b] lets through its guard, against none: by night ([NIGHT_STALKED] of it) its
 * night fire, its dogs, its watch ([Band.watchHours]), the walls of its houses as far as [walled] (their shelter times how they
 * hold) and a palisade as far as [fenced]; by day, out at work, only its dogs. (Scouts keep beasts off only by where they find
 * them, which the band's choice of camp weighs: [Band.scoutShare].)
 */
internal fun stalkGuard(b: Band, walled: Float, fenced: Float, watchHours: Float = b.watchHours, nightGuard: Float = b.nightGuard): Float {
    val dogs = 1f - DOGS_GUARD * min(1f, b.dogs.dogShare)
    val night = (1f - FIRE_GUARD * nightGuard) * dogs * (1f - shareOfWill(watchHours / WATCH_HOURS, WATCH_GUARD)) *
        (1f - HOUSE_GUARD * walled) * (1f - PALISADE_GUARD * fenced)
    return NIGHT_STALKED * night + (1f - NIGHT_STALKED) * dogs
}

/** How far [b]'s houses keep beasts off it this week ([stalkGuard]'s walled): at home, their shelter times how their walls hold. */
internal fun Band.walledNow() = homeShelter() * homeWalls

/** How far a palisade keeps beasts off [b] this week ([stalkGuard]'s fenced): at home, as much of it as stands. */
internal fun Band.fencedNow() = if (atHome) palisade else 0f

/**
 * The yearly-scaled share of band [b] (group [g]) beasts of prey would take a week camped at place [p], where their flesh is
 * [pressure] kg/km² (see [stalkedBy]), behind house walls as far as [walled] and a palisade as far as [fenced] ([stalkGuard]).
 */
internal fun HexGroups.stalkRate(g: HexGroup, b: Band, p: Place, pressure: Float, walled: Float = b.walledNow(), fenced: Float = b.fencedNow()): Float {
    if (pressure <= 0f) return 0f
    val h = hw.placeHex[p]
    val cover = 0.5f + land.plants.cover(p)
    val guard = stalkGuard(b, walled, fenced)
    // (Gatherers spread further from camp leave more ground to watch: about as its square.)
    val spread = b.gatherRange * b.gatherRange
    dialUse?.let { u ->
        u.amount(DialSite.NIGHT_FIRE_GUARD, b.nightFire, guard, stalkGuard(b, walled, fenced, nightGuard = min(1f, b.fire) * b.nightWood))
        u.amount(DialSite.NIGHT_WATCH_GUARD, b.will[BandWay.NIGHT_WATCH], guard, stalkGuard(b, walled, fenced, watchHours = WATCH_HOURS))
        u.amount(DialSite.GATHER_SPREAD, b.gatherRange, spread, 1f)
    }
    val company = (STALKED_BAND / max(1, g.size).toFloat()).coerceIn(0.5f, 2f).pow(0.3f)
    val sign = b.lapse(Skill.TRACK).pow(0.5f)
    // (Beasts lie up by the paths people and game use: a camp on a busy way is the more dangerous.)
    return STALKED * pressure / (pressure + STALKED_HALF) * cover * guard * company * sign * spread * (1f + 0.5f * AMBUSH * wear.at(h)) * years
}

/** A band's [rate] of loss to beasts of prey (yearly-scaled share) and the beast most to blame, [by] (null when none). */
data class Stalking(val rate: Float, val by: Species?) {
    companion object { val NONE = Stalking(0f, null) }
}

/**
 * Of the beasts that take people ([Species.takesPeople]): the yearly share of people they take at most
 * where they are thick, from the unwary in thick cover with no fire, dogs or watch (an estimate, set against the record:
 * foragers lose few to beasts, the Hiwi none in their records though anacondas, caimans and jaguars attacked them, Hill,
 * Hurtado & Walker 2007; the Agta, a quarter of whose men pythons attacked, some 0.26 a thousand a year, Headland & Greene
 * 2011; at 0.02, ten times this, the bands lost 4-5 a thousand a year, many times either); density of beast flesh, kg/km² weighted, at which they take
 * half that; a band size the company factor is set for; how much a fire at night and dogs each keep off; the share of the
 * loss that falls at night, at camp, against out at work by day (an estimate: in Tanzania lions took people mostly after dark at
 * or by their houses, and over a quarter in the fields, most asleep alone in makeshift huts, Packer et al. 2005, Nature 436:
 * 927); how much of the night's loss whole houses with solid walls keep off (an estimate: [wallGuard]); how much a whole
 * palisade does (an estimate: thorn enclosures in Kenya still lose livestock, chain-link fencing cut that by about three
 * quarters, Conservation Evidence 2017); how much more often children are taken.
 */
internal const val STALKED = 0.002f

internal const val STALKED_HALF = 3f

internal const val STALKED_BAND = 25f

internal const val FIRE_GUARD = 0.5f

internal const val DOGS_GUARD = 0.5f

internal const val NIGHT_STALKED = 0.6f

internal const val HOUSE_GUARD = 0.8f

internal const val PALISADE_GUARD = 0.5f

internal const val YOUNG_STALKED = 2f

/**
 * Of the children a beast would take, the share their watchers save by putting themselves in its way, and are taken
 * instead, at full watch ([Band.childWatch] 1 or more; less as fewer watch): the deaths are moved from the young to the
 * grown, not made fewer (Olli 2026-11-05: caretakers may sacrifice themselves for the young; the size is a guess).
 */
internal const val CARETAKERS_SHIELD = 0.5f

/** The share of the children a beast would take that [b]'s watchers take on themselves instead ([CARETAKERS_SHIELD]). */
internal fun caretakersTake(b: Band) = CARETAKERS_SHIELD * b.childWatch.coerceIn(0f, 1f)
