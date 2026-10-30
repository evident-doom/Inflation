package inflation.ecology

import inflation.HASH_A
import inflation.HASH_B
import inflation.mix64
import inflation.unitOf

/**
 * How a band chooses ([Band.mind]): [NETS], today's way (the mover's place score or its network, and the ways' dials), or
 * [PLANS], the discrete planning kind (doc: Discrete planning: build spec): the sim offers it a menu of activities each week,
 * it holds some, and its camp and days follow from them. The two share every mechanic of the world; only the choosing
 * differs, and bands of the two never merge. So far a [PLANS] band reads no ways (the scaffold's, whatever the network) and
 * holds its week's days by [planDays]; it moves as [NETS] bands do.
 */
enum class Mind(val label: String) { NETS("nets"), PLANS("plans") }

/**
 * Each founding band's mind: [inflation.Tuning.planners] of them, drawn by the band's id from the world's seed (its own
 * hashed draw, so the world's stream of chance is untouched), start as [Mind.PLANS]; the rest as [Mind.NETS].
 */
internal fun HexGroups.foundMinds() {
    if (tuning.planners <= 0f) return
    for (g in groups) g.band?.let { b ->
        // (The full finaliser: one round of it left founders' ids, which run in order, clumped, about 30% where 50% was asked.)
        if (unitOf(mix64(seedBase * HASH_A + g.id * HASH_B + MIND_SALT)) < tuning.planners) b.mind = Mind.PLANS
    }
}

/** Salts the founders' draw of their mind apart from the world's other hashed draws. */
private const val MIND_SALT = 0x504C414EL
