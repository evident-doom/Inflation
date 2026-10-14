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
    /** Making food more nourishing with fire: roasting, boiling with hot stones, earth ovens. */
    COOKING,
    /** Making bitter and poisonous plants food: leaching acorns and bitter roots in running water, soaking, long roasting. */
    DETOXIFYING,
    /** Making clothes of hides: wrapped and tied, or cut and sewn to fit. */
    CLOTHING,
    /** Building houses: stone-footed huts, pit houses dug into the ground, mudbrick. */
    BUILDING,
    /** Caring for the sick and wounded: herbs, cleaning and binding wounds, setting bones. */
    HEALING,
    /** Helping at births and keeping infants alive: midwifery, care of the newborn. */
    CHILDCARE,
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
    /** Drying meat and fish in the sun and wind on racks: slow in wet weather. */
    STORE(Domain.KEEPING, 0.3f),
    /** Smoking meat and fish over a slow fire: works in any weather, and keeps longer. Needs fire. */
    SMOKING(Domain.KEEPING, 0.3f, known = false),
    /** Sealing storage pits and bins with clay and plaster: keeps mice and damp out of the stores at home. */
    SEALING(Domain.KEEPING, 0.3f, known = false),
    KNAPPING(Domain.KNAPPING, CRAFT_FLOOR, 0.5f),
    WOODWORK(Domain.WOODWORK, CRAFT_FLOOR, 0.5f),
    HIDEWORK(Domain.HIDEWORK, CRAFT_FLOOR, 0.5f),
    CORDAGE(Domain.CORDAGE, CRAFT_FLOOR, 0.5f),
    /** Fire by friction (the hand drill): novices may fail for days. */
    FIRE(Domain.FIRE, FIRE_FLOOR, 0.5f),
    /** Roasting on the embers: everyone with fire does it. */
    ROASTING(Domain.COOKING, 0.5f, 0.8f),
    /**
     * Boiling with hot stones dropped in a tight basket, a hide or a pit: seed into gruel (fuller
     * use of it), and grease rendered from bones. Needs baskets (or hides).
     */
    STONE_BOILING(Domain.COOKING, 0.3f, known = false),
    /** Earth ovens: roots and bulbs baked for a day under hot stones, their starches made digestible. */
    EARTH_OVEN(Domain.COOKING, 0.3f, known = false),
    /** Leaching acorns and bitter roots in baskets in running water: needs baskets and water by the camp. */
    LEACHING(Domain.DETOXIFYING, 0.2f, known = false),
    /** Sewing with awls and eyed needles: clothes cut to fit, far warmer than hides wrapped and tied. */
    SEWING(Domain.CLOTHING, 0.3f, 0.5f),
    /** Round huts on stone footings, roofed with timber and brush. */
    STONE_FOOTING(Domain.BUILDING, CRAFT_FLOOR, 0.5f),
    /** Pit houses dug half into the ground: warmer, and a third of the stone. */
    PIT_HOUSE(Domain.BUILDING, CRAFT_FLOOR, known = false),
    /** Walls of mud and straw (pisé, then moulded bricks) where there is clay by water: no stone, half the timber, longer to build. */
    MUDBRICK(Domain.BUILDING, CRAFT_FLOOR, known = false),
    /** Herbal remedies and wound care, learned over a lifetime of the sick. */
    HERBS(Domain.HEALING, CRAFT_FLOOR, 0.5f),
    /** Midwifery and care of infants. */
    MIDWIFERY(Domain.CHILDCARE, CRAFT_FLOOR, 0.5f),
    ;

    companion object {
        private val byDomain = Domain.entries.map { d -> entries.filter { it.domain == d } }
        /** The variants of a kind of work. */
        fun of(d: Domain) = byDomain[d.ordinal]
        /** The ways of getting food, in the order the bands' ways networks read them. */
        val FORAGING = listOf(GATHER, SMALL_GAME, FISH, HUNT, STORE)
    }
}
