package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.Tuning
import inflation.Config

import inflation.world.Biome
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.exp

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
/**
 * Share of its hands' week a band gives to leisure at which visits keep a tie with one friendly band near most of the way
 * from fading (1 - 1/e): visits to more bands share the same leisure. Foragers spend much of their free time visiting
 * other camps (Lee 1979, Wiessner on hxaro); the rate is an estimate, no time-use figure found.
 */
const val VISIT_SHARE = 0.18f
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
fun traditionsAt(lineage: Int) = EnumBools<Tradition>(BooleanArray(Tradition.entries.size) { t ->
    Tradition.entries[t] in UNIVERSAL || Math.floorMod((lineage + 1) * 2_654_435_761L.toInt() xor (t * 40_503), 100) < 25
}.also { it[Tradition.LEAVE_DEAD.ordinal] = !it[Tradition.BURIAL.ordinal] }) // (the old way, where the dead are not kept)

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
internal fun HexGroups.meetingEndsInRaid(a: HexGroup, b: HexGroup, weather: Weather): Boolean {
    val bandA = a.band ?: return false; val bandB = b.band ?: return false
    val u = Language.understanding(bandA.words, bandB.words)
    if (raid(a, bandA, b, bandB, u, weather) || raid(b, bandB, a, bandA, u, weather)) return true
    // A band that sees sickness among the others may keep away: no talk, no partners, no feast.
    fun showsSickness(x: Band) = x.sick.sum() > SICK_SHOWS
    fun shuns(x: Band) = drawn(DialSite.SHUN_SICK, x.will[BandWay.SHUNNING_THE_SICK], rnd.nextFloat(), x.shunSick) { 0.5f }
    if ((showsSickness(bandB) && shuns(bandA)) || (showsSickness(bandA) && shuns(bandB))) {
        stats.meeting(Met.BANDS_KEPT_AWAY_FROM_THE_SICK)
        return false
    }
    // Meeting in peace makes them a little closer; how close they are colours the rest.
    bandA.befriend(b.id, TIE_MEET); bandB.befriend(a.id, TIE_MEET)
    val close = max(0f, 0.5f * (bandA.tie(b.id) + bandB.tie(a.id)))
    // The naming game: the larger band's word tends to win.
    repeat(WORDS_PER_MEETING) {
        val k = rnd.nextInt(Language.CONCEPTS)
        if (bandA.words[k] != bandB.words[k]) {
            if (rnd.nextFloat() < a.size.toFloat() / (a.size + b.size)) bandB.words[k] = bandA.words[k] else bandA.words[k] = bandB.words[k]
        }
    }
    // Ways are copied from the band that does better: more food in hand, more children.
    fun doing(g: HexGroup) = g.wellOff() + g.young.toFloat() / max(1, g.adults) +
        STANDING * (g.band?.ornaments ?: 0f) / max(1, g.size) + RENOWN * (g.band?.renown ?: 0f)
    val (lo, hi) = if (doing(a) < doing(b)) bandA to bandB else bandB to bandA
    for (t in Tradition.entries) if (hi.holds(t) != lo.holds(t) && rnd.nextFloat() < COPY_TRADITION * u) {
        lo.traditions[t] = hi.holds(t)
        stats.meeting(if (hi.holds(t)) Met.Of.TOOK_UP else Met.Of.TOOK_LEAVE_OF, t.name.lowercase())
    }
    riteMeet(lo, hi, u)
    teach(bandA, bandB, u, (doing(a) - doing(b)).coerceIn(-1f, 1f))
    // And the sick among them pass on what is catching.
    passIllness(bandA, bandB)
    // Each tells the other of the country it knows round about, as far as they understand each other.
    if (rnd.nextFloat() < u * (0.6f + 0.4f * close)) tellOfCountry(a, bandA, b, bandB, u, weather)
    // Partners: where either holds to marrying out, young adults change bands, both ways.
    val marry = (if (bandA.holds(Tradition.EXOGAMY)) 0.5f else 0f) + (if (bandB.holds(Tradition.EXOGAMY)) 0.5f else 0f)
    if (a.adults >= 2 && b.adults >= 2 && rnd.nextFloat() < MARRY * marry * (0.5f + close)) {
        marryOut(a, bandA, b, bandB)
        bandA.befriend(b.id, TIE_MARRY); bandB.befriend(a.id, TIE_MARRY)
        stats.meeting(Met.BANDS_EXCHANGED_PARTNERS)
    }
    return false
}

/**
 * Bands [a] and [b] that understand each other as far as [u] tell each other of the country round about,
 * the routes they know, where they met, where their homes and those of others they know are, the
 * appointments they keep, and the places they remember (as far as they trust each other).
 */
private fun HexGroups.tellOfCountry(a: HexGroup, bandA: Band, b: HexGroup, bandB: Band, u: Float, weather: Weather) {
    val h0 = hw.placeHex[a.place]
    var ring = listOf(h0); val seen = hashSetOf(h0)
    for (r in 0..TOLD_RINGS) {
        for (h in ring) { if (bandA.knows(h)) bandB.learn(h, hex.size); if (bandB.knows(h)) bandA.learn(h, hex.size) }
        if (r == TOLD_RINGS) break
        ring = hex.nextRing(ring, seen)
    }
    stats.meeting(Met.BANDS_TOLD_OF_COUNTRY)
    // And of the ways they know further off: landmarks, and the legs between those they know.
    val toldWays = routes.tell(bandA, bandB, h0) + routes.tell(bandB, bandA, h0)
    if (toldWays > 0) stats.meeting(Met.BANDS_TOLD_OF_ROUTES)
    // Where they met is a meeting place; and a band without an appointment, or with a weaker one,
    // takes up the other's (the more they are close, the likelier).
    val wk = stepOf(weather.date); val sMeet = PlaceMemory.seasonOf(weather.date.month)
    a.memory.mark(h0, sMeet, wk, stepsPerYear, a.memory.met, 1f); b.memory.mark(h0, sMeet, wk, stepsPerYear, b.memory.met, 1f)
    // And where each has its houses, and the homes of others they know of: settled bands can be found.
    fun showsHome(teller: HexGroup, bt: Band, hearer: Band) {
        if (bt.houseHex.none || bt.house < Thresholds.HOUSE_STANDING) return
        hearer.knownHomes[teller.id] = bt.houseHex.i
        hearer.knownLineage[teller.id] = bt.mainLineage()
    }
    fun tellsHomes(bt: Band, hearer: HexGroup, bh: Band) {
        for ((id, hh) in bt.knownHomes) if (id != hearer.id && rnd.nextFloat() < u) {
            bh.knownHomes.putIfAbsent(id, hh)
            bt.knownLineage[id]?.let { bh.knownLineage.putIfAbsent(id, it) }
        }
    }
    showsHome(b, bandB, bandA); showsHome(a, bandA, bandB)
    tellsHomes(bandB, a, bandA); tellsHomes(bandA, b, bandB)
    fun adopt(x: HexGroup, bx: Band, y: HexGroup, by: Band) {
        if (by.appointHex.none || by.appointHex == bx.appointHex) return
        val theirs = y.memory.deadAt(by.appointHex) + y.memory.metAt(by.appointHex)
        val ours = if (!bx.appointHex.none) x.memory.deadAt(bx.appointHex) + x.memory.metAt(bx.appointHex) else 0f
        if (theirs > ours && rnd.nextFloat() < u * (0.3f + 0.7f * max(0f, bx.tie(y.id)))) {
            bx.appointment = by.appointment
            stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_APPOINTMENT)
        }
    }
    adopt(a, bandA, b, bandB); adopt(b, bandB, a, bandA)
    // And of the places they remember, as far as they trust each other.
    val trust = (0.5f + 0.5f * max(0f, bandA.tie(b.id))) * u
    a.memory.hear(b.memory, wk, stepsPerYear, trust); b.memory.hear(a.memory, wk, stepsPerYear, trust)
}

/** [g] raids [o] if it is hungry and [o] has food, or holds a feud against its people. Returns whether it did. */
private fun HexGroups.raid(g: HexGroup, b: Band, o: HexGroup, ob: Band, understood: Float, weather: Weather): Boolean {
    // What drives a raid: an old feud, or competition (hunger with food in the other's larder, or too
    // many people on the same country); kin and those who understand each other fight less.
    val feud = b.grudge[ob.mainLineage()] > FEUD
    val hunger = if (o.larder > 10f * o.dailyNeedKg) (1f - g.fed) else 0f
    // (On ground it holds as its own, a band drives off those it is not close to: trespass.)
    val trespass = if (b.claims(hw.hexOf(g.place), g.memory) && b.tie(o.id) <= 0f) CLAIM_PRESSURE else 0f
    val pressure = if (feud) 1f else maxOf(hunger, min(1f, peopleCrowding(g)), trespass)
    // Strangers (never met before) and those of other customs are easier to fight.
    val stranger = if (o.id !in b.ties) STRANGER_HOSTILITY else 0f
    val otherWays = Tradition.entries.count { b.holds(it) != ob.holds(it) }.toFloat() / Tradition.entries.size
    // (Whether it comes to blows is the band's choice: its readiness to raid.)
    val chance = RAID * b.raidWill * pressure * (0.5f + 0.5f * (1f - understood)) * (1f - 0.5f * kinship(b.lineage, ob.lineage)) *
        (1f - TIE_PEACE * max(0f, b.tie(o.id))) *
        (1f + stranger + CUSTOMS_HOSTILITY * otherWays)
    if (!drawn(DialSite.RAIDS_MEETING, b.raidWill, rnd.nextFloat(), chance) { chance / b.raidWill.coerceAtLeast(1e-6f) }) return false
    fight(g, b, o, ob, planned = false, weather)
    return true
}

/**
 * [g] falls on [o]. A planned raid on a band at home may take it by surprise, the less so the more
 * it sees (scouts, open ground, moonlight); the defenders gain from high, broken ground, their
 * houses and a palisade. A band with no home to hold may slip away, losing what it cannot carry.
 */
internal fun HexGroups.fight(g: HexGroup, b: Band, o: HexGroup, ob: Band, planned: Boolean, weather: Weather) {
    stats.meeting(if (planned) Met.RAIDS_ON_HOMES else Met.RAIDS)
    note(g) { "raided band #%d%s".format(o.id, if (planned) " at its home" else "") }; note(o) { "was raided by band #%d".format(g.id) }
    ob.sinceRaided = 0
    // The mobile slip away (and lose some of their stores).
    if (!ob.atHome && rnd.nextFloat() < FLEE) {
        val t = 0.3f * RAID_TAKE * o.larder; moveFood(o, g, t)
        stats.meeting(Met.RAIDED_BANDS_SLIPPED_AWAY)
        ob.grudge[b.mainLineage()] += 0.5f * GRUDGE; ob.befriend(g.id, -0.5f * TIE_RAID)
        return
    }
    val (sg, so) = raidStrengths(g, o, ob, planned, ob.atHome, weather)
    raidWorth(o, ob, sg, so)
    // Each side loses some of its people, the more so against the stronger side.
    val lostG = binomial(g.adults, RAID_DEATHS * so / (sg + so), rnd)
    val lostO = binomial(o.adults, RAID_DEATHS * sg / (sg + so), rnd)
    for ((who, lost) in listOf(g to lostG, o to lostO)) if (lost > 0) {
        adultsDied(who, lost, rnd)
        // (And as many again are wounded, some to be lamed for life.)
        who.band?.wounded(WOUNDED_PER_DEAD * lost, who.adults)
        stats.deaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        small.deposit(DeadPool.CARRION, who.place, Kg(lost * who.species.massKg))
    }
    // The stronger side carries off food; the raided remember.
    if (sg > so) { val t = RAID_TAKE * o.larder; moveFood(o, g, t); stats.meeting(Met.LARDERS_RAIDED) }
    ob.grudge[b.mainLineage()] += GRUDGE
    ob.befriend(g.id, -TIE_RAID); b.befriend(o.id, -0.3f * TIE_RAID)
    if (lostG > 0) b.grudge[ob.mainLineage()] += 0.3f * GRUDGE
}

/**
 * What a whole house would have done for [o] (band [ob]) in a raid of strength [sg] on its [so], against the houses it has:
 * the lives it would have saved and the food it would have kept from the raiders, by [fight]'s rules; for [Band.houseLives]
 * and [Band.houseFood].
 */
private fun raidWorth(o: HexGroup, ob: Band, sg: Float, so: Float) {
    val works = if (ob.atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val whole = 1f + HOUSE_DEFENCE + (if (ob.atHome) PALISADE_DEFENCE * ob.palisade else 0f)
    val so1 = so * whole / works
    ob.houseLivesWeek += max(0f, o.adults * RAID_DEATHS * (sg / (sg + so) - sg / (sg + so1)))
    if (sg > so && sg <= so1) ob.houseFoodWeek += RAID_TAKE * o.larder
}

/**
 * The raiders' [g] and the defenders' [o] (band [ob]) strengths when [g] falls on [o] where it is: a planned raid may
 * take it by surprise, the less so the more it sees (scouts, open ground, moonlight); defenders [atHome] gain from high,
 * broken ground, their houses and a palisade. The one rule for a raid ([fight]) and for a raiding band weighing its
 * target ([plotRaid]).
 */
internal fun HexGroups.raidStrengths(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather): Pair<Float, Float> {
    val p = o.place
    // Surprise: a planned raid at night, in cover, on a band that keeps no lookout.
    val seen = (sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * ob.scoutShare)).coerceIn(0f, 1f)
    if (planned) dialUse?.amount(DialSite.SCOUTS_SEEN, ob.will[BandWay.SCOUTS], seen, (sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * SCOUT_SHARE)).coerceIn(0f, 1f),
        cut = sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * ob.scoutShare) > 1f)
    val surprise = if (planned) 1f + SURPRISE_RAID * (1f - seen) else 1f
    // The defenders' ground and works.
    val ground = 1f + HIGH_GROUND * land.bestDefence[p]
    val works = if (atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    return strength(g) * surprise to strength(o) * ground * works
}

/** One or two young adults from each band join the other; their descent and skills come with them. */
private fun HexGroups.marryOut(a: HexGroup, bandA: Band, b: HexGroup, bandB: Band) {
    val sa = min(2, a.adults / 4).coerceAtLeast(1).toFloat() / a.adults
    val sb = min(2, b.adults / 4).coerceAtLeast(1).toFloat() / b.adults
    val kin = kinship(bandA.lineage, bandB.lineage)
    // The children of a partner from outside are less inbred: as kin as the two bands' shared descent allows.
    val fa = bandA.inbred; val fb = bandB.inbred
    bandA.inbred = (1f - sa) * fa + sa * OUTSIDER_KIN * kin
    bandB.inbred = (1f - sb) * fb + sb * OUTSIDER_KIN * kin
    val la = bandA.lineage.copyOf()
    for (i in la.indices) { bandA.lineage[i] += sa * (bandB.lineage[i] - la[i]); bandB.lineage[i] += sb * (la[i] - bandB.lineage[i]) }
    // Partners bring what they know how to do.
    for (i in Biome.entries) {
        val x = bandA.plants[i]
        bandA.plants[i] = max(x, x + sa * (bandB.plants[i] - x))
        bandB.plants[i] = max(bandB.plants[i], bandB.plants[i] + sb * (x - bandB.plants[i]))
    }
    // (Each partner holds the skills its band's people do, as many as hold them; it brings them along,
    // a little short of the best it learned from, and its old band loses them.)
    val na = sa * a.adults; val nb = sb * b.adults
    val fromA = enumFloats<Skill> { i -> if (bandA.holders[i] > 0f) min(na, na * bandA.holders[i] / a.adults) else 0f }
    val fromB = enumFloats<Skill> { i -> if (bandB.holders[i] > 0f) min(nb, nb * bandB.holders[i] / b.adults) else 0f }
    for (i in Skill.entries) {
        val la = bandA.skills[i]; val lb = bandB.skills[i]
        if (fromB[i] > 0f) {
            if (!bandA.knowsSkill[i]) {
                bandA.knowsSkill[i] = true
                bandA.skills[i] = 0f
                bandA.lastUsed[i] = bandA.weeks
            }
            bandA.skills[i] = max(bandA.skills[i], lb - TRANSMIT_LOSS)
            bandA.holders[i] = max(0f, bandA.holders[i]) + fromB[i]
        }
        if (fromA[i] > 0f) {
            if (!bandB.knowsSkill[i]) {
                bandB.knowsSkill[i] = true
                bandB.skills[i] = 0f
                bandB.lastUsed[i] = bandB.weeks
            }
            bandB.skills[i] = max(bandB.skills[i], la - TRANSMIT_LOSS)
            bandB.holders[i] = max(0f, bandB.holders[i]) + fromA[i]
        }
        if (bandA.holders[i] > 0f) bandA.holders[i] = max(0f, bandA.holders[i] - fromA[i])
        if (bandB.holders[i] > 0f) bandB.holders[i] = max(0f, bandB.holders[i] - fromB[i])
    }
    // Kin by marriage forgive.
    bandA.grudge[bandB.mainLineage()] *= 0.5f; bandB.grudge[bandA.mainLineage()] *= 0.5f
}

/**
 * A band that went hungry this week and has nothing laid by goes to a band within a day or two's
 * walk that has, and is fed there: foragers rarely starve while their neighbours eat, because food
 * is shared on demand and kin are visited in lean times (and the debt is remembered). The hungry
 * band goes where it can hope for most, a band that owes it favours before one that merely has food;
 * it eats what makes up its week, and takes some home; a band that holds to sharing gives more.
 */
internal fun HexGroups.visitKin(weather: Weather) {
    // (The hungry come in a drawn order, not by age in the list: the first take the most from the best-stocked.)
    val today = weather.date.absoluteDay
    val hungry = groups.filter { it.alive && it.band != null && it.fed < VISIT_FED && it.larder < it.dailyNeedKg * days }.sortedBy { tieBreak(it.id, 0, today) }
    if (hungry.isEmpty()) return
    val byHex = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null && g.larder > 0f) byHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in hungry) {
        // The best-stocked band within two hexes, the nearer the better.
        var best: HexGroup? = null; var bestScore = 0f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in byHex[h].orEmpty()) if (o !== g) {
                // (What it can hope for there: an ordinary share of the larder, or what the band owes it.)
                val owed = min(0.9f * o.larder, o.band!!.owesTo(g.id) * FAVOUR_DAYS * g.dailyNeedKg)
                val score = max(BAND_GIFT * o.larder, owed) / (1f + r)
                if (score > bestScore) {
                    bestScore = score
                    best = o
                }
            }
            if (r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        val o = best ?: continue
        // (How much the band visited gives is its choice; and it owes the favours it owes.)
        val share = min(0.9f, BAND_GIFT * (if (o.band!!.holds(Tradition.SHARING)) 2f else 1f) * o.band!!.giveWill)
        o.band?.let { ob -> val base = BAND_GIFT * (if (ob.holds(Tradition.SHARING)) 2f else 1f)
            dialUse?.amount(DialSite.GIVE_VISIT, ob.giveWill, share, min(0.9f, base), cut = base * ob.giveWill > 0.9f) }
        val week = g.dailyNeedKg * days
        val need = (1f - g.fed) * week + week
        val gift = given(o, g, min(share * o.larder, need), need)
        if (gift <= 0f) continue
        val eat = min(gift, (1f - g.fed) * week)
        g.fed += eat / week
        moveFood(o, g, gift); val ateKg = eatStores(g, eat)
        land.books?.flow(inflation.Books.Stuff.MATTER, "eaten", -ateKg.toDouble())
        // Who was helped remembers: grudges against the helpers' people fade, and it owes them a favour.
        g.band!!.grudge[o.band!!.mainLineage()] *= 0.5f
        helped(o, g, gift)
        stats.meeting(Met.HUNGRY_BANDS_FED_BY_KIN)
    }
}

/** A band's year, a week at a time: inbreeding builds in a closed band, grudges fade, words drift, traditions are now and then taken up or dropped. */
internal fun HexGroups.cultureWeek(g: HexGroup) {
    val b = g.band ?: return
    // A home it knows of that it passed this week and found no house standing (its people dead, or
    // moved on) is forgotten: it is no longer ground to keep off, nor a place to raid.
    if (b.knownHomes.isNotEmpty()) {
        val it = b.knownHomes.entries.iterator()
        while (it.hasNext()) { val (id, hh) = it.next(); if (hh in g.path && houseOf[id] != hh) { it.remove(); b.knownLineage.remove(id) } }
    }
    // Once a year, a band without an appointment makes one, where its dead lie or where it has met
    // most bands, in the season it fared best there.
    if (b.appointHex.none &&
        drawn(DialSite.APPOINTMENTS, b.appointWill, rnd.nextFloat(), years * b.byCustom(b.holds(Tradition.BURIAL) || b.holds(Tradition.FEASTS), b.appointWill),
            cut = b.byCustom(b.holds(Tradition.BURIAL) || b.holds(Tradition.FEASTS), b.appointWill) == 0f && b.appointWill > 0f)
            { years * b.byCustom(b.holds(Tradition.BURIAL) || b.holds(Tradition.FEASTS), 1f) }) {
        val mem = g.memory
        var best = -1; var bestScore = 0f
        for (i in 0 until PlaceMemory.MEMORY_SLOTS) {
            if (mem.hex[i] < 0) continue
            val score = mem.dead[i] + 0.5f * mem.met[i] + mem.fed[i]
            if (mem.dead[i] + mem.met[i] >= APPOINT_MIN && score > bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) {
            b.appointment = Appointment(Hex(mem.hex[best]), mem.season[best] * 3 + 1)
            stats.meeting(Met.BANDS_MADE_AN_APPOINTMENT)
        }
    }
    // Ties fade unless renewed: by visits to the friendly bands within a day or two's walk, in the band's leisure.
    if (b.ties.isNotEmpty()) {
        val fade = TIE_FADE * days / Date.DAYS_PER_YEAR.toFloat()
        val here = hw.placeHex[g.place]
        val near = HashSet<Int>()
        for ((k, v) in b.ties) if (v > 0f) bandHex[k]?.let { h -> if (hexKm(here, h) <= VISIT_RINGS * hex.hexKm) near += k }
        val leisure = b.spareDays / (forageHands(g) * days).toFloat()
        val visited = if (near.isEmpty()) 0f else 1f - exp(-leisure / (VISIT_SHARE * near.size))
        if (near.isNotEmpty()) stats.meeting(Met.BANDS_VISITED_FRIENDS)
        val it = b.ties.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.setValue(e.value * (1f - fade * (if (e.key in near) 1f - visited else 1f))); if (abs(e.value) < 0.01f) it.remove() }
    }
    // Country far from where it now lives fades from memory, a share of it a year.
    if (rnd.nextFloat() < years) {
        val here = hw.placeHex[g.place]
        for (w in b.known.indices) { var bits = b.known[w]; while (bits != 0L) { val i = java.lang.Long.numberOfTrailingZeros(bits); bits = bits and (bits - 1)
            val h = w * 64 + i
            if (hex.distanceKm(h, here) > REMEMBERED_KM && rnd.nextFloat() < 0.5f * routes.forgetShare(h)) b.known[w] = b.known[w] and (1L shl i).inv() } }
    }
    b.inbred += (1f - b.inbred) / (2f * max(2, g.adults)) * years / GENERATION_YEARS
    for (i in b.grudge.indices) b.grudge[i] *= 1f - GRUDGE_FADE * years
    if (rnd.nextFloat() < WORD_DRIFT * Language.CONCEPTS * years) b.words[rnd.nextInt(Language.CONCEPTS)] = rnd.nextInt(Language.FORMS)
    for (t in Tradition.entries) {
        val held = b.holds(t)
        if (rnd.nextFloat() < (if (held) DROP else TAKE_UP) * years) {
            b.traditions[t] = !held
            stats.meeting(if (held) Met.Of.DROPPED else Met.Of.TOOK_UP, t.name.lowercase())
        }
    }
}

/** Most months of the year a band's rites take it somewhere ([Band.rite]). */
const val RITE_SITES = 3
/** How much a band's hold on its rites changes when it splits: a fifth of the founders' range, as temperament drifts. Not measured. */
internal val HexGroups.riteDrift get() = tuning.riteFounderMax / 5f
/** Chance at a split that one of the offshoot's rites shifts by a month (the telling changes). Not measured. */
const val RITE_SHIFT = 0.1f

/** The founders' hold on their rites in lineage [lineage] (the same for every band of it, as [traditionsAt]). */
fun riteStrengthAt(lineage: Int, tuning: Tuning) = tuning.riteFounderMax * Math.floorMod((lineage + 7) * 2_654_435_761L.toInt() xor 0x5bd1e995, 1000) / 1000f

/**
 * A band's rites through the year (rounds on, see [ROUNDS]): about once a year a band whose rites
 * have room for another takes up one where its story is: the graves of its dead, where bands gather
 * (both in the season they mark), or a far landmark it knows, walked to or told of (a pass, a spring,
 * a path's end: the further, the more it stands for); and now and then it lets one go, less often
 * where songs and stories keep them. Nothing here weighs what a rite brings.
 */
internal fun HexGroups.riteWeek(g: HexGroup, weather: Weather) {
    if (!ROUNDS) return
    val b = g.band ?: return
    val keep = if (b.holds(Tradition.ORAL_TRADITION)) 0.5f else 1f
    for (m in 0 until 12) if (b.rite[m] >= 0 && rnd.nextFloat() < RITE_FORGET * keep * years) {
        b.rite[m] = -1
        stats.meeting(Met.BANDS_LET_A_RITE_GO)
    }
    if (b.rite.count { it >= 0 } >= RITE_SITES || rnd.nextFloat() >= years) return
    val held = b.rite.toHashSet()
    var bestPlace = -1; var bestMonth = -1; var bestScore = 0f
    fun consider(p: Int, month: Int, score: Float) {
        if (p >= 0 && p !in held && b.rite[month] < 0 && score > bestScore) {
            bestScore = score
            bestPlace = p
            bestMonth = month
        }
    }
    val mem = g.memory
    for (i in 0 until PlaceMemory.MEMORY_SLOTS) {
        if (mem.hex[i] < 0) continue
        val month = mem.season[i] * 3 + 1
        if (mem.dead[i] >= APPOINT_MIN) consider((hw.firstPlaceIn(Hex(mem.hex[i]))).i, month, mem.dead[i])
        if (mem.met[i] >= APPOINT_MIN) consider((hw.firstPlaceIn(Hex(mem.hex[i]))).i, month, 0.5f * mem.met[i])
    }
    val here = hw.placeHex[g.place]; val month = weather.date.month
    for (h in routes.landmarks) {
        val k = routes.kind(h)
        if ((k != HexRoutes.Kind.PASS && k != HexRoutes.Kind.WATER && k != HexRoutes.Kind.END) || !b.knows(h)) continue
        val km = hex.distanceKm(here, h)
        if (km > REMEMBERED_KM) consider((hw.firstPlaceIn(Hex(h))).i, month, APPOINT_MIN * (km / REMEMBERED_KM).toFloat())
    }
    // (Where the good stone, salt or ochre it lacks is got, if it knows the place: the stronger its want, the likelier.)
    for (m in TRADED) {
        val want = 1f - b.goods[m] / (KEEP_YEARS * YEAR_NEED[m] * max(1, g.adults))
        if (want <= 0f) continue
        for (h in materialSources.getValue(m)) if (b.knows(h)) {
            val km = hex.distanceKm(here, h)
            if (km > tuning.roundArrivedKm) consider((hw.firstPlaceIn(Hex(h))).i, month, APPOINT_MIN * SOURCE_RITE * want)
        }
    }
    if (bestPlace >= 0) { b.rite[bestMonth] = bestPlace; stats.meeting(Met.BANDS_TOOK_UP_A_RITE) }
}

/**
 * Material sources (trade on): the square each is gathered from, km; the least share of a place's
 * ground for it to count; how a source counts as a rite's site against a band's graves (at full
 * want). Not measured.
 */
const val SOURCE_KM = 50.0
const val SOURCE_MIN = 0.1f
const val SOURCE_RITE = 2f

/** A band split off from another: its hold on the rites drifts, and now and then one shifts by a month (rounds on). */
internal fun HexGroups.riteInherit(nb: Band) {
    if (!ROUNDS) return
    nb.riteStrength = (nb.riteStrength + riteDrift * (2f * rnd.nextFloat() - 1f) * 1.73f).coerceAtLeast(0f)
    if (rnd.nextFloat() < RITE_SHIFT) {
        val ms = (0 until 12).filter { nb.rite[it] >= 0 }
        if (ms.isNotEmpty()) {
            val m = ms[rnd.nextInt(ms.size)]
            val to = Math.floorMod(m + (if (rnd.nextBoolean()) 1 else -1), 12)
            if (nb.rite[to] < 0) {
                nb.rite[to] = nb.rite[m]
                nb.rite[m] = -1
            }
        }
    }
}

/**
 * Two bands talking (rounds on): the one doing less well may take up a rite of the other's in a
 * month it has none, and its hold moves toward theirs, as far as they understand each other.
 */
internal fun HexGroups.riteMeet(lo: Band, hi: Band, u: Float) {
    if (!ROUNDS) return
    for (m in 0 until 12) if (hi.rite[m] >= 0 && lo.rite[m] < 0 && lo.rite.count { it >= 0 } < RITE_SITES && hi.rite[m] !in lo.rite &&
        rnd.nextFloat() < COPY_TRADITION * u) {
        lo.rite[m] = hi.rite[m]; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_RITE)
    }
    lo.riteStrength += COPY_TRADITION * u * (hi.riteStrength - lo.riteStrength)
}

/** Share of a band's rites let go a year (a fifth of them; half that where songs and stories keep them). Not measured. */
const val RITE_FORGET = 0.2f

/** Days of a band's food that make one favour ([Band.favours]): a week. */
const val FAVOUR_DAYS = 7f

/**
 * [giver] helped [taker] with [kg] of food: [taker] owes it favours, the more the more of its needs the
 * help met; help to a band that is owed favours settles them first.
 */
internal fun helped(giver: HexGroup, taker: HexGroup, kg: Float) {
    val gb = giver.band ?: return; val tb = taker.band ?: return
    if (kg <= 0f) return
    val f = kg / max(1e-3f, FAVOUR_DAYS * taker.dailyNeedKg)
    fun add(b: Band, other: Int, by: Float) { val v = (b.favours[other] ?: 0f) + by; if (abs(v) < 0.01f) b.favours.remove(other) else b.favours[other] = v }
    add(gb, taker.id, f); add(tb, giver.id, -f)
}

/**
 * What [giver] gives hungry [taker], short of [need] kg, when of its own accord it would give [chosen]
 * kg: no less than the favours it owes [taker], as fully as it chooses to return them ([Band.repayWill]),
 * up to the need and nine tenths of its larder. A return it could have made and held back costs it
 * [taker]'s regard, as far as it fell short (as much as a gift of that size would have won).
 */
internal fun HexGroups.given(giver: HexGroup, taker: HexGroup, chosen: Float, need: Float): Float {
    val gb = giver.band ?: return chosen
    val due = min(gb.owesTo(taker.id) * FAVOUR_DAYS * taker.dailyNeedKg, min(0.9f * giver.larder, need))
    if (due <= 0f) return chosen
    val gift = max(chosen, due * gb.repayWill.coerceIn(0f, 1f))
    dialUse?.amount(DialSite.REPAYING, gb.repayWill, gift, max(chosen, due), cut = gb.repayWill > 1f)
    if (gift < due) taker.band?.befriend(giver.id, -TIE_GIFT * (due - gift) / due)
    stats.meeting(if (gift < due) Met.FAVOURS_HELD_BACK else Met.FAVOURS_RETURNED)
    return gift
}
