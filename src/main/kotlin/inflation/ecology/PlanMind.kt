package inflation.ecology

import inflation.HASH_A
import inflation.HASH_B
import inflation.mix64
import inflation.unitOf

/**
 * How a band chooses ([Band.mind]): [NETS], today's way (the mover's place score or its network, and the ways' dials), or
 * [PLANS], the discrete planning kind (doc: Discrete planning: build spec): the sim offers it a menu of activities each week,
 * it holds some, and its camp and days follow from them. The two share every mechanic of the world; only the choosing
 * differs, and bands of the two never merge. A [PLANS] band reads no ways (the scaffold's, whatever the network), holds
 * its week's days by [planDays], and camps where it foresees the fewest deaths ([GroupPlanner.planCamp]), but for weeks its
 * customs, a raid or a trek for salt send it as they send [NETS] bands.
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
        // (The full finaliser: one round of it left founders' ids, which run in order, clumped, about 30% where 50% was asked.)
        b.mind = if (unitOf(mix64(seedBase * HASH_A + g.id * HASH_B + MIND_SALT)) < tuning.planners) Mind.PLANS else Mind.NETS
    }
}

/** Salts the founders' draw of their mind apart from the world's other hashed draws. */
private const val MIND_SALT = 0x504C414EL
