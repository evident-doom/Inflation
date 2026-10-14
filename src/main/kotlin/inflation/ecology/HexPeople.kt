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
enum class Tool(val recipe: Map<Material, Float>, val skill: Making, val wearPerYear: Float, val makeDays: Float, val heavy: Boolean = false, val known: Boolean = false) {
    /** Stone-tipped spears: the basic hunting weapon. */
    SPEAR(mapOf(Material.STONE to 0.3f, Material.WOOD to 2f, Material.SINEW to 0.05f), Making.KNAPPING, 1f, 1f, known = true),
    /** Bow and arrows tipped with microliths set in resin: reach and silence against fast, shy game. */
    BOW(mapOf(Material.WOOD to 1f, Material.SINEW to 0.2f, Material.STONE to 0.2f, Material.RESIN to 0.1f), Making.WOODWORK, 1.5f, 4f),
    /** Nets and snares: hares, birds and fish in quantity. */
    NET(mapOf(Material.FIBRE to 2f), Making.CORDAGE, 1f, 3f, known = true),
    /** Sickles of flint bladelets set in bone or wood: wild cereals cut before the ears shatter. */
    SICKLE(mapOf(Material.STONE to 0.2f, Material.BONE to 0.3f, Material.RESIN to 0.05f), Making.KNAPPING, 0.7f, 1f),
    /** Mortars, pestles and querns: nuts, acorns and seeds made into meal. Too heavy to move. */
    GRINDER(mapOf(Material.STONE to 15f), Making.KNAPPING, 0.05f, 3f, heavy = true),
    /** Baskets and bags: carrying the harvest home and keeping it. */
    BASKET(mapOf(Material.FIBRE to 3f), Making.CORDAGE, 0.7f, 2f, known = true),
    /** Sewn, fitted clothes and footwear of hide and fur: winter becomes bearable. */
    CLOTHES(mapOf(Material.HIDE to 4f, Material.SINEW to 0.3f, Material.BONE to 0.05f), Making.HIDEWORK, 0.5f, 4f, known = true),
}

/** Share of the success an untrained band still has at a hunt or a harvest. */
const val CRAFT_FLOOR = 0.3f
/** How far one week's use closes the gap to mastery; share of a skill that fades each week (about a fifth a year). */
const val PRACTICE = 0.02f
const val FORGET = 0.004f
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
/** What an adult carries when the camp moves, besides children and gear, kg of food. */
const val CARRY_KG = 15f
/** Most that settled life with stores raises births (birth spacing about 4 years to under 3). */
const val SETTLED_FERTILITY = 0.4f
/** Extra yearly deaths among children in a camp kept for a year or more. */
const val CAMP_FOULING = 0.02f
/** People's deaths of thirst against an animal's at the same days without water. */
const val HUMAN_THIRST = 0.3f
/** People's births against the catalogue's rate: long nursing spaces births three and a half to four years apart. */
const val HUMAN_BIRTHS = 0.8f

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
    val cache = FloatArray(Store.entries.size)
    val cacheKg get() = cache.sum()
    var cachePlace = -1
    /** Its choices: days of food it aims to keep in store (the scaffold's; a learned policy sets its own). */
    var storeAim = STORE_AIM_DAYS
    /**
     * Its appointment: the hex where it means to be in a month of the year, kept by a moon count
     * (with sky lore, to the month; without, a month either way), -1 for none; taken up from its
     * graves and meeting places, or from bands it meets.
     */
    var appointHex = -1
    var appointMonth = -1
    /**
     * Its houses: round, half-sunken huts of stone and brush at hex [houseHex], built 0..1 (enough
     * for all its people at 1), with storage pits; they weather away while it is elsewhere. At home
     * ([atHome]) they keep it warm, its stores keep better (though mice get into them) and it is
     * harder to raid.
     */
    var houseHex = -1
    var house = 0f
    /** Where it knows other bands have their houses (band id to hex), and their descent (main lineage). */
    val knownHomes = HashMap<Int, Int>()
    val knownLineage = HashMap<Int, Int>()
    /** A raid it is set on: the hex of the band's home it means to fall on, the band, and weeks left before it gives up. */
    var raidHex = -1
    var raidOn = -1
    var raidWeeks = 0
    /** A palisade of posts and brush round its houses, 0..1; weeks since it was last raided. */
    var palisade = 0f
    var sinceRaided = 1000
    /** A hex it shuns after a death there, and for how many more weeks ([Tradition.LEAVE_DEAD]). */
    var unluckyHex = -1
    var unluckyWeeks = 0
    /** Brush shelters at its present camp, 0..1 (left behind when it moves on). */
    var shelter = 0f
    /** Its choices: how ready it is to build (1 the scaffold's: after [HOUSE_AFTER_WEEKS] weeks at a camp that feeds it well). */
    var houseWill = 1f
    var atHome = false
    /** Its choices: the share of its adults out scouting rather than foraging. */
    var scoutShare = SCOUT_SHARE
    /** Its choices: how it ranks making each tool ([Tool]) with its spare time (the scaffold: the most worn first). */
    val makeFirst = FloatArray(Tool.entries.size) { 1f }
    /**
     * How it stands with each band it has met (by the band's id), -1..1: raised by meeting in peace,
     * gifts, feasts and marriages, lowered by raids; fading unless renewed.
     */
    val ties = HashMap<Int, Float>()
    fun tie(other: Int) = ties[other] ?: 0f
    fun befriend(other: Int, by: Float) { ties[other] = (tie(other) + by * (if (by > 0f) 1f - tie(other) else 1f + tie(other))).coerceIn(-1f, 1f) }
    /** Person-days its people had to spare this week, after getting and keeping food. */
    var spareDays = 0f
    /** Its choices: how much of its effort goes to each kind of food ([FOOD_TYPES]: big game, small game, carrion, bones, fruit, roots, grass seed, small finds, fish), 1 the scaffold's. */
    val effort = FloatArray(FOOD_TYPES) { 1f }
    /** Hides, bone and sinew from the game it killed, what each kind yields (see [Species.products]). */
    fun skin(s: Species, n: Int) {
        goods[Material.HIDE.ordinal] += n * s.products.hide; goods[Material.BONE.ordinal] += n * s.products.bone; goods[Material.SINEW.ordinal] += n * s.products.sinew
    }
    /** Its customs ([Tradition]), its words for things ([Language]), its descent (a share for each founding lineage), how inbred its children are, its grudges against each lineage's people. */
    val traditions = BooleanArray(Tradition.entries.size)
    val words = IntArray(Language.CONCEPTS)
    val lineage = FloatArray(LINEAGES)
    var inbred = 0.02f
    val grudge = FloatArray(LINEAGES)
    /** Of its adults, the elders: past bearing children, helping their daughters raise theirs. */
    var elders = 0
    /**
     * Of its adults not yet elders, how many are in the second and third of the three equal spans of
     * adult life (the rest are in the first): adults age through them in turn, so becoming an elder
     * comes at a fairly steady age, not at random.
     */
    val ageStage = IntArray(2)
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
        o.ties.clear(); o.ties.putAll(ties); o.appointHex = appointHex; o.appointMonth = appointMonth
        o.houseHex = houseHex; o.house = house; o.palisade = palisade
        o.knownHomes.putAll(knownHomes); o.knownLineage.putAll(knownLineage)
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


    /** Small game and fish: snare lines and nets, in practised hands. */
    fun snares() = (1f + NET_GAIN * has(Tool.NET)) * skill(Forage.SMALL_GAME)
    fun fishing() = (1f + NET_GAIN * has(Tool.NET)) / (1f + NET_GAIN) * skill(Forage.FISH)

    /** Temperature as the band feels it at [t] °C: clothes take the edge off the cold. */
    fun feels(t: Float) = if (t < 15f) min(15f, t + CLOTHES_C * (has(Tool.CLOTHES) - 0.5f) + max(SHELTER_C * shelter, if (atHome) HOUSE_C * house else 0f)) else t

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
    // From the high points on its way it sees as far as the line of sight and the weather allow;
    // its scouts range further still.
    var lookKm = 0f
    for (h in g.path) { val q = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue; lookKm = max(lookKm, land.bestViewKm[q] * sight(q) / (1f - SIGHT_COVER * land.cover(q)).coerceAtLeast(0.1f)) }
    val scouts = b.scoutShare * g.adults
    val rings = min(SIGHT_RINGS, max(1, (lookKm / hex.hexKm).toInt())) + if (scouts >= 2f) SCOUT_RINGS else if (scouts >= 1f) SCOUT_RINGS / 2 else 0
    for (h in g.path) {
        var ring = listOf(h); val seenHex = hashSetOf(h); b.learn(h, hex.size)
        for (r in 1..rings) {
            val next = ArrayList<Int>()
            for (x in ring) for (d in 0 until 6) { val q = hex.neighbour(x, d); if (q >= 0 && seenHex.add(q)) { next += q; b.learn(q, hex.size) } }
            ring = next
        }
    }
    val p = g.place
    val h = hw.placeHex[p]
    val area = land.areaKm2[p] * 1e6f
    if (h != b.campHex) {
        // Moving camp: food that cannot be carried is cached at the old camp (an older cache is given
        // up to the mice), and the grinding stones are left behind.
        if (b.campHex >= 0) {
            // What it can carry: its adults' loads, more with baskets and bags.
            val carry = CARRY_KG * max(1, g.adults) * (1f + BASKET_GAIN * b.has(Tool.BASKET))
            val total = g.larder
            if (total > carry && b.campPlace >= 0) {
                dropCache(b)
                val left = 1f - carry / total
                for (i in g.stores.indices) { b.cache[i] = g.stores[i] * left; g.stores[i] -= b.cache[i] }
                b.cachePlace = b.campPlace
            }
            for (t in Tool.entries) if (t.heavy) b.kit[t.ordinal] = 0f
        }
        b.campHex = h; b.campWeeks = 0
    } else b.campWeeks++
    b.campPlace = p
    // A cache keeps, but not perfectly; back at it, the band takes up what it has room for.
    if (b.cachePlace >= 0) {
        // (A cache spoils as stores do, and mice and badgers get into it too; boar, bears and hyenas dig
        // it up where they are about.)
        run {
            val cp = b.cachePlace
            val diggers = small.rodents[cp] / CACHE_RODENTS_HALF + small.foxes[cp] / CACHE_FOXES_HALF +
                fauna.kinds.indices.filter { fauna.kinds[it].name == "wild boar" || fauna.kinds[it].name == "brown bear" }.sumOf { (fauna.adults[it][cp] / 0.5f).toDouble() }.toFloat()
            val dug = min(0.5f, CACHE_DUG * min(1f, diggers) * years)
            if (dug > 0f) { var lost = 0f; for (i in b.cache.indices) { val x = b.cache[i] * dug; b.cache[i] -= x; lost += x }; small.carrion[cp] += 0.3f * lost / (land.areaKm2[cp] * 1e6f) }
        }
        val spoilt = spoil(b.cache, hw.temp[date.month][hw.placeHex[b.cachePlace]]) + CACHE_SPOIL * b.cacheKg
        for (i in b.cache.indices) b.cache[i] *= 1f - CACHE_SPOIL
        small.litter[b.cachePlace] += spoilt / (land.areaKm2[b.cachePlace] * 1e6f)
        if (hw.placeHex[b.cachePlace] == h) {
            val room = max(0f, CARRY_KG * max(1, g.adults) * (1f + BASKET_GAIN * b.has(Tool.BASKET)) - g.larder)
            val take = min(b.cacheKg, room)
            val share = if (b.cacheKg > 0f) take / b.cacheKg else 0f
            for (i in b.cache.indices) { val x = b.cache[i] * share; b.cache[i] -= x; g.stores[i] += x }
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
    // (Reeds and rushes in a marsh give the best fibre of all.)
    val fibre = people * min(1f, (land.herb[p] + land.shrub[p]) / 0.5f + 2f * land.logged[p])
    gd[Material.FIBRE.ordinal] += fibre
    land.consume(p, LifeForm.TREE, wood * land.tree[p] / max(1e-6f, woody) / area)
    land.consume(p, LifeForm.HERB, fibre / area)
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
    }
    // Making and mending takes the time the band has to spare, the tools it ranks first first (the
    // scaffold: the most worn); each as far as the time, the scarcest material and the skill allow.
    var time = b.spareDays
    val order = Tool.entries.filter { b.knows[it.ordinal] && b.kit[it.ordinal] < 1f }.sortedByDescending { b.makeFirst[it.ordinal] * (1f - b.kit[it.ordinal]) }
    for (t in order) {
        if (time <= 0f || people <= 0f) break
        val k = t.ordinal
        val skill = b.making[t.skill.ordinal]
        val daysEach = t.makeDays * people / (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * skill) // for a whole band's kit
        var can = min(1f - b.kit[k], time / daysEach)
        for ((m, perPerson) in t.recipe) can = min(can, gd[m.ordinal] / max(1e-3f, perPerson * people))
        if (can <= 0f) continue
        for ((m, perPerson) in t.recipe) gd[m.ordinal] -= can * perPerson * people
        b.kit[k] += can; time -= can * daysEach
        b.making[t.skill.ordinal] += PRACTICE * (1f - skill)
    }
    if (b.unluckyWeeks > 0 && --b.unluckyWeeks == 0) b.unluckyHex = -1
    b.sinceRaided++
    plotRaid(g, b)
    // At any camp it first puts up brush shelters, quickly (and leaves them when it moves on).
    if (b.campWeeks == 0) b.shelter = 0f
    if (b.shelter < 1f && time > 0f && people > 0f) { val built = min(1f - b.shelter, time / (SHELTER_DAYS * people)); b.shelter += built; time -= built * SHELTER_DAYS * people }
    // With the time still to spare, a band that has kept its camp a while builds stone-footed houses
    // there, as far as it has gathered the stone and timber (and starts afresh where it settles
    // anew); houses it has left weather away.
    if (b.houseHex >= 0 && b.houseHex != h) b.house = max(0f, b.house - HOUSE_DECAY * years)
    // (Only where the camp keeps it well fed and its stores filled: building is worth it where staying pays.)
    if (b.campWeeks >= HOUSE_AFTER_WEEKS / b.houseWill.coerceAtLeast(0.1f) && time > 0f && people > 0f && g.fed >= 0.9f && g.larder >= 0.5f * b.storeAim * g.dailyNeedKg) {
        if (b.houseHex != h && b.house < 0.2f) { b.houseHex = h; b.house = 0f }
        if (b.houseHex == h && b.house < 1f) {
            var built = min(1f - b.house, time / (HOUSE_DAYS * people))
            built = minOf(built, gd[Material.STONE.ordinal] / (HOUSE_STONE * people), gd[Material.WOOD.ordinal] / (HOUSE_WOOD * people))
            built = max(0f, built)
            gd[Material.STONE.ordinal] -= built * HOUSE_STONE * people; gd[Material.WOOD.ordinal] -= built * HOUSE_WOOD * people
            b.house += built; time -= built * HOUSE_DAYS * people
            if (built > 0f) meetings.merge("bands built houses", 1, Int::plus)
        }
    }
    b.atHome = b.houseHex == h && b.house > 0.05f
    // A band at home that has been raided in the last year rings its houses with a palisade, as far
    // as its time and timber go.
    if (b.atHome && b.sinceRaided < 52 && b.palisade < 1f && time > 0f && people > 0f) {
        val built = minOf(1f - b.palisade, time / (PALISADE_DAYS * people), gd[Material.WOOD.ordinal] / (PALISADE_WOOD * people)).coerceAtLeast(0f)
        gd[Material.WOOD.ordinal] -= built * PALISADE_WOOD * people; b.palisade += built; time -= built * PALISADE_DAYS * people
        if (built > 0f) meetings.merge("bands built palisades", 1, Int::plus)
    }
    if (b.houseHex >= 0 && b.houseHex != h) b.palisade = max(0f, b.palisade - HOUSE_DECAY * years)
    b.spareDays = time
    // A band carries only so much: the rest is left behind (and goes back to the ground); at a camp it
    // keeps, it stacks building stone and timber beyond that.
    for (m in gd.indices) {
        val stack = if (m == Material.STONE.ordinal || m == Material.WOOD.ordinal) (if (b.campWeeks >= 4) HOUSE_STONE + HOUSE_WOOD else 0f) else 0f
        val over = gd[m] - (GOODS_PER_PERSON + stack) * people
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
    b.cache.fill(0f); b.cachePlace = -1
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
const val PEOPLE_SICK = 0.025f
/** Outbreaks start among people at this share of the rate for animals of the same crowding (small bands keep few diseases going). */
const val PEOPLE_OUTBREAK = 0.3f
/** How much a cave or rock shelter adds to a camp's worth (dry, warm, easy to defend). */
const val CAVE_CAMP = 0.5f
/** Yearly deaths from vipers and scorpions among people, in the warm months. */
const val VENOM = 0.002f
/** Yearly deaths from marsh fever among people camped by a marsh in the warm months. */
const val MARSH_FEVER = 0.05f

/** Settled life with stores shortens the spacing of births. */
internal fun settledFertility(g: HexGroup): Float {
    val b = g.band ?: return 1f
    return 1f + SETTLED_FERTILITY * Tuning.settledFertility * min(1f, b.campWeeks / 26f) * min(1f, g.larder / max(1e-3f, 30f * g.dailyNeedKg))
}

/** Extra yearly deaths among children of a camp kept long: refuse, flies and fouled water. */
internal fun campFouling(g: HexGroup): Float {
    val b = g.band ?: return 0f
    return CAMP_FOULING * Tuning.campFouling * fouled(b) * (if (b.holds(Tradition.HYGIENE)) 0.4f else 1f) * (if (b.holds(Tradition.MEDICINE)) 0.7f else 1f)
}

/** How fouled a band's camp is, 0..1: not at all for its first two months, fully after a year more. */
internal fun fouled(b: Band) = min(1f, max(0, b.campWeeks - 8) / 52f)

/** How much a band would rather leave its camp than stay: the camp fouled (less where it keeps it clean), against the graves of its dead. */
internal fun stayCost(b: Band): Float {
    var c = FOUL_AVOID * fouled(b) * (if (b.holds(Tradition.HYGIENE)) 0.4f else 1f)
    if (b.holds(Tradition.BURIAL) && b.campWeeks > 4) c -= BURIAL_STAY
    return c
}

/**
 * Food on hand, by how it keeps: fresh meat and fish rot in days (faster in the heat), dried meat
 * and fish keep for months, fruit for a week or two, roots for some weeks, seed and nuts for a year.
 * [lostWeekly] is the share lost a week (fresh meat: see [spoil]); [meat] whether it counts as meat.
 */
enum class Store(val lostWeekly: Float, val meat: Boolean) {
    FRESH(0f, true), DRIED(0.01f, true), FRUIT(0.1f, false), ROOTS(0.03f, false), SEED(0.005f, false)
}
/** The order a band eats its food in: what spoils first, first. */
val EAT_ORDER = listOf(Store.FRESH, Store.FRUIT, Store.ROOTS, Store.DRIED, Store.SEED)
/** The scaffold's store: days of food a band aims to keep; share of the gap it works to close a week. */
const val STORE_AIM_DAYS = 30f
const val STORE_FILL = 0.25f
/** Person-days to dry or smoke a kilo of meat or fish (cutting it thin, tending the racks and fire), at full skill. */
const val DRY_DAYS_PER_KG = 0.1f

/** Kinds of food a party forages for (see [forage]). */
const val FOOD_TYPES = 9

/**
 * How a band sets its ways for the week: given what it knows of itself ([bandInputs], [WAYS_IN]
 * numbers), it writes [WAYS_OUT] numbers to `out`, each 0 for the scaffold's choice: its effort on
 * each kind of food, the days of food it aims to keep, and how it ranks making each tool.
 */
fun interface BandWays { fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) }
val WAYS_IN = 2 + 1 + 1 + Store.entries.size + 2 + Forage.entries.size + Tool.entries.size + 3
val WAYS_OUT = FOOD_TYPES + 1 + Tool.entries.size + 2

/** What a band knows of itself this week, for its ways: the season, how fed it is and what it has on hand, its people, skills and tools, the warmth, its camp and its spare time. */
internal fun HexGroups.bandInputs(g: HexGroup, weather: inflation.world.Weather, into: FloatArray) {
    val b = g.band ?: return
    var i = 0
    val angle = 2.0 * Math.PI * weather.date.dayOfYear / Date.DAYS_PER_YEAR
    into[i++] = kotlin.math.sin(angle).toFloat(); into[i++] = kotlin.math.cos(angle).toFloat()
    into[i++] = g.fed
    val total = g.larder
    into[i++] = total / max(1e-3f, 60f * g.dailyNeedKg)
    for (st in Store.entries) into[i++] = if (total > 0f) g.stores[st.ordinal] / total else 0f
    into[i++] = g.adults / 30f; into[i++] = g.young.toFloat() / max(1, g.adults)
    for (f in Forage.entries) into[i++] = b.skill(f)
    for (t in Tool.entries) into[i++] = b.has(t)
    into[i++] = weather.temperature[g.place] / 30f
    into[i++] = min(1f, b.campWeeks / 26f)
    into[i++] = b.spareDays / max(1f, g.adults * days.toFloat())
}

/** Sets a band's ways from [ways]' outputs: 0 is the scaffold (effort 1, stores [STORE_AIM_DAYS], every tool alike). */
internal fun HexGroups.setWays(g: HexGroup, weather: inflation.world.Weather, ways: BandWays, inputs: FloatArray, out: FloatArray) {
    val b = g.band ?: return
    bandInputs(g, weather, inputs)
    out.fill(0f)
    ways.choose(g, inputs, out)
    fun squash(x: Float) = 2f / (1f + kotlin.math.exp(-x))
    for (t in 0 until FOOD_TYPES) b.effort[t] = squash(out[t])
    b.storeAim = STORE_AIM_DAYS * squash(out[FOOD_TYPES])
    for (t in Tool.entries) b.makeFirst[t.ordinal] = squash(out[FOOD_TYPES + 1 + t.ordinal])
    b.scoutShare = SCOUT_SHARE * squash(out[FOOD_TYPES + 1 + Tool.entries.size])
    b.houseWill = squash(out[FOOD_TYPES + 2 + Tool.entries.size])
}

/**
 * Houses: person-days of work for one person's share of a hamlet (digging, walls, roof); weeks at a
 * camp before a band starts to build; share a year a left house weathers away; how much warmer a
 * full house keeps its people, °C; how much less its pits let stores spoil; share of the stored
 * grain, nuts and roots mice take a week from full pits; how much harder a band at home is to raid;
 * how strongly its houses draw a band back.
 */
const val HOUSE_DAYS = 20f
/** Caches: yearly share dug up where diggers are thick; rodents and foxes (kg/km²) at which they count fully; chance a band passing a hex finds another's cache there; share it takes. */
const val CACHE_DUG = 2f
const val CACHE_RODENTS_HALF = 200f
const val CACHE_FOXES_HALF = 20f
const val CACHE_FIND = 0.15f
const val CACHE_TAKE = 0.6f
/** Stone and timber for one person's share of a stone-footed house, kg; person-days for a brush shelter; how much warmer a brush shelter keeps, °C. */
const val HOUSE_STONE = 150f
const val HOUSE_WOOD = 60f
const val SHELTER_DAYS = 1f
const val SHELTER_C = 3f
const val HOUSE_AFTER_WEEKS = 26
const val HOUSE_DECAY = 0.3f
const val HOUSE_C = 8f
const val PIT_KEEP = 0.5f
const val MICE = 0.01f
const val HOUSE_DEFENCE = 0.5f
const val HOME_PULL = 0.15f

/**
 * Bands passing through another band's cache may find it and carry off what they can, unless they
 * are close to its owners: the more it lies in the open and the more they see, the likelier. The
 * owners hold it against the takers when they learn of it.
 */
internal fun HexGroups.robCaches() {
    val caches = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) { val b = g.band ?: continue; if (g.alive && b.cachePlace >= 0 && b.cacheKg > 1f) caches.getOrPut(hw.placeHex[b.cachePlace]) { ArrayList() } += g }
    if (caches.isEmpty()) return
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive) continue
        for (h in g.path) {
            val owners = caches[h] ?: continue
            for (o in owners) {
                val ob = o.band ?: continue
                if (o === g || b.tie(o.id) > 0.3f || ob.cacheKg <= 1f) continue
                if (rnd.nextFloat() >= CACHE_FIND * sight(ob.cachePlace) * (1f + b.scoutShare * 5f)) continue
                val room = max(0f, CARRY_KG * max(1, g.adults) - g.larder)
                val take = min(ob.cacheKg * CACHE_TAKE, room)
                if (take <= 0f) continue
                val share = take / ob.cacheKg
                for (i in ob.cache.indices) { val x = ob.cache[i] * share; ob.cache[i] -= x; g.stores[i] += x }
                ob.befriend(g.id, -TIE_RAID * 0.5f); ob.grudge[b.mainLineage()] += 0.5f * GRUDGE
                meetings.merge("caches robbed", 1, Int::plus)
                note(g) { "found and took %.0f kg from band #%d's cache".format(take, o.id) }; note(o) { "its cache was robbed by band #%d".format(g.id) }
            }
        }
    }
}

/**
 * A band driven by hunger or a feud, knowing where a band it is not close to has its home within
 * reach, sets out to fall on it there: settled bands can be found. Once there, if the others are at
 * home, it attacks (see [fight]); otherwise it gives up.
 */
internal fun HexGroups.plotRaid(g: HexGroup, b: Band) {
    val here = hw.placeHex[g.place]
    if (b.raidHex >= 0) {
        if (here == b.raidHex) {
            val target = groups.firstOrNull { it.id == b.raidOn && it.alive && hw.placeHex[it.place] == here }
            if (target != null) target.band?.let { tb -> fight(g, b, target, tb, planned = true) }
            b.raidHex = -1; b.raidOn = -1
        } else if (--b.raidWeeks <= 0) { b.raidHex = -1; b.raidOn = -1 }
        return
    }
    val hungry = g.fed < RAID_HUNGER
    var best = -1; var bestKm = Double.MAX_VALUE
    for ((id, h) in b.knownHomes) {
        if (b.tie(id) > 0f) continue
        val feud = (b.knownLineage[id] ?: -1).let { it >= 0 && b.grudge[it] > FEUD }
        if (!hungry && !feud) continue
        val km = hexKm(here, h)
        if (km <= RAID_RANGE_KM && km < bestKm) { bestKm = km; best = id }
    }
    if (best >= 0 && rnd.nextFloat() < RAID_PLOT * days / 7f) {
        b.raidOn = best; b.raidHex = b.knownHomes.getValue(best); b.raidWeeks = RAID_WEEKS
        meetings.merge("raids set out", 1, Int::plus)
        note(g) { "set out to raid band #%d at hex %d".format(best, b.raidHex) }
    }
}

/** Raids: hunger under which a band will go raiding; how far, km; weekly chance a driven band sets out; weeks it keeps at it. Palisades: person-days and timber (kg) for one person's share. Fighting: surprise and the lie of the land. */
const val RAID_HUNGER = 0.6f
const val RAID_RANGE_KM = 60.0
const val RAID_PLOT = 0.3f
const val RAID_WEEKS = 4
const val PALISADE_DAYS = 6f
const val PALISADE_WOOD = 80f
const val PALISADE_DEFENCE = 1f
const val SURPRISE_RAID = 1f
const val HIGH_GROUND = 0.5f
/** Chance a band with no home to hold slips away from a raid, losing only what it cannot carry. */
const val FLEE = 0.5f
