package inflation.ecology
import java.util.EnumSet
import kotlin.math.exp

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
    /** Pecking and grinding hard stone: mortars, querns, pestles, polished axes, stone bowls. */
    GROUND_STONE,
    /** Working bone and antler: awls, eyed needles, points, harpoons, sickle hafts. */
    BONEWORK,
    /** Fixing points and blades in hafts with resin, sinew and cord: tools that last. */
    HAFTING,
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
    /** Reading tracks, dung and sign, and following them to the game. */
    TRACKING,
    /** Getting within reach unseen: moving downwind, in cover, at the right hour. */
    STALKING,
    /** Butchering a kill: taking all the meat, fat and marrow, and the hide whole. */
    BUTCHERY,
    /** Keeping animals: penning, feeding, gentling, culling the wild ones, helping at births. */
    HERDING,
    /** Caring for the sick and wounded: herbs, cleaning and binding wounds, setting bones. */
    HEALING,
    /** Passing know-how on: showing, correcting, letting the young try. */
    TEACHING,
    /** Keeping know-how alive between uses: songs, stories, names, marks. */
    REMEMBERING,
    /** Trying new ways: tinkering, borrowing an idea from one craft for another. */
    TINKERING,
    /** Helping at births and keeping infants alive: midwifery, care of the newborn. */
    CHILDCARE,
}

/**
 * A skill: a way of doing one kind of work ([domain]). A band holds each at a level, 0..1, that
 * grows by doing ([practice] of the way to mastery each time) and fades unused; children learn the
 * band's skills, partners from other bands bring theirs. Untrained, a band does [floor] as well as a
 * master; [gain] is how well this variant does against the domain's first. [known]: whether bands
 * know it from the start (others are hit on now and then, and passed on, as tools are); [start] is
 * the level bands start with. [invent] and [teach] scale how readily bands hit on it and pass it on
 * (ancient, simple ways spread in years; building techniques took centuries to millennia); [settled]
 * ways are hit on only by bands living in their houses.
 */
enum class Skill(val domain: Domain, val floor: Float, val start: Float = 0f, val known: Boolean = true, val gain: Float = 1f, val practice: Float = PRACTICE,
                 val invent: Float = 1f, val teach: Float = 1f, val settled: Boolean = false, ref: Float = -1f) {
    GATHER(Domain.GATHERING, 0.15f),
    SMALL_GAME(Domain.TRAPPING, 0.1f),
    FISH(Domain.FISHING, 0.1f),
    HUNT(Domain.HUNTING, 0.05f, practice = HUNTING_PRACTICE),
    /** Drying meat and fish in the sun and wind on racks: slow in wet weather. */
    STORE(Domain.KEEPING, 0.3f),
    /** Smoking meat and fish over a slow fire: works in any weather, and keeps longer. Needs fire. */
    SMOKING(Domain.KEEPING, 0.3f, known = false),
    /** Sealing storage pits and bins with clay and plaster: keeps mice and damp out of the stores at home. */
    SEALING(Domain.KEEPING, 0.3f, known = false, invent = 0.1f, teach = 0.1f, settled = true),
    /** Flakes struck from a core: the basic edge. */
    KNAPPING(Domain.KNAPPING, CRAFT_FLOOR, 0.5f),
    /** Long blades struck from prepared cores: several times the cutting edge from a kilo of flint. */
    BLADES(Domain.KNAPPING, CRAFT_FLOOR, 0.5f, gain = 1.1f, invent = 0.5f),
    /** Microliths snapped from bladelets and set in rows: barbs, arrowheads, sickle edges, easily mended. */
    MICROLITHS(Domain.KNAPPING, CRAFT_FLOOR, 0.5f, gain = 1.2f, invent = 0.3f),
    WOODWORK(Domain.WOODWORK, CRAFT_FLOOR, 0.5f),
    HIDEWORK(Domain.HIDEWORK, CRAFT_FLOOR, 0.5f),
    CORDAGE(Domain.CORDAGE, CRAFT_FLOOR, 0.5f),
    PECKING(Domain.GROUND_STONE, CRAFT_FLOOR, 0.3f),
    BONE_TOOLS(Domain.BONEWORK, CRAFT_FLOOR, 0.5f),
    /** Hafting with resin and sinew: hafted tools wear out the slower the better they are set. */
    GLUING(Domain.HAFTING, CRAFT_FLOOR, 0.5f),
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
    PIT_HOUSE(Domain.BUILDING, CRAFT_FLOOR, known = false, invent = 0.1f, teach = 0.1f, settled = true),
    /** Walls of mud and straw (pisé, then moulded bricks) where there is clay by water: no stone, half the timber, longer to build. */
    MUDBRICK(Domain.BUILDING, CRAFT_FLOOR, known = false, invent = 0.01f, teach = 0.05f, settled = true),
    TRACK(Domain.TRACKING, 0.2f, 0.8f, practice = HUNTING_PRACTICE),
    STALK(Domain.STALKING, 0.2f, 0.8f, practice = HUNTING_PRACTICE),
    BUTCHER(Domain.BUTCHERY, 0.5f, 0.8f),
    /** Tending kept animals: fewer run off or are lost on the move, more can be kept, and they grow tame faster under practised hands. */
    TENDING(Domain.HERDING, CRAFT_FLOOR, known = false, invent = 0.05f, teach = 0.1f, settled = true, ref = 0.5f),
    /** Apprenticeship: how fast the band's young and newcomers come to its skills (all practice goes faster or slower). */
    SHOWING(Domain.TEACHING, CRAFT_FLOOR, 0.5f),
    /** Songs, stories and names that keep know-how from fading. */
    LORE(Domain.REMEMBERING, CRAFT_FLOOR, 0.5f),
    /** A habit of trying new ways: bands that tinker hit on new tools and ways more often. */
    TINKER(Domain.TINKERING, CRAFT_FLOOR, 0.5f),
    /** Herbal remedies and wound care, learned over a lifetime of the sick. */
    HERBS(Domain.HEALING, CRAFT_FLOOR, 0.5f),
    /** Midwifery and care of infants. */
    MIDWIFERY(Domain.CHILDCARE, CRAFT_FLOOR, 0.5f),
    /**
     * Curing meat and fish with salt: works in any weather, and keeps far longer. Hit on only by a band
     * that has salt; not attested among Levantine foragers (salt working is first dated about 6000 BC).
     */
    SALTING(Domain.KEEPING, 0.3f, known = false),
    ;

    /**
     * Share of a band's adults who ordinarily do this work (everyone gathers and keeps the fire; a
     * few knap, fewer heal); and how many practised holders it takes to keep it alive at its best
     * (simple ways survive with one or two, intricate crafts need several to learn from and correct
     * each other: with few holders, what is learned falls short and the craft decays).
     */
    val share: Float get() = when (this) {
        GATHER, FIRE, ROASTING -> 1f
        SMALL_GAME, FISH, HUNT, STORE, SMOKING, SALTING, SEWING, TRACK, STALK, BUTCHER, STONE_BOILING, EARTH_OVEN, LEACHING -> 0.5f
        HERBS, MIDWIFERY, LORE, TINKER, TENDING -> 0.1f
        SHOWING -> 0.3f
        else -> 0.2f
    }
    /**
     * How hard it is to learn: practice closes the gap to mastery the slower the higher. Fishing is among the quick ones:
     * among the Meriam, children's returns at line and spear fishing show no significant rise with age beyond what comes
     * of growing, while shellfish collecting, easy to learn, rises with size and strength (Bliege Bird and Bird 2002,
     * "Constraints of knowing or constraints of growing?", Human Nature 13: 239-267). Hunting is slow besides
     * ([HUNTING_PRACTICE]).
     */
    val complexity: Float get() = when (this) {
        GATHER, FIRE, ROASTING, STORE, SMALL_GAME, FISH, MIDWIFERY -> 0.5f
        BLADES, MICROLITHS, MUDBRICK, HERBS, SEALING, PIT_HOUSE -> 3f
        else -> 1.5f
    }
    /**
     * Whether it is knowledge (which plant, which recipe, which remedy, which step comes next) rather
     * than a skill of the hands: knowledge fades fast unused, hand skills last for years (Arthur and
     * colleagues 1998); songs and stories keep knowledge.
     */
    val knowledge: Boolean get() = when (this) {
        HERBS, LORE, LEACHING, EARTH_OVEN, STONE_BOILING, SEALING, TENDING -> true
        else -> false
    }
    /**
     * Whether a band can lose this way altogether: not the ways everyone lives by (gathering, fire,
     * plain flakes, wood, hide and cord, simple huts, drying, the hunt, care of the sick and the
     * newborn, teaching). Fishing, trapping and the finer crafts can be lost, as the Tasmanians lost
     * fishing and bone tools.
     */
    val losable: Boolean get() = domain !in KEPT_DOMAINS && this !in KEPT
    /** A way that must be held first: microliths are snapped from bladelets; boiling with hot stones and leaching need tight baskets. */
    val needs: Skill? get() = when (this) {
        MICROLITHS -> BLADES
        STONE_BOILING, LEACHING -> CORDAGE
        SEALING, PIT_HOUSE, MUDBRICK -> STONE_FOOTING
        else -> null
    }
    /** The best level [n] holders can keep a way at: what is passed on falls a little short each time, and more learners make up for it. */
    fun cap(n: Float) = 1f - exp(-n / complexity)

    /** The level its effects are set for (a start band's, unless bands start without it). */
    val ref = if (ref >= 0f) ref else start

    companion object {
        private val KEPT_DOMAINS = EnumSet.of(Domain.GATHERING, Domain.FIRE, Domain.HEALING, Domain.CHILDCARE, Domain.TEACHING,
            Domain.REMEMBERING, Domain.TINKERING)
        private val KEPT = EnumSet.of(ROASTING, STONE_FOOTING, KNAPPING, WOODWORK, HIDEWORK, CORDAGE, STORE, HUNT)
        private val byDomain = Domain.entries.map { d -> entries.filter { it.domain == d } }
        /** The variants of a kind of work. */
        fun of(d: Domain) = byDomain[d.ordinal]
        /** The ways of getting food, in the order the bands' ways networks read them. */
        val FORAGING = listOf(GATHER, SMALL_GAME, FISH, HUNT, STORE)
        /** The ways of building houses ([HexGroups.buildHouse]). */
        val BUILDING_WAYS = listOf(STONE_FOOTING, PIT_HOUSE, MUDBRICK)
    }
}
