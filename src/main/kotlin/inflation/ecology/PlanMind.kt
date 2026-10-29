package inflation.ecology

import inflation.HASH_A
import inflation.mix64Once
import inflation.unitOf

/**
 * How a band chooses ([Band.mind]): [NETS], today's way (the mover's place score or its network, and the ways' dials), or
 * [PLANS], the discrete planning kind (doc: Discrete planning: build spec): the sim offers it a menu of activities each week,
 * it holds some, and its camp and days follow from them. The two share every mechanic of the world; only the choosing
 * differs, and bands of the two never merge. Stage 1: a [PLANS] band still chooses as [NETS] does; only the census tells
 * them apart.
 */
enum class Mind(val label: String) { NETS("nets"), PLANS("plans") }

/**
 * Each founding band's mind: [inflation.Tuning.planners] of them, drawn by the band's id from the world's seed (its own
 * hashed draw, so the world's stream of chance is untouched), start as [Mind.PLANS]; the rest as [Mind.NETS].
 */
internal fun HexGroups.foundMinds() {
    if (tuning.planners <= 0f) return
    for (g in groups) g.band?.let { b ->
        if (unitOf(mix64Once((seedBase + 0x504C414EL) * HASH_A + g.id)) < tuning.planners) b.mind = Mind.PLANS
    }
}
