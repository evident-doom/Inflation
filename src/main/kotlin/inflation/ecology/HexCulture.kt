package inflation.ecology

import inflation.Profile
import inflation.EnumBools
import inflation.enumFloats
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexMap
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
internal const val BURY_DAYS = 2f
/** Bearers who carry one of the dead to its ancestors' place, there and back ([BurialWay.AT_ANCESTORS]). An estimate. */
internal const val BEARERS = 4f
/** How far bearers carry one of the dead: a day's walk, before the body has begun to rot in most weather. Farther off
 *  the band lays it where it camps. */
val BODY_CARRY_KM = (WALK_KM_PER_HOUR * Activity.WORK_HOURS).toDouble()

/**
 * Conformist learning (Henrich and Boyd 1998): the weight a band gives a way held by share [s] of the bands it has seen,
 * s + [CONFORMITY_D] s (1 - s) (2s - 1): above its share where most hold it, below where few do.
 */
fun conformist(s: Float) = s + CONFORMITY_D * s * (1f - s) * (2f * s - 1f)
/** How strongly bands follow the many in their marks (Henrich and Boyd's D, 0..1). Not measured: the full strength, so the effect shows. */
internal const val CONFORMITY_D = 1f
/** Years over which a band's sense of what the bands about hold ([Band.heard]) fades to a third. Not measured. */
internal const val HEARD_YEARS = 2f

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
internal const val LINEAGES = 16
internal const val LINEAGE_KM = 150.0
/** Years in a generation, for how fast inbreeding builds in a closed band. */
internal const val GENERATION_YEARS = 25f
/**
 * Extra yearly deaths among children per unit of inbreeding. First cousins' children (F = 0.0625)
 * die before adulthood some 4% more often than others (Bittles and Neel 1994, from memory): about
 * 0.6 per unit of F over fifteen years.
 */
internal const val INBREEDING_DEATHS = 0.04f
/** Chance a meeting of bands that take partners from outside ends in an exchange (half when only one of them holds to it). */
internal const val MARRY = 0.5f
/** Kinship with a partner from another band, per unit of shared descent (about second cousins). */
internal const val OUTSIDER_KIN = 0.03f
/**
 * Years an adult spends bearing and raising children before becoming an elder; elders' extra yearly
 * deaths. Foragers who reach 45 live some 20 years more on average (Gurven and Kaplan 2007, from
 * memory): about 5% a year with the ordinary deaths.
 */
internal const val ADULT_YEARS = 30f
internal const val ELDER_DEATHS = 0.03f
/** Mothers one elder can help; most that grandmothers raise births and cut children's deaths, when there are enough of them. */
internal const val ELDER_MOTHERS = 3f
/** How many births a woman has in each third of her adult life (about 15-25, 25-35, 35-45) against her best years. */
val FERTILE_BY_SPAN = floatArrayOf(0.75f, 1f, 0.5f)
internal const val GRANDMOTHERS = 0.3f
internal const val ELDER_CARE = 0.3f
/** Words two bands bring into line when they meet; yearly chance a word changes by itself. */
internal const val WORDS_PER_MEETING = 3
internal const val WORD_DRIFT = 0.0003f
/** Chance at a meeting that a band takes up a tradition the more successful one holds, at full understanding. */
internal const val COPY_TRADITION = 0.1f
/** Yearly chance a band takes up a tradition by itself, or drops one. */
internal const val TAKE_UP = 0.0005f
internal const val DROP = 0.0005f
/** Chance a band raids one it meets, at full pressure (a feud, hunger with food in the other's larder, or crowded country); share of the larder taken. */
internal const val RAID = 0.015f
internal const val RAID_TAKE = 0.5f
/**
 * Share of each side's adults a raid kills at most, against the other's strength; and the wounded who live for each one it
 * kills. Most violent wounds in prehistoric skeletons are healed ones (Nasca highlands, McCool and colleagues 2021; Jebel
 * Sahaba, Crevecoeur and colleagues 2021: people who had lived through several attacks), but none of these gives a ratio:
 * both numbers are estimates, set so a raid leaves as many hurt as before (once 8% killed and two wounded for each).
 */
internal const val RAID_DEATHS = 0.04f
internal const val RAID_WOUNDED_PER_DEAD = 5f
/** Grudge held for a raid suffered, and the share that fades a year (half in about seven years). */
internal const val GRUDGE = 1f
internal const val GRUDGE_FADE = 0.1f
/** A band's grudge against another's lineage above which it raids it unprovoked. */
internal const val FEUD = 0.9f
/** Rings of hexes round a meeting whose country bands tell each other of; country further than this from a band's camp is half forgotten each year, as are the homes of bands that far off (unless tied). */
internal const val TOLD_RINGS = 4
internal const val REMEMBERED_KM = 100.0
/** A band fed under this share of its fill with nothing laid by goes to a better-stocked band within this many rings of hexes. */
internal const val VISIT_FED = 0.6f
internal const val VISIT_RINGS = 2
/** Adults below which a band joins kin nearby: too few to hunt, gather, guard and marry within. */
internal const val SMALL_BAND = 8
/** How much a fouled camp puts a band off staying, and how much the graves of its dead hold it. */
internal const val FOUL_AVOID = 0.3f
internal const val BURIAL_STAY = 0.1f
/** Most a band of [Tradition.FEASTS] grows to before it splits, against its kind's usual most. */
internal const val FEAST_SIZE = 1.3f
/** How much likelier a raid is on strangers (never met before), and on bands of wholly other customs. */
internal const val STRANGER_HOSTILITY = 0.5f
/** How much a band's ornaments per head add to its standing (its ways are the more copied). */
internal const val STANDING = 0.05f
/** How hard a band presses those it finds on ground it holds (graves, houses, appointment place); how strongly bands keep off others' known homes. */
internal const val CLAIM_PRESSURE = 0.7f
internal const val TRESPASS_AVOID = 0.2f
/** How strongly a band on a raid heads for its target. */
internal const val RAID_PULL = 0.8f
internal const val CUSTOMS_HOSTILITY = 1f
/** How strongly an unlucky camp (a death, among bands that leave their dead) is shunned, and for how many weeks. */
internal const val UNLUCKY = 0.6f
internal const val UNLUCKY_WEEKS = 8
/** Share of its store aim a band holding to demand sharing keeps (what it stores is asked of it). */
internal const val SHARING_STORE = 0.5f
/** Most people a band at home in its houses holds before it splits. Villages are larger than forager camps: early villages
 * without bigger institutions split at 127 to 170 (Taraco Peninsula, Bandy 2004: mean about 127, splits near 170;
 * Hutterite colonies kept under about 150, splitting at 168 on average, Alberti 2014; both as summarised by Dunbar 2022);
 * Natufian settlements about 60 to 100, PPNA villages about 330 (Kuijt 2000), held by rites and shared buildings. */
internal const val SETTLED_SIZE = 150
/** How alike ([alike]) two bands at home in one hex must find each other, both ways, to become one. No source gives where people
 * living together come to feel one group; an estimate, set so halves of a split (alike about 1) are one again at once, and bands
 * of one tongue and the same customs need kin or a close tie (unrelated, the same customs and fully understood, alike 0.8 with
 * no tie and 0.96 at a full tie). */
internal const val ONE_GROUP = 0.9f
/** Tie between the two halves of a band when it splits: they were one band the week before, and grow apart only as ties fade. */
internal const val SPLIT_TIE = 1f
/** Graves and meetings at a place (in a band's memory) before it makes an appointment there. */
internal const val APPOINT_MIN = 3f
/** How strongly an appointment draws a band in its month, and its ancestors' places draw it at any time. */
internal const val APPOINT_PULL = 0.5f
internal const val ANCESTOR_PULL = 0.15f
/**
 * Ties between bands: how much closer a peaceful meeting, a gift, a feast and an exchange of
 * partners bring them (of the way left to full friendship), how far a raid sets them back; share
 * of a tie lost a year unless renewed; how much a full friendship lowers the chance of a raid.
 */
internal const val TIE_MEET = 0.05f
internal const val TIE_GIFT = 0.2f
internal const val TIE_FEAST = 0.3f
internal const val TIE_MARRY = 0.3f
internal const val TIE_RAID = 0.8f
internal const val TIE_FADE = 0.5f
internal const val TIE_PEACE = 0.8f
/** A tie above which a band counts the other a friend ([Regard.friend]). */
internal const val FRIEND_TIE = 0.3f
/**
 * Share of its hands' week a band gives to leisure at which visits keep a tie with one friendly band near most of the way
 * from fading (1 - 1/e): visits to more bands share the same leisure. Foragers spend much of their free time visiting
 * other camps (Lee 1979, Wiessner on hxaro); the rate is an estimate, no time-use figure found.
 */
internal const val VISIT_SHARE = 0.18f
/** A feast: food set before each guest, kg; days of food a band keeps back for itself beyond its aim before it feasts others. */
internal const val FEAST_KG = 3f
/** How strongly a band with food to feast others is drawn to where other bands are, and the people there at which the draw is full. */
internal const val GATHER_PULL = 0.3f
internal const val GATHER_HEADS = 150f

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

/**
 * How many customs bands are counted apart by ([Regard.customsApart]): their traditions, their totem, their burial way, their
 * rites, how they live and their home ground.
 */
val CUSTOMS = Tradition.entries.size + 5

/** How a band lives, as others see it: by the crops it sows, the herds it keeps, or what it finds. */
enum class Livelihood { FORAGING, HERDING, FARMING }

/**
 * How [this] band lives now: it farms while it keeps seed corn of its own crop ([Band.seedCorn]) to sow, herds while it keeps a
 * herd, and forages otherwise. (Farming first: those who keep both are farmers who keep beasts.)
 */
fun Band.livelihood() = if (seedCorn > 0f) Livelihood.FARMING else if (herds.herd.any { it > 0f }) Livelihood.HERDING else Livelihood.FORAGING

/**
 * Whether two bands that both have a home have them on other ground: farther apart than a day's walk ([BODY_CARRY_KM]), the
 * ground a band walks over from its home and back in a day. A band with no home is not counted apart by it, as one that
 * leaves its dead is not by where the other lays them.
 */
fun homesApart(a: Band, b: Band, map: HexMap) =
    !a.houseHex.none && !b.houseHex.none && map.distanceKm(a.houseHex, b.houseHex) > BODY_CARRY_KM

/**
 * Whether two bands that both keep rites keep none at the same site: those who gather at the same holy place, whatever the
 * month, are of one faith there; those who keep rites elsewhere only are not. A band that keeps no rite is not counted
 * apart by it, as one that leaves its dead is not by where the other lays them.
 */
fun ritesApart(a: Band, b: Band): Boolean =
    a.rite.any { it >= 0 } && b.rite.any { it >= 0 } && a.rite.none { it >= 0 && it in b.rite }

/** Where the dead of lineage [lineage]'s bands are laid ([Band.burialWay]): one way drawn for each lineage. */
fun burialWayAt(lineage: Int) = BurialWay.entries[((mix64(lineage.toLong() * HASH_C + HASH_B) ushr 33) % BurialWay.entries.size).toInt()]

/**
 * Years the bands that keep a totem hold it against a band of it that ate of its kind ([Band.totemBroken]): a year, as it
 * goes round the bands that meet. An estimate; no figure found.
 */
internal const val TOTEM_SHAME_YEARS = 1f

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
 * or robs its cache, what it gives, how far it believes what it is told, whom it takes partners from, whose meeting place it
 * takes up, and whom it counts as friend or foe about it.
 */
internal class Regard(private val of: Band, private val toId: Int, private val to: Band, private var heard: Float = -1f, private val map: HexMap? = null) {
    val understood: Float get() { if (heard < 0f) heard = Language.understanding(of.words, to.words); return heard }
    val tie get() = of.tie(toId)
    val close get() = closeOf(of, toId)
    /** Close enough to count as friends: given ornaments, its cache left alone. */
    val friend get() = tie > FRIEND_TIE
    /** Never met before. */
    val stranger get() = toId !in of.ties
    /** Its grudge against their people (their main lineage), and whether it is a feud: enough to raid them unprovoked. */
    val grudge get() = of.grudge[to.mainLineage()]
    val feud get() = grudge > FEUD
    val kin get() = kinship(of.lineage, to.lineage)
    /**
     * How many of its customs ([CUSTOMS]: its traditions, its totem as the other keeps it, where it lays its dead, if both
     * bury them, its rites' sites, if both keep rites: [ritesApart], how it lives: [livelihood], and its home ground, if both
     * have homes: [homesApart]; read where the map is at hand) the other holds otherwise.
     */
    val customsApart get() = Tradition.entries.count { of.holds(it) != to.holds(it) } + (if (of.totem != to.keptTotem) 1 else 0) +
        (if (of.holds(Tradition.BURIAL) && to.holds(Tradition.BURIAL) && of.burialWay != to.burialWay) 1 else 0) +
        (if (ritesApart(of, to)) 1 else 0) + (if (of.livelihood() != to.livelihood()) 1 else 0) +
        (if (map != null && homesApart(of, to, map)) 1 else 0)

    /**
     * The chance [of] falls on [to], [drive] being the chance at its least (what drives it, by its readiness): the less they
     * understand each other and the less kin, the likelier; close friends hardly; strangers and those of other customs the
     * likelier.
     */
    fun fightChance(drive: Float) = drive * (0.5f + 0.5f * (1f - understood)) * (1f - 0.5f * kin) * (1f - TIE_PEACE * close) *
        (1f + (if (stranger) STRANGER_HOSTILITY else 0f) + CUSTOMS_HOSTILITY * (customsApart.toFloat() / CUSTOMS)) *
        (if (holy) 1f / (1f + of.riteStrength) else 1f)
    /**
     * They meet at a rite both keep at this site, in its month ([gatherAtRite]): [of] holds the peace there as far as it
     * holds to its rites ([Band.riteStrength]; none at all, it fights as anywhere). Its choice: nothing stops it.
     */
    var holy = false
    /** How many times readier than at its least [of] is to fight [to] ([fightChance] of 1). */
    val wariness get() = fightChance(1f)
    /** How far [of] believes what [to] tells it of the places it remembers. */
    val trust get() = (0.5f + 0.5f * close) * understood
    /**
     * How readily [of] exchanges partners with [to], 0 to 1: as far as they understand each other and keep the same customs
     * ([customsApart]). Most marriages fall within a people that speak one tongue and keep one way; some peoples marry out
     * of their language on purpose (the Vaupés, parts of Arnhem Land), so this is an estimate, not a rule from the record.
     */
    val weds get() = understood * (1f - customsApart.toFloat() / CUSTOMS)
    /** The chance [of] takes up [to]'s meeting place over its own weaker one. */
    val takesUpPlace get() = understood * (0.3f + 0.7f * close)

    companion object {
        /**
         * How close [of] is to the band of group [toId], as [close] reads it: for a choice that turns on a band it knows of
         * only by its group (a home it was told of), whether or not that band is still about.
         */
        fun closeOf(of: Band, toId: Int) = max(0f, of.tie(toId))
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
internal fun HexGroups.meetingEndsInRaid(a: HexGroup, b: HexGroup, weather: Weather, rite: RiteGathering? = null): Boolean {
    val bandA = a.band ?: return false; val bandB = b.band ?: return false
    var lap = Profile.mark()
    bandA.ensureMarks(fauna.kinds.size); bandB.ensureMarks(fauna.kinds.size)
    val u = Language.understanding(bandA.words, bandB.words)
    val ab = Regard(bandA, b.id, bandB, u, hex); val ba = Regard(bandB, a.id, bandA, u, hex)
    if (rite != null && rite.site in bandA.rite && rite.site in bandB.rite) { ab.holy = true; ba.holy = true }
    val metAs = MetAs(ab, ba)
    tallyMeeting(metAs, Tally.MeetingEnd.MET)
    lap = Profile.lap("bandsMeet: regard and tally", lap)
    val raider = if (raid(a, bandA, b, bandB, ab, weather)) bandA else if (raid(b, bandB, a, bandA, ba, weather)) bandB else null
    lap = Profile.lap("bandsMeet: raid chance", lap)
    if (raider != null) {
        tallyMeeting(metAs, Tally.MeetingEnd.RAIDED)
        if (ab.holy && rite != null) { if (raider === bandA) sacrilege(bandA, a.id, b.id, rite) else sacrilege(bandB, b.id, a.id, rite) }
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
    lap = Profile.lap("bandsMeet: words", lap)
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
            when (takeUpSide(rnd.nextFloat(), toA, toB)) { TakeUp.A -> bandB to bandA; TakeUp.B -> bandA to bandB; TakeUp.NEITHER -> continue }
        }
        to.traditions[t] = from.holds(t)
        stats.meeting(if (from.holds(t)) Met.Of.TOOK_UP else Met.Of.TOOK_LEAVE_OF, t.name.lowercase())
    }
    // A totem goes the same way as a mark: either may take up the other's as the other keeps it (none, if it ate of its own).
    val keptA = bandA.keptTotem; val keptB = bandB.keptTotem
    if (keptA != keptB) {
        val toA = COPY_TRADITION * u * conformist(bandA.heardTotemShare(keptB))
        val toB = COPY_TRADITION * u * conformist(bandB.heardTotemShare(keptA))
        when (takeUpSide(rnd.nextFloat(), toA, toB)) {
            TakeUp.A -> { bandA.totem = keptB; bandA.totemBroken = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_TOTEM) }
            TakeUp.B -> { bandB.totem = keptA; bandB.totemBroken = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_TOTEM) }
            TakeUp.NEITHER -> {}
        }
    }
    // And where bands that both bury their dead lay them.
    if (bandA.holds(Tradition.BURIAL) && bandB.holds(Tradition.BURIAL) && bandA.burialWay != bandB.burialWay) {
        val toA = COPY_TRADITION * u * conformist(bandA.heardBurialShare(bandB.burialWay))
        val toB = COPY_TRADITION * u * conformist(bandB.heardBurialShare(bandA.burialWay))
        when (takeUpSide(rnd.nextFloat(), toA, toB)) {
            TakeUp.A -> { bandA.burialWay = bandB.burialWay; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_BURIAL) }
            TakeUp.B -> { bandB.burialWay = bandA.burialWay; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_BURIAL) }
            TakeUp.NEITHER -> {}
        }
    }
    lap = Profile.lap("bandsMeet: traditions, totem, burial", lap)
    riteMeet(bandA, bandB, ab, ba)
    lap = Profile.lap("bandsMeet: rite meet", lap)
    teach(bandA, bandB, u, (doing(a) - doing(b)).coerceIn(-1f, 1f))
    lap = Profile.lap("bandsMeet: teach", lap)
    // And the sick among them pass on what is catching.
    passIllness(bandA, bandB)
    lap = Profile.lap("bandsMeet: illness", lap)
    // Each tells the other of the country it knows round about, as far as they understand each other.
    if (rnd.nextFloat() < u * (0.6f + 0.4f * close)) tellOfCountry(a, bandA, b, bandB, ab, ba, weather)
    lap = Profile.lap("bandsMeet: tell of country", lap)
    // Partners: where either holds to marrying out, young adults change bands, both ways, between bands that understand each
    // other and keep the same ways the likelier.
    val marry = (if (bandA.holds(Tradition.EXOGAMY)) 0.5f else 0f) + (if (bandB.holds(Tradition.EXOGAMY)) 0.5f else 0f)
    // (As far as each would take partners from the other: [Regard.weds], the less willing of the two.)
    if (a.adults >= 2 && b.adults >= 2 && rnd.nextFloat() < MARRY * marry * (0.5f + close) * min(ab.weds, ba.weds)) {
        marryOut(a, bandA, b, bandB)
        bandA.befriend(b.id, TIE_MARRY); bandB.befriend(a.id, TIE_MARRY)
        stats.meeting(Met.BANDS_EXCHANGED_PARTNERS)
        tallyMeeting(metAs, Tally.MeetingEnd.PARTNERS)
    }
    Profile.lap("bandsMeet: partners", lap)
    return false
}

/** Which of two bands a meeting's one draw has take up the other's way: [A], [B], or neither. */
private enum class TakeUp { A, B, NEITHER }

/** The one draw [r] for the two sides of a take-up, so that neither comes first: A below [toA], B in the [toB] after it. */
private fun takeUpSide(r: Float, toA: Float, toB: Float) = if (r < toA) TakeUp.A else if (r < toA + toB) TakeUp.B else TakeUp.NEITHER

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
    var lap = Profile.mark()
    val h0 = hw.placeHex[a.place]
    var ring = listOf(h0); val seen = hashSetOf(h0)
    for (r in 0..TOLD_RINGS) {
        for (h in ring) { if (bandA.knows(h)) bandB.learn(h, hex.size); if (bandB.knows(h)) bandA.learn(h, hex.size) }
        if (r == TOLD_RINGS) break
        ring = hex.nextRing(ring, seen)
    }
    stats.meeting(Met.BANDS_TOLD_OF_COUNTRY)
    lap = Profile.lap("tellOfCountry: hexes", lap)
    // And of the ways they know further off: landmarks, and the legs between those they know.
    val toldWays = routes.tell(bandA, bandB, h0) + routes.tell(bandB, bandA, h0)
    if (toldWays > 0) stats.meeting(Met.BANDS_TOLD_OF_ROUTES)
    lap = Profile.lap("tellOfCountry: routes", lap)
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
        // (A home the hearer knows already is nothing to tell: no draw is made for it. Homes far from where they meet are not talked of, as the country is not: see [REMEMBERED_KM].)
        for ((id, hh) in bt.knownHomes) if (id != hearer.id && id !in bh.knownHomes && hex.distanceKm(hh, h0) <= REMEMBERED_KM && rnd.nextFloat() < u) {
            bh.knownHomes[id] = hh
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
    lap = Profile.lap("tellOfCountry: homes and appointments", lap)
    // And of the places they remember, each as far as it trusts the other.
    a.memory.hear(b.memory, wk, stepsPerYear, ab.trust); b.memory.hear(a.memory, wk, stepsPerYear, ba.trust)
    Profile.lap("tellOfCountry: places remembered", lap)
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
    // (A band takes in the skill [got] of its partners hold, a little short of [best], the best of the band they came from.)
    fun takeIn(to: Band, i: Skill, got: Float, best: Float) {
        if (got <= 0f) return
        if (!to.knowsSkill[i]) {
            to.knowsSkill[i] = true
            to.skills[i] = 0f
            to.lastUsed[i] = to.weeks
        }
        to.skills[i] = max(to.skills[i], best - TRANSMIT_LOSS)
        to.holders[i] = max(0f, to.holders[i]) + got
    }
    for (i in Skill.entries) {
        val la = bandA.skills[i]; val lb = bandB.skills[i]
        takeIn(bandA, i, fromB[i], lb)
        takeIn(bandB, i, fromA[i], la)
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
 * it eats what makes up its week, and takes some home; a band that holds to sharing gives more, and a band gives more
 * to one it is close to ([Regard.close]), as at a meeting.
 */
internal fun HexGroups.visitKin(weather: Weather) {
    // (The hungry come in a drawn order, not by age in the list: the first take the most from the best-stocked.)
    val today = weather.date.absoluteDay
    val hungry = groups.filter { it.alive && it.band != null && it.fed < VISIT_FED && it.larder < it.dailyNeedKg * days }.sortedBy { tieBreak(it.id, 0, today) }
    if (hungry.isEmpty()) return
    val byHex = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null && g.larder > 0f) byHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in hungry) {
        val gb = g.band ?: continue
        // The best-stocked band within two hexes, the nearer the better.
        var best: HexGroup? = null; var bestScore = 0f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in byHex[h].orEmpty()) if (o !== g) {
                // (What it can hope for there: an ordinary share of the larder, the more the closer the band holds it
                // ([Regard.close], as at a meeting), or what the band owes it.)
                val owed = min(0.9f * o.larder, o.band!!.owesTo(g.id) * FAVOUR_DAYS * g.dailyNeedKg)
                val ob = o.band ?: continue
                val score = max(BAND_GIFT * (1f + Regard(ob, g.id, gb, map = hex).close) * o.larder, owed) / (1f + r)
                if (score > bestScore) {
                    bestScore = score
                    best = o
                }
            }
            if (r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        val o = best ?: continue
        // (How much the band visited gives is its choice, more to a band it is close to, as at a meeting; and it owes the
        // favours it owes.)
        val close = o.band?.let { ob -> Regard(ob, g.id, gb, map = hex).close } ?: 0f
        val base = min(0.9f, BAND_GIFT * (if (o.band!!.holds(Tradition.SHARING)) 2f else 1f) * (1f + close))
        val share = 0.9f * shareOfWill(o.band!!.giveWill, base / 0.9f)
        o.band?.let { ob -> dialUse?.amount(DialSite.GIVE_VISIT, ob.giveWill, share, base) }
        val week = g.dailyNeedKg * days
        val need = (1f - g.fed) * week + week
        val gift = given(o, g, min(share * o.larder, need), need)
        if (gift <= 0f) continue
        val eat = min(gift, (1f - g.fed) * week)
        g.fed += eat / week
        moveFood(o, g, gift, LarderMove.KIN); val ateKg = eatStores(g, eat)
        land.books?.flow(inflation.Books.Stuff.MATTER, "eaten", -ateKg.toDouble())
        // Who was helped remembers: grudges against the helpers' people fade, and it owes them a favour.
        g.band!!.grudge[o.band!!.mainLineage()] *= 0.5f
        helped(o, g, gift)
        stats.meeting(Met.HUNGRY_BANDS_FED_BY_KIN)
    }
}

/**
 * A band's year, a week at a time: inbreeding builds in a closed band, grudges fade, words drift, traditions are now and then taken up or dropped.
 * Touches only the band's own state (and the week's tallies), so the bands' run side by side, each drawing from its own stream [rnd].
 */
internal fun HexGroups.cultureWeek(g: HexGroup, rnd: kotlin.random.Random) {
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
    // Country far from where it now lives fades from memory, a share of it a year; so do the homes of bands far off,
    // unless a tie (friendship or feud) keeps them in mind.
    if (rnd.nextFloat() < years) {
        val here = hw.placeHex[g.place]
        if (b.knownHomes.isNotEmpty()) {
            val it = b.knownHomes.entries.iterator()
            while (it.hasNext()) { val (id, hh) = it.next()
                if (hex.distanceKm(hh, here) > REMEMBERED_KM && id !in b.ties && rnd.nextFloat() < 0.5f) { it.remove(); b.knownLineage.remove(id) } }
        }
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
internal const val RITE_SITES = 3
/** Person-days each adult gives a rite held at its site: the gathering, the dances, the telling. An estimate; no figure found. */
internal const val RITE_DAYS = 2f
/**
 * Strain a young belief bears ([Band.strainBorne]): rites held back from, about the year's rites. Strained that far, a
 * band holds together at half its size ([HexGroups.maxSize]), and the part that leaves at a split goes without the belief
 * as likely as the strain is near it. A belief kept [BELIEF_AGE_YEARS] bears twice that (older beliefs are clung to). Strain eases to a third over
 * [STRAIN_EASE_YEARS] of weeks the band eats its fill. Estimates; no figure found.
 */
internal const val STRAIN_SPLIT = 3f
internal const val BELIEF_AGE_YEARS = 25f
internal const val STRAIN_EASE_YEARS = 1f
/** Years over which a place's sanctity fades to a third (1/e) while no rites are held and no dead laid there: about a generation. An estimate. */
internal const val SANCTITY_YEARS = 25f
/** How much a band's hold on its rites changes when it splits: a fifth of the founders' range, as temperament drifts. Not measured. */
internal val HexGroups.riteDrift get() = tuning.riteFounderMax / 5f
/** Chance at a split that one of the offshoot's rites shifts by a month (the telling changes). Not measured. */
internal const val RITE_SHIFT = 0.1f

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
        gatherAtRite(g, b.riteDue, b.riteHeld, weather)
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
            gatherAtRite(g, b.riteDue, b.riteHeld, weather)
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
    // Plenty after long want is an omen too: where and when a spell on its reserves ended, against its usual spell.
    if (b.wantBroke > 0) {
        omen(b, g.place, weather.date, b.wantBroke / max(LEAN_USUAL_WEEKS, b.wantUsual), Met.BANDS_MARKED_PLENTY_AFTER_WANT)
        b.wantUsual += 0.5f * (b.wantBroke - b.wantUsual); b.wantBroke = 0
    }
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
 * [g] held its rite at [site] in [month] (counted from the world's start): there it meets each band that held its own rite
 * at the same site that month, as bands that come across each other do ([bandsMeet]: news, gifts, words and customs, and
 * a raid if how they regard each other comes to it). Nothing keeps the peace but what the meeting makes of them; a shared
 * date ([turnOfYear]) makes such gatherings the likelier.
 */
private fun HexGroups.gatherAtRite(g: HexGroup, site: Int, month: Long, weather: Weather) {
    riteGatherings.keys.removeIf { (it shr 32) < month - 1 }
    val here = riteGatherings.getOrPut((month shl 32) or site.toLong()) { ArrayList() }
    // (A band with sickness among its people that keeps away from the sick keeps its own away too: it holds the rite where
    // it is, as far as its will to shun the sick goes, and misses the gathering.)
    val b = g.band
    if (b != null && b.sick.sum() > SICK_SHOWS &&
        drawn(DialSite.SHUN_SICK, b.will[BandWay.SHUNNING_THE_SICK], rnd.nextFloat(), b.shunSick) { 0.5f }) {
        stats.meeting(Met.SICK_BANDS_KEPT_FROM_A_RITE); return
    }
    val rite = RiteGathering(site, here)
    for (o in here) if (g.alive && o.alive && o !== g) {
        stats.meeting(Met.BANDS_GATHERED_AT_A_RITE)
        bandsMeet(g, o, weather, rite)
    }
    here += g
}

/** A rite's gathering under way ([gatherAtRite]): its [site], and the bands [there] before the one now coming. */
internal class RiteGathering(val site: Int, val there: List<HexGroup>)

/**
 * [raider] (group [raiderId]) fell on band [victim] at a rite both keep, before the others gathered there: each that keeps the rite holds it
 * against the raider's people as far as it holds to its rites ([Band.riteStrength], as for the peace itself), and thinks
 * the less of the raider. The cost of breaking the peace is what the others remember.
 */
private fun HexGroups.sacrilege(raider: Band, raiderId: Int, victim: Int, rite: RiteGathering) {
    stats.meeting(Met.RAIDS_ON_HOLY_GROUND)
    for (w in rite.there) {
        val wb = w.band ?: continue
        if (!w.alive || w.id == raiderId || w.id == victim || rite.site !in wb.rite) continue
        val held = wb.riteStrength / (1f + wb.riteStrength)
        wb.grudge[raider.mainLineage()] += held * GRUDGE
        wb.befriend(raiderId, -held * TIE_RAID)
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
internal const val SOURCE_KM = 50.0
internal const val SOURCE_MIN = 0.1f

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
 * usual, [Band.deathsUsual]; a spell of steps on its reserves ended, against its usual spell, [Band.wantUsual], and
 * never less than [LEAN_USUAL_WEEKS]); it fades to a
 * third over [OMEN_YEARS] and is forgotten once no more than the usual, unless borne out; one [OMEN_AT_ONCE] times the usual
 * is a rite at once. A band can't make a rite of what is usual for it.
 * Estimates; no figure found.
 */
internal const val SALIENT = 3f
internal const val OMEN_AT_ONCE = 10f
internal const val OMEN_YEARS = 1f
internal const val LEAN_USUAL_WEEKS = 2f

/**
 * Strange things a band sees in a year, good signs and bad alike; how strange each is, against the usual, falls off as
 * one over it (a third are [SALIENT], a tenth [OMEN_AT_ONCE]). Nothing in the world causes them and nothing follows from
 * them but the rite they may become. Estimate; no figure found.
 */
internal const val STRANGE_SIGNS_A_YEAR = 1f

/** Days of a band's food that make one favour ([Band.favours]): a week. */
internal const val FAVOUR_DAYS = 7f

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
