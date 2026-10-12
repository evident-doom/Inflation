package inflation.ecology

/** Raw materials a band gathers, carries and trades, kg. */
enum class Material {
    /** Good knapping stone: flint, chert, obsidian. */
    STONE,
    WOOD,
    HIDE,
    BONE,
    SINEW,
    /** Plant fibre for cord, nets and baskets: bark, flax, reeds, grasses. */
    FIBRE,
    /** Tree resin and bitumen for glue. */
    RESIN,
    /** Shell beads, ochre, fine obsidian: valued for beauty and meaning, given to win renown and friends. */
    ORNAMENT,
}

/** Making skills: the *doing* behind every tool, built by practice like hunting and gathering. */
enum class Making { KNAPPING, WOODWORK, HIDEWORK, CORDAGE }

/**
 * A kind of tool, with what one person's share takes to make ([recipe], kg per person), the
 * skill that makes it, how fast it wears out (share a year), and whether it is too heavy to take
 * along when the camp moves.
 */
enum class Tool(val recipe: Map<Material, Float>, val skill: Making, val wearPerYear: Float, val heavy: Boolean = false) {
    /** Stone-tipped spears: the basic hunting weapon. */
    SPEAR(mapOf(Material.STONE to 0.3f, Material.WOOD to 2f, Material.SINEW to 0.05f), Making.KNAPPING, 1f),
    /** Bow and arrows tipped with microliths set in resin: reach and silence against fast, shy game. */
    BOW(mapOf(Material.WOOD to 1f, Material.SINEW to 0.2f, Material.STONE to 0.2f, Material.RESIN to 0.1f), Making.WOODWORK, 1.5f),
    /** Nets and snares: hares, birds and fish in quantity. */
    NET(mapOf(Material.FIBRE to 2f), Making.CORDAGE, 1f),
    /** Sickles of flint bladelets set in bone or wood: wild cereals cut before the ears shatter. */
    SICKLE(mapOf(Material.STONE to 0.2f, Material.BONE to 0.3f, Material.RESIN to 0.05f), Making.KNAPPING, 0.7f),
    /** Mortars, pestles and querns: nuts, acorns and seeds made into meal and gruel. Too heavy to move. */
    GRINDER(mapOf(Material.STONE to 15f), Making.KNAPPING, 0.05f, heavy = true),
    /** Baskets and bags: carrying the harvest home and keeping it. */
    BASKET(mapOf(Material.FIBRE to 3f), Making.CORDAGE, 0.7f),
    /** Sewn, fitted clothes and footwear of hide and fur: winter becomes bearable. */
    CLOTHES(mapOf(Material.HIDE to 4f, Material.SINEW to 0.3f, Material.BONE to 0.05f), Making.HIDEWORK, 0.5f),
}

/**
 * A tradition: a practice or belief a band holds, passed on to both halves when it splits, copied
 * from neighbours who do well, and now and then taken up or dropped. Each has a concrete effect.
 */
enum class Tradition(val about: String) {
    BURIAL("the dead are buried near camp: the band is loath to leave its place"),
    SKY_LORE("sun, moon and stars are watched: the band keeps its seasonal round on time"),
    TABOO_YOUNG("mothers and young are not hunted: game is spared in its breeding years"),
    ORAL_TRADITION("songs, stories and genealogies: knowledge and skills fade more slowly"),
    FEASTS("dance, song and shared feasts: bands grow larger before they split and seek each other out"),
    MEDICINE("herbs, setting bones, care of the sick: fewer die of wounds and illness"),
    EXOGAMY("partners are taken from other bands: less inbreeding, skills travel"),
    HYGIENE("middens and water kept apart, camps cleaned: a kept camp fouls slowly"),
    SHARING("food is shared with neighbouring bands in bad times: insurance against hunger"),
}

/**
 * A deliberately small language: a fixed set of concepts (the species, the foods, water and
 * weather, places, the months, actions), each named by a band with a word of its own. What matters
 * to the simulation is how much two bands understand each other: the share of concepts they name
 * alike ([understanding]). News, know-how and customs pass between bands in proportion to it.
 */
object Language {
    /** Things there are words for: one per land species, foods, water, weather, places, months and actions. */
    const val CONCEPTS = 64
    /** Number of distinct word forms. */
    const val FORMS = 1 shl 20

    fun understanding(a: IntArray, b: IntArray): Float = a.indices.count { a[it] == b[it] }.toFloat() / a.size
}

/** Kinds of named places a band knows. */
enum class PlaceKind {
    WATER, STONE, CAMP,
    /** Ochre seams and shell beaches: where ornaments come from. */
    ORNAMENT,
    /** A desert kite: long low walls funnelling herds into an enclosure or pits, built by many hands. */
    KITE,
    /** Where bands gather: a confluence of rivers and routes, or a landmark seen from afar, with water. */
    GATHERING,
}

/** A place a band knows and has a name for: where, what, how good, and when last seen or heard of (step). */
class Place(val x: Float, val y: Float, val kind: PlaceKind, var quality: Float, var seen: Int)
