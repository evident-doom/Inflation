package inflation.ecology

import inflation.drawKey
import inflation.drawUnit
import inflation.Part
import inflation.EnumBools
import inflation.Stream
import inflation.world.Hex
import inflation.world.HexMap
import inflation.world.get
import inflation.world.set
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64
import inflation.world.Date
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.exp

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

/** Rings of hexes round a meeting whose country bands tell each other of; country further than this from a band's camp is half forgotten each year. */
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
fun lineageAt(seed: Long, x: Double, y: Double): Int {
    val bx = Math.floorDiv(x.toLong(), LINEAGE_KM.toLong()); val by = Math.floorDiv(y.toLong(), LINEAGE_KM.toLong())
    return ((drawKey(seed, Stream.LINEAGE, bx, by) ushr 33) % LINEAGES).toInt()
}

/** Words of a band founded at ([x], [y]) km: half named as in its own country, half as in the wider region, so neighbours understand each other in part. */
fun wordsAt(seed: Long, x: Double, y: Double): IntArray {
    val fx = (x / LINEAGE_KM).toInt(); val fy = (y / LINEAGE_KM).toInt()
    val cx = (x / (3 * LINEAGE_KM)).toInt(); val cy = (y / (3 * LINEAGE_KM)).toInt()
    return IntArray(Language.CONCEPTS) { k ->
        val (bx, by) = if (k % 2 == 0) fx to fy else (cx + 1000) to (cy + 1000)
        ((drawKey(seed, Stream.WORDS, bx.toLong(), by.toLong(), k.toLong()) ushr 1) % Language.FORMS).toInt()
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
 * How [this] band lives now: it farms while it keeps seed corn of its own crop ([Band.seedCorn]) to sow or has a crop in the
 * ground sown within the year ([Band.sownKg], [Band.sinceSowed]: all its seed sown, it is a farmer still until the harvest),
 * herds while it keeps a herd, and forages otherwise. (Farming first: those who keep both are farmers who keep beasts.)
 */
fun Band.livelihood() = if (seedCorn > 0f || (sownKg > 0f && sinceSowed < 1f)) Livelihood.FARMING else if (herds.herd.any { it > 0f }) Livelihood.HERDING else Livelihood.FORAGING

/**
 * How firmly the founders of lineage [lineage] hold their way of life ([Band.livelihoodHold]), 0 to 1: one drawn for each lineage,
 * spread evenly, so that some peoples take to a new way at once and others keep theirs for a generation of living otherwise.
 * Not measured; kept wide so that runs try both.
 */
fun livelihoodHoldAt(lineage: Int) = Math.floorMod((lineage + 11) * 2_654_435_761L.toInt() xor 0x27d4eb2f, 1000) / 1000f

/**
 * Years a band that holds its way of life at its firmest ([Band.livelihoodHold] 1) lives otherwise before it comes to hold how
 * it lives: a generation ([GENERATION_YEARS]), as the young who grew up in the new way take over. An estimate (Olli,
 * 2026-11-02); no figure found.
 */
internal const val LIVELIHOOD_YEARS = GENERATION_YEARS

/**
 * An offshoot's hold on its way of life ([Band.livelihoodHold]): its parent's ([parent]), drifting with a spread (SD) of a fifth
 * of the founders' range (as rites and temperament drift), drawn from its id [id] and the world's [seed].
 */
fun livelihoodHoldInherit(parent: Float, id: Int, seed: Long): Float {
    fun u(salt: Long) = drawUnit(seed, Stream.LIVELIHOOD, id.toLong(), salt)
    return (parent + LIVELIHOOD_DRIFT * (u(1) + u(2) + u(3) - 1.5f) * 2f).coerceIn(0f, 1f)
}

/**
 * The way of life a way of working belongs to, if any: tending kept animals to herding; sealing storage bins and building
 * of mudbrick to farming. The rest (gathering, the hunt, crafts, houses of stone or dug in) belong to every way of life.
 * A judgement, not a measure.
 */
val Skill.ofLivelihood: Livelihood? get() = when (this) {
    Skill.TENDING -> Livelihood.HERDING
    Skill.SEALING, Skill.MUDBRICK -> Livelihood.FARMING
    else -> null
}

/**
 * How many times faster than [SKILL_DROP] band [this] loses the holders of way [s] while unused: as fast as any way if [s]
 * belongs to the way of life it holds or to none, else the faster the firmer it holds its own (twice at the firmest: "that is
 * not what we do"). An estimate.
 */
fun Band.dropPace(s: Skill): Float { val l = s.ofLivelihood; return if (l == null || l == heldLivelihood) 1f else 1f + livelihoodHold }

/** The spread (SD) of an offshoot's hold on its way of life about its parent's: a fifth of the founders' range. Not measured. */
internal const val LIVELIHOOD_DRIFT = 0.2f

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
     * bury them, its rites' sites, if both keep rites: [ritesApart], the way of life it holds against the one the other shows
     * ([Band.shownLivelihood]: none while it lives against its own), and its home ground, if both
     * have homes: [homesApart]; read where the map is at hand) the other holds otherwise.
     */
    val customsApart get() = Tradition.entries.count { of.holds(it) != to.holds(it) } + (if (of.totem != to.keptTotem) 1 else 0) +
        (if (of.holds(Tradition.BURIAL) && to.holds(Tradition.BURIAL) && of.burialWay != to.burialWay) 1 else 0) +
        (if (ritesApart(of, to)) 1 else 0) + (if (of.heldLivelihood != to.shownLivelihood) 1 else 0) +
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
 * A band's year, a week at a time: inbreeding builds in a closed band, grudges fade, words drift, traditions are now and then taken up or dropped.
 * Touches only the band's own state (and the week's tallies), so the bands' run side by side, each drawing from its own stream [rnd].
 */
@Part("people: cultureWeek")
internal fun HexGroups.cultureWeek(g: HexGroup, rnd: kotlin.random.Random, weeks: Int = 1) {
    val b = g.band ?: return
    // (Over [weeks] weeks' time at once where the customs week is kept less often, [inflation.Ablation.CULTURE_WEEK]; 1 as a rule.)
    val years = if (weeks == 1) this.years else this.years * weeks
    val days = if (weeks == 1) this.days else this.days * weeks
    // A home it knows of that it passed this week and found no house standing (its people dead, or
    // moved on) is forgotten: it is no longer ground to keep off, nor a place to raid.
    if (b.knownHomes.isNotEmpty()) {
        val it = b.knownHomes.entries.iterator()
        while (it.hasNext()) { val (id, hh) = it.next(); if (hh in g.path && bands.houseOf[id] != hh) { it.remove(); b.knownLineage.remove(id); b.homesVersion++ } }
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
    b.ensureMarks(fauna.totemKinds)
    b.sinceSowed += years
    b.raidDeathRate *= exp(-years)
    // (It comes to hold the way it lives once it has lived so for long enough, the longer the firmer it held its own.)
    val lives = b.livelihood()
    if (lives == b.heldLivelihood) b.livedOther = 0f
    else {
        b.livedOther += years
        if (b.livedOther >= b.livelihoodHold * LIVELIHOOD_YEARS) { b.heldLivelihood = lives; b.livedOther = 0f; stats.meeting(Met.BANDS_CAME_TO_HOLD_THEIR_WAY_OF_LIFE) }
    }
    for (i in b.heardTotem.indices) b.heardTotem[i] *= keep
    for (w in BurialWay.entries) b.heardBurial[w] *= keep
    for (l in Livelihood.entries) b.heardLivelihood[l] *= keep
    b.totemBroken = max(0f, b.totemBroken - years)
    // (Now and then a band gives up its totem, or one without takes up a kind as its own, as with its traditions.)
    if (fauna.totemKinds > 0 && rnd.nextFloat() < (if (b.totem.none) TAKE_UP else DROP) * years) {
        b.totem = if (b.totem.none) FaunaKind(rnd.nextInt(fauna.totemKinds)) else FaunaKind.NONE; b.totemBroken = 0f
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
