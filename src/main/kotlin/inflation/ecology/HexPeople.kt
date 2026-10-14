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
const val CARRY_DAYS = 5f
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
    var campPlace = -1
    /** Food it left in a cache at an old camp, kg, and where (-1 for none). */
    var cacheKg = 0f
    var cachePlace = -1
    /** Meat of big game it got this week, kg: hides, bone and sinew come with it. */
    var bigGameKg = 0f
    /** Its customs ([Tradition]), its words for things ([Language]), its descent (a share for each founding lineage), how inbred its children are, its grudges against each lineage's people. */
    val traditions = BooleanArray(Tradition.entries.size)
    val words = IntArray(Language.CONCEPTS)
    val lineage = FloatArray(LINEAGES)
    var inbred = 0.02f
    val grudge = FloatArray(LINEAGES)
    /** Of its adults, the elders: past bearing children, helping their daughters raise theirs. */
    var elders = 0
    /** Animals it keeps, by kind ([Species.index]): wolf pups taken from bold packs become dogs. */
    var kept = FloatArray(0)
    /** The country it knows, a bit for each hex: walked, seen from where it walked, or told of by bands it understands. */
    var known = LongArray(0)

    fun knows(h: Int) = h / 64 < known.size && (known[h / 64] ushr (h % 64)) and 1L == 1L
    fun learn(h: Int, size: Int) { if (known.size * 64 < size) known = known.copyOf((size + 63) / 64); known[h / 64] = known[h / 64] or (1L shl (h % 64)) }

    fun holds(t: Tradition) = traditions[t.ordinal]
    fun mainLineage(): Int { var best = 0; for (i in lineage.indices) if (lineage[i] > lineage[best]) best = i; return best }

    /** What a band splitting off takes along: everything it knew and believed, its descent and its grudges. */
    fun copyInto(o: Band) {
        craft.copyInto(o.craft); making.copyInto(o.making); kit.copyInto(o.kit); knows.copyInto(o.knows)
        traditions.copyInto(o.traditions); words.copyInto(o.words); lineage.copyInto(o.lineage); grudge.copyInto(o.grudge)
        o.inbred = inbred; o.campWeeks = campWeeks; o.campHex = campHex; o.campPlace = campPlace; o.known = known.copyOf(); o.kept = kept.copyOf()
    }

    /** How well the band does at [f], against a master: from [CRAFT_FLOOR] untrained to 1. */
    fun skill(f: Forage) = CRAFT_FLOOR + (1f - CRAFT_FLOOR) * craft[f.ordinal]
    fun practise(f: Forage) { craft[f.ordinal] += PRACTICE * (1f - craft[f.ordinal]) }
    fun has(t: Tool) = kit[t.ordinal]

    /** How its weapons, hunting skill and dogs do against [prey], against an ordinary armed, practised band without dogs (1). */
    fun huntEdge(prey: Species) =
        skill(Forage.HUNT) * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) + BOW_GAIN * has(Tool.BOW) * min(1f, prey.speedKmh / 70f)) * (1f + DOG_HUNT * dogShare)

    /** Its dogs against its hunters (one to three a full pack): they track, bay and hold game, and bark at night. */
    var dogShare = 0f

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
        /**
         * A band starting out in country it knows, founded at ([x], [y]) km: practised at what the
         * country offers, with a basic kit, and the descent, words and customs of its country.
         */
        fun start(nearWater: Boolean, x: Double, y: Double) = Band().apply {
            val l = lineageAt(x, y)
            lineage[l] = 1f
            wordsAt(x, y).copyInto(words)
            traditionsAt(l).copyInto(traditions)
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
    // It comes to know the country it walks, and what it sees round about.
    for (h in g.path) { b.learn(h, hex.size); for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0) b.learn(q, hex.size) } }
    val p = g.place
    val h = hw.placeHex[p]
    val area = land.areaKm2[p] * 1e6f
    if (h != b.campHex) {
        // Moving camp: food that cannot be carried is cached at the old camp (an older cache is given
        // up to the mice), and the grinding stones are left behind.
        if (b.campHex >= 0) {
            val carry = CARRY_DAYS * g.dailyNeedKg
            if (g.larder > carry && b.campPlace >= 0) {
                dropCache(b)
                b.cacheKg = g.larder - carry; b.cachePlace = b.campPlace; g.larder = carry
            }
            for (t in Tool.entries) if (t.heavy) b.kit[t.ordinal] = 0f
        }
        b.campHex = h; b.campWeeks = 0
    } else b.campWeeks++
    b.campPlace = p
    // A cache keeps, but not perfectly; back at it, the band takes up what it has room for.
    if (b.cachePlace >= 0) {
        val spoilt = CACHE_SPOIL * b.cacheKg
        b.cacheKg -= spoilt; small.litter[b.cachePlace] += spoilt / (land.areaKm2[b.cachePlace] * 1e6f)
        if (hw.placeHex[b.cachePlace] == h) {
            val take = min(b.cacheKg, max(0f, LARDER_DAYS * g.dailyNeedKg * b.storeShare() - g.larder))
            g.larder += take; b.cacheKg -= take
            if (take > 0f) meetings.merge("bands back at a cache", 1, Int::plus)
        }
        if (b.cacheKg < 1f) dropCache(b)
    }
    buildKite(g, b)
    if (TAMING) keepAnimals(g, b) else b.dogShare = 0f
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
    // Songs and stories keep what is not practised from fading as fast.
    val forget = if (b.holds(Tradition.ORAL_TRADITION)) FORGET * 0.4f else FORGET
    for (i in b.craft.indices) b.craft[i] *= 1f - forget
    for (i in b.making.indices) b.making[i] *= 1f - forget
}

/**
 * Packs of kinds that follow people (wolves, jackals) and live by a band's camps grow bolder over
 * generations, the shy ones keeping away; from a bold pack the band now and then takes a pup and
 * raises it. Its dogs breed when the band eats well, help it hunt and guard its camp.
 */
private fun HexGroups.keepAnimals(g: HexGroup, b: Band) {
    val years = days / inflation.world.Date.DAYS_PER_YEAR.toFloat()
    val h = hw.placeHex[g.place]
    for (o in followersByHex[h].orEmpty()) {
        if (!o.alive) continue
        val s = o.species
        val bold = boldness(o)
        o.bold = bold + BOLD_DRIFT * (1f - bold)
        val tameable = s.domestication.average().toFloat()
        if (o.young > 0 && rnd.nextFloat() < TAME * bold * tameable * years) {
            o.young--
            if (b.kept.size < Catalogue.all.size) b.kept = b.kept.copyOf(Catalogue.all.size)
            b.kept[s.index] += 1f
            meetings.merge("bands took a ${s.name} pup", 1, Int::plus)
        }
    }
    if (b.kept.isEmpty()) return
    var dogs = 0f
    for (i in b.kept.indices) {
        val k = b.kept[i]
        if (k <= 0f) continue
        val cap = DOGS_PER_ADULT * g.adults
        b.kept[i] = max(0f, k + k * (DOG_GROWTH * g.fed * (1f - k / max(1f, cap)) - DOG_DEATHS) * years)
        dogs += b.kept[i]
    }
    b.dogShare = min(1f, dogs / max(1f, g.adults / 3f))
}

/** A group's boldness towards people: its kind's share until it has lived by them. */
internal fun boldness(g: HexGroup) = if (g.bold < 0f) g.species.boldShare else g.bold

/** Gives up a band's cache: what is in it goes back to the ground. */
private fun HexGroups.dropCache(b: Band) {
    if (b.cachePlace >= 0) small.litter[b.cachePlace] += b.cacheKg / (land.areaKm2[b.cachePlace] * 1e6f)
    b.cacheKg = 0f; b.cachePlace = -1
}

/**
 * Desert kites: a large band, or one that holds feasts and can call on its neighbours, builds long
 * low walls in open country where herds pass, to drive them into a pen or pits. Once built, a kite
 * stands for generations, and any band that camps by it can use it.
 */
private fun HexGroups.buildKite(g: HexGroup, b: Band) {
    if (!KITES) return
    val p = g.place
    if (kites[p] >= 1f || g.adults < (if (b.holds(Tradition.FEASTS)) KITE_HANDS / 2 else KITE_HANDS)) return
    if (land.cover(p) > 0.3f) return
    val herds = KITE_QUARRY.sumOf { s -> headsHere[kindSlot[s.index]][p] }
    if (herds < KITE_HERDS) return
    kites[p] = min(1f, kites[p] + KITE_WORK)
    if (kites[p] >= 1f) meetings.merge("kites built", 1, Int::plus)
}

/** The skill a tool serves, for how likely a band is to hit on it. */
private fun useOf(t: Tool) = when (t) {
    Tool.SPEAR, Tool.BOW -> Forage.HUNT
    Tool.NET -> Forage.SMALL_GAME
    Tool.SICKLE, Tool.GRINDER, Tool.BASKET -> Forage.GATHER
    Tool.CLOTHES -> Forage.STORE
}

/** Two bands that meet show each other tools one of them does not know, the more readily the better they understand each other ([understood]). */
internal fun HexGroups.teach(a: Band, b: Band, understood: Float) {
    for (k in a.knows.indices) if (a.knows[k] != b.knows[k] && rnd.nextFloat() < TEACH * (0.5f + 0.5f * understood)) {
        a.knows[k] = true; b.knows[k] = true
        meetings.merge("band learned ${Tool.entries[k].name.lowercase()}", 1, Int::plus)
    }
}

/** How much a band is put off country it does not know (beside the pull of better country it senses); hexes round its first camp it knows from the start. */
const val UNKNOWN_COUNTRY = 0.2f
const val KNOWN_AT_START = 3

/** Taming: weekly drift towards boldness of a pack that lives by people's camps (centuries, not years); yearly chance a band takes a pup from such a pack, at full boldness and tameability. */
const val BOLD_DRIFT = 0.0003f
const val TAME = 0.5f
/** Dogs: yearly growth of a band's dogs when fed, their yearly deaths, most a band keeps per adult; how much a full pack of dogs adds to the hunt and keeps off night attacks. */
const val DOG_GROWTH = 0.4f
const val DOG_DEATHS = 0.1f
const val DOGS_PER_ADULT = 0.3f
const val DOG_HUNT = 0.2f
const val DOG_GUARD = 0.5f
/** Taming is off unless asked for (-Dhex.taming): whether, and how soon, wolves become dogs is still to be decided. */
val TAMING = System.getProperty("hex.taming") != null
/** How strongly people's camps draw bold animals of kinds that follow people (refuse, scraps, warmth). */
const val CAMP_LURE = 0.3f

/** Share of a cache lost each week to damp, mice and thieves. */
const val CACHE_SPOIL = 0.02f
/** Desert kites are later than the Natufian: off unless asked for (-Dhex.kites). */
val KITES = System.getProperty("hex.kites") != null
/** Adults it takes to build a kite (half for a band that feasts with its neighbours); share of one built in a week's work. */
const val KITE_HANDS = 30
const val KITE_WORK = 0.1f
/** Open-country herds kites are built for; heads of them in a place that make a kite worth building; share of a herd taken in a drive. */
val KITE_QUARRY by lazy { with(Catalogue) { listOf(GAZELLE, ONAGER, ORYX, HARTEBEEST, HORSE) } }
const val KITE_HERDS = 100
const val KITE_TAKE = 0.4f
/** People per km² around a camp (its hex and the six round it) at which crowding brings sickness; yearly deaths from it then. */
const val PEOPLE_FULL_KM2 = 0.5f
const val PEOPLE_SICK = 0.05f

/** Settled life with stores shortens the spacing of births. */
internal fun settledFertility(g: HexGroup): Float {
    val b = g.band ?: return 1f
    return 1f + SETTLED_FERTILITY * min(1f, b.campWeeks / 26f) * min(1f, g.larder / max(1e-3f, 30f * g.dailyNeedKg))
}

/** Extra yearly deaths among children of a camp kept long: refuse, flies and fouled water. */
internal fun campFouling(g: HexGroup): Float {
    val b = g.band ?: return 0f
    return CAMP_FOULING * fouled(b) * (if (b.holds(Tradition.HYGIENE)) 0.4f else 1f) * (if (b.holds(Tradition.MEDICINE)) 0.7f else 1f)
}

/** How fouled a band's camp is, 0..1: not at all for its first two months, fully after a year more. */
internal fun fouled(b: Band) = min(1f, max(0, b.campWeeks - 8) / 52f)

/** How much a band would rather leave its camp than stay: the camp fouled (less where it keeps it clean), against the graves of its dead. */
internal fun stayCost(b: Band): Float {
    var c = FOUL_AVOID * fouled(b) * (if (b.holds(Tradition.HYGIENE)) 0.4f else 1f)
    if (b.holds(Tradition.BURIAL) && b.campWeeks > 4) c -= BURIAL_STAY
    return c
}
