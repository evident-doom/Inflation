package inflation.ecology

import inflation.world.Date
import kotlin.math.max
import kotlin.math.min

// What passes between bands: words, traditions, partners and grudges. A band's culture is data on
// the band ([Band]); it changes when bands meet, when they split, and now and then by itself.

/**
 * A tradition: a practice or belief a band holds, passed on to both halves when it splits, copied
 * from neighbours who do better, and now and then taken up or dropped. Each has a concrete effect.
 */
enum class Tradition(val about: String) {
    BURIAL("the dead are buried near camp: the band is loath to leave its place"),
    SKY_LORE("sun, moon and stars are watched: the band keeps its seasonal round on time"),
    TABOO_YOUNG("mothers and young are not hunted: game is spared in its breeding years"),
    ORAL_TRADITION("songs, stories and genealogies: knowledge and skills fade more slowly"),
    FEASTS("dance, song and shared feasts: bands grow larger before they split"),
    MEDICINE("herbs, setting bones, care of the sick: fewer die of wounds and illness"),
    EXOGAMY("partners are taken from other bands: less inbreeding, skills travel"),
    HYGIENE("middens and water kept apart, camps cleaned: a kept camp fouls slowly"),
    SHARING("food is shared on demand: insurance against hunger, but little point in storing up for oneself"),
    LEAVE_DEAD("after a death the camp is unlucky and abandoned (unless the band buries its dead)"),
}

/**
 * A deliberately small language: a fixed set of concepts, each named by a band with a word of its
 * own. What matters to the model is how much two bands understand each other: the share of
 * concepts they name alike. Know-how, customs and news pass between bands in proportion to it.
 */
object Language {
    const val CONCEPTS = 64
    const val FORMS = 1 shl 20
    fun understanding(a: IntArray, b: IntArray): Float { var k = 0; for (i in a.indices) if (a[i] == b[i]) k++; return k.toFloat() / a.size }
}

/** Founding lineages tracked in a band's descent; the size of the blocks of country they start in, km. */
const val LINEAGES = 16
const val LINEAGE_KM = 150.0
/** Years in a generation, for how fast inbreeding builds in a closed band. */
const val GENERATION_YEARS = 25f
/**
 * Extra yearly deaths among children per unit of inbreeding. First cousins' children (F = 0.0625)
 * die before adulthood some 4% more often than others (Bittles and Neel 1994, from memory): about
 * 0.6 per unit of F over fifteen years.
 */
const val INBREEDING_DEATHS = 0.04f
/** Chance a meeting of bands that take partners from outside ends in an exchange (half when only one of them holds to it). */
const val MARRY = 0.5f
/** Kinship with a partner from another band, per unit of shared descent (about second cousins). */
const val OUTSIDER_KIN = 0.03f
/**
 * Years an adult spends bearing and raising children before becoming an elder; elders' extra yearly
 * deaths. Foragers who reach 45 live some 20 years more on average (Gurven and Kaplan 2007, from
 * memory): about 5% a year with the ordinary deaths.
 */
const val ADULT_YEARS = 30f
const val ELDER_DEATHS = 0.03f
/** Mothers one elder can help; most that grandmothers raise births and cut children's deaths, when there are enough of them. */
const val ELDER_MOTHERS = 3f
/** How many births a woman has in each third of her adult life (about 15-25, 25-35, 35-45) against her best years. */
val FERTILE_BY_SPAN = floatArrayOf(0.75f, 1f, 0.5f)
const val GRANDMOTHERS = 0.3f
const val ELDER_CARE = 0.3f
/** Words two bands bring into line when they meet; yearly chance a word changes by itself. */
const val WORDS_PER_MEETING = 3
const val WORD_DRIFT = 0.0003f
/** Chance at a meeting that a band takes up a tradition the more successful one holds, at full understanding. */
const val COPY_TRADITION = 0.1f
/** Yearly chance a band takes up a tradition by itself, or drops one. */
const val TAKE_UP = 0.0005f
const val DROP = 0.0005f
/** Chance a band raids one it meets, at full pressure (a feud, hunger with food in the other's larder, or crowded country); share of the larder taken. */
const val RAID = 0.015f
const val RAID_TAKE = 0.5f
/** Share of each side's adults a raid kills at most, against the other's strength. */
const val RAID_DEATHS = 0.08f
/** Grudge held for a raid suffered, and the share that fades a year (half in about seven years). */
const val GRUDGE = 1f
const val GRUDGE_FADE = 0.1f
/** A band's grudge against another's lineage above which it raids it unprovoked. */
const val FEUD = 0.9f
/** Rings of hexes round a meeting whose country bands tell each other of; country further than this from a band's camp is half forgotten each year. */
const val TOLD_RINGS = 4
const val REMEMBERED_KM = 100.0
/** A band fed under this share of its fill with nothing laid by goes to a better-stocked band within this many rings of hexes. */
const val VISIT_FED = 0.6f
const val VISIT_RINGS = 2
/** Adults below which a band joins kin nearby: too few to hunt, gather, guard and marry within. */
const val SMALL_BAND = 8
/** How much a fouled camp puts a band off staying, and how much the graves of its dead hold it. */
const val FOUL_AVOID = 0.3f
const val BURIAL_STAY = 0.1f
/** Most a band of [Tradition.FEASTS] grows to before it splits, against its kind's usual most. */
const val FEAST_SIZE = 1.3f
/** How much likelier a raid is on strangers (never met before), and on bands of wholly other customs. */
const val STRANGER_HOSTILITY = 0.5f
/** How much a band's ornaments per head add to its standing (its ways are the more copied). */
const val STANDING = 0.05f
/** How hard a band presses those it finds on ground it holds (graves, houses, appointment place); how strongly bands keep off others' known homes. */
const val CLAIM_PRESSURE = 0.7f
const val TRESPASS_AVOID = 0.2f
/** How strongly a band on a raid heads for its target. */
const val RAID_PULL = 0.8f
const val CUSTOMS_HOSTILITY = 1f
/** How strongly an unlucky camp (a death, among bands that leave their dead) is shunned, and for how many weeks. */
const val UNLUCKY = 0.6f
const val UNLUCKY_WEEKS = 8
/** Share of its store aim a band holding to demand sharing keeps (what it stores is asked of it). */
const val SHARING_STORE = 0.5f
/** A settled band without feasts to keep the peace splits at this share of its usual most (quarrels). */
const val SETTLED_QUARRELS = 0.75f
/** Graves and meetings at a place (in a band's memory) before it makes an appointment there. */
const val APPOINT_MIN = 3f
/** How strongly an appointment draws a band in its month, and its ancestors' places draw it at any time. */
const val APPOINT_PULL = 0.5f
const val ANCESTOR_PULL = 0.15f
/**
 * Ties between bands: how much closer a peaceful meeting, a gift, a feast and an exchange of
 * partners bring them (of the way left to full friendship), how far a raid sets them back; share
 * of a tie lost a year unless renewed; how much a full friendship lowers the chance of a raid.
 */
const val TIE_MEET = 0.05f
const val TIE_GIFT = 0.2f
const val TIE_FEAST = 0.3f
const val TIE_MARRY = 0.3f
const val TIE_RAID = 0.8f
const val TIE_FADE = 0.5f
const val TIE_PEACE = 0.8f
/** A feast: food set before each guest, kg; days of food a band keeps back for itself beyond its aim before it feasts others. */
const val FEAST_KG = 3f
/** How strongly a band with food to feast others is drawn to where other bands are, and the people there at which the draw is full. */
const val GATHER_PULL = 0.3f
const val GATHER_HEADS = 150f

/** The lineage a band founded at hex centre ([x], [y]) km descends from. */
fun lineageAt(x: Double, y: Double): Int {
    val bx = (x / LINEAGE_KM).toInt(); val by = (y / LINEAGE_KM).toInt()
    return Math.floorMod(bx, 4) + 4 * Math.floorMod(by, 4)
}

/** Words of a band founded at ([x], [y]) km: half named as in its own country, half as in the wider region, so neighbours understand each other in part. */
fun wordsAt(x: Double, y: Double): IntArray {
    val fx = (x / LINEAGE_KM).toInt(); val fy = (y / LINEAGE_KM).toInt()
    val cx = (x / (3 * LINEAGE_KM)).toInt(); val cy = (y / (3 * LINEAGE_KM)).toInt()
    return IntArray(Language.CONCEPTS) { k ->
        val (bx, by) = if (k % 2 == 0) fx to fy else (cx + 1000) to (cy + 1000)
        Math.floorMod((bx * 73_856_093) xor (by * 19_349_663) xor (k * 83_492_791), Language.FORMS)
    }
}

/**
 * The traditions a band founded in a lineage's country starts with. Marrying out, sharing food,
 * caring for the sick and handing down knowledge are near universal among foragers, so every band
 * holds them; the others come about a quarter each, the same for neighbours.
 */
fun traditionsAt(lineage: Int) = BooleanArray(Tradition.entries.size) { t ->
    Tradition.entries[t] in UNIVERSAL || Math.floorMod((lineage + 1) * 2_654_435_761L.toInt() xor (t * 40_503), 100) < 25
}.also { it[Tradition.LEAVE_DEAD.ordinal] = !it[Tradition.BURIAL.ordinal] } // (the old way, where the dead are not kept)

private val UNIVERSAL = setOf(Tradition.EXOGAMY, Tradition.SHARING, Tradition.MEDICINE, Tradition.ORAL_TRADITION)

/** Kinship of two lineage mixes: the chance two people, one from each, share a founding lineage. */
fun kinship(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

/**
 * Two bands meet. If one is hungry and the other well stocked, or one holds a feud against the
 * other's people, it may raid: the less they understand each other, the likelier. Otherwise they
 * talk: they bring a few words into line, the less successful band may take up the other's ways,
 * they show each other tools, and bands that marry out exchange partners, who bring their skills.
 * Returns true if it came to a raid.
 */
internal fun HexGroups.cultureMeets(a: HexGroup, b: HexGroup): Boolean {
    val ba = a.band ?: return false; val bb = b.band ?: return false
    val u = Language.understanding(ba.words, bb.words)
    if (raid(a, ba, b, bb, u) || raid(b, bb, a, ba, u)) return true
    // Meeting in peace makes them a little closer; how close they are colours the rest.
    ba.befriend(b.id, TIE_MEET); bb.befriend(a.id, TIE_MEET)
    val close = max(0f, 0.5f * (ba.tie(b.id) + bb.tie(a.id)))
    // The naming game: the larger band's word tends to win.
    repeat(WORDS_PER_MEETING) {
        val k = rnd.nextInt(Language.CONCEPTS)
        if (ba.words[k] != bb.words[k]) { if (rnd.nextFloat() < a.size.toFloat() / (a.size + b.size)) bb.words[k] = ba.words[k] else ba.words[k] = bb.words[k] }
    }
    // Ways are copied from the band that does better: more food in hand, more children.
    fun doing(g: HexGroup) = g.fed + g.larder / max(1f, g.dailyNeedKg * 60f) + g.young.toFloat() / max(1, g.adults) + STANDING * (g.band?.ornaments ?: 0f) / max(1, g.size)
    val (lo, hi) = if (doing(a) < doing(b)) ba to bb else bb to ba
    for (t in Tradition.entries) if (hi.holds(t) != lo.holds(t) && rnd.nextFloat() < COPY_TRADITION * u) {
        lo.traditions[t.ordinal] = hi.holds(t)
        meetings.merge("band took ${if (hi.holds(t)) "up" else "leave of"} ${t.name.lowercase()}", 1, Int::plus)
    }
    teach(ba, bb, u, (doing(a) - doing(b)).coerceIn(-1f, 1f))
    // Each tells the other of the country it knows round about, as far as they understand each other.
    if (rnd.nextFloat() < u * (0.6f + 0.4f * close)) {
        val h0 = hw.placeHex[a.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..TOLD_RINGS) {
            for (h in ring) { if (ba.knows(h)) bb.learn(h, hex.size); if (bb.knows(h)) ba.learn(h, hex.size) }
            if (r == TOLD_RINGS) break
            val next = ArrayList<Int>(); for (h in ring) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && seen.add(q)) next += q }; ring = next
        }
        meetings.merge("bands told of country", 1, Int::plus)
        // Where they met is a meeting place; and a band without an appointment, or with a weaker one,
        // takes up the other's (the more they are close, the likelier).
        val hMeet = hw.placeHex[a.place]; val wk = (weatherNow.date.absoluteDay / days).toInt(); val sMeet = PlaceMemory.seasonOf(weatherNow.date.month)
        a.memory.mark(hMeet, sMeet, wk, a.memory.met, 1f); b.memory.mark(hMeet, sMeet, wk, b.memory.met, 1f)
        // And where each has its houses, and the homes of others they know of: settled bands can be found.
        if (bb.houseHex >= 0 && bb.house >= 0.2f) { ba.knownHomes[b.id] = bb.houseHex; ba.knownLineage[b.id] = bb.mainLineage() }
        if (ba.houseHex >= 0 && ba.house >= 0.2f) { bb.knownHomes[a.id] = ba.houseHex; bb.knownLineage[a.id] = ba.mainLineage() }
        for ((id, hh) in bb.knownHomes) if (id != a.id && rnd.nextFloat() < u) { ba.knownHomes.putIfAbsent(id, hh); bb.knownLineage[id]?.let { ba.knownLineage.putIfAbsent(id, it) } }
        for ((id, hh) in ba.knownHomes) if (id != b.id && rnd.nextFloat() < u) { bb.knownHomes.putIfAbsent(id, hh); ba.knownLineage[id]?.let { bb.knownLineage.putIfAbsent(id, it) } }
        fun adopt(x: HexGroup, bx: Band, y: HexGroup, by: Band) {
            if (by.appointHex < 0 || by.appointHex == bx.appointHex) return
            val theirs = y.memory.deadAt(by.appointHex) + y.memory.metAt(by.appointHex)
            val ours = if (bx.appointHex >= 0) x.memory.deadAt(bx.appointHex) + x.memory.metAt(bx.appointHex) else 0f
            if (theirs > ours && rnd.nextFloat() < u * (0.3f + 0.7f * max(0f, bx.tie(y.id)))) {
                bx.appointHex = by.appointHex; bx.appointMonth = by.appointMonth
                meetings.merge("bands took up another's appointment", 1, Int::plus)
            }
        }
        adopt(a, ba, b, bb); adopt(b, bb, a, ba)
        // And of the places they remember, as far as they trust each other.
        val week = (weatherNow.date.absoluteDay / days).toInt()
        val trust = (0.5f + 0.5f * max(0f, ba.tie(b.id))) * u
        a.memory.hear(b.memory, week, trust); b.memory.hear(a.memory, week, trust)
    }
    // Partners: where either holds to marrying out, young adults change bands, both ways.
    val marry = (if (ba.holds(Tradition.EXOGAMY)) 0.5f else 0f) + (if (bb.holds(Tradition.EXOGAMY)) 0.5f else 0f)
    if (a.adults >= 2 && b.adults >= 2 && rnd.nextFloat() < MARRY * marry * (0.5f + close)) {
        marryOut(a, ba, b, bb)
        ba.befriend(b.id, TIE_MARRY); bb.befriend(a.id, TIE_MARRY)
        meetings.merge("bands exchanged partners", 1, Int::plus)
    }
    return false
}

/** [g] raids [o] if it is hungry and [o] has food, or holds a feud against its people. Returns whether it did. */
private fun HexGroups.raid(g: HexGroup, b: Band, o: HexGroup, ob: Band, understood: Float): Boolean {
    // What drives a raid: an old feud, or competition (hunger with food in the other's larder, or too
    // many people on the same country); kin and those who understand each other fight less.
    val feud = b.grudge[ob.mainLineage()] > FEUD
    val hunger = if (o.larder > 10f * o.dailyNeedKg) (1f - g.fed) else 0f
    // (On ground it holds as its own, a band drives off those it is not close to: trespass.)
    val trespass = if (b.claims(hw.placeHex[g.place], g.memory) && b.tie(o.id) <= 0f) CLAIM_PRESSURE else 0f
    val pressure = if (feud) 1f else maxOf(hunger, min(1f, peopleCrowding(g)), trespass)
    // Strangers (never met before) and those of other customs are easier to fight.
    val stranger = if (o.id !in b.ties) STRANGER_HOSTILITY else 0f
    val otherWays = Tradition.entries.count { b.holds(it) != ob.holds(it) }.toFloat() / Tradition.entries.size
    val chance = RAID * pressure * (0.5f + 0.5f * (1f - understood)) * (1f - 0.5f * kinship(b.lineage, ob.lineage)) * (1f - TIE_PEACE * max(0f, b.tie(o.id))) *
        (1f + stranger + CUSTOMS_HOSTILITY * otherWays)
    if (rnd.nextFloat() >= chance) return false
    fight(g, b, o, ob, planned = false)
    return true
}

/**
 * [g] falls on [o]. A planned raid on a band at home may take it by surprise, the less so the more
 * it sees (scouts, open ground, moonlight); the defenders gain from high, broken ground, their
 * houses and a palisade. A band with no home to hold may slip away, losing what it cannot carry.
 */
internal fun HexGroups.fight(g: HexGroup, b: Band, o: HexGroup, ob: Band, planned: Boolean) {
    meetings.merge(if (planned) "raids on homes" else "raids", 1, Int::plus)
    note(g) { "raided band #%d%s".format(o.id, if (planned) " at its home" else "") }; note(o) { "was raided by band #%d".format(g.id) }
    ob.sinceRaided = 0
    // The mobile slip away (and lose some of their stores).
    if (!ob.atHome && rnd.nextFloat() < FLEE) {
        val t = 0.3f * RAID_TAKE * o.larder; o.larder -= t; g.larder += t
        meetings.merge("raided bands slipped away", 1, Int::plus)
        ob.grudge[b.mainLineage()] += 0.5f * GRUDGE; ob.befriend(g.id, -0.5f * TIE_RAID)
        return
    }
    val p = o.place
    // Surprise: a planned raid at night, in cover, on a band that keeps no lookout.
    val seen = (sight(p) * (0.5f + 0.5f * moon) * (1f + 5f * ob.scoutShare)).coerceIn(0f, 1f)
    val surprise = if (planned) 1f + SURPRISE_RAID * (1f - seen) else 1f
    // The defenders' ground and works.
    val ground = 1f + HIGH_GROUND * land.bestDefence[p]
    val works = if (ob.atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val sg = strength(g) * surprise; val so = strength(o) * ground * works
    // Each side loses some of its people, the more so against the stronger side.
    val lostG = binomial(g.adults, RAID_DEATHS * so / (sg + so))
    val lostO = binomial(o.adults, RAID_DEATHS * sg / (sg + so))
    for ((who, lost) in listOf(g to lostG, o to lostO)) if (lost > 0) {
        val wb = who.band!!
        wb.elders = (wb.elders - (lost * wb.elders + who.adults / 2) / who.adults.coerceAtLeast(1)).coerceAtLeast(0)
        who.adults -= lost
        wb.elders = min(wb.elders, who.adults)
        deaths.merge("${who.species.name}:raids", lost, Int::plus)
        small.carrion[who.place] += lost * who.species.massKg / (land.areaKm2[who.place] * 1e6f)
    }
    // The stronger side carries off food; the raided remember.
    if (sg > so) { val t = RAID_TAKE * o.larder; o.larder -= t; g.larder += t; meetings.merge("larders raided", 1, Int::plus) }
    ob.grudge[b.mainLineage()] += GRUDGE
    ob.befriend(g.id, -TIE_RAID); b.befriend(o.id, -0.3f * TIE_RAID)
    if (lostG > 0) b.grudge[ob.mainLineage()] += 0.3f * GRUDGE
}

/** One or two young adults from each band join the other; their descent and skills come with them. */
private fun HexGroups.marryOut(a: HexGroup, ba: Band, b: HexGroup, bb: Band) {
    val sa = min(2, a.adults / 4).coerceAtLeast(1).toFloat() / a.adults
    val sb = min(2, b.adults / 4).coerceAtLeast(1).toFloat() / b.adults
    val kin = kinship(ba.lineage, bb.lineage)
    // The children of a partner from outside are less inbred: as kin as the two bands' shared descent allows.
    val fa = ba.inbred; val fb = bb.inbred
    ba.inbred = (1f - sa) * fa + sa * OUTSIDER_KIN * kin
    bb.inbred = (1f - sb) * fb + sb * OUTSIDER_KIN * kin
    val la = ba.lineage.copyOf()
    for (i in la.indices) { ba.lineage[i] += sa * (bb.lineage[i] - la[i]); bb.lineage[i] += sb * (la[i] - bb.lineage[i]) }
    // Partners bring what they know how to do.
    for (i in ba.plants.indices) { val x = ba.plants[i]; ba.plants[i] = max(x, x + sa * (bb.plants[i] - x)); bb.plants[i] = max(bb.plants[i], bb.plants[i] + sb * (x - bb.plants[i])) }
    // (Each partner holds the skills its band's people do, as many as hold them; it brings them along,
    // a little short of the best it learned from, and its old band loses them.)
    val na = sa * a.adults; val nb = sb * b.adults
    val fromA = FloatArray(ba.holders.size) { i -> if (ba.holders[i] > 0f) min(na, na * ba.holders[i] / a.adults) else 0f }
    val fromB = FloatArray(bb.holders.size) { i -> if (bb.holders[i] > 0f) min(nb, nb * bb.holders[i] / b.adults) else 0f }
    for (i in ba.skills.indices) {
        val la = ba.skills[i]; val lb = bb.skills[i]
        if (fromB[i] > 0f) { if (!ba.knowsSkill[i]) { ba.knowsSkill[i] = true; ba.skills[i] = 0f; ba.lastUsed[i] = ba.weeks }; ba.skills[i] = max(ba.skills[i], lb - TRANSMIT_LOSS); ba.holders[i] = max(0f, ba.holders[i]) + fromB[i] }
        if (fromA[i] > 0f) { if (!bb.knowsSkill[i]) { bb.knowsSkill[i] = true; bb.skills[i] = 0f; bb.lastUsed[i] = bb.weeks }; bb.skills[i] = max(bb.skills[i], la - TRANSMIT_LOSS); bb.holders[i] = max(0f, bb.holders[i]) + fromA[i] }
        if (ba.holders[i] > 0f) ba.holders[i] = max(0f, ba.holders[i] - fromA[i])
        if (bb.holders[i] > 0f) bb.holders[i] = max(0f, bb.holders[i] - fromB[i])
    }
    // Kin by marriage forgive.
    ba.grudge[bb.mainLineage()] *= 0.5f; bb.grudge[ba.mainLineage()] *= 0.5f
}

/**
 * A band that went hungry this week and has nothing laid by goes to a band within a day or two's
 * walk that has, and is fed there: foragers rarely starve while their neighbours eat, because food
 * is shared on demand and kin are visited in lean times (and the debt is remembered). The hungry
 * band eats what makes up its week, and takes some home; a band that holds to sharing gives more.
 */
internal fun HexGroups.visitKin() {
    val hungry = groups.filter { it.alive && it.band != null && it.fed < VISIT_FED && it.larder < it.dailyNeedKg * days }
    if (hungry.isEmpty()) return
    val byHex = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null && g.larder > 0f) byHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in hungry) {
        // The best-stocked band within two hexes, the nearer the better.
        var best: HexGroup? = null; var bestScore = 0f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in byHex[h].orEmpty()) if (o !== g) { val sc = o.larder / (1f + r); if (sc > bestScore) { bestScore = sc; best = o } }
            if (r == VISIT_RINGS) break
            val next = ArrayList<Int>()
            for (h in ring) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && seen.add(q)) next += q }
            ring = next
        }
        val o = best ?: continue
        val share = BAND_GIFT * (if (o.band!!.holds(Tradition.SHARING)) 2f else 1f)
        val week = g.dailyNeedKg * days
        val gift = min(share * o.larder, (1f - g.fed) * week + week)
        o.larder -= gift
        val eat = min(gift, (1f - g.fed) * week)
        g.fed += eat / week; g.larder += gift - eat
        // Who was helped remembers: grudges against the helpers' people fade.
        g.band!!.grudge[o.band!!.mainLineage()] *= 0.5f
        meetings.merge("hungry bands fed by kin", 1, Int::plus)
    }
}

/** A band's year, a week at a time: inbreeding builds in a closed band, grudges fade, words drift, traditions are now and then taken up or dropped. */
internal fun HexGroups.cultureWeek(g: HexGroup) {
    val b = g.band ?: return
    // Once a year, a band without an appointment makes one, where its dead lie or where it has met
    // most bands, in the season it fared best there.
    if (b.appointHex < 0 && rnd.nextFloat() < days / Date.DAYS_PER_YEAR.toFloat() && (b.holds(Tradition.BURIAL) || b.holds(Tradition.FEASTS))) {
        val mem = g.memory
        var best = -1; var bestScore = 0f
        for (i in 0 until PlaceMemory.MEMORY_SLOTS) {
            if (mem.hex[i] < 0) continue
            val sc = mem.dead[i] + 0.5f * mem.met[i] + mem.fed[i]
            if (mem.dead[i] + mem.met[i] >= APPOINT_MIN && sc > bestScore) { bestScore = sc; best = i }
        }
        if (best >= 0) { b.appointHex = mem.hex[best]; b.appointMonth = mem.season[best] * 3 + 1; meetings.merge("bands made an appointment", 1, Int::plus) }
    }
    // Ties fade unless renewed.
    if (b.ties.isNotEmpty()) {
        val keep = 1f - TIE_FADE * days / Date.DAYS_PER_YEAR.toFloat()
        val it = b.ties.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.setValue(e.value * keep); if (kotlin.math.abs(e.value) < 0.01f) it.remove() }
    }
    // Country far from where it now lives fades from memory, a share of it a year.
    if (rnd.nextFloat() < days / Date.DAYS_PER_YEAR.toFloat()) {
        val here = hw.placeHex[g.place]
        for (w in b.known.indices) { var bits = b.known[w]; while (bits != 0L) { val i = java.lang.Long.numberOfTrailingZeros(bits); bits = bits and (bits - 1)
            val h = w * 64 + i
            if (hex.distanceKm(h, here) > REMEMBERED_KM && rnd.nextFloat() < 0.5f) b.known[w] = b.known[w] and (1L shl i).inv() } }
    }
    val years = days / Date.DAYS_PER_YEAR.toFloat()
    b.inbred += (1f - b.inbred) / (2f * max(2, g.adults)) * years / GENERATION_YEARS
    for (i in b.grudge.indices) b.grudge[i] *= 1f - GRUDGE_FADE * years
    if (rnd.nextFloat() < WORD_DRIFT * Language.CONCEPTS * years) b.words[rnd.nextInt(Language.CONCEPTS)] = rnd.nextInt(Language.FORMS)
    for (t in Tradition.entries) {
        val held = b.holds(t)
        if (rnd.nextFloat() < (if (held) DROP else TAKE_UP) * years) { b.traditions[t.ordinal] = !held; meetings.merge("band ${if (held) "dropped" else "took up"} ${t.name.lowercase()}", 1, Int::plus) }
    }
}
