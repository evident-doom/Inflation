package inflation.ecology

import inflation.Part
import inflation.enumFloats
import inflation.world.Hex
import inflation.world.get
import inflation.world.set
import inflation.world.Biome
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

// Bands meeting: how new a meeting is, whether it ends in a raid, what each takes up of the other's ways, the country they tell of, partners exchanged, kin visited, food given and the favours owed for it.

/** Years between two meetings of the same bands after which a meeting is wholly new: news is told, a feast given, once a season. An estimate. */
internal const val MEETING_AGAIN_YEARS = 0.25f

/**
 * How new this meeting of [a] and [b] is, 0..1: 1 if they have not met within [MEETING_AGAIN_YEARS], less the more lately
 * they last met. Bands that meet every week share words, ways and company every time (their cultures come closer
 * gradually) but tell news and feast each other in proportion to it: over a season, once between them. Records the meeting.
 */
internal fun HexGroups.meetingFresh(a: HexGroup, b: HexGroup, weather: Weather): Float {
    val ba = a.band ?: return 1f; val bb = b.band ?: return 1f
    val now = stepOf(weather.date)
    val last = ba.lastMet[b.id]
    ba.lastMet[b.id] = now; bb.lastMet[a.id] = now
    return if (last == null) 1f else ((now - last) / (MEETING_AGAIN_YEARS * stepsPerYear)).coerceIn(0f, 1f)
}

/**
 * Two bands meet. If one is hungry and the other well stocked, or one holds a feud against the
 * other's people, it may raid: the less they understand each other, the likelier. Otherwise they
 * talk: they bring a few words into line, the less successful band may take up the other's ways,
 * they show each other tools, and bands that marry out exchange partners, who bring their skills.
 * Returns true if it came to a raid.
 */
@Part("meetings: talk or raid")
internal fun HexGroups.meetingEndsInRaid(a: HexGroup, b: HexGroup, weather: Weather, rite: RiteGathering? = null, fresh: Float = 1f): Boolean {
    val bandA = a.band ?: return false; val bandB = b.band ?: return false
    bandA.ensureMarks(fauna.totemKinds); bandB.ensureMarks(fauna.totemKinds)
    val u = Language.understanding(bandA.words, bandB.words)
    val ab = Regard(bandA, b.id, bandB, u, hex); val ba = Regard(bandB, a.id, bandA, u, hex)
    if (rite != null && rite.site in bandA.rite && rite.site in bandB.rite) { ab.holy = true; ba.holy = true }
    val metAs = MetAs(ab, ba)
    tallyMeeting(metAs, Tally.MeetingEnd.MET)
    val raider = if (raid(a, bandA, b, bandB, ab, weather)) bandA else if (raid(b, bandB, a, bandA, ba, weather)) bandB else null
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
    // A way of life goes as a mark does, as far as a band holds its own loosely ([Band.livelihoodHold]): either may come to
    // hold the one the other shows (none shown by a band living against its own).
    val showA = bandA.shownLivelihood; val showB = bandB.shownLivelihood
    val toA = if (showB != null && showB != bandA.heldLivelihood) COPY_TRADITION * u * conformist(bandA.heardLivelihoodShare(showB)) * (1f - bandA.livelihoodHold) else 0f
    val toB = if (showA != null && showA != bandB.heldLivelihood) COPY_TRADITION * u * conformist(bandB.heardLivelihoodShare(showA)) * (1f - bandB.livelihoodHold) else 0f
    if (toA + toB > 0f) when (takeUpSide(rnd.nextFloat(), toA, toB)) {
        TakeUp.A -> if (showB != null) { bandA.heldLivelihood = showB; bandA.livedOther = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_WAY_OF_LIFE) }
        TakeUp.B -> if (showA != null) { bandB.heldLivelihood = showA; bandB.livedOther = 0f; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_WAY_OF_LIFE) }
        TakeUp.NEITHER -> {}
    }
    riteMeet(bandA, bandB, ab, ba)
    teach(bandA, bandB, u, (doing(a) - doing(b)).coerceIn(-1f, 1f))
    // And the sick among them pass on what is catching.
    passIllness(bandA, bandB)
    // Each tells the other of the country it knows round about, as far as they understand each other.
    // (News told is old: bands that meet again and again have little new to tell, [meetingFresh].)
    if (rnd.nextFloat() < u * (0.6f + 0.4f * close) * fresh) tellOfCountry(a, bandA, b, bandB, ab, ba, weather)
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
    stats.tally(Tally.meetingsBy(metAs.understood, what), 1)
    stats.tally(Tally.meetingsBy(metAs.customs, what), 1)
    stats.tally(Tally.meetingsBy(metAs.wary, what), 1)
}

/**
 * Bands [a] and [b] (regarding each other as [ab] and [ba]) tell each other of the country round about,
 * the routes they know, where they met, where their homes and those of others they know are, the
 * appointments they keep, and the places they remember (as far as they trust each other).
 */
@Part("meetings: tell of country")
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
        // (A home the hearer knows already is nothing to tell: no draw is made for it. Most are: bands that meet come to know the same homes.)
        for ((id, hh) in bt.knownHomes) if (id != hearer.id && id !in bh.knownHomes && rnd.nextFloat() < u) {
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
    // And of the places they remember, each as far as it trusts the other.
    a.memory.hear(b.memory, wk, stepsPerYear, ab.trust); b.memory.hear(a.memory, wk, stepsPerYear, ba.trust)
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
@Part("people: visit kin")
internal fun HexGroups.visitKin(weather: Weather) {
    // (The hungry come in a drawn order, not by age in the list: the first take the most from the best-stocked.)
    val today = weather.date.absoluteDay
    val hungry = groups.filter { it.alive && it.band != null && it.fed < VISIT_FED && it.larder < it.dailyNeedKg * days }.sortedBy { tieBreak(seedBase, it.id, 0, today) }
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
