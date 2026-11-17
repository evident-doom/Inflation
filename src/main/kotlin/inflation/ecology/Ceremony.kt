package inflation.ecology

import inflation.world.Hex
import kotlin.math.max
import kotlin.math.min

/*
 * Ceremonial work (docs/design/trade.md, "What flows back", 3b): days given to what has no use but holiness and fame. A
 * settled band whose home ground is holy (its dead laid there, its rites held there: [HexLand.sanctity]) raises a monument
 * there, a shrine, a tower, an enclosure (Jericho's tower, Çatalhöyük's shrines, Göbekli Tepe's enclosures); a band at home
 * on ground not yet holy enough adorns the houses it lives in (plaster and paint). Both are of leisure only, as finery is
 * ([fineryLeft]), and of the days debtors lend it ([callInLabour]), for a monument asks more hands than one band has: the
 * days it would give and cannot are its building's shortfall, and its debtors are called to build and fed at its feast. A
 * monument belongs to no one: its holiness is the place's and its fame the builders'. Off with `t.ceremony=0`.
 */

/** How holy a band's home ground must be for it to raise a monument there ([HexLand.sanctity] summed over its places): a few years of its dead and rites. An estimate. */
internal const val MONUMENT_HOLY = 20f

/** The person-days a person a week a band would give its monument, more than its leisure gives: the rest is asked of its debtors. An estimate. */
internal const val MONUMENT_WISH = 1f

/** Stone a person-day of a monument's work sets, kg. An estimate. */
internal const val MONUMENT_STONE = 20f

/** Share of a monument's work that weathers away a year: stone set to last. An estimate. */
internal const val MONUMENT_WEAR = 0.02f

/** Share of a house's adornment that weathers away a year, plaster and paint renewed or lost. An estimate. */
internal const val ADORN_WEAR = 0.2f

/** Holiness a person-day of a monument's work adds to the place ([HexLand.sanctity]): a tenth of what a grown person's rite gives. An estimate. */
internal const val MONUMENT_SANCTITY = 0.1f

/**
 * Renown a person-day of ceremonial work wins its band ([Band.renown]). An estimate. At 0.05 it more than doubled bands'
 * renown, which steers whose ways are copied, and the share of bands with spears fell below its floor (A/B 2026-11-17).
 */
internal const val CEREMONY_RENOWN = 0.01f

/**
 * The stone group [g]'s band [b] may set in a monument: what it holds beyond its year's keep for its tools and its stock to
 * give on ([KEEP_YEARS], [inflation.Tuning.passOnYears], [YEAR_NEED]). Of all its stone, monuments took some 6,000 t in five
 * years on m7-1 and the share of bands with spears fell below its floor (A/B 2026-11-17).
 */
internal fun HexGroups.monumentStone(g: HexGroup, b: Band): Float =
    max(0f, b.goods[Material.STONE] - (KEEP_YEARS + tuning.passOnYears) * YEAR_NEED[Material.STONE] * g.adults)

/** How holy band [b]'s home hex [h] is: the sanctity of its places summed. */
internal fun HexGroups.holiness(h: Hex): Float { var s = 0f; for (pi in hw.placesOf(h)) s += land.sanctity[pi]; return s }

/**
 * Group [g]'s band [b], at home in hex [h], gives ceremonial work of its leisure ([fineryLeft], when it ate its fill) and of
 * the days lent it ([BandTime.lentLeft]), from its building's days ([Activity.HOUSES]): to a monument there if the ground is
 * holy enough ([MONUMENT_HOLY]) and it has the stone, else to adorning the houses it uses. What it would give a monument and
 * cannot ([MONUMENT_WISH]) is returned, for its building's shortfall ([Band.buildShort]) and so its debtors' labour.
 */
internal fun HexGroups.ceremonialWork(g: HexGroup, b: Band, h: Hex, people: Float, time: BandTime): Float {
    if (tuning.ceremony <= 0f || !b.atHome || b.houseHex != h || people <= 0f) return 0f
    val own = fineryLeft(time, people, g.fed)
    val lent = time.lentLeft(Activity.HOUSES)
    val holy = holiness(h) >= MONUMENT_HOLY
    val gd = b.goods
    if (holy) {
        val wish = MONUMENT_WISH * people * ticks
        // (Only of the stone beyond what it keeps for its tools and its stock to give on: [monumentStone].)
        val stone = monumentStone(g, b)
        val days = minOf(own + lent, time.leftFor(Activity.HOUSES), wish, stone / MONUMENT_STONE)
        if (days > 0f) {
            val s = bands.built.firstOrNull { it.kind == Built.MONUMENT && it.hex == h }
                ?: Structure(Built.MONUMENT, h, -1, g.id, 0f).also { it.built = 1f; it.done = true; bands.built += it; stats.meeting(Met.MONUMENTS_BEGUN) }
            gd[Material.STONE] -= days * MONUMENT_STONE
            s.size += days; s.built = 1f
            time.spend(Activity.HOUSES, days)
            land.sanctity[g.place.i] += days * MONUMENT_SANCTITY
            b.renown += days * CEREMONY_RENOWN
            stats.tally(ceremonyKey(CeremonyCount.MONUMENT), Math.round(10f * days))
        }
        // (What it would give and could not, as far as the stone it holds still allows: debtors called for days that could not be
        // built with only walked. Asked for whatever the stone, three quarters of the labour went unused and people fell 8%,
        // A/B 2026-11-17.)
        return max(0f, min(wish - max(0f, days), (stone - max(0f, days) * MONUMENT_STONE) / MONUMENT_STONE))
    }
    // (Not holy ground: it adorns the houses it lives in, of its leisure and what is lent.)
    val house = bands.built.firstOrNull { it.kind == Built.HOUSES && it.hex == h && it.user == g.id } ?: return 0f
    val days = min(own + lent, time.leftFor(Activity.HOUSES))
    if (days <= 0f) return 0f
    house.adorned += days
    time.spend(Activity.HOUSES, days)
    b.renown += days * CEREMONY_RENOWN
    stats.tally(ceremonyKey(CeremonyCount.ADORNED), Math.round(10f * days))
    return 0f
}

/** The census of ceremonial work, person-days: given to monuments, to adorning houses. */
enum class CeremonyCount(val label: String) { MONUMENT("monuments, days/10"), ADORNED("adorning houses, days/10") }

@OptIn(RawKey::class)
internal fun ceremonyKey(c: CeremonyCount) = TallyKey.raw("ceremonial work:${c.label}")
