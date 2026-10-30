package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.Tuning
import inflation.binomial
import inflation.world.HexLand
import kotlin.random.Random
import inflation.wrapMonth
import inflation.monthsFrom
import inflation.Config

import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import inflation.world.Biome
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin

/** Raw materials a band gathers and carries, kg. */
enum class Material(
    /** Where it is dug or gathered, as a share of each place (for the materials bands trade, [TRADED]). */
    val deposit: ((HexLand) -> FloatArray)? = null,
) {
    /** Good knapping stone (flint, chert, obsidian); poorer stone is found almost anywhere. */
    STONE({ it.stone }), WOOD, HIDE, BONE, SINEW,
    /** Plant fibre for cord, nets and baskets: bark, reeds, grasses. */
    FIBRE,
    /** Tree resin for glue. */
    RESIN,
    /** Sea shells (dentalium, cowrie) gathered on the shore, for beads. */
    SHELL,
    /** Salt scraped from dry lake beds and salt pans (and a little from the shore): meat and fish salted keep, and cure in any weather (see [TRADED]). */
    SALT({ it.salt }),
    /** Ochre, the red and yellow earth: hides tanned with it last (with trade on). */
    OCHRE({ it.ochre }),
}

/**
 * A kind of tool, with what one person's share takes to make ([recipe], kg per person), the skill
 * that makes it, how fast it wears out (share a year), whether it is too heavy to take along when
 * the camp moves, and whether bands know it from the start (the others are hit on now and then and
 * passed on when bands meet); the work it is used in ([uses]: it wears when that work is done), the
 * skill it serves ([serves], for how likely a band is to hit on it), and whether it is [hafted] (set
 * with resin and sinew, so wearing the slower the better the band glues).
 */
enum class Tool(val recipe: Map<Material, Float>, val skill: Skill, val wearPerYear: Float, val makeDays: Float,
    val uses: List<Skill>, val serves: Skill, val hafted: Boolean = false, val heavy: Boolean = false, val known: Boolean = false) {
    /** Stone-tipped spears: the basic hunting weapon. */
    SPEAR(mapOf(Material.STONE to 0.3f, Material.WOOD to 2f, Material.SINEW to 0.05f), Skill.KNAPPING, 1f, 1f, listOf(Skill.HUNT), Skill.HUNT, hafted = true, known = true),
    /** Bow and arrows tipped with microliths set in resin: reach and silence against fast, shy game. */
    BOW(mapOf(Material.WOOD to 1f, Material.SINEW to 0.2f, Material.STONE to 0.2f, Material.RESIN to 0.1f), Skill.WOODWORK, 1.5f, 4f, listOf(Skill.HUNT), Skill.HUNT, hafted = true),
    /** Nets and snares: hares, birds and fish in quantity. */
    NET(mapOf(Material.FIBRE to 2f), Skill.CORDAGE, 1f, 3f, listOf(Skill.SMALL_GAME, Skill.FISH), Skill.SMALL_GAME, known = true),
    /** Sickles of flint bladelets set in bone or wood: wild cereals cut before the ears shatter. */
    SICKLE(mapOf(Material.STONE to 0.2f, Material.BONE to 0.3f, Material.RESIN to 0.05f), Skill.KNAPPING, 0.7f, 1f, listOf(Skill.GATHER), Skill.GATHER, hafted = true),
    /** Mortars, pestles and querns: nuts, acorns and seeds made into meal. Too heavy to move. */
    GRINDER(mapOf(Material.STONE to 15f), Skill.PECKING, 0.05f, 3f, listOf(Skill.GATHER), Skill.GATHER, heavy = true),
    /** Baskets and bags: carrying the harvest home and keeping it. */
    BASKET(mapOf(Material.FIBRE to 3f), Skill.CORDAGE, 0.7f, 2f, listOf(Skill.GATHER, Skill.STONE_BOILING, Skill.LEACHING), Skill.GATHER,
        known = true),
    /** Sewn, fitted clothes and footwear of hide and fur: winter becomes bearable. */
    CLOTHES(mapOf(Material.HIDE to 4f, Material.SINEW to 0.3f, Material.BONE to 0.05f), Skill.HIDEWORK, 0.5f, 4f, emptyList(), Skill.STORE, known = true),
    /**
     * Picks of antler or bone hafted in wood: wells are sunk the faster ([PICK_GAIN]). Antler picks dug the flint mines of
     * Neolithic Europe; the recipe and wear are estimates. Hit on only by a band that knows wells.
     */
    PICK(mapOf(Material.BONE to 1f, Material.WOOD to 0.5f, Material.SINEW to 0.05f), Skill.BONE_TOOLS, 0.5f, 1f, listOf(Skill.WELLS), Skill.WELLS, hafted = true),
}
