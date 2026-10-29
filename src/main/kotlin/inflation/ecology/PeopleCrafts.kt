package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Books
import inflation.Kg
import inflation.min
import inflation.Tuning
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.min

// Bands' goods, tools and skills: gathering materials, making and wearing tools and ornaments, customs, teaching, forgetting and wounds.

/** How far one week's use closes the gap to mastery. */
const val PRACTICE = 0.02f
/** Raw materials a band carries, kg per adult of each kind. */
const val GOODS_PER_PERSON = 20f
/** Yearly chance a band hits on a tool it does not know, where it has use for it; chance a band that meets one that knows a tool learns it. */
const val INVENT = 0.002f
const val TEACH = 0.2f
/**
 * What [g]'s band gathers of its materials in a week, into its goods: what the country gives along the way, as far as the
 * band lacks it (up to what it carries, [carried]) and as far as the [time] it has to spare goes, each material at its
 * own rate of gathering. With no [time] it gathers nothing, and returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
internal fun GroupWorld.gatherMaterials(g: HexGroup, b: Band, p: Place, h: Hex, people: Float, m2: Float, time: BandTime?): Float {
    val gd = b.goods
    var asked = 0f
    /** Takes up to [give] kg of [m], as far as the band lacks it and has the time at [perDay] kg a person-day; returns what it took. */
    @IgnorableReturnValue fun gather(m: Material, give: Float, perDay: Float): Float {
        val want = min(give, carried(b, m, people) - gd[m])
        if (want <= 0f || perDay <= 0f) return 0f
        if (time == null) { asked += want / perDay; return 0f }
        val left = time.leftFor(Activity.MATERIALS)
        if (left <= 0f) return 0f
        val took = min(want, left * perDay)
        gd[m] += took; time.spend(Activity.MATERIALS, took / perDay)
        return took
    }
    // What the country gives, as far as the band's time goes: good stone where flint or obsidian crops out
    // along the way (poorer stone anywhere), wood and resin where there are trees, fibre from grass,
    // scrub and reeds; hides, bone and sinew from the big game it killed.
    fun alongTheWay(a: FloatArray) = max(a[p], g.path.maxOfOrNull { q -> hw.placeNear(p, q).takeIf { !it.none }?.let { a[it] } ?: 0f } ?: 0f)
    val stone = alongTheWay(land.stone)
    // (Only good stone is kept: whatever stone is at hand makes poorer tools, see [TRADED]. It takes as much as it wants
    // and has the time for, slower where little crops out.)
    if (stone > 0f) gather(Material.STONE, Float.MAX_VALUE, STONE_A_DAY * min(1f, 5f * stone))
    // Salt scraped from a dry lake bed or pan along the way, and a little from the shore; ochre dug where it crops out.
    gather(Material.SALT, people * (SALT_A_WEEK * min(1f, 5f * alongTheWay(land.salt)) + if (hw.coast[h]) SEA_SALT_A_WEEK else 0f), SALT_A_DAY)
    gather(Material.OCHRE, people * OCHRE_A_WEEK * min(1f, 5f * alongTheWay(land.ochre)), OCHRE_A_DAY)
    // (Wood and fibre are what the plants give up: from the trees and the scrub, and the grass and scrub, each by its share.
    // Wood comes as slowly as firewood does, by how thick the trees and scrub stand, as much as it wants and has the time for.)
    val tree = land.plants.tree[p]; val shrub = land.plants.shrub[p]; val herb = land.plants.herb[p]
    val woody = tree + shrub
    if (woody > 0f) {
        val cover = min(1f, woody / 3f)
        val wood = gd[Material.WOOD]
        val took = gather(Material.WOOD, Float.MAX_VALUE, FUEL_A_DAY * cover)
        if (took > 0f) gd[Material.WOOD] = wood + land.plants.consume(p, LifeForm.TREE, Kg(took * tree / woody).perM2(m2)).over(m2).v +
            land.plants.consume(p, LifeForm.SHRUB, Kg(took * shrub / woody).perM2(m2)).over(m2).v
    }
    val resin = gather(Material.RESIN, 0.05f * people * min(1f, tree / 3f), RESIN_A_DAY)
    if (resin > 0f) land.books?.leak(Books.Stuff.MATTER, "resin, not taken from the trees", resin.toDouble())
    // (Reeds and rushes in a marsh give the best fibre of all.)
    val green = herb + shrub
    if (green > 0f) {
        val fibre = gd[Material.FIBRE]
        val took = gather(Material.FIBRE, people * min(1f, (herb + shrub) / 0.5f + 2f * land.waterlogged[p]), FIBRE_A_DAY)
        if (took > 0f) gd[Material.FIBRE] = fibre + land.plants.consume(p, LifeForm.HERB, Kg(took * herb / green).perM2(m2)).over(m2).v +
            land.plants.consume(p, LifeForm.SHRUB, Kg(took * shrub / green).perM2(m2)).over(m2).v
    }
    // Shells on the shore, for beads.
    if (hw.coast[h]) gather(Material.SHELL, SHELL_A_WEEK * people, SHELL_A_DAY)
    return asked
}

/**
 * What [b] carries of [m] at most, kg: its share of the goods a person carries, and at a camp it has kept long enough to build
 * at ([houseAfterWeeks]), building stone and timber stacked beyond that.
 */
internal fun GroupWorld.carried(b: Band, m: Material, people: Float): Float {
    val stack = if (m == Material.STONE || m == Material.WOOD) (if (b.campWeeks >= houseAfterWeeks(b)) HOUSE_STONE + HOUSE_WOOD else 0f) else 0f
    return (GOODS_PER_PERSON + stack) * people
}

/** How far [g]'s band [b] lacks [m]: 1 with none of it, 0 with all it carries ([carried]). */
internal fun GroupWorld.lacking(g: HexGroup, b: Band, m: Material) = max(0f, 1f - b.goods[m] / carried(b, m, g.adults.toFloat()).coerceAtLeast(1e-3f))

/**
 * The work [b]'s customs ask of it this week, from [time] (person-days) after the firewood: its burials first (the digging
 * and carrying its dead still ask, [Band.burialOwed]; what the time does not cover waits for next week), its rites held ([Band.riteOwed]), then keeping the
 * camp clean (middens carried off, water fetched apart from them) and nursing its sick and hurt. Each is done as far as
 * the time goes, and helps only as far as it was done.
 */
internal fun GroupWorld.keepCustoms(b: Band, people: Float, time: BandTime) {
    if (b.burialOwed > 0f) {
        val paid = min(b.burialOwed, max(0f, time.leftFor(Activity.CUSTOMS)))
        time.spend(Activity.CUSTOMS, paid); b.burialOwed -= paid
    }
    if (b.riteOwed > 0f) {
        val paid = min(b.riteOwed, max(0f, time.leftFor(Activity.CUSTOMS)))
        time.spend(Activity.CUSTOMS, paid); b.riteOwed -= paid
    }
    val clean = cleanDays(b, people)
    b.cleaned = if (clean > 0f) min(1f, time.leftFor(Activity.CUSTOMS) / clean) else 1f
    time.spend(Activity.CUSTOMS, b.cleaned * clean)
    val nurse = nurseDays(b, people)
    b.nursed = if (nurse > 0f) min(1f, time.leftFor(Activity.CUSTOMS) / nurse) else 1f
    time.spend(Activity.CUSTOMS, b.nursed * nurse)
    time.noneBelowZero()
    time.release(Activity.CUSTOMS)
}

/** Person-days [b]'s [people] give a week to keeping clean, if it holds to it. */
internal fun GroupWorld.cleanDays(b: Band, people: Float) = if (b.holds(Tradition.HYGIENE)) HYGIENE_DAYS * people * days else 0f

/** Person-days [b] gives a week to nursing its sick and hurt among its [people], if it holds to it. */
internal fun GroupWorld.nurseDays(b: Band, people: Float) =
    if (b.holds(Tradition.MEDICINE)) NURSE_DAYS * people * (b.hurt + b.sick.sum()).coerceAtMost(1f) * days else 0f

/** [b]'s tools wear out in use (slowly lying idle); a band with a use for a tool or practised at a kind of work now and then hits on a new one. */
internal fun GroupWorld.wearAndInvent(b: Band, years: Float) {
    for (t in Tool.entries) {
        // Tools wear out in use; lying idle, slowly (cords rot, hafts dry and crack).
        val inUse = t == Tool.CLOTHES || t.uses.any { b.weeks - b.lastUsed[it] <= 1 }
        b.kit[t] = max(0f, b.kit[t] - t.wearPerYear * years * (if (inUse) 1f else IDLE_WEAR) *
            (if (t.hafted) b.lapse(Skill.GLUING) else 1f) * wearOfMake(t, b.fine[t], tuning))
        // A band that has a use for a tool now and then hits on it: bows where game is shy and
        // fast, sickles and grinding stones where it reaps seed, and so on.
        if (!b.knows[t] && rnd.nextFloat() < INVENT * b.edge(Skill.TINKER) * years * (0.5f + b.skills[t.serves])) {
            b.knows[t] = true
            stats.meeting(Met.Of.HIT_ON, t.name.lowercase())
        }
    }
    // So with ways of working: a band practised at a kind of work now and then hits on a new way of doing it.
    // (Salting is hit on only with salt to hand.)
    for (s in Skill.entries) if (!b.knowsSkill[s] && (!s.settled || b.atHome) && (s != Skill.SALTING || b.goods[Material.SALT] > 0f) &&
        (s != Skill.TENDING || b.herd.any { it > 0f }) &&
        s.needs.let { it == null || (b.knowsSkill[it] && b.skills[it] >= NEEDS_LEVEL) } &&
        rnd.nextFloat() < s.invent * INVENT * b.edge(Skill.TINKER) * years * (0.5f + b.skill(s.domain))) {
        b.knowsSkill[s] = true
        b.lastUsed[s] = b.weeks
        stats.meeting(Met.Of.HIT_ON, s.name.lowercase().replace('_', ' '))
    }
}

/**
 * Making and mending in the [time] days [b] has to spare: the tools it ranks first first (the
 * scaffold: the most worn), each as far as the time, the scarcest material and the skill allow. With no [time] it makes
 * nothing, and returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
internal fun HexGroups.makeTools(b: Band, people: Float, time: BandTime?): Float {
    val gd = if (time == null) b.goods.copyOf() else b.goods
    var asked = 0f
    val order = Tool.entries.filter { b.knows[it] && b.kit[it] < 1f }.sortedByDescending { b.makeFirst[it] * (1f - b.kit[it]) }
    if (time != null) dialUse?.let { u -> if (order.size > 1) {
        val order1 = Tool.entries.filter { b.knows[it] && b.kit[it] < 1f }.sortedByDescending { 1f - b.kit[it] }
        // (Counted under the tool it put first: whether the order differs from the scaffold's, most worn first.)
        u.choice(DialSite.MAKE_FIRST, b.makeFirst[order[0]], order != order1, false, dial = WaysOut.MAKE_FIRST + order[0].ordinal)
    } }
    if (time != null) b.clothesMade = if (Tool.CLOTHES in order) Tally.ClothesMade.NOT_REACHED else null
    for (t in order) {
        val left = time?.leftFor(Activity.TOOLS) ?: Float.MAX_VALUE
        if (left <= 0f || people <= 0f) break
        val way = b.makeWay(t)
        val daysEach = t.makeDays * people / b.makeSkill(t) // for a whole band's kit
        // (Blades get several times the edge out of a kilo of flint.)
        val stoneUse = if (way == Skill.BLADES || way == Skill.MICROLITHS) BLADE_STONE else 1f
        var can = min(1f - b.kit[t], left / daysEach)
        fun use(m: Material) = if (m == Material.STONE) stoneUse else 1f
        // (Stone lacking is made up from whatever stone is at hand, and the tool is the poorer
        // for it; clothes are tanned with ochre as far as it goes.)
        val made = fineMaterial(t)
        for ((m, perPerson) in t.recipe) if (m != made) can = min(can, gd[m] / max(1e-3f, perPerson * use(m) * people))
        if (time != null && t == Tool.CLOTHES) b.clothesMade = clothesMade(1f - b.kit[t], left / daysEach, can)
        if (can <= 0f) continue
        for ((m, perPerson) in t.recipe) if (m != made) gd[m] = max(0f, gd[m] - can * perPerson * use(m) * people)
        if (time == null) { asked += can * daysEach; continue }
        if (made != null) {
            val want = can * (t.recipe[made] ?: TAN_OCHRE) * use(made) * people
            val good = if (want > 0f) min(1f, gd[made] / want) else 1f
            gd[made] = max(0f, gd[made] - good * want)
            b.fine[t] = (b.kit[t] * b.fine[t] + can * good) / (b.kit[t] + can)
        }
        b.kit[t] += can; time.spend(Activity.TOOLS, can * daysEach)
        b.practise(way); b.practise(Skill.TINKER, 0.02f * PRACTICE)
        // (And tries the other ways of the craft it knows now and then, so a new way can come to beat the old.)
        for (o in Skill.of(way.domain)) if (o != way && b.knowsSkill[o]) b.practise(o, 0.2f * PRACTICE * b.edge(Skill.TINKER))
        if (t == Tool.CLOTHES) { b.practise(Skill.SEWING); b.practise(Skill.BONE_TOOLS) }
        if (t.hafted) b.practise(Skill.GLUING)
    }
    return asked
}

/** What stopped a week's clothes making ([Band.clothesMade]): [want] of a kit wanted, [byTime] of it the days allowed, [can] made. */
private fun clothesMade(want: Float, byTime: Float, can: Float) = when {
    can >= 0.999f * want -> Tally.ClothesMade.MADE
    byTime <= 1.001f * can -> Tally.ClothesMade.TIME_SHORT
    else -> Tally.ClothesMade.GOODS_SHORT
}

/** Ornaments of shell and bone, made with the [time] days [b] has still to spare. */
internal fun GroupWorld.makeOrnaments(b: Band, people: Float, time: BandTime) {
    val gd = b.goods
    val left = time.leftFor(Activity.ORNAMENTS)
    if (left > 0f && people > 0f) {
        val made = min(left / ORNAMENT_DAYS, ornamentsWanted(b, people))
        if (made > 0f) {
            val fromShell = min(made, gd[Material.SHELL] / SHELL_EACH)
            gd[Material.SHELL] = max(0f, gd[Material.SHELL] - fromShell * SHELL_EACH)
            gd[Material.BONE] = max(0f, gd[Material.BONE] - (made - fromShell) * BONE_EACH)
            b.ornaments += made; b.exotic += fromShell; time.spend(Activity.ORNAMENTS, made * ORNAMENT_DAYS)
        }
    }
}

/** The ornaments [b] would make for its [people], as far as it lacks them and has shell and bone. */
internal fun ornamentsWanted(b: Band, people: Float) =
    min(b.goods[Material.SHELL] / SHELL_EACH + b.goods[Material.BONE] / BONE_EACH, ORNAMENTS_PER_PERSON * people - b.ornaments).coerceAtLeast(0f)

/** Knowledge fades fast unused, unless songs and stories keep [b]'s; hand skills last for years; a way no one holds is lost. */
internal fun GroupWorld.forget(b: Band) {
    val forgetKnowledge = (if (b.holds(Tradition.ORAL_TRADITION)) FORGET_KNOWLEDGE * 0.4f else FORGET_KNOWLEDGE) * b.lapse(Skill.LORE)
    for (s in Skill.entries) {
        val unused = b.weeks - b.lastUsed[s] > 4
        // A skill learned slowly is also lost slowly, or its level would settle far lower (r / (r + forgetting)).
        val hands = FORGET_HANDS * minOf(1f, s.practice / PRACTICE)
        b.skills[s] *= 1f - (if (!unused) hands else if (s.knowledge) forgetKnowledge else hands * 2f)
        // A way no one holds and no one has used for years is lost altogether; so is one whose groundwork is lost.
        if (b.knowsSkill[s] && s.losable && b.holders[s] < 0.5f && b.weeks - b.lastUsed[s] > (if (s.knowledge) LOST_KNOWLEDGE_YEARS else LOST_HANDS_YEARS) * stepsPerYear) {
            b.knowsSkill[s] = false; b.skills[s] = 0f; b.holders[s] = 0f
            stats.meeting(Met.Of.LOST, s.name.lowercase().replace('_', ' '))
        }
        s.needs?.let { n -> if (b.knowsSkill[s] && !b.knowsSkill[n]) { b.knowsSkill[s] = false; b.skills[s] = 0f; b.holders[s] = 0f } }
    }
}

/** How much more slowly a tool wears lying idle than in use; how much of a craft a band learns making do without its tools. */
const val IDLE_WEAR = 0.3f
const val TOOLLESS = 0.2f

/** Two bands that meet show each other tools one of them does not know, the more readily the better they understand each other ([understood]). */
internal fun GroupWorld.teach(a: Band, b: Band, understood: Float, aDoesBetter: Float = 0f) {
    for (k in Tool.entries) if (a.knows[k] != b.knows[k] && rnd.nextFloat() < TEACH * (0.5f + 0.5f * understood)) {
        a.knows[k] = true; b.knows[k] = true
        stats.meeting(Met.Of.LEARNED, k.name.lowercase())
    }
    // And ways of working: the one that knows a way shows it; the other starts on it as a beginner.
    // (Bands take up a stranger's way the less readily the more their customs differ, and the more
    // readily from a band that does better than they do.)
    val alike = 1f - CONFORMITY * Tradition.entries.count { a.holds(it) != b.holds(it) }.toFloat() / Tradition.entries.size
    for (k in Skill.entries) if (a.knowsSkill[k] != b.knowsSkill[k]) {
        val (from, to) = if (a.knowsSkill[k]) a to b else b to a
        val need = k.needs
        if (need != null && (!to.knowsSkill[need] || to.skills[need] < NEEDS_LEVEL)) continue
        val prestige = (1f + PRESTIGE * (if (from === a) aDoesBetter else -aDoesBetter)).coerceIn(0.2f, 2f)
        if (rnd.nextFloat() >= k.teach * TEACH * (0.5f + 0.5f * understood) * alike * prestige) continue
        // (A meeting shows that a way exists; the skill itself comes only with people who stay to teach it.)
        // (Newly learned, it counts as fresh: it is lost only if it then goes unused for years.)
        to.knowsSkill[k] = true; to.lastUsed[k] = to.weeks
        stats.meeting(Met.Of.LEARNED, k.name.lowercase().replace('_', ' '))
    }
}

/**
 * Ornaments: shell gathered on a shore a week per person, kg; shell and bone for one ornament, kg;
 * person-days to make one; most a band keeps per person; share of its ornaments a band gives a band
 * it is close to when they meet; share of a band's ornaments buried with each of its dead (grave goods).
 */
const val SHELL_A_WEEK = 0.05f
const val SHELL_EACH = 0.02f
const val BONE_EACH = 0.05f
const val ORNAMENT_DAYS = 2f
const val ORNAMENTS_PER_PERSON = 3f
const val ORNAMENT_GIFT = 0.03f
const val GRAVE_GOODS = 0.05f

/*
 * Trade: good knapping stone, salt and ochre are found only in some
 * country, and each is worth having. Stone tools of good stone (flint, chert, obsidian) cut and wear
 * better than those of whatever stone is at hand; meat and fish salted keep far longer and cure in
 * any weather; hides tanned with ochre last. Bands give what they have to spare of them to bands that
 * lack them, which binds both, and a band's rites may take it to where they are got.
 */
/**
 * What a person gets in a week at a source, kg: salt where a salt pan or dry lake bed lies along the way,
 * from the shore; ochre where it crops out. Not measured: a day's scraping or digging gives far more,
 * these are a week's worth carried off by a band passing by.
 */
const val SALT_A_WEEK = 1f
const val SEA_SALT_A_WEEK = 0.05f
const val OCHRE_A_WEEK = 0.3f
/** Ochre to tan a person's share of clothes, kg; how much slower tanned clothes wear. */
const val TAN_OCHRE = 0.2f
const val TANNED_WEAR = 0.5f
/** The material that makes tool [t] well (with trade on): good stone for stone tools, ochre for tanned clothes; null for the others. */
fun fineMaterial(t: Tool): Material? = when { Material.STONE in t.recipe -> Material.STONE; t == Tool.CLOTHES -> Material.OCHRE; else -> null }
/** How much faster tool [t] wears for being made well only in share [fine] */
fun wearOfMake(t: Tool, fine: Float, tuning: Tuning): Float = when (fineMaterial(t)) {
    Material.STONE -> 1f + tuning.poorStoneWear * (1f - fine)
    Material.OCHRE -> 1f - TANNED_WEAR * fine
    else -> 1f
}
/** How well tool [t] serves for being made well only in share [fine] stone tools of poor stone, less. */
fun serveOfMake(t: Tool, fine: Float, tuning: Tuning): Float = if (fineMaterial(t) != Material.STONE) 1f else tuning.poorStoneEdge + (1f - tuning.poorStoneEdge) * fine

/** Flint a tool's stone takes when knapped as blades or microliths, against plain flakes. */
const val BLADE_STONE = 0.4f
/**
 * Skills live in people. Each week the band's practised holders of each skill follow the work: the
 * young and the newcomers are taught the ways in use (taking time from the band's spare days, the
 * more the more intricate the way, the faster the better its teachers), people drop ways unused for
 * half a year, the dead take theirs with them, and a person can keep up only so many. A way held by
 * too few falls back toward what they can pass on (see [Skill.cap]).
 */
internal fun GroupWorld.trainHolders(g: HexGroup, b: Band, time: BandTime) {
    val adults = g.adults.toFloat()
    if (b.holders.a[0] < 0f) for (s in Skill.entries) {
        b.holders[s] = if (b.knowsSkill[s]) adults * s.share else 0f
        b.lastUsed[s] = b.weeks
    }
    // The dead take their skills with them.
    if (b.lastAdults > 0 && g.adults < b.lastAdults) { val k = adults / b.lastAdults; for (i in Skill.entries) b.holders[i] *= k }
    b.lastAdults = g.adults
    for (s in Skill.entries) {
        if (!b.knowsSkill[s]) { b.holders[s] = 0f; continue }
        val used = b.weeks - b.lastUsed[s] <= DISUSE_YEARS * stepsPerYear
        val learners = learners(b, s, adults)
        val left = time.leftFor(Activity.TRAINING)
        if (learners > 0f && left > 0f) {
            val add = min(learners, left / (APPRENTICE_DAYS * s.complexity))
            b.holders[s] += add; time.spend(Activity.TRAINING, add * APPRENTICE_DAYS * s.complexity)
        } else if (!used) b.holders[s] = max(0f, b.holders[s] - SKILL_DROP * b.holders[s])
        b.holders[s] = min(b.holders[s], adults)
        // A way held by too few falls back toward what they can pass on.
        val cap = s.cap(b.holders[s])
        if (b.skills[s] > cap) b.skills[s] -= SHORTFALL * (b.skills[s] - cap)
    }
    // A person keeps up only so many ways: beyond that, the least used give way.
    val most = adults * SLOTS
    if (b.holders.sum() > most) for (s in Skill.entries.sortedBy { b.lastUsed[it] }) {
        val over = b.holders.sum() - most
        if (over <= 0f) break
        if (s.share >= 1f) continue
        b.holders[s] = max(0f, b.holders[s] - over)
    }
}

/**
 * The learners of way [s] [b] would bring on this week among its [adults], time allowing: as far as it lacks holders of a
 * way in use, as fast as they learn. (Learning from a holder is quick; working it out alone is slow.)
 */
internal fun GroupWorld.learners(b: Band, s: Skill, adults: Float): Float {
    if (!b.knowsSkill[s] || b.weeks - b.lastUsed[s] > DISUSE_YEARS * stepsPerYear) return 0f
    val pace = (if (b.holders[s] >= 1f) 1f else SELF_TAUGHT) * b.learning
    return minOf(adults * s.share - b.holders[s], adults * TRAINED_A_WEEK * pace).coerceAtLeast(0f)
}

/**
 * Holders: the share of a year unused before people drop a way; weekly share of its holders that drop it then;
 * pace of working a way out with no one to learn from; most of the adults taught a way in a week;
 * person-days to bring one learner on (times the way's intricacy); how many ways a person keeps
 * up; weekly share of the gap a way held by too few loses.
 */
const val DISUSE_YEARS = 0.5f
const val SKILL_DROP = 0.02f
const val SELF_TAUGHT = 0.1f
const val TRAINED_A_WEEK = 0.05f
const val APPRENTICE_DAYS = 10f
const val SLOTS = 14f
/**
 * Forgetting (Arthur and colleagues 1998): weekly share a hand skill loses in use and unused, and
 * knowledge unused (about a third a year); years unheld and unused before a way is lost altogether;
 * the level a way's groundwork must reach before it can be taken up; how much a difference in
 * customs puts bands off each other's ways (all of them differing: this share less), and how much
 * a band doing better is copied more readily.
 */
const val FORGET_HANDS = 0.001f
const val FORGET_KNOWLEDGE = 0.008f
const val LOST_KNOWLEDGE_YEARS = 5f
const val LOST_HANDS_YEARS = 25f
const val NEEDS_LEVEL = 0.3f
const val CONFORMITY = 0.8f
const val PRESTIGE = 0.5f
const val SHORTFALL = 0.02f
/**
 * Materials gathered, kg a person-day where the country has them (wood comes at [FUEL_A_DAY]). Estimates, not field
 * figures: good stone picked up where it crops out, salt scraped from a pan, ochre dug, reeds and grass cut for fibre,
 * resin scraped from trees and shells picked over on the shore for beads.
 */
const val STONE_A_DAY = 50f
/** Food cached, kg a person-day: pits dug, lined and covered (an estimate, not a field figure). */
const val CACHE_KG_A_DAY = 100f
const val SALT_A_DAY = 20f
const val OCHRE_A_DAY = 5f
const val FIBRE_A_DAY = 10f
const val RESIN_A_DAY = 0.5f
const val SHELL_A_DAY = 0.5f
/**
 * Days of work a clean camp takes a person each day (about a quarter of an hour of a twelve-hour day), and days a
 * carer gives each sick or hurt person each day (half a day). Estimates: no time-use figures found.
 */
const val HYGIENE_DAYS = 0.02f
const val NURSE_DAYS = 0.5f

/** A band takes [n] wounded among its [adults]: from a fight, a hunt gone wrong, a beast, a fall. */
internal fun Band.wounded(n: Float, adults: Int) { if (adults > 0) hurt = min(1f, hurt + n / adults) }
