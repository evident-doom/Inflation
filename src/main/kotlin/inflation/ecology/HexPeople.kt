package inflation.ecology

import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.min

// What makes bands of people more than a pack: skills learned in practice, tools made from what the
// country gives, and camps they stay in.

/** Kinds of getting food, each a skill of its own that a band learns by doing and forgets unused. */
enum class Forage {
    /** Fruit, nuts, roots and seed: knowing where and when, and how to make them edible. */
    GATHER,
    SMALL_GAME, FISH,
    /** Big game: tracking, stalking, the throw. */
    HUNT,
    /** Laying food by: drying, pits, baskets, keeping it from damp and mice. */
    STORE,
}

/** Raw materials a band gathers and carries, kg. */
enum class Material {
    /** Good knapping stone (flint, chert, obsidian); poorer stone is found almost anywhere. */
    STONE, WOOD, HIDE, BONE, SINEW,
    /** Plant fibre for cord, nets and baskets: bark, reeds, grasses. */
    FIBRE,
    /** Tree resin for glue. */
    RESIN,
}

/** Making skills: the doing behind every tool, built by practice like getting food. */
enum class Making { KNAPPING, WOODWORK, HIDEWORK, CORDAGE }

/**
 * A kind of tool, with what one person's share takes to make ([recipe], kg per person), the skill
 * that makes it, how fast it wears out (share a year), whether it is too heavy to take along when
 * the camp moves, and whether bands know it from the start (the others are hit on now and then and
 * passed on when bands meet).
 */
enum class Tool(val recipe: Map<Material, Float>, val skill: Making, val wearPerYear: Float, val heavy: Boolean = false, val known: Boolean = false) {
    /** Stone-tipped spears: the basic hunting weapon. */
    SPEAR(mapOf(Material.STONE to 0.3f, Material.WOOD to 2f, Material.SINEW to 0.05f), Making.KNAPPING, 1f, known = true),
    /** Bow and arrows tipped with microliths set in resin: reach and silence against fast, shy game. */
    BOW(mapOf(Material.WOOD to 1f, Material.SINEW to 0.2f, Material.STONE to 0.2f, Material.RESIN to 0.1f), Making.WOODWORK, 1.5f),
    /** Nets and snares: hares, birds and fish in quantity. */
    NET(mapOf(Material.FIBRE to 2f), Making.CORDAGE, 1f, known = true),
    /** Sickles of flint bladelets set in bone or wood: wild cereals cut before the ears shatter. */
    SICKLE(mapOf(Material.STONE to 0.2f, Material.BONE to 0.3f, Material.RESIN to 0.05f), Making.KNAPPING, 0.7f),
    /** Mortars, pestles and querns: nuts, acorns and seeds made into meal. Too heavy to move. */
    GRINDER(mapOf(Material.STONE to 15f), Making.KNAPPING, 0.05f, heavy = true),
    /** Baskets and bags: carrying the harvest home and keeping it. */
    BASKET(mapOf(Material.FIBRE to 3f), Making.CORDAGE, 0.7f, known = true),
    /** Sewn, fitted clothes and footwear of hide and fur: winter becomes bearable. */
    CLOTHES(mapOf(Material.HIDE to 4f, Material.SINEW to 0.3f, Material.BONE to 0.05f), Making.HIDEWORK, 0.5f, known = true),
}

/** Share of the success an untrained band still has at a hunt or a harvest. */
const val CRAFT_FLOOR = 0.3f
/** How far one week's use closes the gap to mastery; share of a skill that fades each week (about a fifth a year). */
const val PRACTICE = 0.02f
const val FORGET = 0.004f
/** Most of a band's kit that can be made in a week. */
const val MAKE_WEEKLY = 0.1f
/** Raw materials a band carries, kg per adult of each kind. */
const val GOODS_PER_PERSON = 20f
/** Hunting success of a band without spears, against one with them; what bows add against fast quarry. */
const val WEAPONLESS = 0.3f
const val BOW_GAIN = 0.5f
/** Extra plant food with sickles (seed), grinding stones (seed and roots) and baskets (all of it, and stores). */
const val SICKLE_GAIN = 0.3f
const val GRINDER_GAIN = 0.4f
const val BASKET_GAIN = 0.3f
/** Extra small game and fish with nets and snares. */
const val NET_GAIN = 1f
/** Most of each kind of plant food in a place a band can gather in a week, at full skill and without tools. */
const val GATHER_SHARE = 0.5f
/** Degrees of cold that full clothes take off, against the half-clothed bands start with. */
const val CLOTHES_C = 10f
/** Yearly chance a band hits on a tool it does not know, where it has use for it; chance a band that meets one that knows a tool learns it. */
const val INVENT = 0.002f
const val TEACH = 0.2f
/** Days of food a band can carry when it moves camp; the rest is left. */
const val CARRY_DAYS = 20f
/** Most that settled life with stores raises births (birth spacing about 4 years to under 3). */
const val SETTLED_FERTILITY = 0.4f
/** Extra yearly deaths among children in a camp kept for a year or more. */
const val CAMP_FOULING = 0.04f

/** What a band is: its skills, making skills, tools (0..1 of a full kit each), the tools it knows, its goods, and how long it has kept its camp. */
class Band {
    val craft = FloatArray(Forage.entries.size)
    val making = FloatArray(Making.entries.size) { 0.5f }
    val kit = FloatArray(Tool.entries.size)
    val knows = BooleanArray(Tool.entries.size) { Tool.entries[it].known }
    val goods = FloatArray(Material.entries.size)
    var campWeeks = 0
    var campHex = -1
    /** Meat of big game it got this week, kg: hides, bone and sinew come with it. */
    var bigGameKg = 0f

    fun copyInto(o: Band) {
        craft.copyInto(o.craft); making.copyInto(o.making); kit.copyInto(o.kit); knows.copyInto(o.knows)
        o.campWeeks = campWeeks; o.campHex = campHex
    }

    /** How well the band does at [f], against a master: from [CRAFT_FLOOR] untrained to 1. */
    fun skill(f: Forage) = CRAFT_FLOOR + (1f - CRAFT_FLOOR) * craft[f.ordinal]
    fun practise(f: Forage) { craft[f.ordinal] += PRACTICE * (1f - craft[f.ordinal]) }
    fun has(t: Tool) = kit[t.ordinal]

    /** How its weapons and hunting skill do against [prey], against an ordinary armed, practised band (1). */
    fun huntEdge(prey: Species) =
        skill(Forage.HUNT) * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) + BOW_GAIN * has(Tool.BOW) * min(1f, prey.speedKmh / 70f))

    /** Its people's fighting weight per kg: with spears and bows lions and hyenas give way; without, they are no match. */
    fun ferocity(s: Species) = s.ferocity * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) + 0.5f * has(Tool.BOW))

    /** Gathering: the most of a plant food's stock in a place it can take in a week; [seed] for grass seed. */
    fun gatherShare(seed: Boolean): Float {
        val kit = 1f + BASKET_GAIN * has(Tool.BASKET) + if (seed) SICKLE_GAIN * has(Tool.SICKLE) + GRINDER_GAIN * has(Tool.GRINDER) else GRINDER_GAIN * 0.5f * has(Tool.GRINDER)
        return min(0.9f, GATHER_SHARE * skill(Forage.GATHER) * kit)
    }

    /** Days of food its larder can hold, as a share of [LARDER_DAYS]: storing skill and baskets. */
    fun storeShare() = skill(Forage.STORE) * (1f + BASKET_GAIN * has(Tool.BASKET)) / (1f + BASKET_GAIN)

    /** Small game and fish: snare lines and nets, in practised hands. */
    fun snares() = (1f + NET_GAIN * has(Tool.NET)) * skill(Forage.SMALL_GAME)
    fun fishing() = (1f + NET_GAIN * has(Tool.NET)) / (1f + NET_GAIN) * skill(Forage.FISH)

    /** Temperature as the band feels it at [t] °C: clothes take the edge off the cold. */
    fun feels(t: Float) = if (t < 15f) min(15f, t + CLOTHES_C * (has(Tool.CLOTHES) - 0.5f)) else t

    companion object {
        /** A band starting out in country it knows: practised at what the country offers, with a basic kit. */
        fun start(nearWater: Boolean) = Band().apply {
            craft[Forage.GATHER.ordinal] = 0.6f; craft[Forage.SMALL_GAME.ordinal] = 0.6f; craft[Forage.HUNT.ordinal] = 0.6f
            craft[Forage.FISH.ordinal] = if (nearWater) 0.5f else 0.1f; craft[Forage.STORE.ordinal] = 0.4f
            kit[Tool.SPEAR.ordinal] = 1f; kit[Tool.NET.ordinal] = 0.5f; kit[Tool.BASKET.ordinal] = 0.5f; kit[Tool.CLOTHES.ordinal] = 0.5f
        }
    }
}

/**
 * A band's week at camp: it keeps the camp or moves on (leaving what it cannot carry), gathers
 * materials from the country, makes and mends its tools, now and then hits on a new one, and its
 * skills fade a little where unused.
 */
internal fun HexGroups.bandWeek(g: HexGroup, date: Date) {
    val b = g.band ?: return
    val p = g.place
    val h = hw.placeHex[p]
    val area = land.areaKm2[p] * 1e6f
    if (h != b.campHex) {
        // Moving camp: food that cannot be carried is left to rot, the grinding stones are left behind.
        if (b.campHex >= 0) {
            val carry = CARRY_DAYS * g.dailyNeedKg
            if (g.larder > carry) { small.litter[p] += (g.larder - carry) / area; g.larder = carry }
            for (t in Tool.entries) if (t.heavy) b.kit[t.ordinal] = 0f
        }
        b.campHex = h; b.campWeeks = 0
    } else b.campWeeks++
    val people = g.adults.toFloat()
    val gd = b.goods
    // What the country gives in a week, kg per adult: good stone where flint or obsidian crops out
    // along the way (poorer stone anywhere), wood and resin where there are trees, fibre from grass,
    // scrub and reeds; hides, bone and sinew from the big game it killed.
    val stone = g.path.maxOfOrNull { q -> hw.placeOf[hw.placeBand[p]][q].takeIf { it >= 0 }?.let { land.stone[it] } ?: 0f } ?: land.stone[p]
    gd[Material.STONE.ordinal] += people * (0.2f + 0.8f * min(1f, 5f * max(stone, land.stone[p])))
    val woody = land.tree[p] + land.shrub[p]
    val wood = 2f * people * min(1f, woody / 3f)
    gd[Material.WOOD.ordinal] += wood
    gd[Material.RESIN.ordinal] += 0.05f * people * min(1f, land.tree[p] / 3f)
    val fibre = people * min(1f, (land.herb[p] + land.shrub[p]) / 0.5f)
    gd[Material.FIBRE.ordinal] += fibre
    land.consume(p, LifeForm.TREE, wood * land.tree[p] / max(1e-6f, woody) / area)
    land.consume(p, LifeForm.HERB, fibre / area)
    gd[Material.HIDE.ordinal] += 0.08f * b.bigGameKg
    gd[Material.BONE.ordinal] += 0.1f * b.bigGameKg
    gd[Material.SINEW.ordinal] += 0.01f * b.bigGameKg
    b.bigGameKg = 0f
    val years = days / Date.DAYS_PER_YEAR.toFloat()
    for (t in Tool.entries) {
        val k = t.ordinal
        b.kit[k] = max(0f, b.kit[k] - t.wearPerYear * years)
        if (!b.knows[k]) {
            // A band that has a use for a tool now and then hits on it: bows where game is shy and
            // fast, sickles and grinding stones where it reaps seed, and so on.
            if (rnd.nextFloat() < INVENT * years * (0.5f + b.craft[useOf(t).ordinal])) { b.knows[k] = true; meetings.merge("band hit on ${t.name.lowercase()}", 1, Int::plus) }
            continue
        }
        if (b.kit[k] >= 1f || people <= 0f) continue
        // Make as much of the shortfall this week as the scarcest material and the skill allow.
        val skill = b.making[t.skill.ordinal]
        var can = min(1f - b.kit[k], MAKE_WEEKLY * (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * skill))
        for ((m, perPerson) in t.recipe) can = min(can, gd[m.ordinal] / max(1e-3f, perPerson * people))
        if (can <= 0f) continue
        for ((m, perPerson) in t.recipe) gd[m.ordinal] -= can * perPerson * people
        b.kit[k] += can
        b.making[t.skill.ordinal] += PRACTICE * (1f - skill)
    }
    // A band carries only so much: the rest is left behind (and goes back to the ground).
    for (m in gd.indices) {
        val over = gd[m] - GOODS_PER_PERSON * people
        if (over > 0f) { gd[m] -= over; if (m == Material.WOOD.ordinal || m == Material.FIBRE.ordinal || m == Material.HIDE.ordinal) small.litter[p] += over / area }
    }
    for (i in b.craft.indices) b.craft[i] *= 1f - FORGET
    for (i in b.making.indices) b.making[i] *= 1f - FORGET
}

/** The skill a tool serves, for how likely a band is to hit on it. */
private fun useOf(t: Tool) = when (t) {
    Tool.SPEAR, Tool.BOW -> Forage.HUNT
    Tool.NET -> Forage.SMALL_GAME
    Tool.SICKLE, Tool.GRINDER, Tool.BASKET -> Forage.GATHER
    Tool.CLOTHES -> Forage.STORE
}

/** Two bands that meet show each other tools one of them does not know. */
internal fun HexGroups.teach(a: Band, b: Band) {
    for (k in a.knows.indices) if (a.knows[k] != b.knows[k] && rnd.nextFloat() < TEACH) {
        a.knows[k] = true; b.knows[k] = true
        meetings.merge("band learned ${Tool.entries[k].name.lowercase()}", 1, Int::plus)
    }
}

/** Settled life with stores shortens the spacing of births. */
internal fun settledFertility(g: HexGroup): Float {
    val b = g.band ?: return 1f
    return 1f + SETTLED_FERTILITY * min(1f, b.campWeeks / 26f) * min(1f, g.larder / max(1e-3f, 30f * g.dailyNeedKg))
}

/** Extra yearly deaths among children of a camp kept long: refuse, flies and fouled water. */
internal fun campFouling(g: HexGroup): Float {
    val b = g.band ?: return 0f
    return CAMP_FOULING * min(1f, max(0, b.campWeeks - 8) / 52f)
}
