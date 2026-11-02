package inflation.ecology

import inflation.Par
import inflation.Part
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.world.hexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.gaussianF
import inflation.KindStock
import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.Kg
import inflation.KgM2
import inflation.Tuning
import inflation.store.MovedInto
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.Config
import kotlin.math.roundToLong

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.ecology.HexFeatures as Feature
import inflation.world.Biome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How each group chooses where to go this week ([plan]): the places it can reach for the effort it will spend
 * ([searchReach]), what it knows and expects of each ([PlaceTerms], [fillFeatures]), the hand-written score ([scorePlace])
 * or its kind's learned policy, and the deaths a place would cost ([deathsAt]). It keeps what the choosing reads across
 * the week's groups: each place's worth to each kind, what a group may expect where it has not looked, the strongest
 * hunter in each place, and the searches groups of a kind share. What it asks of the groups and their world goes
 * through [gs].
 */
internal class GroupPlanner(internal val gs: HexGroups) {

    /** A week's plan: the [place] a group heads for and the hexes of its [path] there. */
    class Plan(val place: Place, val path: HexArray) { operator fun component1() = place; operator fun component2() = path }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    @Part("choosing: plan one group")
    internal fun plan(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray): Plan {
        return planInner(g, weather, date, predatorKg)
    }

    private fun planInner(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray): Plan {
        val s = g.species
        val here = gs.hw.placeHex[g.place]
        // What it sees of the others: in its own hex and the six around, which its week's ranging covers.
        // Beyond, it goes by what it expects (see [expect]).
        val sensed = hexArray(7) { if (it == 0) here else gs.hex.neighbour(here, it - 1) }
        // How far it will shift its ground this week. Most of a week's walking is within its range
        // (grazing, patrolling); the net shift is a couple of days' going when content, more on a trek
        // when hungry, cold or snowed in. A pack holding ground patrols it; a starving or homeless one roams.
        val thirsty = g.dry > 0.5f * s.daysWithoutWater
        // The place a band remembers doing best at this time of year: while it is far off, the band is on its way.
        val month = wrapMonth(date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
        val goal = g.band?.seasonPlace?.get(month) ?: -1
        val goalQ = g.band?.seasonQ?.get(month) ?: 0f
        // Far out of its country, it treks.
        val astray = gs.bearings.strength(gs.kindSlot[s.index], here) > ASTRAY
        // (A band whose appointment falls this month or next, and is not there, sets off: it is restless.)
        val dueSoon = g.band?.let { b -> b.appointment.let { ap -> ap != null && ap.hex != here && monthsFrom(month, ap.month) <= 1 } ||
            b.unluckyHex == here || b.raid != null } ?: false
        val restless = gs.restless(g, weather, thirsty, astray, dueSoon)
        // (Every group weighs its places every week.)
        val capture = gs.control != null && g.id == gs.watched
        // (A band goes at the pace of its slowest: the old, the sick and the lamed hold it back.)
        val weak = weakShare(g)
        val reach = (if (restless) s.maxDailyTravelKm else s.dailyTravelKm) * SHIFT_DAYS * (1f - WEAK_SLOW * weak) *
            (if (s.human) 1f / (1f + COVER_GOING * gs.land.plants.cover(g.place)) else 1f)
        val kind = gs.kindSlot[s.index]
        // Least effort to each hex within reach.
        val buf = scratch.get()
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        searchReach(g, here, reach, restless, weather, buf)
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = gs.policies[s]
        // (moveTrace: a band choosing by the hand-written score books what moved it, [bookMove].)
        val traced = MOVE_TRACE && g.band != null && policy == null
        if (traced) buf.stayParts.fill(0f)
        // (For a followed group: every place it weighed, and how it scored.)
        // planDiag=kind: once a month, one group's reasons, for the place it chose and the best-placed step toward its season's place.
        val diag = goal >= 0 && date.dayOfMonth <= gs.days && PLAN_REASONS.wants(g.id.toLong(), of = s.name)
        val seen = if (g.id == gs.watched || diag) ArrayList<Pair<Int, Float>>() else null
        val candPlaces = buf.candPlaces; candPlaces.clear(); val candFeats = buf.candFeats
        // (Every place's whole score is wanted for a network, for capture and for a watched group; else only the best.)
        // (A band draws its place by its will to explore, from every place it weighs, see [exploreChoice].)
        val explores = g.band != null
        val wantAll = seen != null || policy != null || capture || explores
        // (A network that reads no hand-written score, for a group that does not explore and is not followed: the score and
        // the deaths it weighs are not worked out at all, only what the network reads.)
        val handless = policy != null && seen == null && !capture && !explores && !traced && !policy.readsBaseline(g)
        buf.laterPlace.clear(); buf.explPlaces.clear()
        val season = 2 * Math.PI * (date.dayOfYear - Date.START_DAY) / Date.DAYS_PER_YEAR
        // Its ground is as big as the game on it needs it to be (smaller where game is thick), and a
        // hungry pack ranges wider: up to three times that after weeks of hunger.
        val homeValue = if (!g.home.none) placeValue[kind][gs.hw.placeNear(g.place, g.home).takeIf { !it.none }?.i ?: g.place.i] else PREY_HALF
        // (A pack with young in the den, from its births for [inflation.Tuning.denMonths], hunts only
        // within [inflation.Tuning.denKm] of its home, however hungry: it cannot follow game that moves on.)
        val denning = s.lifeway == Lifeway.PACK && !g.home.none && g.young > 0 && monthsFrom(g.birthMonth, month) < gs.tuning.denMonths
        val territory = if (denning) gs.tuning.denKm else gs.territoryKm(s) * sqrt(PREY_HALF / homeValue.coerceAtLeast(1f)).coerceIn(0.5f, 3f) *
            (1f + min(2f, g.lean.toFloat() / GIVE_UP_WEEKS))
        val goalHex = if (goal >= 0) gs.hw.placeHex[goal] else Hex.NONE
        val fromHere = if (goal >= 0) gs.hexKm(here, goalHex) else 0.0
        val pullKm = reach.toDouble().coerceAtLeast(gs.hex.hexKm)
        // (A band keeps off the known homes of bands it is not close to ([Regard.closeOf]): those hexes, once for the week.)
        // (And off the place raiders drove it from, while it fears them: [Band.keptFrom].)
        val keepOff = g.band?.let { b ->
            val off = HexList(8)
            for ((id, home) in b.knownHomes) if (Regard.closeOf(b, id) <= 0f) off.add(home)
            if (b.keptFrom(b.drivenFrom)) off.add(b.drivenFrom)
            off.toHexArray().also { it.sort() } }
        // A band: how far it lacks each of the rarer materials, and the remembered place with the most of what it lacks.
        val lacked = g.band?.let { b -> FloatArray(PlaceMemory.RARE.size) { k -> gs.lacking(g, b, PlaceMemory.RARE[k]) } }
        var lackedHex = Hex.NONE; var lackedQ = 0f
        if (lacked != null) { val mem = g.memory
            for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (!mem.hex[i].none) for (k in lacked.indices) {
                val q = lacked[k] * mem.foundAt(i, k)
                if (q > lackedQ) { lackedQ = q; lackedHex = mem.hex[i] }
            } }
        // (A band leaving its camp's hex caches or leaves what it cannot carry: the lives that costs it through the year ahead
        // count with each place it would move camp to, [Band.leavingDying].)
        val campHex = g.band?.campPlace?.takeIf { !it.none }?.let { gs.hw.placeHex[it] } ?: Hex.NONE
        val leaving = if (campHex.none) 0f else g.band?.leavingDying ?: 0f
        fun leavingAt(h: Hex) = if (leaving > 0f && h != campHex) leaving else 0f
        val c = PlanContext(g, kind, here, sensed, month, goal, goalQ, fromHere, reach, thirsty, restless, season, keepOff,
            weather, date, goalHex, predatorKg, buf, lacked, lackedHex, lackedQ, if (!lackedHex.none) gs.hexKm(here, lackedHex) else 0.0)
        val at = c.at
        for (ri in 0 until reached.size) {
            val h = reached[ri]
            val d = dist[h]
            at.d = d
            // How far beyond its ground the hex lies (0 within it), and how fresh another pack's mark on it is.
            val beyond = if (s.lifeway == Lifeway.PACK && !g.home.none) (gs.hexKm(h, g.home) / territory - 1.0).toFloat().coerceIn(0f, 2f) else 0f
            val foreign = if (s.lifeway == Lifeway.PACK && h != g.home) gs.scent.foreign(s, h, g.id) else 0f
            at.beyond = beyond; at.foreign = foreign
            // A pack on its own ground stays on it while fed, and keeps off ground another pack of its kind holds
            // (its choice: the hand-written score weighs these heavily; a learned one sees both, see [HexFeatures]).
            at.offGround = (if (beyond > 0f && (denning || (g.fed >= Thresholds.HUNGRY && !astray))) KEEP_TO_GROUND else 0f) +
                (if (foreign > MARK_FRESH && g.fed >= 0.3f) KEEP_OFF_MARKS else 0f)
            at.toGoal = if (goal >= 0) gs.hexKm(h, goalHex) else 0.0
            // The open water on the way there, as the share of it a crossing drowns (see [move]): worked out when first wanted.
            var drowning = -1f
            // Better country further off: the way the kind's sense of the land points.
            at.pull = gs.bearings.along(kind, here, h, pullKm)
            // (Its own kind about there, as it knows it.)
            val kin = kinAround(g, kind, h, sensed)
            val crowded = gs.crowdedBy(s, kin)
            at.crowded = crowded
            for (pi in gs.hw.placesOf(h)) {
                val p = Place(pi)
                val prey = knownGame(g, kind, p, h)
                val fed = foreseenFed(g, prey, kin, crowded) * gs.saltFeedsAt(g, p, h)
                val hunters = huntersKm2(c, p, h)
                at.prey = prey; at.fed = fed; at.hunters = hunters
                val t = buf.terms.also { fillTerms(it, g, p, weather, date) }
                if (handless) {
                    val nc = candPlaces.size
                    if (nc == candFeats.size) candFeats += FloatArray(Feature.size)
                    fillFeatures(candFeats[nc].also { it.fill(0f) }, c, t, p, h)
                    if (nc == buf.candScores.size) buf.candScores = buf.candScores.copyOf(2 * nc)
                    candPlaces += p.i; buf.candScores[nc] = 0f
                    continue
                }
                val tp = if (traced) buf.moveParts.also { it.fill(0f) } else null
                var score = scorePlace(c, t, p, h, tp)
                // (Above all, it keeps alive: see below.)
                val beside = 0.02f * (gs.hashNoise(g.id, p) - 0.5f) + gs.temperPull(g, p, h, hunters, crowded)
                // (Where only the best place is wanted, its deaths are worked out after, best first, see below.)
                if (!wantAll) { buf.later(p, d, prey, fed, score, beside); continue }
                if (drowning < 0f) drowning = crossingDeaths(s, h, here, via)
                val alive = score
                val steps = gs.survivalSteps(g, deathsAt(c, p, h, fed, d) + drowning + leavingAt(h))
                score -= steps
                if (tp != null) { tp[MoveTerm.DEATHS.ordinal] = -steps; tp[MoveTerm.BESIDE.ordinal] = beside
                    if (p == g.place) tp.copyInto(buf.stayParts) }
                if (!score.isFinite()) tellNotANumber(c, t, p, h, drowning, alive)
                if (explores) buf.explore(p, score + beside, alive - score)
                seen?.add(p.i to score)
                if (policy != null || capture) {
                    val nc = candPlaces.size
                    if (nc == candFeats.size) candFeats += FloatArray(Feature.size)
                    val f = candFeats[nc].also { it.fill(0f) }
                    fillFeatures(f, c, t, p, h)
                    if (nc == buf.candScores.size) buf.candScores = buf.candScores.copyOf(2 * nc)
                    candPlaces += p.i; buf.candScores[nc] = score
                    if (policy != null) continue
                }
                score += beside
                if (capture) buf.candScores[candPlaces.size - 1] = score
                if (score > bestScore) { bestScore = score; best = p; tp?.copyInto(buf.bestParts) }
            }
        }
        // Above all, it keeps alive: the share of it that would die at a place this week weighs against all the rest
        // (see [survivalSteps]). Its deaths only take away, so they are worked out best place first, and only while a place
        // could still beat the best so far: the same place is chosen as if every place's were (the first weighed, of equals).
        val nl = buf.laterPlace.size
        if (nl > 0) {
            val up = buf.laterUpper
            val order = (0 until nl).sortedWith { a, b -> if (up[a] != up[b]) up[b].compareTo(up[a]) else a.compareTo(b) }
            var bestK = Int.MAX_VALUE
            for (k in order) {
                if (up[k] < bestScore) break
                val p = Place(buf.laterPlace[k]); val h = gs.hw.placeHex[p]
                val total = buf.laterScore[k] - gs.survivalSteps(g, deathsAt(c, p, h, buf.laterFed[k], buf.laterD[k]) +
                    crossingDeaths(s, h, here, via) + leavingAt(h)) + buf.laterBeside[k]
                if (!total.isFinite()) {
                    at.d = buf.laterD[k]; at.prey = buf.laterPrey[k]; at.crowded = Float.NaN; at.fed = buf.laterFed[k]
                    tellNotANumber(c, null, p, h, crossingDeaths(s, h, here, via), buf.laterScore[k])
                }
                if (total > bestScore || (total == bestScore && k < bestK)) { bestScore = total; best = p; bestK = k }
            }
        }
        g.drawn = false; g.weighed = true
        if (policy != null && candPlaces.size > 0) {
            val nc = candPlaces.size
            best = Place(candPlaces[policy.choose(g, Array(nc) { candFeats[it] }, buf.candScores.copyOf(nc), gs.policyRandom(g.id, date.absoluteDay))])
        }
        // (A choice the policy drew by chance to learn from stands; every other band draws by its will to explore, in training worlds as outside them.)
        val chosen = best
        if (traced) bookMove(chosen != g.place, buf)
        g.band?.planLoads = Float.NaN
        if (explores && !g.drawn) best = if (g.band?.mind == Mind.PLANS) planned(g, policy, buf, best, dueSoon, here, date) else if (g.weighed) explored(g, policy, buf, best, date) else best
        // (For the census: whether the band would have moved by its best choice, and whether it moved by its draw.)
        if (g.band != null) {
            val k = s.index * 4
            exploreCounts.incrementAndGet(k)
            if (chosen != g.place) exploreCounts.incrementAndGet(k + 1)
            if (best != g.place) exploreCounts.incrementAndGet(k + 2)
            if (best != g.place && chosen == g.place) exploreCounts.incrementAndGet(k + 3)
        }
        // The hexes passed on the way to [p], by the cheapest chain.
        fun pathTo(p: Int): HexArray {
            val chain = ArrayList<Hex>(); var h = gs.hw.placeHex[p]
            while (true) { chain += h; if (h == here) break; h = via[h].takeIf { !it.none } ?: break }
            chain.reverse(); return chain.toHexArray()
        }
        if (capture) best = Place(gs.askControl(g, weather, date, policy, buf.candPlaces, buf.candFeats, buf.candScores, best.i,
            restless, reach, goal) { pathTo(it) })
        // (A band's ways read how its mover saw the place it goes to: see [HexGroup.plan].)
        if (g.band != null) {
            var k = -1
            if (policy != null) for (i in 0 until candPlaces.size) if (candPlaces[i] == best.i) { k = i; break }
            g.plan = if (k >= 0) policy?.planned(g, candFeats[k]) else null
        }
        if (seen != null && g.id == gs.watched) logWatched(c, seen, best, policy != null)
        if (diag && seen != null) printPlanDiag(c, seen, best)
        planCounts.incrementAndGet(s.index * 3 + (if (!g.weighed) 0 else if (best == g.place) 1 else 2))
        return Plan(best, pathTo(best.i))
    }

    /** How many places' hand-written scores have come out not a number this run (see [tellNotANumber]). */
    @Transient internal val notANumber = java.util.concurrent.atomic.AtomicInteger()

    /**
     * What a group weighing its places this week brings to each of them: worked out once in [planInner], read by
     * [scorePlace], [fillFeatures], [deathsAt] and the diagnostics. [buf] is the thread's scratch: the reach search's
     * hexes and the place being weighed ([at]).
     */
    internal class PlanContext(val g: HexGroup, val kind: Int, val here: Hex, val sensed: HexArray, val month: Int, val goal: Int, val goalQ: Float,
        val fromHere: Double, val reach: Float, val thirsty: Boolean, val restless: Boolean,
        /** The time of year as an angle, 0 at the year's start ([Date.START_DAY]). */
        val season: Double, val keepOff: HexArray?,
        val weather: Weather, val date: Date,
        /** The hex of its season's place ([goal]), else [Hex.NONE]. */
        val goalHex: Hex, val predatorKg: FloatArray, val buf: Scratch,
        /** For a band, how far it lacks each rarer material ([PlaceMemory.RARE]); the remembered hex with the most of what it lacks, how much, and how far off (km). */
        val lacked: FloatArray?, val lackedHex: Hex, val lackedQ: Float, val lackedFrom: Double) {
        /** Reused for each place it weighs: see [deathsAt]. */
        val odds = Odds()
        /** The place being weighed, as it knows it. */
        val at get() = buf.at
    }

    /**
     * What a group knows of the place it is weighing ([PlanContext.at]), filled in by [planInner] hex by hex and place by
     * place: the going there ([d], km), how far it would then be from its goal ([toGoal], km), how far beyond its own ground
     * ([beyond]) and how fresh another pack's marks ([foreign]), the cost of leaving its ground ([offGround]), the kind's
     * sense of better country that way ([pull]), the game it knows of ([prey]), its own kind's crowding ([crowded]),
     * the hunters about ([hunters], kg per km²) and how fed it foresees it would be ([fed]).
     */
    internal class PlaceLook {
        var d = 0f; var toGoal = 0.0; var beyond = 0f; var foreign = 0f; var offGround = 0f; var pull = 0f
        var prey = 0f; var crowded = 0f; var hunters = 0f; var fed = 0f
    }

    /** The odds of the hazards at a place, summed ([deathsAt]). */
    internal class Odds : HazardSink {
        var adult = 0f; var young = 0f
        override fun add(cause: DeathCause, adult: Float, young: Float) { this.adult += adult; this.young += young }
    }

    /** The week's start, before packs set their ways: the man-eaters about each hex, and the strongest hunting party in each place. */
    internal fun takeStock() {
        manEaterKm2.fill(0f)
        for (g in gs.groups) if (g.alive && g.species.takesPeople > 0f)
            manEaterKm2[gs.hw.placeHex[g.place]] += g.species.takesPeople * g.size * g.species.massKg / gs.hex.areaKm2.toFloat()
        // The strongest hunting party in each place, and its kind: weaker hunters keep out of its way.
        topStrength.fill(0f); topKind.fill(-1)
        for (g in gs.groups) if (g.alive) {
            val st = gs.strength(g)
            if (st > topStrength[g.place]) { topStrength[g.place] = st; topKind[g.place] = g.species.index }
        }
        // (The settled of kinds that also roam hold the ground too, as a stand-in band of them does at the meetings
        // ([ResidentParties]): a hex with a band's worth of them is held by one, so that weaker packs keep out of its way
        // whether its kind's packs or its settled hold it.)
        val f = gs.fauna
        for (s in Catalogue.roamingResidents) {
            val k = f.slot(s); if (k.none) continue
            val st = residentStrength(s)
            for (h in gs.hex.all) {
                var heads = 0f
                for (q in gs.hw.placesOf(h)) heads += (f.adults[k][q] + f.young[k][q]) * gs.land.areaKm2[q]
                if (heads < ResidentParties.MIN_BANDS * s.groupSize) continue
                for (q in gs.hw.placesOf(h)) if (st > topStrength[q]) { topStrength[q] = st; topKind[q] = s.index }
            }
        }
        // (The residents' rivals keep out of its way too, next week as they move: [HexFauna.strongest].)
        topStrength.copyInto(gs.fauna.strongest)
    }

    /** Before the groups choose: each place's worth to each kind this week ([valuePlaces]); last week's searches and efforts let go. */
    @Part("choosing: valuing places for hunters")
    internal fun beginChoosing(predatorKg: FloatArray, month: Int, weather: Weather) {
        valuePlaces(predatorKg, month)
        reachCache.clear(); stepEffort.clear()
        // (The week's crossing efforts are worked out here, once for each way of going and height band the groups have, not by
        // whichever group asks first: that one held the others up while it worked, and they stood waiting in the planning.)
        val done = HashSet<Int>()
        for (g in gs.groups) if (g.alive) {
            val travel = Catalogue.travel(g.species).ordinal; val band = gs.hw.placeBand[g.place]
            if (done.add(travel * 64 + band)) stepEffort(travel, band, weather)
        }
    }

    private fun valuePlaces(predatorKg: FloatArray, month: Int) {
        for (a in gs.headsHere) a.fill(0)
        for (g in gs.groups) if (g.alive) gs.headsHere[gs.kindSlot[g.species.index]][g.place] += g.size
        // (What may be expected changes slowly: it is reckoned once a month.)
        if (month != expectMonth) { expect(predatorKg); expectMonth = month }
        Par.forEach("choosing: value places", gs.n) { p -> for ((k, s) in gs.kinds.withIndex()) placeValue[k][p] = gs.lifewayValue(s, Place(p)) }
    }

    /**
     * What a group may expect of the others where it has not looked: each kind as thick as it usually is
     * on ground that suits it, by how well a place suits it; hunters as thick as they usually are.
     */
    private fun expect(predatorKg: FloatArray) {
        var land2 = 0.0; var kg = 0.0
        for (p in 0 until gs.n) { land2 += gs.land.areaKm2[p]; kg += predatorKg[p] }
        val huntersNow = (kg / land2.coerceAtLeast(1.0)).toFloat()
        usualHuntersKm2 = if (usualHuntersKm2 <= 0f) huntersNow else usualHuntersKm2 + (huntersNow - usualHuntersKm2) / USUAL_MONTHS
        for ((k, s) in gs.kinds.withIndex()) {
            val e = expectHeads[k]
            Par.forEach("choosing: suited area", gs.n) { p -> e[p] = gs.fauna.suits(s, Place(p)) * gs.land.areaKm2[p] }
            val suitArea = e.sumOf { it.toDouble() }
            var heads = 0L; for (p in 0 until gs.n) heads += gs.headsHere[k][p]
            val now = (heads / suitArea.coerceAtLeast(1.0)).toFloat()
            usualDensity[k] = if (usualDensity[k] <= 0f) now else usualDensity[k] + (now - usualDensity[k]) / USUAL_MONTHS
            val d = usualDensity[k]
            Par.forEach("choosing: expected shares", gs.n) { p -> e[p] *= d }
        }
    }

    /** Per thread: least effort to each hex, the hex it was reached from, the hexes reached, the queue. */
    internal class Scratch(size: Int) {
        val dist = FloatArray(size) { Float.MAX_VALUE }; val via = HexArray(size)
        val reached = HexList(); val queue = LongHeap()
        /** The candidates weighed for a policy: places, their features (arrays reused from week to week), hand-written scores. */
        val candPlaces = IntList(); val candFeats = ArrayList<FloatArray>(); var candScores = FloatArray(64)
        /** For a band, every place weighed ([exploreChoice]): the place, its whole hand-written score, and the survival steps it would cost. */
        val explPlaces = IntList(); var explScores = FloatArray(64); var explDeaths = FloatArray(64)
        /** moveTrace: the parts of the place being weighed, of the best so far and of staying ([MoveTerm]). */
        val moveParts = FloatArray(MoveTerm.entries.size); val bestParts = FloatArray(MoveTerm.entries.size); val stayParts = FloatArray(MoveTerm.entries.size)
        fun explore(p: Place, score: Float, deaths: Float) {
            val k = explPlaces.size
            if (k == explScores.size) { explScores = explScores.copyOf(2 * k); explDeaths = explDeaths.copyOf(2 * k) }
            explPlaces += p.i; explScores[k] = score; explDeaths[k] = deaths
        }
        /** The place being weighed: what the hand-written score and the features both read of it. */
        val terms = PlaceTerms()
        val at = PlaceLook()
        /**
         * The places weighed by the hand-written score whose deaths are left till after ([planInner]): each place, its
         * hex, the going there, the game and crowding it knows of there, and its score without its deaths (and with
         * its noise and temper).
         */
        val laterPlace = IntList(); var laterD = FloatArray(64); var laterPrey = FloatArray(64); var laterFed = FloatArray(64)
        var laterScore = FloatArray(64); var laterBeside = FloatArray(64); var laterUpper = FloatArray(64)
        fun later(p: Place, d: Float, prey: Float, fed: Float, score: Float, beside: Float) {
            val k = laterPlace.size
            if (k == laterD.size) { val m = 2 * k; laterD = laterD.copyOf(m); laterPrey = laterPrey.copyOf(m); laterFed = laterFed.copyOf(m)
                laterScore = laterScore.copyOf(m); laterBeside = laterBeside.copyOf(m); laterUpper = laterUpper.copyOf(m) }
            laterPlace += p.i; laterD[k] = d; laterPrey[k] = prey; laterFed[k] = fed; laterScore[k] = score; laterBeside[k] = beside; laterUpper[k] = score + beside
        }
    }

    /**
     * The raw quantities of a place that the hand-written score ([scorePlace]) and a learned policy's features
     * ([fillFeatures]) both weigh, worked out once for each place weighed; each scales them its own way.
     */
    internal class PlaceTerms {
        /** The cold or heat felt there now ([discomfort]), and what the place drains from the group ([drain]). */
        var discomfort = 0f; var drain = 0f
        /** The cold or heat coming there, in degrees beyond the kind's comfort (unscaled). */
        var aheadC = 0f
        /** How far drinking water lies, km (for people, including what they can dig for). */
        var waterKm = 0f
        /** How well the place's country suits the kind, as a log. */
        var suitsLn = 0f
    }

    /** A growable list of ints, in the order added. */
    internal class IntList {
        var items = IntArray(64); var size = 0
        operator fun plusAssign(v: Int) { if (size == items.size) items = items.copyOf(2 * size); items[size++] = v }
        operator fun get(i: Int) = items[i]
        fun clear() { size = 0 }
        fun toIntArray() = items.copyOf(size)
    }

    /** A binary min-heap of longs (no boxing). */
    internal class LongHeap {
        private var a = LongArray(256); private var n = 0
        fun clear() { n = 0 }
        fun isNotEmpty() = n > 0
        operator fun plusAssign(v: Long) {
            if (n == a.size) a = a.copyOf(2 * n)
            var i = n++
            while (i > 0) { val up = (i - 1) / 2; if (a[up] <= v) break; a[i] = a[up]; i = up }
            a[i] = v
        }
        fun poll(): Long {
            val top = a[0]; val last = a[--n]
            var i = 0
            while (true) {
                var c = 2 * i + 1; if (c >= n) break
                if (c + 1 < n && a[c + 1] < a[c]) c++
                if (a[c] >= last) break
                a[i] = a[c]; i = c
            }
            if (n > 0) a[i] = last
            return top
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch(gs.hex.size) }

    /** This week's searches, shared by the groups of a kind starting from the same place in the same mood. */
    internal class Reach(val reach: Float, val hexes: HexArray, val dist: FloatArray, val via: HexArray)

    @Transient internal val reachCache = ConcurrentHashMap<Long, Reach>()

    /**
     * This week's effort of each crossing (hex × 6 + direction) for a kind's way of going and the height
     * band it walks in: the crossing's own effort, harder through lying snow, easier along a worn path.
     * The same for every group of those, so worked out once a week for each (see [planInner]); +∞ where
     * there is no way across.
     */
    @Transient internal val stepEffort = ConcurrentHashMap<Int, FloatArray>()

    /** Effort and hex packed in one number, ordered by effort (efforts are never negative). */
    internal fun Long.withPlace(d: Float, h: Hex): Long = (d.toRawBits().toLong() shl 32) or h.i.toLong()

    /**
     * What each place offers each kind this week, worked out once for all its groups: the game it can
     * catch there, and for bands the plant food and the camp too ([lifewayValue]).
     */
    internal val placeValue by lazy { Array(gs.kinds.size) { FloatArray(gs.n) } }

    /** Heads of each kind a place may be expected to hold, by how well it suits the kind: what a group goes by where it has not looked. */
    internal val expectHeads by lazy { Array(gs.kinds.size) { FloatArray(gs.n) } }

    /** The month [expectHeads] was reckoned for. */
    private var expectMonth = -1

    /** Each kind's heads per km² of ground that suits it fully, as it usually is (kept over about a year). */
    val usualDensity = FloatArray(gs.kinds.size)

    /** Hunters' weight per km² of land, kg, as it usually is (kept over about a year). */
    var usualHuntersKm2 = 0f

    /** Strongest hunting party in each place last week, and its kind's index (-1 for none). */
    internal val topStrength = FloatArray(gs.n)

    /** The flesh of the groups of beasts that take people in each hex this week, kg/km², by how readily they do ([stalkRate]). */
    internal val manEaterKm2 = FloatArray(gs.hex.size)

    internal val topKind = IntArray(gs.n) { -1 }

    /** Choices made in planning, by kind: stayed without weighing (its policy's gate, [HexGroup.weighed]), weighed and stayed, weighed and moved. */
    val planCounts = AtomicIntegerArray(64 * 3)
    /**
     * Bands' weeks by kind, for the census: weeks weighed, those its best choice (the hand-written score's or its network's)
     * would have moved it, those it moved, and those it moved only by its will to explore's draw ([explored]), the best
     * staying put. Not saved: the census's, cleared as it prints.
     */
    @Transient val exploreCounts = AtomicIntegerArray(64 * 4)

    /**
     * moveTrace: bands' weeks weighed by the hand-written score ([MOVE_TRACE_WEEKS]), those it moved them ([MOVE_TRACE_MOVES]), and on its
     * moves, each part of the score of the place it chose less staying's, summed, 1/10000 ([MOVE_TRACE_SUMS]), and the moves each
     * part did the most for ([MOVE_TRACE_LEADS]). Not saved: the census's, cleared as it prints.
     */
    @Transient val moveCounts = java.util.concurrent.atomic.AtomicLongArray(2 + 2 * MoveTerm.entries.size)

}

/**
 * The parts of the hand-written score of a place ([GroupPlanner.scorePlace]), as moveTrace books them: its food, the cold
 * and drain there, what is coming (and its own kind of country), water and snow, the walk, the days a camp's move takes,
 * the season's round or place, the bearing, staying put (and the camp's cost), the band's other pulls (and the hungry
 * leaving ground), the deaths there in survival steps, and the noise and temper beside.
 */
enum class MoveTerm(val label: String) {
    FOOD("food"), COLD("cold and drain"), FORECAST("coming weather"), WATER("water and snow"), WALK("walk"), MOVE_DAYS("move days"),
    SEASON("season's place"), BEARING("bearing"), STAY("staying"), BAND("band pulls"), DEATHS("deaths"), BESIDE("noise and temper")
}
internal const val MOVE_TRACE_WEEKS = 0
internal const val MOVE_TRACE_MOVES = 1
internal const val MOVE_TRACE_SUMS = 2
internal val MOVE_TRACE_LEADS = MOVE_TRACE_SUMS + MoveTerm.entries.size

/** moveTrace=true: [GroupPlanner.bookMove] each band-week chosen by the hand-written score. Report-only. */
internal val MOVE_TRACE = Config.flag(Config.Key.MOVE_TRACE, false)

/**
 * What each place input of the movement nets reads as 1 (a scale, not a limit: the input is the amount over it). Changing
 * one changes what trained nets see, so it is a net version change ([inflation.learning.NetVersion]).
 */
internal object InputScale {
    const val SUITS_LN = 2f
    const val USUAL_C = 20f
    const val AHEAD_C = 10f
    const val ANCESTOR_DEAD = 10f
    const val OWN_SIZE_LN = 2f
    const val SICKNESS_PER_DEATH = 10f
    const val TREES_FULL = 10f
    const val SHRUBS_FULL = 2f
    const val GRASS_FULL = 0.5f
    const val DUNG_PER = 1e4f
    const val GRAZING_FULL = 1000f
}
