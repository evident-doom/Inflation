package inflation.ecology

/**
 * Kinds of work people know how to do. Each is done by one or more [Skill]s, its variants (fire by the
 * hand drill or by striking flint on pyrite; cooking by roasting or by boiling with hot stones), each
 * with its own tools and materials; a band does a kind of work by the best variant it knows.
 */
enum class Domain {
    /** Fruit, nuts, roots and seed: knowing where and when, and how to make them edible. */
    GATHERING,
    TRAPPING,
    FISHING,
    /** Big game: tracking, stalking, the throw. */
    HUNTING,
    /** Laying food by: drying, pits, baskets, keeping it from damp and mice. */
    KEEPING,
    KNAPPING, WOODWORK, HIDEWORK, CORDAGE,
    /** Making fire, and keeping embers alive on the move. */
    FIRE,
}

/**
 * A skill: a way of doing one kind of work ([domain]). A band holds each at a level, 0..1, that
 * grows by doing ([practice] of the way to mastery each time) and fades unused; children learn the
 * band's skills, partners from other bands bring theirs. Untrained, a band does [floor] as well as a
 * master; [gain] is how well this variant does against the domain's first. [known]: whether bands
 * know it from the start (others are hit on now and then, and passed on, as tools are); [start] is
 * the level bands start with.
 */
enum class Skill(val domain: Domain, val floor: Float, val start: Float = 0f, val known: Boolean = true, val gain: Float = 1f, val practice: Float = PRACTICE) {
    GATHER(Domain.GATHERING, 0.15f),
    SMALL_GAME(Domain.TRAPPING, 0.1f),
    FISH(Domain.FISHING, 0.1f),
    HUNT(Domain.HUNTING, 0.05f),
    STORE(Domain.KEEPING, 0.3f),
    KNAPPING(Domain.KNAPPING, CRAFT_FLOOR, 0.5f),
    WOODWORK(Domain.WOODWORK, CRAFT_FLOOR, 0.5f),
    HIDEWORK(Domain.HIDEWORK, CRAFT_FLOOR, 0.5f),
    CORDAGE(Domain.CORDAGE, CRAFT_FLOOR, 0.5f),
    /** Fire by friction (the hand drill): novices may fail for days. */
    FIRE(Domain.FIRE, FIRE_FLOOR, 0.5f),
    ;

    companion object {
        private val byDomain = Domain.entries.map { d -> entries.filter { it.domain == d } }
        /** The variants of a kind of work. */
        fun of(d: Domain) = byDomain[d.ordinal]
        /** The ways of getting food, in the order the bands' ways networks read them. */
        val FORAGING = listOf(GATHER, SMALL_GAME, FISH, HUNT, STORE)
    }
}
