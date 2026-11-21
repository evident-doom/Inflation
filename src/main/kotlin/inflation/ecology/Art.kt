package inflation.ecology

import kotlin.math.max
import kotlin.math.min

// Art as goods of perceived worth ([inflation.Tuning.art]; doc: Art and goods of perceived value, step 2): what a piece is worth
// to the band that takes it is its days of skilled work, more the stranger its style is to that band ([perceivedWorth]). Things
// from far away carry worth because they are far (Helms 1988), and ornament styles mark who people are, read by strangers
// (Vanhaeren and d'Errico 2006; Kuhn and Stiner 2007). So a band with no rare stone, whose free days go into carving wood,
// painting hides and fine cordage ([OrnamentKind.soft]), has pieces worth most where its style is least known, and can pay its
// debts in them ([artRepaid]). All from memory, to check; the weights are estimates.

/**
 * How much more a wholly strange piece is worth to its taker than its days of work ([perceivedWorth]): 1, twice. An estimate:
 * no figure found; chosen so that strangeness can at most match the work in a piece.
 */
internal const val ART_STRANGE = 1f

/** Share a year of the ornaments of wood, hide and fibre that rot, wear out and are lost ([OrnamentKind.soft]), against [FINERY_WEAR] for stone, shell and bone. An estimate. */
internal const val SOFT_WEAR = 0.3f

/** The most of its ornaments a band gives at one meeting to settle what it owes ([artRepaid]). An estimate. */
internal const val ART_REPAY_SHARE = 0.5f

/**
 * How strange the style of [b]'s ornaments is to [to], 0..1: the share of style traits ([Band.style]) in which the pieces' style
 * ([Band.artMix], by worth) is not the taker's own. Pieces with no style yet (a band from a save made before art, or none made
 * since) count as [b]'s own style.
 */
internal fun artStrangeness(b: Band, to: Band): Float {
    var alike = 0f
    for (t in 0 until STYLE_TRAITS) {
        var all = 0f
        for (v in 0 until STYLE_VARIANTS) all += b.artMix[t * STYLE_VARIANTS + v]
        alike += if (all > 0f) b.artMix[t * STYLE_VARIANTS + to.style[t]] / all else if (b.style[t] == to.style[t]) 1f else 0f
    }
    return (1f - alike / STYLE_TRAITS).coerceIn(0f, 1f)
}

/** What [worth] person-days of [giver]'s ornaments are worth to [taker]: more, the stranger their style to it ([ART_STRANGE], [artStrangeness]). */
internal fun perceivedWorth(worth: Float, giver: Band, taker: Band): Float = worth * (1f + ART_STRANGE * artStrangeness(giver, taker))

/**
 * Blends into this band's [Band.artMix], held at [had] person-days of worth, pieces worth [added] in the style mix [from]
 * (a band's [Band.artMix], or a one-hot of a band's own style: [addOwnArt]); call before the worth is added.
 */
internal fun Band.blendArt(from: FloatArray, had: Float, added: Float) {
    if (added <= 0f) return
    // (A mix with no style yet is read as its band's own: [artStrangeness]; blended, it stands as this band's own style.)
    if (had <= 0f || artMix.all { it == 0f }) { if (had > 0f) ownArtInto(artMix) }
    val w = added / (max(0f, had) + added)
    for (t in 0 until STYLE_TRAITS) {
        var all = 0f
        for (v in 0 until STYLE_VARIANTS) all += from[t * STYLE_VARIANTS + v]
        for (v in 0 until STYLE_VARIANTS) {
            val i = t * STYLE_VARIANTS + v
            val src = if (all > 0f) from[i] / all else if (style[t] == v) 1f else 0f
            artMix[i] += w * (src - artMix[i])
        }
    }
}

/** Gives a band whose ornaments have no style yet (made before art, [Band.artMix] all 0) its own style for them. */
internal fun Band.ensureArt() { if (ornamentWorth > 0f && artMix.all { it == 0f }) ownArtInto(artMix) }

/** Writes this band's own style as a style mix into [into]. */
private fun Band.ownArtInto(into: FloatArray) {
    into.fill(0f)
    for (t in 0 until STYLE_TRAITS) into[t * STYLE_VARIANTS + style[t]] = 1f
}

/** Blends [added] person-days of its own work, in its own style, into this band's [Band.artMix]; call before the worth is added. */
internal fun Band.addOwnArt(added: Float) {
    if (added <= 0f) return
    val own = FloatArray(STYLE_TRAITS * STYLE_VARIANTS).also { ownArtInto(it) }
    blendArt(own, ornamentWorth, added)
}

/**
 * The kg of [m] a band of [people] keeps from its art ([OrnamentKind.soft]): a whole set of every tool it knows that is made of
 * it, a person, so that carving and painting never take what its kit and clothes need. A choice, not a measure.
 */
internal fun artKeepKg(b: Band, m: Material, people: Float): Float =
    people * Tool.entries.sumOf { t -> if (b.knows[t]) (t.recipe[m] ?: 0f).toDouble() else 0.0 }.toFloat()

/**
 * The ornaments [gb] gives the band [tb] it owes ([Band.owesTo]) to settle the debt, at most [ART_REPAY_SHARE] of what it
 * holds, [fresh] of it at a meeting soon after the last: as many as, at their worth to the taker ([perceivedWorth]), meet
 * [owedDays] of the taker's days, as fully as it repays ([Band.repayWill]). 0 without a debt or ornaments.
 */
internal fun artRepaid(gb: Band, tb: Band, owedDays: Float, fresh: Float): Float {
    if (owedDays <= 0f || gb.ornaments < 1f) return 0f
    val each = perceivedWorth(gb.ornamentWorthOf(1f), gb, tb)
    if (each <= 0f) return 0f
    return min(ART_REPAY_SHARE * gb.ornaments, owedDays / each * max(0f, gb.repayWill)) * fresh
}
