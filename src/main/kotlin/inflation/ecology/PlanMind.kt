package inflation.ecology

import inflation.drawUnit
import inflation.Stream

/**
 * How a band chooses ([Band.mind]): [NETS], today's way (the mover's place score or its network, and the ways' dials), or
 * [PLANS], the discrete planning kind (doc: Discrete planning: build spec): the sim offers it a menu of activities each week,
 * it holds some, and its camp and days follow from them. The two share every mechanic of the world; only the choosing
 * differs, and bands of the two never merge. So far a [PLANS] band reads no ways (the scaffold's, whatever the network) and
 * holds its week's days by [planDays]; it weighs places as [NETS] bands do, but moves camp only when its customs send it or
 * staying is an emergency ([plannedMove]), with no draw by a will to explore.
 */
enum class Mind(val label: String) { NETS("nets"), PLANS("plans") }

/**
 * Each founding band's mind: [inflation.Tuning.planners] of them, drawn by the band's id from the world's seed (its own
 * hashed draw, so the world's stream of chance is untouched), start as [Mind.PLANS]; the rest as [Mind.NETS]. A saved world
 * carried on with [inflation.Tuning.planners] above 0 draws every band's mind afresh the same way once it is loaded, so a
 * world settled with nets alone (every test world) takes it up as a fresh one does; a split's heirs then draw by their own
 * ids. (Before, a loaded world kept its minds once any band was a planning one, and a band the fresh world drew before
 * the load could carry its mind into it: one test world took up 1% where all was asked.)
 */
internal fun HexGroups.foundMinds() {
    if (tuning.planners <= 0f) return
    for (g in groups) g.band?.let { b ->
        b.mind = if (drawUnit(seedBase, Stream.PLANNERS, g.id.toLong(), 0L) < tuning.planners) Mind.PLANS else Mind.NETS
    }
}
