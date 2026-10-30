package inflation.ecology

import inflation.EnumBools
import inflation.enumFloats
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.get
import inflation.world.set

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.Tuning
import inflation.Config
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64

import inflation.world.Biome
import inflation.world.Date
import inflation.world.Place
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

// What passes between bands: words, traditions, partners and grudges. A band's culture is data on
// the band ([Band]); it changes when bands meet, when they split, and now and then by itself.

/**
 * A tradition: a practice or belief a band holds, passed on to both halves when it splits, and now and then taken up or
 * dropped. Each has a concrete effect. Know-how ([mark] false) is copied from neighbours who do better; a [mark], a custom
 * of who a band is (how it treats its dead, whom it marries, what it will not eat, how it feasts), goes the way most of the
 * bands about go ([conformist]), whoever does better: such customs are learned from parents and from the many, not weighed
 * by what they bring (Hewlett, De Silvestri and Guglielmino 2002; Henrich and Boyd 1998).
 */
enum class Tradition(val about: String, val mark: Boolean) {
    BURIAL("the dead are buried near camp: the band is loath to leave its place", true),
    SKY_LORE("sun, moon and stars are watched: the band keeps to its season's places on time", false),
    TABOO_YOUNG("mothers and young are not hunted: game is spared in its breeding years", true),
    ORAL_TRADITION("songs, stories and genealogies: knowledge and skills fade more slowly", false),
    FEASTS("dance, song and shared feasts: bands grow larger before they split", true),
    MEDICINE("herbs, setting bones, care of the sick: fewer die of wounds and illness", false),
    EXOGAMY("partners are taken from other bands: less inbreeding, skills travel", true),
    HYGIENE("middens and water kept apart, camps cleaned: a kept camp fouls slowly", false),
    SHARING("food is shared on demand: insurance against hunger, but little point in storing up for oneself", false),
    LEAVE_DEAD("after a death the camp is unlucky and abandoned (unless the band buries its dead)", true),
}

/**
 * Where a band that buries its dead ([Tradition.BURIAL]) lays them, a mark of its own: in the camp where they died
 * ([AT_CAMP]), or carried to the place where most of its dead lie ([AT_ANCESTORS]), as Natufian bands brought their dead
 * back to base camps and cemeteries (Bar-Yosef 1998; Grosman 2013). Carrying them costs the bearers' walk there and back;
 * it gathers the band's graves, and so its claims, its pull to its ancestors and its appointments, in one place.
 */
enum class BurialWay(val about: String) {
    AT_CAMP("the dead are buried where the band camps"),
    AT_ANCESTORS("the dead are carried to the place where most of the band's dead lie"),
}

/** Person-days digging a grave and the rites at it take, a death. An estimate; no figure found. */
const val BURY_DAYS = 2f
/** Bearers who carry one of the dead to its ancestors' place, there and back ([BurialWay.AT_ANCESTORS]). An estimate. */
const val BEARERS = 4f
/** How far bearers carry one of the dead: a day's walk, before the body has begun to rot in most weather. Farther off
 *  the band lays it where it camps. */
val BODY_CARRY_KM = (WALK_KM_PER_HOUR * Activity.WORK_HOURS).toDouble()

/**
 * Conformist learning (Henrich and Boyd 1998): the weight a band gives a way held by share [s] of the bands it has seen,
 * s + [CONFORMITY_D] s (1 - s) (2s - 1): above its share where most hold it, below where few do.
 */
fun conformist(s: Float) = s + CONFORMITY_D * s * (1f - s) * (2f * s - 1f)
/** How strongly bands follow the many in their marks (Henrich and Boyd's D, 0..1). Not measured: the full strength, so the effect shows. */
const val CONFORMITY_D = 1f
/** Years over which a band's sense of what the bands about hold ([Band.heard]) fades to a third. Not measured. */
const val HEARD_YEARS = 2f

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
/** A tie above which a band counts the other a friend ([Regard.friend]). */
const val FRIEND_TIE = 0.3f
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

/**
 * The lineage a band founded at hex centre ([x], [y]) km descends from: one of [LINEAGES], drawn for each block of country
 * [LINEAGE_KM] across, so that blocks far apart share one only by chance (not in a pattern repeating across the map).
 */
fun lineageAt(x: Double, y: Double): Int {
    val bx = Math.floorDiv(x.toLong(), LINEAGE_KM.toLong()); val by = Math.floorDiv(y.toLong(), LINEAGE_KM.toLong())
    return ((mix64(bx * HASH_A + by * HASH_B) ushr 33) % LINEAGES).toInt()
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
 * The resident kind (of [kinds]) the bands of [lineage] keep as their totem, and neither hunt nor eat: one drawn for each
 * lineage, so that a lineage's bands share it, and lineages only by chance.
 */
fun totemAt(lineage: Int, kinds: Int) =
    if (kinds <= 0) FaunaKind.NONE else FaunaKind(((mix64(lineage.toLong() * HASH_B + HASH_A) ushr 33) % kinds).toInt())

/** How many customs bands are counted apart by ([Regard.customsApart]): their traditions, their totem and their burial way. */
val CUSTOMS = Tradition.entries.size + 2

/** Where the dead of lineage [lineage]'s bands are laid ([Band.burialWay]): one way drawn for each lineage. */
fun burialWayAt(lineage: Int) = BurialWay.entries[((mix64(lineage.toLong() * HASH_C + HASH_B) ushr 33) % BurialWay.entries.size).toInt()]

/**
 * Years the bands that keep a totem hold it against a band of it that ate of its kind ([Band.totemBroken]): a year, as it
 * goes round the bands that meet. An estimate; no figure found.
 */
const val TOTEM_SHAME_YEARS = 1f

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
 * How band [of] stands toward band [to] (of group [toId]): how well they understand each other (at a meeting, [heard] as it
 * began; otherwise their words as they stand), its tie to them, whether they are strangers, its grudge against their people,
 * how near kin they are and how far apart their customs, each read as it stands when asked. Everything between two bands
 * that turns on how one sees the other reads it from here: whether it comes to blows at a meeting, sets out to raid its home
 * or robs its cache, what it gives, how far it believes what it is told, whose meeting place it takes up, and whom it counts
 * as friend or foe about it.
 */
internal class Regard(private val of: Band, private val toId: Int, private val to: Band, private var heard: Float = -1f) {
    val understood: Float get() { if (heard < 0f) heard = Language.understanding(of.words, to.words); return heard }
    val tie get() = of.tie(toId)
    val close get() = max(0f, tie)
    /** Close enough to count as friends: given ornaments, its cache left alone. */
    val friend get() = tie > FRIEND_TIE
    /** Never met before. */
    val stranger get() = toId !in of.ties
    /** Its grudge against their people (their main lineage), and whether it is a feud: enough to raid them unprovoked. */
    val grudge get() = of.grudge[to.mainLineage()]
    val feud get() = grudge > FEUD
    val kin get() = kinship(of.lineage, to.lineage)
    /**
     * How many of its customs ([CUSTOMS]: its traditions, its totem as the other keeps it, and where it lays its dead, if both
     * bury them) the other holds otherwise.
     */
    val customsApart get() = Tradition.entries.count { of.holds(it) != to.holds(it) } + (if (of.totem != to.keptTotem) 1 else 0) +
        (if (of.holds(Tradition.BURIAL) && to.holds(Tradition.BURIAL) && of.burialWay != to.burialWay) 1 else 0)

    /**
     * The chance [of] falls on [to], [drive] being the chance at its least (what drives it, by its readiness): the less they
     * understand each other and the less kin, the likelier; close friends hardly; strangers and those of other customs the
     * likelier.
     */
    fun fightChance(drive: Float) = drive * (0.5f + 0.5f * (1f - understood)) * (1f - 0.5f * kin) * (1f - TIE_PEACE * close) *
        (1f + (if (stranger) STRANGER_HOSTILITY else 0f) + CUSTOMS_HOSTILITY * (customsApart.toFloat() / CUSTOMS))
    /** How many times readier than at its least [of] is to fight [to] ([fightChance] of 1). */
    val wariness get() = fightChance(1f)
    /** How far [of] believes what [to] tells it of the places it remembers. */
    val trust get() = (0.5f + 0.5f * close) * understood
    /** The chance [of] takes up [to]'s meeting place over its own weaker one. */
    val takesUpPlace get() = understood * (0.3f + 0.7f * close)

    companion object {
        /** How close two bands are to each other, between them. */
        fun close(ab: Regard, ba: Regard) = max(0f, 0.5f * (ab.tie + ba.tie))
        /** The most [wariness] can be: strangers of wholly other customs, no kin, not understood at all. */
        const val MOST_WARY = 1f + STRANGER_HOSTILITY + CUSTOMS_HOSTILITY
    }
}

/**
 * Two bands meet. If one is hungry and the other well stocked, or one holds a feud against the
 * other's people, it may raid: the less they understand each other, the likelier. Otherwise they
 * talk: they bring a few words into line, the less successful band may take up the other's ways,
 * they show each other tools, and bands that marry out exchange partners, who bring their skills.
 * Returns true if it came to a raid.
 */
internal fun HexGroups.meetingEndsInRaid(a: HexGroup, b: HexGroup, weather: Weather): Boolean {
    val bandA = a.band ?: return false; val bandB = b.band ?: return false
    bandA.ensureMarks(fauna.kinds.size); bandB.ensureMarks(fauna.kinds.size)
    val u = Language.understanding(bandA.words, bandB.words)
    val ab = Regard(bandA, b.id, bandB, u); val ba = Regard(bandB, a.id, bandA, u)
    val metAs = MetAs(ab, ba)
    tallyMeeting(metAs, Tally.MeetingEnd.MET)
    if (raid(a, bandA, b, bandB, ab, weather) || raid(b, bandB, a, bandA, ba, weather)) {
        tallyMeeting(metAs, Tally.MeetingEnd.RAIDED)
        return true
    }
    // A band that sees sickness among the others may keep away: no talk, no partners, no feast.
    fun showsSickness(x: Band) = x.sick.sum() > SICK_SHOWS
    fun shuns(x: Band) = drawn(DialSite.SHUN_SICK, x.will[BandWay.SHUNNING_THE_SICK], rnd.nextFloat(), x.shunSick) { 0.5f }
    if ((showsSickness(bandB) && shuns(bandA)) || (showsSickness(bandA) && shuns(bandB))) {
        stats.meeting(Met.BANDS_KEPT_AWAY_FROM_THE_SICK)
        return false
    }
    // Meeting in peace makes them a little closer; how close they are colours the rest.
    bandA.befriend(b.id, TIE_MEET); bandB.befriend(a.id, TIE_MEET)
    val close = Regard.close(ab, ba)
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
    // Each sees what the other holds, as far as it understands it.
    bandA.hear(bandB, u); bandB.hear(bandA, u)
    for (t in Tradition.entries) {
        if (hi.holds(t) == lo.holds(t)) continue
        // A mark goes the way most of the bands about go: either may take up the other's, the likelier the more of the bands
        // it has seen hold that way (one draw for the two, so neither comes first).
        val (from, to) = if (!t.mark) {
            if (rnd.nextFloat() < COPY_TRADITION * u) hi to lo else continue
        } else {
            val toA = COPY_TRADITION * u * conformist(bandA.heardShare(t, bandB.holds(t)))
            val toB = COPY_TRADITION * u * conformist(bandB.heardShare(t, bandA.holds(t)))
            val r = rnd.nextFloat()
            if (r < toA) bandB to bandA else if (r < toA + toB) bandA to bandB else continue
        }
        to.traditions[t] = from.holds(t)
        stats.meeting(if (from.holds(t)) Met.Of.TOOK_UP else Met.Of.TOOK_LEAVE_OF, t.name.lowercase())
    }
    // A totem goes the same way as a mark: either may take up the other's as the other keeps it (none, if it ate of its own).
    val keptA = bandA.keptTotem; val keptB = bandB.keptTotem
    if (keptA != keptB) {
        val toA = COPY_TRADITION * u * conformist(bandA.heardTotemShare(keptB))
        val toB = COPY_TRADITION * u * conformist(bandB.heardTotemShare(keptA))
        val r = rnd.nextFloat()
        if (r < toA) { bandA.totem = keptB; bandA.totemBroken = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_TOTEM) }
        else if (r < toA + toB) { bandB.totem = keptA; bandB.totemBroken = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_TOTEM) }
    }
    // And where bands that both bury their dead lay them.
    if (bandA.holds(Tradition.BURIAL) && bandB.holds(Tradition.BURIAL) && bandA.burialWay != bandB.burialWay) {
        val toA = COPY_TRADITION * u * conformist(bandA.heardBurialShare(bandB.burialWay))
        val toB = COPY_TRADITION * u * conformist(bandB.heardBurialShare(bandA.burialWay))
        val r = rnd.nextFloat()
        if (r < toA) { bandA.burialWay = bandB.burialWay; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_BURIAL) }
        else if (r < toA + toB) { bandB.burialWay = bandA.burialWay; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_BURIAL) }
    }
    riteMeet(bandA, bandB, ab, ba)
    teach(bandA, bandB, u, (doing(a) - doing(b)).coerceIn(-1f, 1f))
    // And the sick among them pass on what is catching.
    passIllness(bandA, bandB)
    // Each tells the other of the country it knows round about, as far as they understand each other.
    if (rnd.nextFloat() < u * (0.6f + 0.4f * close)) tellOfCountry(a, bandA, b, bandB, ab, ba, weather)
    // Partners: where either holds to marrying out, young adults change bands, both ways.
    val marry = (if (bandA.holds(Tradition.EXOGAMY)) 0.5f else 0f) + (if (bandB.holds(Tradition.EXOGAMY)) 0.5f else 0f)
    if (a.adults >= 2 && b.adults >= 2 && rnd.nextFloat() < MARRY * marry * (0.5f + close)) {
        marryOut(a, bandA, b, bandB)
        bandA.befriend(b.id, TIE_MARRY); bandB.befriend(a.id, TIE_MARRY)
        stats.meeting(Met.BANDS_EXCHANGED_PARTNERS)
        tallyMeeting(metAs, Tally.MeetingEnd.PARTNERS)
    }
    return false
}

/**
 * How two bands stood toward each other as they met ([ab], one's regard of the other, and [ba]): how well they understood
 * each other, how far apart their customs were, and how wary the warier was. For the identity census: taken once, so that
 * all a meeting came to is counted in the classes it began in.
 */
internal class MetAs(ab: Regard, ba: Regard) {
    val understood = Tally.Understood.of(ab.understood)
    val customs = Tally.CustomsApart.of(ab.customsApart)
    val wary = Tally.Wary.of(max(ab.wariness, ba.wariness))
}

/**
 * Counts what came of two bands meeting ([what]) by how they stood toward each other ([metAs]): for the identity census.
 * Nothing in the model reads it back, and it draws no chance.
 */
internal fun GroupWorld.tallyMeeting(metAs: MetAs, what: Tally.MeetingEnd) {
    stats.hunting.merge(Tally.meetingsBy(metAs.understood, what), 1, Int::plus)
    stats.hunting.merge(Tally.meetingsBy(metAs.customs, what), 1, Int::plus)
    stats.hunting.merge(Tally.meetingsBy(metAs.wary, what), 1, Int::plus)
}

/**
 * Bands [a] and [b] (regarding each other as [ab] and [ba]) tell each other of the country round about,
 * the routes they know, where they met, where their homes and those of others they know are, the
 * appointments they keep, and the places they remember (as far as they trust each other).
 */
private fun HexGroups.tellOfCountry(a: HexGroup, bandA: Band, b: HexGroup, bandB: Band, ab: Regard, ba: Regard, weather: Weather) {
    val u = ab.understood
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
        hearer.knownHomes[teller.id] = bt.houseHex
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
    fun adopt(x: HexGroup, bx: Band, y: HexGroup, by: Band, r: Regard) {
        if (by.appointHex.none || by.appointHex == bx.appointHex) return
        val theirs = y.memory.deadAt(by.appointHex) + y.memory.metAt(by.appointHex)
        val ours = if (!bx.appointHex.none) x.memory.deadAt(bx.appointHex) + x.memory.metAt(bx.appointHex) else 0f
        if (theirs > ours && rnd.nextFloat() < r.takesUpPlace) {
            bx.appointment = by.appointment
            stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_APPOINTMENT)
        }
    }
    adopt(a, bandA, b, bandB, ab); adopt(b, bandB, a, bandA, ba)
    // And of the places they remember, each as far as it trusts the other.
    a.memory.hear(b.memory, wk, stepsPerYear, ab.trust); b.memory.hear(a.memory, wk, stepsPerYear, ba.trust)
}

/** [g] raids [o] (as it regards it: [r]) if it is hungry and [o] has food, or holds a feud against its people. Returns whether it did. */
private fun HexGroups.raid(g: HexGroup, b: Band, o: HexGroup, ob: Band, r: Regard, weather: Weather): Boolean {
    // What drives a raid: an old feud, or competition (hunger with food in the other's larder, or too
    // many people on the same country); kin and those who understand each other fight less.
    val feud = r.feud
    val hunger = if (o.larder > 10f * o.dailyNeedKg) (1f - g.fed) else 0f
    // (On ground it holds as its own, a band drives off those it is not close to: trespass.)
    val trespass = if (b.claims(hw.placeHex[g.place], g.memory) && r.tie <= 0f) CLAIM_PRESSURE else 0f
    val pressure = if (feud) 1f else maxOf(hunger, min(1f, peopleCrowding(g)), trespass)
    // (Whether it comes to blows is the band's choice: its readiness to raid; and how it regards the others.)
    val chance = r.fightChance(RAID * b.raidWill * pressure)
    if (!drawn(DialSite.RAIDS_MEETING, b.raidWill, rnd.nextFloat(), chance) { chance / b.raidWill.coerceAtLeast(1e-6f) }) return false
    fight(g, b, o, ob, planned = false, weather)
    return true
}

/**
 * [g] falls on [o]. A planned raid on a band at home may take it by surprise, the less so the more
 * it sees (scouts, open ground, moonlight); the defenders gain from high, broken ground, their
 * houses and a palisade. A band with no home to hold may slip away, losing what it cannot carry; one beaten at its home
 * may be driven from it ([driveOff]).
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
    val sides = raidSides(g, o, ob, planned, ob.atHome, weather)
    val sg = sides.raiders; val so = sides.defenders
    raidWorth(o, ob, sg, so, sides.own)
    // Each side loses some of its people, the more so against the stronger side; neighbours who stood with the raided
    // lose theirs as the raided do, of as many as came.
    val lostG = binomial(g.adults, RAID_DEATHS * so / (sg + so), rnd)
    val lostO = binomial(o.adults, RAID_DEATHS * sg / (sg + so), rnd)
    val losses = arrayListOf(g to lostG, o to lostO)
    for (n in sides.helpers) {
        losses += n.group to binomial((n.share * n.group.adults).roundToInt(), RAID_DEATHS * sg / (sg + so), rnd)
        stats.meeting(Met.NEIGHBOURS_DEFENDED)
        note(n.group) { "stood with band #%d against band #%d".format(o.id, g.id) }
    }
    for ((who, lost) in losses) if (lost > 0) {
        adultsDied(who, lost, rnd)
        // (And as many again are wounded, some to be lamed for life.)
        who.band?.wounded(WOUNDED_PER_DEAD * lost, who.adults)
        stats.deaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        if (who.species.human) stats.adultDeaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        who.band?.let {
            stats.hunting.merge(Tally.bandsLivingDied(bandLiving(it), DeathCause.RAIDS), lost, Int::plus)
            stats.hunting.merge(Tally.bandsMindDied(it.mind, DeathCause.RAIDS), lost, Int::plus)
        }
        small.deposit(DeadPool.CARRION, who.place, Kg(lost * who.species.massKg))
    }
    // The stronger side carries off food; the raided remember.
    if (sg > so) { val t = RAID_TAKE * o.larder; moveFood(o, g, t); stats.meeting(Met.LARDERS_RAIDED) }
    // A band beaten at its home may be driven from it, the likelier the more the raiders overpower it (none when they are
    // only as strong; Wrangham and Glowacki 2012: victors drove the defeated off their land in most non-state wars).
    if (ob.atHome && sg > so && rnd.nextFloat() < 1f - so / sg) driveOff(o, ob, b)
    ob.grudge[b.mainLineage()] += GRUDGE
    ob.befriend(g.id, -TIE_RAID); b.befriend(o.id, -0.3f * TIE_RAID)
    if (lostG > 0) b.grudge[ob.mainLineage()] += 0.3f * GRUDGE
    // (Those who stood with them remember the raiders too, as far as they stood.)
    for (n in sides.helpers) { n.band.grudge[b.mainLineage()] += n.share * GRUDGE; n.band.befriend(g.id, -n.share * TIE_RAID) }
}

/**
 * Band [o] ([ob]) is driven from its home by raiders of band [by]: it leaves its houses and palisade to whoever takes them up,
 * and while it fears the raiders' people it neither takes up houses there again nor seeks the place ([Band.keptFrom]).
 */
private fun HexGroups.driveOff(o: HexGroup, ob: Band, by: Band) {
    val h = ob.houseHex
    leaveStructures(o, h)
    ob.drivenFrom = h; ob.drivenBy = by.mainLineage()
    ob.houseHex = Hex.NONE; ob.house = 0f; ob.atHome = false; ob.palisade = 0f
    ob.homeEarth = 0f; ob.homeWalls = 0f; ob.homeGroundC = 0f
    stats.meeting(Met.BANDS_DRIVEN_FROM_HOME)
    note(o) { "was driven from its home at hex %d".format(h.i) }
}

/**
 * What a whole house would have done for [o] (band [ob]) in a raid of strength [sg] on its [so] (its [own] and its neighbours'), against the houses it has:
 * the lives it would have saved and the food it would have kept from the raiders, by [fight]'s rules; for [Band.houseLives]
 * and [Band.houseFood].
 */
private fun raidWorth(o: HexGroup, ob: Band, sg: Float, so: Float, own: Float) {
    val works = if (ob.atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val whole = 1f + HOUSE_DEFENCE + (if (ob.atHome) PALISADE_DEFENCE * ob.palisade else 0f)
    // (Its own part of the defence, [own], behind a whole house; the neighbours who stood with it as they were.)
    val so1 = so + own * (whole / works - 1f)
    val lives = max(0f, o.adults * RAID_DEATHS * (sg / (sg + so) - sg / (sg + so1)))
    ob.houseLivesWeek += lives; ob.houseWeek[HouseWorth.RAIDS] += lives
    if (sg > so && sg <= so1) { ob.houseFoodWeek += RAID_TAKE * o.larder; ob.houseWeek[HouseWorth.RAID_KG] += RAID_TAKE * o.larder }
}

/** A band [group] (band [band]) at home where a neighbour is raided, standing with it with [share] of its people ([raidSides]). */
internal class Helper(val group: HexGroup, val band: Band, val share: Float)

/** The sides of a raid: the [raiders]' strength, the raided band's [own] and its [helpers], with theirs. */
internal class RaidSides(val raiders: Float, val own: Float, val helpers: List<Helper>, private val helped: Float) {
    /** All who hold off the raiders. */
    val defenders get() = own + helped
}

/** The raiders' [g] and the defenders' [o] (band [ob]) strengths when [g] falls on [o] where it is ([raidSides]). */
internal fun HexGroups.raidStrengths(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather): Pair<Float, Float> =
    raidSides(g, o, ob, planned, atHome, weather).let { it.raiders to it.defenders }

/**
 * The sides when [g] falls on [o] (band [ob]) where it is: a planned raid may take it by surprise, the less so the more it
 * sees (scouts, open ground, moonlight); defenders [atHome] gain from high, broken ground, their houses and a palisade, and
 * the other bands at home in the same hex stand with them, each with as many of its people as it is closer to the raided
 * than to the raiders (by its [Regard] of each: a band that holds neither close, or the raiders as close, stays out), behind
 * its own houses and palisade. The one rule for a raid ([fight]) and for a raiding band weighing its target ([plotRaid]).
 */
internal fun HexGroups.raidSides(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather): RaidSides {
    val p = o.place
    // Surprise: a planned raid at night, in cover, on a band that keeps no lookout.
    val seen = (sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * ob.scoutShare)).coerceIn(0f, 1f)
    if (planned) dialUse?.amount(DialSite.SCOUTS_SEEN, ob.will[BandWay.SCOUTS], seen, (sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * SCOUT_SHARE)).coerceIn(0f, 1f),
        cut = sight(p, weather) * (0.5f + 0.5f * moon) * (1f + 5f * ob.scoutShare) > 1f)
    val surprise = if (planned) 1f + SURPRISE_RAID * (1f - seen) else 1f
    // The defenders' ground and works.
    val ground = 1f + HIGH_GROUND * land.bestDefence[p]
    val works = if (atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val helpers = ArrayList<Helper>(); var helped = 0f
    val gb = g.band
    if (atHome && gb != null) {
        val h = hw.placeHex[p]
        for (n in groups) {
            val nb = n.band ?: continue
            if (n === o || n === g || !n.alive || !nb.atHome || hw.placeHex[n.place] != h) continue
            val share = Regard(nb, o.id, ob).close - Regard(nb, g.id, gb).close
            if (share <= 0f) continue
            helpers += Helper(n, nb, share)
            helped += share * strength(n) * ground * (1f + HOUSE_DEFENCE * nb.house + PALISADE_DEFENCE * nb.palisade)
        }
    }
    return RaidSides(strength(g) * surprise, strength(o) * ground * works, helpers, helped)
}

/** One or two young adults from each band join the other; their descent and skills come with them. */
private fun GroupWorld.marryOut(a: HexGroup, bandA: Band, b: HexGroup, bandB: Band) {
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
    val byHex = HashMap<Hex, ArrayList<HexGroup>>()
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
        val base = min(0.9f, BAND_GIFT * (if (o.band!!.holds(Tradition.SHARING)) 2f else 1f))
        val share = 0.9f * shareOfWill(o.band!!.giveWill, base / 0.9f)
        o.band?.let { ob -> dialUse?.amount(DialSite.GIVE_VISIT, ob.giveWill, share, base) }
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
        while (it.hasNext()) { val (id, hh) = it.next(); if (hh in g.path && bands.houseOf[id] != hh) { it.remove(); b.knownLineage.remove(id) } }
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
            if (mem.hex[i].none) continue
            val score = mem.dead[i] + 0.5f * mem.met[i] + mem.fed[i]
            if (mem.dead[i] + mem.met[i] >= APPOINT_MIN && score > bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) {
            b.appointment = Appointment(mem.hex[best], mem.season[best] * 3 + 1)
            stats.meeting(Met.BANDS_MADE_AN_APPOINTMENT)
        }
    }
    // Ties fade unless renewed: by visits to the friendly bands within a day or two's walk, in the band's leisure.
    if (b.ties.isNotEmpty()) {
        val fade = TIE_FADE * days / Date.DAYS_PER_YEAR.toFloat()
        val here = hw.placeHex[g.place]
        val near = HashSet<Int>()
        for ((k, v) in b.ties) if (v > 0f) bands.bandHex[k]?.let { h -> if (hexKm(here, h) <= VISIT_RINGS * hex.hexKm) near += k }
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
            val h = Hex(w * 64 + i)
            if (hex.distanceKm(h, here) > REMEMBERED_KM && rnd.nextFloat() < 0.5f * routes.forgetShare(h)) b.known[w] = b.known[w] and (1L shl i).inv() } }
    }
    b.inbred += (1f - b.inbred) / (2f * max(2, g.adults)) * years / GENERATION_YEARS
    val keep = exp(-years / HEARD_YEARS)
    for (t in Tradition.entries) b.heard[t] *= keep
    b.heardOf *= keep
    b.ensureMarks(fauna.kinds.size)
    for (i in b.heardTotem.indices) b.heardTotem[i] *= keep
    for (w in BurialWay.entries) b.heardBurial[w] *= keep
    b.totemBroken = max(0f, b.totemBroken - years)
    // (Now and then a band gives up its totem, or one without takes up a kind as its own, as with its traditions.)
    if (fauna.kinds.isNotEmpty() && rnd.nextFloat() < (if (b.totem.none) TAKE_UP else DROP) * years) {
        b.totem = if (b.totem.none) FaunaKind(rnd.nextInt(fauna.kinds.size)) else FaunaKind.NONE; b.totemBroken = 0f
    }
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

/** Where most of [g]'s dead lie, of the places it remembers ([PlaceMemory.deadAt]); none if it remembers no graves. */
internal fun ancestorsHex(g: HexGroup): Hex {
    val m = g.memory; var best = Hex.NONE; var most = 0f
    for (i in 0 until PlaceMemory.MEMORY_SLOTS) {
        val h = m.hex[i]; if (h.none) continue
        val d = m.deadAt(h)
        if (d > most) { most = d; best = h }
    }
    return best
}

/** Most months of the year a band's rites take it somewhere ([Band.rite]). */
const val RITE_SITES = 3
/** Person-days each adult gives a rite held at its site: the gathering, the dances, the telling. An estimate; no figure found. */
const val RITE_DAYS = 2f
/**
 * Strain a young belief bears ([Band.strainBorne]): rites held back from, about the year's rites. Strained that far, a
 * band holds together at half its size ([HexGroups.maxSize]), and the part that leaves at a split goes without the belief
 * as likely as the strain is near it. A belief kept [BELIEF_AGE_YEARS] bears twice that (older beliefs are clung to). Strain eases to a third over
 * [STRAIN_EASE_YEARS] of weeks the band eats its fill. Estimates; no figure found.
 */
const val STRAIN_SPLIT = 3f
const val BELIEF_AGE_YEARS = 25f
const val STRAIN_EASE_YEARS = 1f
/** Years over which a place's sanctity fades to a third (1/e) while no rites are held and no dead laid there: about a generation. An estimate. */
const val SANCTITY_YEARS = 25f
/** How much a band's hold on its rites changes when it splits: a fifth of the founders' range, as temperament drifts. Not measured. */
internal val HexGroups.riteDrift get() = tuning.riteFounderMax / 5f
/** Chance at a split that one of the offshoot's rites shifts by a month (the telling changes). Not measured. */
const val RITE_SHIFT = 0.1f

/** The founders' hold on their rites in lineage [lineage] (the same for every band of it, as [traditionsAt]). */
fun riteStrengthAt(lineage: Int, tuning: Tuning) = tuning.riteFounderMax * Math.floorMod((lineage + 7) * 2_654_435_761L.toInt() xor 0x5bd1e995, 1000) / 1000f

/**
 * A band's rites through the year (rounds on, see [inflation.Tuning.rounds]). A rite is born of an omen ([omen]): a
 * salient event at a place in a month (many deaths in a week, plenty after long want, a strange sign) that is borne out. Nothing here
 * weighs what a rite brings, and a rite is not let go: a belief ends with the bands that keep it, or with the part of a
 * band that breaks from it ([HexGroups.maxSize]).
 *
 * A rite obliges: at the start of its month the band's adults owe [RITE_DAYS] each of their customs' time, and, unless
 * the band is camped at the site ([inflation.Tuning.roundArrivedKm]), the walk there and back. Nothing weighs the trip
 * against what it brings; the band does not move for it, its adults go and come back. Once the days are given the rite
 * is held and the place grows holier by the adults at it. A rite adjusts to the season: one whose month ran out before
 * its days were given is held in a lesser form, with what was given (the place grows holier as far), and the belief is
 * strained only as far as the days not given went to other work than the band's needs ([Band.riteSpare],
 * [Band.strain]; see [HexGroups.maxSize]): need excuses, choice does not. What need cut short moves, as often as
 * not, to a month beside it the band eats better in ([riteToPlenty]): harvest feasts, not famine fasts.
 */
internal fun HexGroups.riteWeek(g: HexGroup, weather: Weather) {
    if (!tuning.rounds) return
    val b = g.band ?: return
    val now = weather.date.year * 12 + weather.date.month
    // (A rite whose days are all given is held; one whose month ran out before they were, was cut short.)
    if (b.riteDue >= 0 && b.riteOwed <= 0f) {
        land.sanctity[b.riteDue] += g.adults.toFloat()
        b.riteKept = now
        stats.meeting(Met.BANDS_HELD_A_RITE)
        stats.meeting(Met.Of.RITES_HELD, RiteWalk.of(riteKm(g, b.riteDue), tuning).label)
        b.riteDue = -1
    } else if (b.riteDue >= 0 && b.riteHeld != now) {
        // (For the report: how far off the site is now, and how much of the rite's days had been given.)
        val km = riteKm(g, b.riteDue); val walk = RiteWalk.of(km, tuning)
        val asked = riteDays(km) * g.adults
        stats.meeting(Met.Of.RITES_CUT_SHORT, walk.label)
        stats.meeting(Met.Of.RITES_CUT_SHORT_PERCENT_GIVEN, walk.label, if (asked > 0f) (100f * (1f - b.riteOwed / asked)).roundToInt().coerceIn(0, 100) else 0)
        // (Held in a lesser form with what was given; the strain is what other work took of what it did not give.)
        val month = Math.floorMod(b.riteHeld, 12L).toInt()
        val given = (b.riteAsked - b.riteOwed).coerceAtLeast(0f)
        val withheld = min(b.riteOwed, b.riteSpare)
        if (given > 0f && b.riteAsked > 0f) {
            land.sanctity[b.riteDue] += g.adults * given / b.riteAsked
            stats.meeting(Met.BANDS_HELD_A_RITE_IN_A_LESSER_FORM)
        }
        b.strain += if (b.riteAsked > 0f) withheld / b.riteAsked else 1f
        val byNeed = if (b.riteAsked > 0f) (b.riteOwed - withheld) / b.riteAsked else 0f
        b.riteOwed = 0f; b.riteDue = -1; b.riteAsked = 0f; b.riteSpare = 0f
        stats.meeting(Met.BANDS_CUT_A_RITE_SHORT)
        if (rnd.nextFloat() < byNeed) riteToPlenty(b, month)
    }
    // (A belief grows older while it is kept; its strain eases while the band eats its fill.)
    if (b.rite.any { it >= 0 }) b.beliefYears += years
    if (g.fed >= Thresholds.HUNGRY) b.strain *= exp(-years / STRAIN_EASE_YEARS)
    val site = b.rite[weather.date.month]
    if (site >= 0 && b.riteHeld != now) {
        b.riteHeld = now; b.riteDue = site
        b.riteOwed = riteDays(riteKm(g, site)) * g.adults
        b.riteAsked = b.riteOwed; b.riteSpare = 0f
        stats.meeting(Met.Of.CUSTOMS_DAYS_ASKED, CustomsPart.RITES.label, (10f * b.riteOwed).roundToInt())
    }
    // Plenty after long want is an omen too: where and when the hunger broke.
    if (g.lean > 0 && g.fed >= Thresholds.HUNGRY) omen(b, g.place, weather.date, g.lean / LEAN_USUAL_WEEKS, Met.BANDS_MARKED_PLENTY_AFTER_WANT)
    // So is the strange thing with no cause in the world: a white bird, a shape in the trees, a lightning strike, a
    // misborn child, a fall. It befalls the band where it is, and the stranger the rarer ([STRANGE_SIGNS_A_YEAR]).
    if (rnd.nextFloat() < STRANGE_SIGNS_A_YEAR * years)
        omen(b, g.place, weather.date, 1f / (1f - rnd.nextFloat()), Met.BANDS_MARKED_A_STRANGE_SIGN)
    // An omen fades unless it is borne out: the same month going well again where it fell, a year or more on.
    if (b.omenPlace >= 0) {
        b.omenWeight *= exp(-years / OMEN_YEARS)
        if (b.omenWeight < 1f) b.omenPlace = -1
        else if (weather.date.month == b.omenMonth && weather.date.year > b.omenYear && g.fed >= Thresholds.HUNGRY &&
            hex.distanceKm(hw.placeHex[g.place], hw.placeHex[b.omenPlace]) <= tuning.roundArrivedKm) bearRite(b)
    }
}

/**
 * The months the year turns in, which most peoples mark and gather at (the sun's year, [inflation.world.Date.solarDeclination],
 * from [inflation.world.Date.START_DAY]: the spring equinox opens month 3, midsummer month 6, the autumn equinox month 9,
 * midwinter month 0). Rites are drawn to them as they are born ([bearRite]) and moved ([riteToPlenty]).
 */
fun turnOfYear(month: Int) = month % 3 == 0

/** The two months beside [month]. */
private fun besideMonths(month: Int) = listOf(Math.floorMod(month - 1, 12), Math.floorMod(month + 1, 12))

/**
 * [b]'s rite in [month], cut short by need, moves to a month beside it with no rite of its own that the band eats
 * better in ([Band.monthSupply]), or as well if it is a turn of the year ([turnOfYear]); the turn of the year first,
 * else the best fed.
 */
private fun HexGroups.riteToPlenty(b: Band, month: Int) {
    val site = b.rite[month]
    if (site < 0) return
    val can = besideMonths(month).filter { b.rite[it] < 0 &&
        (b.monthSupply[it] > b.monthSupply[month] || turnOfYear(it) && b.monthSupply[it] >= b.monthSupply[month]) }
    val to = can.firstOrNull { turnOfYear(it) } ?: can.maxByOrNull { b.monthSupply[it] } ?: return
    b.rite[to] = site; b.rite[month] = -1
    stats.meeting(Met.BANDS_MOVED_A_RITE_TO_A_BETTER_MONTH)
}

/** How far [g] is from rite site [site], km. */
private fun HexGroups.riteKm(g: HexGroup, site: Int) = hex.distanceKm(hw.placeHex[g.place], hw.placeHex[site])

/** The days a rite [km] off asks of each adult: [RITE_DAYS], and the walk there and back unless the band is camped at it. */
private fun HexGroups.riteDays(km: Double) =
    RITE_DAYS + if (km <= tuning.roundArrivedKm) 0f else (2.0 * km / (WALK_KM_PER_HOUR * Activity.WORK_HOURS)).toFloat()

/** How far a band is from its rite's site, for the report ([Met.Of.RITES_HELD], [Met.Of.RITES_CUT_SHORT]). */
enum class RiteWalk(val label: String) {
    AT_SITE("at the site"), UNDER_100_KM("under 100 km"), UNDER_200_KM("100-200 km"), FARTHER("200 km or more");

    companion object {
        fun of(km: Double, tuning: Tuning) = when {
            km <= tuning.roundArrivedKm -> AT_SITE
            km < 100.0 -> UNDER_100_KM
            km < 200.0 -> UNDER_200_KM
            else -> FARTHER
        }
    }
}

/**
 * [b] marks what befell it at [place] on [date], [weight] times what it is used to ([SALIENT] at least, or it is not marked):
 * an omen it remembers over the next [OMEN_YEARS] (the greater kept before the lesser), which becomes a rite in its month
 * if borne out (see [riteWeek], [riteMeet]), or at once if it was very great ([OMEN_AT_ONCE]).
 */
internal fun GroupWorld.omen(b: Band, place: Place, date: Date, weight: Float, kind: Met) {
    if (weight < SALIENT || weight <= b.omenWeight) return
    b.omenPlace = place.i; b.omenMonth = date.month; b.omenYear = date.year; b.omenWeight = weight
    stats.meeting(kind)
    if (weight >= OMEN_AT_ONCE) bearRite(b)
}

/** [b]'s omen becomes a rite at its place in its month, if the band has that month free and room for another. */
internal fun GroupWorld.bearRite(b: Band) {
    val p = b.omenPlace; val m = b.omenMonth
    b.omenPlace = -1; b.omenWeight = 0f
    if (p < 0 || b.rite[m] >= 0 || p in b.rite || b.rite.count { it >= 0 } >= RITE_SITES) return
    // (Held at the turn of the year beside it, if that is free: the dates every band knows.)
    val at = if (turnOfYear(m)) m else besideMonths(m).firstOrNull { turnOfYear(it) && b.rite[it] < 0 } ?: m
    b.rite[at] = p
    stats.meeting(Met.BANDS_BORE_A_RITE)
}

/**
 * Material sources (trade on): the square each is gathered from, km; the least share of a place's
 * ground for it to count. Not measured.
 */
const val SOURCE_KM = 50.0
const val SOURCE_MIN = 0.1f

/** A band split off from another: its hold on the rites drifts, and now and then one shifts by a month (rounds on). */
internal fun HexGroups.riteInherit(nb: Band) {
    if (!tuning.rounds) return
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
 * Two bands talking (rounds on): each may take up a rite of the other's in a month it has none, as far as it believes what
 * the other tells ([Regard.trust]: understanding and closeness), never because the other does better; and each one's hold
 * on its rites moves toward the other's as far. Two that marked the same place each hear their omen told again.
 */
internal fun GroupWorld.riteMeet(a: Band, b: Band, ab: Regard, ba: Regard) {
    if (!tuning.rounds) return
    // (Two bands that marked the same place each hear their omen told again: it is borne out for both.)
    if (a.omenPlace >= 0 && a.omenPlace == b.omenPlace) { bearRite(a); bearRite(b) }
    fun takeUp(to: Band, from: Band, belief: Float) {
        for (m in 0 until 12) if (from.rite[m] >= 0 && to.rite[m] < 0 && to.rite.count { it >= 0 } < RITE_SITES && from.rite[m] !in to.rite &&
            rnd.nextFloat() < COPY_TRADITION * belief) {
            to.rite[m] = from.rite[m]; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_RITE)
        }
    }
    val tA = ab.trust; val tB = ba.trust
    takeUp(a, b, tA); takeUp(b, a, tB)
    val sa = a.riteStrength; val sb = b.riteStrength
    a.riteStrength += COPY_TRADITION * tA * (sb - sa)
    b.riteStrength += COPY_TRADITION * tB * (sa - sb)
}

/**
 * Omens ([Band.omenPlace]): what a band marks is [SALIENT] times what it is used to or more (deaths in a week against its
 * usual, [Band.deathsUsual]; weeks of hunger broken, against [LEAN_USUAL_WEEKS], a hungry month a year); it fades to a
 * third over [OMEN_YEARS] and is forgotten once no more than the usual, unless borne out; one [OMEN_AT_ONCE] times the usual
 * is a rite at once. A band can't make a rite of what is usual for it.
 * Estimates; no figure found.
 */
const val SALIENT = 3f
const val OMEN_AT_ONCE = 10f
const val OMEN_YEARS = 1f
const val LEAN_USUAL_WEEKS = 4f

/**
 * Strange things a band sees in a year, good signs and bad alike; how strange each is, against the usual, falls off as
 * one over it (a third are [SALIENT], a tenth [OMEN_AT_ONCE]). Nothing in the world causes them and nothing follows from
 * them but the rite they may become. Estimate; no figure found.
 */
const val STRANGE_SIGNS_A_YEAR = 1f

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
    val owed = gb.owesTo(taker.id) * FAVOUR_DAYS * taker.dailyNeedKg
    val room = min(0.9f * giver.larder, need)
    val due = min(owed, room)
    if (due <= 0f) return chosen
    // (Above its 1 a band returns more than it owes, as far as the need and its larder go: food for regard.)
    val gift = max(chosen, min(owed * max(0f, gb.repayWill), room))
    dialUse?.amount(DialSite.REPAYING, gb.repayWill, gift, max(chosen, due))
    if (gift < due) taker.band?.befriend(giver.id, -TIE_GIFT * (due - gift) / due)
    stats.meeting(if (gift < due) Met.FAVOURS_HELD_BACK else Met.FAVOURS_RETURNED)
    return gift
}
