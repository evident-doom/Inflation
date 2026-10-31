package inflation.ecology

import inflation.Profile
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.world.forEachHex
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
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import inflation.unitOf
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
import java.util.stream.IntStream
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
internal class GroupPlanner(private val gs: HexGroups) {

    /** A week's plan: the [place] a group heads for and the hexes of its [path] there. */
    class Plan(val place: Place, val path: HexArray) { operator fun component1() = place; operator fun component2() = path }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    internal fun plan(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray): Plan {
        var pt = Profile.mark()
        val r = planInner(g, weather, date, predatorKg) { name -> pt = Profile.lap(name, pt) }
        Profile.lap("choosing: route", pt)
        return r
    }

    /**
     * Least effort to each hex within [reach] of [here] for [g] this week, into [buf] (its dist, via and
     * reached): from a search shared with groups of its kind starting from the same place that reach as
     * far or further, else from a new one (then shared).
     */
    private fun searchReach(g: HexGroup, here: Hex, reach: Float, restless: Boolean, weather: Weather, buf: Scratch) {
        val s = g.species
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
        reached.clear()
        val q = buf.queue; q.clear()
        // Groups of a kind starting from the same place this week share the search.
        // Each group's reach is its own (hunger, the band's weak), so a search is shared only
        // with groups that reach no further, and each takes the hexes within its own reach: least-effort
        // distances within a reach do not depend on how far the search went beyond it. The hexes are kept
        // in index order, so the choice does not depend on which group's search was kept.
        val reachKey = ((s.index.toLong() * 8 + gs.hw.placeBand[g.place]) * gs.hex.size + here.i) * 2 + (if (restless) 1 else 0)
        var shared = reachCache[reachKey]
        if (shared == null || shared.reach < reach) {
            dist[here] = 0f; reached += here; q += 0L.withPlace(0f, here)
            // (The effort of each crossing this week, through snow and along worn paths: see [stepEffort].)
            val effort = stepEffort(Catalogue.travel(s).ordinal, gs.hw.placeBand[g.place], weather)
            while (q.isNotEmpty()) {
                val e = q.poll()
                val d = Float.fromBits((e ushr 32).toInt()); val h = Hex((e and 0xffffffffL).toInt())
                if (d > dist[h]) continue
                for (k in 0 until 6) {
                    val nh = gs.hex.neighbour(h, k)
                    if (nh.none) continue
                    val nd = d + effort[gs.hex.edge(h, k)]
                    if (!nd.isFinite() || nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
                // Swimmers may cross a strait they can cover, at a great effort.
                val swim = Catalogue.swimKm(s)
                if (swim > 0f) for (i in gs.seaLinks[h].indices) { val nh = gs.seaLinks[h][i]
                    val gapKm = gs.seaGaps[h][i]
                    if (gapKm > swim) continue
                    val nd = d + gapKm * SWIM_EFFORT
                    if (nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
            }
            val hs = reached.toHexArray().also { it.sort() }
            val mine = Reach(reach, hs, FloatArray(hs.size) { dist[hs[it]] }, hexArray(hs.size) { via[hs[it]] })
            shared = reachCache.merge(reachKey, mine) { a, b -> if (a.reach >= b.reach) a else b }!!
            for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
            reached.clear()
        }
        for (i in shared.hexes.indices) {
            if (shared.dist[i] > reach) continue
            val h = shared.hexes[i]; reached += h; dist[h] = shared.dist[i]; via[h] = shared.via[i]
        }
    }

    private fun planInner(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray, lap: (String) -> Unit): Plan {
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
        lap("choosing: reach (least-effort search)")
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = gs.policies[s]
        // (moveTrace: a band choosing by the hand-written score books what moved it, [bookMove].)
        val traced = MOVE_TRACE && g.band != null && policy == null
        if (traced) buf.stayParts.fill(0f)
        // (For a followed group: every place it weighed, and how it scored.)
        // planDiag=kind: once a month, one group's reasons, for the place it chose and the best-placed step toward its season's place.
        val diag = PLAN_DIAG == s.name && goal >= 0 && date.dayOfMonth <= gs.days && g.id % 97 == 0
        val seen = if (g.id == gs.watched || diag) ArrayList<Pair<Int, Float>>() else null
        val candPlaces = buf.candPlaces; candPlaces.clear(); val candFeats = buf.candFeats
        // (Every place's whole score is wanted for a network, for capture and for a watched group; else only the best.)
        // (A band draws its place by its will to explore, from every place it weighs, see [exploreChoice].)
        val explores = g.band != null
        val wantAll = seen != null || policy != null || capture || explores
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
        val keepOff = g.band?.let { b -> (b.knownHomes.entries.filter { Regard.closeOf(b, it.key) <= 0f }.map { it.value } +
            listOfNotNull(b.drivenFrom.takeIf { b.keptFrom(it) })).toHexArray() }
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
        // A planning band ([Mind.PLANS]) puts its camp where it foresees the fewest deaths ([planCamp]), but for a week its
        // customs or a raid send it somewhere, or it treks for salt: then it goes as other bands do.
        g.band?.let { b -> if (b.mind == Mind.PLANS && !dueSoon && !saltTrek(g, b, here)) return planCamp(c, b).also { lap("choosing: planning camp") } }
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
        lap("choosing: weighing places (hand-written score)")
        g.drawn = false
        if (policy != null && candPlaces.size > 0) {
            val nc = candPlaces.size
            best = Place(candPlaces[policy.choose(g, Array(nc) { candFeats[it] }, buf.candScores.copyOf(nc), gs.policyRandom(g.id, date.absoluteDay))])
        }
        // (A choice the policy drew by chance to learn from stands; every other band draws by its will to explore, in training worlds as outside them.)
        val chosen = best
        if (traced) bookMove(chosen != g.place, buf)
        if (explores && !g.drawn) best = explored(g, policy, buf, best, date)
        // (For the census: whether the band would have moved by its best choice, and whether it moved by its draw.)
        if (g.band != null) {
            val k = s.index * 4
            exploreCounts.incrementAndGet(k)
            if (chosen != g.place) exploreCounts.incrementAndGet(k + 1)
            if (best != g.place) exploreCounts.incrementAndGet(k + 2)
            if (best != g.place && chosen == g.place) exploreCounts.incrementAndGet(k + 3)
        }
        lap("choosing: network")
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
        planCounts.incrementAndGet(s.index * 3 + (if (best == g.place) 1 else 2))
        return Plan(best, pathTo(best.i))
    }

    /**
     * Where planning band [b] (group [c].g) camps this week ([Mind.PLANS]; doc: Planning stages 3+4: camp follows food): of
     * the places within its week's reach ([searchReach], in [c].buf), those it does not keep off, the one where it foresees
     * the fewest of its people dead over the [PLAN_CAMP_WEEKS] ahead, by the sim's own rules:
     * - of hunger ([campAlive]), each week getting the food it expects there ([foodAhead]), less what its kept animals would
     *   go short of there ([herdLoss]), and at the hex it sowed what it would reap ([cropPull]); in the first week less the
     *   days the walk there takes beyond those it has to spare ([moveDays]); from its larder if it stays, else from what it
     *   carries ([foodCarried]);
     * - of everything else this week's odds there ([deathsAt], hunger left out), for the weeks it expects to stay
     *   ([Band.stayWeeks], at most [PLAN_CAMP_WEEKS]), and the crossing's ([crossingDeaths]).
     * No pulls beside (season's place, bearing, temper, the flat stay, the explore draw, the network): of equals it stays.
     * Its forecast of the food there is kept for the census ([Band.campForeseen]).
     */
    private fun planCamp(c: PlanContext, b: Band): Plan {
        val g = c.g; val s = g.species; val here = c.here; val buf = c.buf
        val reached = buf.reached; val dist = buf.dist; val via = buf.via
        val days = gs.days
        // (In days of its people's need before keeping warm, as its record counts it.)
        val need = (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
        val larder = g.larder / need
        val carried = min(larder, gs.foodCarried(g, b) / need)
        val stayGot = b.gotSinceMove(c.date, days)
        val spare = b.spareDays / forageHands(g)
        val season = PlaceMemory.seasonOf(c.month)
        val stayFor = min(PLAN_CAMP_WEEKS.toFloat(), b.stayWeeks)
        var best = g.place; var bestDying = Float.MAX_VALUE; var bestGot = Float.NaN
        for (ri in 0 until reached.size) {
            val h = reached[ri]
            if (h != here && c.keepOff != null && h in c.keepOff) continue
            val d = dist[h]
            val moves = h != here
            val kin = kinAround(g, c.kind, h, c.sensed); val crowded = gs.crowdedBy(s, kin)
            val crossing = if (moves) crossingDeaths(s, h, here, via) else 0f
            val first = if (moves) (1f - max(0f, gs.moveDays(g, d) - spare) / days).coerceAtLeast(0f) else 1f
            for (pi in gs.hw.placesOf(h)) {
                val p = Place(pi)
                val warm = (1f + gs.drain(g, p, c.weather)).coerceAtLeast(1e-3f)
                val got = foodAhead(c, b, p, h, kin, crowded, stayGot, season, warm) +
                    (cropPull(g, b, h) - herdLoss(g, b, p)) * g.warmth
                val alive = campAlive(s, days, PLAN_CAMP_WEEKS, got, warm, if (moves) carried else larder, g.fat, g.spent, first)
                val dying = (1f - alive) + (if (moves) stayFor else PLAN_CAMP_WEEKS.toFloat()) * deathsAt(c, p, h, 1f, d) + crossing
                if (!dying.isFinite()) continue
                if (dying < bestDying || (dying == bestDying && p == g.place)) { bestDying = dying; best = p; bestGot = got }
            }
        }
        b.campForeseen = bestGot
        g.drawn = false; g.plan = null
        planCounts.incrementAndGet(s.index * 3 + (if (best == g.place) 1 else 2))
        val chain = ArrayList<Hex>(); var h = gs.hw.placeHex[best]
        while (true) { chain += h; if (h == here) break; h = via[h].takeIf { !it.none } ?: break }
        chain.reverse()
        return Plan(best, chain.toHexArray())
    }

    /**
     * The food planning band [b] (group [c].g) expects a week at place [p] (in hex [h], among [kinKm2] of its kind,
     * [crowded] by them), as its record counts it ([Band.weekSupply]: a share of its need before keeping warm, net of its
     * larder), keeping warm there taking [warm] times that need: at its camp what it got there since it came ([stayGot],
     * [Band.gotSinceMove]); where it remembers being itself in this [season], how fed it was ([PlaceMemory.fed]); else what
     * the game, plants and fish it knows of there promise ([foreseenFed]), as dimly as the people network sees strange
     * country.
     */
    private fun foodAhead(c: PlanContext, b: Band, p: Place, h: Hex, kinKm2: Float, crowded: Float, stayGot: Float, season: Int, warm: Float): Float {
        if (h == c.here && !stayGot.isNaN()) return stayGot
        val mem = c.g.memory
        val slot = mem.find(h, season)
        if (slot >= 0 && mem.own[slot]) return mem.fed[slot] * warm
        val fed = foreseenFed(c.g, knownGame(c.g, c.kind, p, h), kinKm2, crowded)
        return (if (b.knows(h)) fed else 0.5f * fed + 0.5f * SIGN_GUESS) * warm
    }

    /**
     * Whether band [b] (group [g], in hex [here]) treks for salt this week as [bandPull] draws it: short of sodium, none
     * where it stands, and the nearest it knows of beyond its own hex.
     */
    private fun saltTrek(g: HexGroup, b: Band, here: Hex): Boolean {
        if (b.sodiumShort <= 0f || gs.hw.coast[here] || gs.land.salt[g.place] > 0f) return false
        val salt = gs.bands.saltKnown(b, here)
        return !salt.none && gs.hexKm(here, salt) > gs.hex.hexKm
    }

    /**
     * The features of place [p] (in hex [h]) for the group weighing its places in [c], into [f] (cleared): what a learned
     * policy sees of it ([Feature]). What it knows of the place ([PlanContext.at]) is worked out in [planInner] as the
     * hand-written score weighs it.
     */
    private fun fillFeatures(f: FloatArray, c: PlanContext, t: PlaceTerms, p: Place, h: Hex) {
        val d = c.at.d; val toGoal = c.at.toGoal; val pull = c.at.pull; val beyond = c.at.beyond; val foreign = c.at.foreign
        val prey = c.at.prey; val crowded = c.at.crowded; val hunters = c.at.hunters
        val g = c.g; val s = g.species; val here = c.here; val month = c.month; val reach = c.reach; val goal = c.goal
        val fromHere = c.fromHere; val weather = c.weather; val date = c.date
        // (The herds' slots, [Feature.HERDS_ABOUT], [Feature.KEEPS_HEADING] and [Feature.FORAGE_QUALITY], stay 0: kept for the
        // networks' layout until they are trained again.)
        // What the place offers it, as the baseline weighs it: game, and for bands plant food and a safe camp.
        f[Feature.FOOD] = prey / (prey + PREY_HALF)
        f[Feature.CLIMATE] = t.suitsLn / InputScale.SUITS_LN
        // (What the coming months are usually like there.)
        for (k in 0 until Feature.LORE_MONTHS)
            f[Feature.USUAL_AHEAD + k] = weather.usualTemperature((month + k + 1) % Date.MONTHS_PER_YEAR, p) / InputScale.USUAL_C
        f[Feature.CROWDING] = crowded
        f[Feature.HUNTERS] = hunters / (hunters + 3f)
        f[Feature.DISCOMFORT] = min(2f, t.discomfort + t.drain)
        // (The flies are felt now; only the weather is foreseen.)
        f[Feature.DISCOMFORT_FORECAST] = min(2f, t.aheadC / InputScale.AHEAD_C + t.drain)
        // People see only what they can read: the weather coming as their lore and sky-watching
        // allow (else they expect this week's again), the beasts about as their tracking does,
        // and the food of country they do not know only dimly.
        g.band?.let { b ->
            val sense = ((if (b.holds(Tradition.SKY_LORE)) 0.5f else 0f) + 0.5f * b.skill(Skill.LORE)).coerceIn(0f, 1f)
            f[Feature.DISCOMFORT_FORECAST] = f[Feature.DISCOMFORT] + sense * (f[Feature.DISCOMFORT_FORECAST] - f[Feature.DISCOMFORT])
            f[Feature.HUNTERS] = SIGN_GUESS + b.skill(Skill.TRACK) * (f[Feature.HUNTERS] - SIGN_GUESS)
            if (!b.knows(h)) f[Feature.FOOD] = 0.5f * f[Feature.FOOD] + 0.5f * SIGN_GUESS
        }
        f[Feature.SNOW_OVER_LIMIT] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
        f[Feature.SNOW] = min(2f, weather.snowpack[p] / s.snowLimitMm)
        // (What the hand-written score weighs besides: snow coming with a freeze, as keenly as its kind foresees it; fire;
        // the fear of ground where game was killed lately; and a much stronger hunter of another kind holding the place.)
        if (weather.forecastTemp(p.i) < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) f[Feature.SNOW_AHEAD] = s.foresight
        f[Feature.BURNING] = gs.land.fire.burning[p]
        f[Feature.KILL_FEAR] = gs.fauna.afraid(p)
        if (gs.hex.distanceKm(h, here) <= gs.hex.hexKm * 1.01 && topKind[p] >= 0 && topKind[p] != s.index)
            f[Feature.STRONGER_HUNTER] = min(1f, topStrength[p] / (4f * gs.strength(g)).coerceAtLeast(1e-3f))
        f[Feature.CLIMB] = ((gs.hw.placeElevationM[p] - gs.hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
        f[Feature.EFFORT] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
        f[Feature.STAYS] = if (p == g.place) 1f else 0f
        f[Feature.SAME_HEX] = if (h == here) 1f else 0f
        if (goal >= 0) {
            f[Feature.TOWARD_BEARING] = if (fromHere > gs.hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
            f[Feature.SEASON_PLACE] = (c.goalQ - g.fed).coerceIn(-1f, 1f)
        }
        f[Feature.FED] = g.fed
        f[Feature.FAT] = g.fat / s.maxFatDays.coerceAtLeast(1f)
        f[Feature.SEASON_SIN] = sin(c.season).toFloat(); f[Feature.SEASON_COS] = cos(c.season).toFloat()
        f[Feature.RESTLESS] = if (c.restless) 1f else 0f
        f[Feature.WATER_DISTANCE] = min(3f, t.waterKm / s.dailyTravelKm)
        f[Feature.DAYS_DRY] = min(2f, g.dry / s.daysWithoutWater)
        f[Feature.PULL] = pull
        // The moon (night hunters see and are seen by it) and how far off its young are due.
        f[Feature.MOON] = gs.moon
        val toBirth = 2.0 * Math.PI * monthsFrom(month, if (g.birthMonth >= 0) g.birthMonth else s.birthMonth) / 12.0
        f[Feature.TO_BIRTHS_SIN] = sin(toBirth).toFloat(); f[Feature.TO_BIRTHS_COS] = cos(toBirth).toFloat()
        // Its ancestors buried here, and how far going here brings it toward its appointment.
        f[Feature.ANCESTORS] = min(1f, g.memory.deadAt(h) / InputScale.ANCESTOR_DEAD)
        f[Feature.APPOINTMENT] = g.band?.let { gs.appointPull(g, it, month, h) } ?: 0f
        f[Feature.HOUSES] = if (g.band != null) gs.housesAt(g, h) else 0f
        f[Feature.OWN_SIZE] = ln(g.size.toFloat().coerceAtLeast(1f) / gs.unitSize(s)) / InputScale.OWN_SIZE_LN
        f[Feature.LASTING_WATER] = min(1f, gs.land.drink.nearRiver[p] + gs.land.drink.nearLake[p] + gs.land.drink.nearSpring[p])
        f[Feature.SICKNESS] = min(1f, gs.fauna.outbreakDeaths(s, p) * InputScale.SICKNESS_PER_DEATH)
        // The signs of the land, as anyone who looks can read them.
        f[Feature.TREES] = min(1f, gs.land.plants.tree[p] / InputScale.TREES_FULL); f[Feature.SHRUBS] = min(1f, gs.land.plants.shrub[p] / InputScale.SHRUBS_FULL)
        f[Feature.GRASS] = min(1f, gs.land.plants.herb[p] / InputScale.GRASS_FULL)
        f[Feature.MARSH] = min(1f, gs.land.waterlogged[p])
        f[Feature.WATER_AGAINST_USUAL] = (gs.land.drink.water[p] - min(1f, gs.land.drink.nearRiver[p] + gs.land.drink.nearLake[p] +
            gs.land.drink.nearSpring[p])).coerceIn(-1f, 1f)
        f[Feature.PATHS] = gs.wear.at(h)
        f[Feature.DUNG] = min(1f, gs.fauna.small.dung[p] * InputScale.DUNG_PER)
        f[Feature.GRAZING_AND_KILLS] = min(1f, (gs.land.plants.grazing[p] + gs.land.plants.browsing[p]) / InputScale.GRAZING_FULL) +
            min(1f, KgM2(gs.fauna.small.carrion[p] + gs.fauna.small.bones[p]).perKm2.v / 20f)
        f[Feature.KNOWN_GROUND] = g.band?.let { if (it.knows(h)) 1f else 0f } ?: 1f
        f[Feature.BEYOND_OWN_GROUND] = beyond; f[Feature.OTHERS_MARKS] = foreign
        // The fish and shellfish a band could take there, with its skill, and from a weir standing there that it may use,
        // against its need (the shore and the river are plain to see; how rich they are, less so where it is a stranger).
        f[Feature.FISH] = g.band?.let { b -> min(2f, catchWithin(g, b, h) * Nourishment.FISH * s.needScale / (g.size * s.dailyIntakeKg * gs.days).coerceAtLeast(1f)) *
            (if (b.knows(h)) 1f else 0.5f) } ?: 0f
        // What it remembers of this hex: in this season, or failing that in any.
        val mem = g.memory
        var slot = mem.find(h, PlaceMemory.seasonOf(month)); val sameSeason = slot >= 0
        if (slot < 0) for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) { slot = i; break }
        if (slot >= 0) {
            f[Feature.REMEMBERED] = 1f; f[Feature.REMEMBERED_SAME_SEASON] = if (sameSeason) 1f else 0f
            f[Feature.REMEMBERED_FED] = mem.fed[slot]; f[Feature.REMEMBERED_WATER] = mem.water[slot]; f[Feature.REMEMBERED_DANGER] = mem.danger[slot]
            f[Feature.REMEMBERED_AGE] = min(1f, (gs.stepOf(date) - mem.lastWeek[slot]) / gs.stepsPerYear)
        }
        // What it remembers finding there that it lacks, and how far the place takes it toward the best of that it knows.
        c.lacked?.let { lacked ->
            var most = 0f
            for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) for (k in lacked.indices) most = max(most, lacked[k] * mem.foundAt(i, k))
            f[Feature.REMEMBERED_LACKED] = most
            if (!c.lackedHex.none && c.lackedFrom > gs.hex.hexKm)
                f[Feature.TOWARD_LACKED] = ((c.lackedFrom - gs.hexKm(h, c.lackedHex)) / c.lackedFrom).toFloat().coerceIn(-1f, 1f) * c.lackedQ
        }
    }

    /**
     * The hand-written score of place [p] (in hex [h]; what the group knows of it in [PlanContext.at]) for the group weighing its places in [c]:
     * its food or game, crowding and hunters, the cold, heat and flies, its own kind of country, what it senses coming,
     * water, the going, the season's place or round, its bearing, staying, and a band's own pulls.
     */
    private fun scorePlace(c: PlanContext, t: PlaceTerms, p: Place, h: Hex, parts: FloatArray? = null): Float {
        val d = c.at.d; val toGoal = c.at.toGoal; val offGround = c.at.offGround; val pull = c.at.pull
        val prey = c.at.prey; val crowded = c.at.crowded
        val g = c.g; val s = g.species; val here = c.here; val sensed = c.sensed; val month = c.month; val goal = c.goal
        val goalQ = c.goalQ; val fromHere = c.fromHere; val reach = c.reach
        val thirsty = c.thirsty; val keepOff = c.keepOff; val weather = c.weather
        // Its food or game there, against crowding (see [foodTerm]).
        var score = gs.foodTerm(g, p, h, sensed, prey, crowded)
        // (moveTrace: each part of the score as it is added, [MoveTerm].)
        var last = 0f
        if (parts != null) { parts[MoveTerm.FOOD.ordinal] += score - last; last = score }
        // The cold or heat it bears there, and what the place drains from it (the extra food the
        // cold burns, the feeding or working time the flies take), weighed as the going is: in
        // shares of the week's need.
        score -= 0.5f * t.discomfort + t.drain + offGround
        if (parts != null) { parts[MoveTerm.COLD.ordinal] += score - last; last = score }
        // Its own kind of country: open plains for a cheetah, crags for a wild goat, whatever the food.
        if (!s.human) score += GROUP_HABITAT * t.suitsLn
        // What it senses coming, as keenly as its kind does: a cold snap or heat wave, snow
        // too deep to dig through, and in a dry spell the rain on its way.
        val tAhead = weather.forecastTemp(p.i)
        score -= 0.5f * s.foresight * t.aheadC / 10f
        if (tAhead < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) score -= 0.5f * s.foresight
        if (gs.land.hydro.soil[p] < DRY_SOIL_MM) score += 0.3f * s.foresight * min(1f, weather.forecastRain(p.i) / RAIN_AHEAD_MM)
        if (parts != null) { parts[MoveTerm.FORECAST.ordinal] += score - last; last = score }
        // Water: a thirsty group heads for it; any group keeps within reach of it, the more so the more often it must drink.
        // (People can dig for water in a dry bed where it lies near; the days their water takes them are in the place's food
        // value already, [GroupWorld.campFactor].)
        score -= ((if (s.human) 0f else 0.02f) + 0.3f * g.dry / s.daysWithoutWater) * t.waterKm / s.dailyTravelKm
        if (thirsty && !gs.canDrink(s, p)) score -= 1f
        if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
        if (parts != null) { parts[MoveTerm.WATER.ordinal] += score - last; last = score }
        // The going costs food: a share of the week's need, the height gained as the level walk it equals (see [walkCost]).
        score -= WALK_COST * (d + climbKmPerM(s.massKg) * max(0f, gs.hw.placeElevationM[p.i] - gs.hw.placeElevationM[g.place.i])) / gs.days
        if (parts != null) { parts[MoveTerm.WALK.ordinal] += score - last; last = score }
        // A band moving camp loses the days the walk takes from its week's work, beyond those it had to spare; the new camp
        // pays them back over the weeks it will keep it (as foragers weigh travel time against a patch's stay).
        g.band?.let { b -> score -= max(0f, gs.moveDays(g, d) - b.spareDays / forageHands(g)) / (gs.days * b.stayWeeks) }
        if (parts != null) { parts[MoveTerm.MOVE_DAYS.ordinal] += score - last; last = score }
        // (Above all, it keeps alive: its deaths there are weighed against all this by the caller, see [planInner].)
        // Bands remember where they did best each month and are drawn back there.
        if (goal >= 0 && s.human) {
            // The season's place draws a band on, the more so the better it promises and the worse it fares now.
            // Bands that watch the sky keep to their season's place on time.
            val sky = if (g.band?.holds(Tradition.SKY_LORE) == true) 1.3f else 1f
            val prog = ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
            if (fromHere > gs.hex.hexKm) score += sky * gs.tuning.seasonPull * (0.3f * goalQ + (goalQ - g.fed).coerceIn(0f, 1f)) * prog
        }
        if (parts != null) { parts[MoveTerm.SEASON.ordinal] += score - last; last = score }
        score += gs.tuning.bearingPull * pull
        if (parts != null) { parts[MoveTerm.BEARING.ordinal] += score - last; last = score }
        // (Staying is staying in the camp's hex: its other ground is the same camp, with the same fouling.)
        if (h == here) score += STAY - (g.band?.let { gs.stayCost(g, it) } ?: 0f)
        if (parts != null) { parts[MoveTerm.STAY.ordinal] += score - last; last = score }
        // Bold animals of kinds that follow people are drawn to their camps: refuse, scraps, warmth.
        if (s.followsPeople && gs.peopleSlot >= 0) score += CAMP_LURE * boldness(g) * min(1f, knownHeads(gs.peopleSlot, p, h, sensed) / 25f)
        g.band?.let { b -> score += bandPull(g, b, month, h, here, p, keepOff, sensed, reach) }
        // A hungry pack gives up ground it has hunted out lately and looks further afield.
        if (g.fed < Thresholds.HUNGRY && (h == here || h in g.recent)) score -= HUNGRY_LEAVE
        if (parts != null) parts[MoveTerm.BAND.ordinal] += score - last
        return score
    }

    /**
     * The share of [c]'s group that would die this week at place [p] (in hex [h]), by the rules that
     * kill it there ([placeHazards], as [liveWeek] has them): hunger at how [fed] it foresees it would be ([foreseenFed]), thirst, the cold, fire, flood and deep snow,
     * plague, crocodiles, eagles, and for a band snakes and the beasts that take people (those of the groups about only
     * where it senses them). The young's odds count for the young. (What it shares wherever it goes, age, the background and its own band's ills, is
     * left out: it cannot tell places apart.)
     */
    private fun deathsAt(c: PlanContext, p: Place, h: Hex, fed: Float, km: Float): Float {
        val g = c.g; val s = g.species; val weather = c.weather; val b = g.band
        val thirst = gs.thirstOdds(g, p, gs.dryAfter(g, p, fed))
        val odds = c.odds.also { it.adult = 0f; it.young = 0f }
        gs.foreseenHazards(g, p, weather, fed, thirst, km, odds)
        var adult = odds.adult; var young = odds.young
        if (b != null) {
            val venom = gs.venomOdds(s, p, weather)
            // (At its houses it would sleep behind their walls.)
            val home = h == b.houseHex
            val walled = if (home) b.house * gs.wallsAt(g, h) else 0f
            val stalked = gs.stalkRate(g, b, p, gs.residentManEaters(p) + (if (h in c.sensed) manEaterKm2[h] else 0f), walled,
                if (home) gs.palisadeAt(g, h) else 0f)
            adult += venom + stalked; young += venom + YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked
            gs.dialUse?.amount(DialSite.CHILDREN_PLAN, b.childWatch, YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked, YOUNG_STALKED * stalked, cut = b.childWatch < 0.25f)
            // The sicknesses it would catch there (marsh fever by the mosquitoes, the flux in a long-kept camp, the cough
            // where people crowd), and the fouling of the ground there, whoever left it: its own camping adds to it if it stays.
            val campWeeks = gs.campWeeksAt(h) + (if (h == c.here) 1 else 0)
            val crowd = gs.peopleCrowding(g, h); val t = weather.temperature[p]; val healed = healedShare(b); val kept = gs.hygieneAt(b, h)
            adult += gs.illnessAhead(b, p, t, crowd, campWeeks, healed, false, kept)
            young += gs.illnessAhead(b, p, t, crowd, campWeeks, healed, true, kept) + gs.campFouling(g, h, campWeeks) * gs.years
            // What it would catch of the bands it sees gathered there (a feast draws bands together, and the cough with them).
            if (h in c.sensed && gs.peopleSlot >= 0) {
                // (Bands meet over the whole hex, whichever ground each stands on.)
                val stays = h == c.here
                var heads = 0; for (q in gs.hw.placesOf(h)) heads += gs.headsHere[gs.peopleSlot][Place(q)]
                val others = heads - (if (stays) g.size else 0)
                if (others > 0) for (ill in Illness.entries) if (ill.contagion > 0f) {
                    var sick = 0f; for (q in gs.hw.placesOf(h)) sick += gs.sickPeople[ill.ordinal][Place(q)]
                    val theirSick = (sick - (if (stays) g.size * b.sick[ill] else 0f)).coerceAtLeast(0f) / others
                    val dying = caughtFrom(ill, theirSick, b) * ill.deadly * healed
                    adult += dying; young += dying * ill.young
                }
            }
        }
        return (g.adults * adult.coerceIn(0f, Hazards.MAX_GROWN_ODDS) + g.young * young.coerceIn(0f, Hazards.MAX_YOUNG_ODDS)) / max(1, g.size)
    }

    /**
     * How fed group [g] foresees it would be where it knows of [prey] (see [knownGame]) among [kinKm2] of its kind per km²
     * (itself counted; [crowded] by the one measure). A beast of prey by the residents' hunters' feeding rule: its kind
     * takes at most [HexFauna.RESIDENT_KILL_MOST] of the game in a week, shared by heads against their need. A band (whose
     * food is mostly gathered) still by how much there is against [PREY_HALF], less for crowding.
     */
    private fun foreseenFed(g: HexGroup, prey: Float, kinKm2: Float, crowded: Float): Float {
        val s = g.species
        if (s.human) return (prey / (prey + PREY_HALF) - 0.5f * crowded).coerceIn(0f, 1f)
        val needKg = s.dailyIntakeKg * g.warmth * gs.days * (g.adults + 0.5f * g.young) / max(1, g.size)
        return min(1f, HexFauna.RESIDENT_KILL_MOST * prey * Nourishment.PREY * s.needScale / max(1e-6f, kinKm2 * needKg))
    }

    /**
     * Where band [g] goes by its will to explore ([exploreChoice]), of the places weighed in [buf]: scored by its [policy]'s
     * scores (the network's), or without one by the hand-written; [best] where the scores alone would send it.
     */
    private fun explored(g: HexGroup, policy: HexPolicy?, buf: Scratch, best: Place, date: Date): Place {
        val n = buf.explPlaces.size
        val b = g.band ?: return best
        if (n == 0) return best
        val scores = if (policy == null) buf.explScores else policy.scores(g, Array(n) { buf.candFeats[it] }, buf.candScores.copyOf(n)) ?: return best
        val stays = (0 until n).firstOrNull { buf.explPlaces[it] == g.place.i } ?: -1
        val k = gs.exploreChoice(g, b.explore, n, scores, buf.explDeaths, stays, if (policy == null) HAND_SCORE_UNIT else 1f, date.absoluteDay)
        return if (k < 0) best else Place(buf.explPlaces[k])
    }

    /**
     * The hunters [c]'s group expects at place [p] (in hex [h]), kg per km². A band reads the beasts of prey about, not
     * other people: those it weighs as bands. Where it has not looked it expects hunters as thick as they usually are,
     * people among them.
     */
    private fun huntersKm2(c: PlanContext, p: Place, h: Hex) =
        if (h in c.sensed) (if (c.g.species.human) gs.beastKg else c.predatorKg)[p] / gs.land.areaKm2[p] else usualHuntersKm2

    /** For the watched group ([watched]): the places it weighed ([seen], with their scores) and where it went ([best]), into [watchLog]. */
    private fun logWatched(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place, byNetwork: Boolean) {
        val g = c.g
        fun where(p: Int) = "hex %d at %.0f m".format(gs.hw.placeHex[p].i, gs.hw.placeElevationM[p])
        val top = seen.sortedByDescending { it.second }.take(5)
        gs.watchLog += "weighed %d places%s; went to %s (hand-written score %.2f%s)".format(seen.size, if (c.restless) ", restless" else "", where(best.i),
            seen.firstOrNull { it.first == best.i }?.second ?: 0f, if (byNetwork) ", chosen by the network" else "")
        for ((p, score) in top) gs.watchLog += "   %s%s: %.2f".format(where(p), if (p == g.place.i) " (stay)" else "", score)
    }

    /**
     * planDiag=kind: one group's reasons ([c], the places it weighed in [seen]), for the place it chose ([best]), staying
     * and the best-placed step toward its season's place.
     */
    private fun printPlanDiag(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place) {
        val g = c.g; val s = g.species; val kind = c.kind; val goalHex = c.goalHex; val reached = c.buf.reached
        // (The score is the hand-written one before its small noise and the group's temper, as [scorePlace] gives it.)
        fun parts(p: Place): String {
            val h = gs.hw.placeHex[p]; val hunters = huntersKm2(c, p, h)
            val dying = deathsAt(c, p, h, 0f, c.buf.dist[h])
            return "score %.2f: deaths %.4f (%.1f steps) food %.2f crowd %.2f hunters %.2f kg/km2 water %.1f km going %.0f km (%.0f km from goal)".format(
                seen.firstOrNull { it.first == p.i }?.second ?: Float.NaN, dying, gs.survivalSteps(g, dying), placeValue[kind][p],
                gs.crowdedBy(s, kinAround(g, kind, h, c.sensed)), hunters,
                if (s.human) gs.land.drink.peopleWaterKm(p) else gs.land.drink.waterKm[p], c.buf.dist[h], gs.hexKm(h, goalHex))
        }
        val towards = (0 until reached.size).map { reached[it] }.minByOrNull { gs.hexKm(it, goalHex) }?.let { h ->
            gs.hw.placeNear(g.place, h).takeIf { !it.none } }
        println("plan ${s.name} #${g.id} of ${g.size} month ${c.date.month + 1}, ${if (c.restless) "restless" else "content"}, reach %.0f km, weighed %d places: chose ".format(c.reach, seen.size) +
            parts(best) + (if (best != g.place) " | stay " + parts(g.place) else "") + (towards?.let { " | toward goal " + parts(it) } ?: ""))
    }

    /** How many places' hand-written scores have come out not a number this run (see [tellNotANumber]). */
    @Transient private val notANumber = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Prints the first few places whose hand-written score for [c]'s group came out not a number (NaN or infinite), with
     * the parts it is made of: such a place is never chosen, by the hand-written rules or a network, so nothing else shows
     * it. [alive] is the score before its deaths, [drowning] the crossing's; [t] the place's terms (null where they were not kept);
     * the rest as [PlanContext.at] holds them.
     */
    private fun tellNotANumber(c: PlanContext, t: PlaceTerms?, p: Place, h: Hex, drowning: Float, alive: Float) {
        if (notANumber.incrementAndGet() > 20) return
        val g = c.g; val s = g.species
        val d = c.at.d; val prey = c.at.prey; val crowded = c.at.crowded; val fed = c.at.fed
        val deaths = deathsAt(c, p, h, fed, d)
        val thirst = gs.thirstOdds(g, p, gs.dryAfter(g, p, fed))
        val causes = StringBuilder()
        gs.foreseenHazards(g, p, c.weather, fed, thirst, d) { cause, a, y ->
            if (a != 0f || y != 0f) causes.append(" ").append(cause).append(" ").append(a).append("/").append(y) }
        val terms = t?.let { "food value %s discomfort %s drain %s suits %s water %s km".format(placeValue[c.kind][p], it.discomfort, it.drain, it.suitsLn, it.waterKm) } ?: "not kept"
        println("not a number: ${s.name} #${g.id} of ${g.size} (${g.adults} grown, ${g.young} young, odds of dying ${g.deathOdds}, fed ${g.fed}) at place ${p.i}, " +
            "$d km of going: score before deaths $alive, deaths $deaths, drowning $drowning, steps ${gs.survivalSteps(g, deaths + drowning)}; " +
            "foreseen fed $fed, thirst $thirst, crowded $crowded, prey $prey; " +
            "hazards:$causes; terms: $terms")
    }

    /**
     * What a group weighing its places this week brings to each of them: worked out once in [planInner], read by
     * [scorePlace], [fillFeatures], [deathsAt] and the diagnostics. [buf] is the thread's scratch: the reach search's
     * hexes and the place being weighed ([at]).
     */
    private class PlanContext(val g: HexGroup, val kind: Int, val here: Hex, val sensed: HexArray, val month: Int, val goal: Int, val goalQ: Float,
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
    private class PlaceLook {
        var d = 0f; var toGoal = 0.0; var beyond = 0f; var foreign = 0f; var offGround = 0f; var pull = 0f
        var prey = 0f; var crowded = 0f; var hunters = 0f; var fed = 0f
    }

    /** The odds of the hazards at a place, summed ([deathsAt]). */
    private class Odds : HazardSink {
        var adult = 0f; var young = 0f
        override fun add(cause: DeathCause, adult: Float, young: Float) { this.adult += adult; this.young += young }
    }

    /**
     * The share of a group of [s] that drowns on the way from hex [here] to hex [to] along the cheapest chain ([via]):
     * each strait crossed, as [move] drowns it (the young twice as many, counted here as grown).
     */
    private fun crossingDeaths(s: Species, to: Hex, here: Hex, via: HexArray): Float {
        var risk = 0f; var h = to
        while (h != here) {
            val from = via[h]; if (from.none) break
            val gap = gs.seaGap(from, h)
            if (gap.isFinite()) risk += Hazards.drowning(s, gap)
            h = from
        }
        return min(0.9f, risk)
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
        // (The residents' rivals keep out of its way too, next week as they move: [HexFauna.strongest].)
        topStrength.copyInto(gs.fauna.strongest)
    }

    /** Before the groups choose: each place's worth to each kind this week ([valuePlaces]); last week's searches and efforts let go. */
    internal fun beginChoosing(predatorKg: FloatArray, month: Int) {
        valuePlaces(predatorKg, month)
        reachCache.clear(); stepEffort.clear()
    }

    private fun valuePlaces(predatorKg: FloatArray, month: Int) {
        for (a in gs.headsHere) a.fill(0)
        for (g in gs.groups) if (g.alive) gs.headsHere[gs.kindSlot[g.species.index]][g.place] += g.size
        // (What may be expected changes slowly: it is reckoned once a month.)
        if (month != expectMonth) { expect(predatorKg); expectMonth = month }
        IntStream.range(0, gs.n).parallel().forEach { p -> for ((k, s) in gs.kinds.withIndex()) placeValue[k][p] = gs.lifewayValue(s, Place(p)) }
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
            val suitArea = IntStream.range(0, gs.n).parallel().mapToDouble { p -> val v = gs.fauna.suits(s, Place(p)) * gs.land.areaKm2[p]; e[p] = v; v.toDouble() }.sum()
            var heads = 0L; for (p in 0 until gs.n) heads += gs.headsHere[k][p]
            val now = (heads / suitArea.coerceAtLeast(1.0)).toFloat()
            usualDensity[k] = if (usualDensity[k] <= 0f) now else usualDensity[k] + (now - usualDensity[k]) / USUAL_MONTHS
            val d = usualDensity[k]
            IntStream.range(0, gs.n).parallel().forEach { p -> e[p] *= d }
        }
    }

    /** What place [p] is worth to hunter [g] as it knows it: [placeValue], and for a band the fish it could take there ([fishWorth]). */
    private fun knownGame(g: HexGroup, kind: Int, p: Place, h: Hex): Float = placeValue[kind][p] + (g.band?.let { fishWorth(g, it, h) } ?: 0f)

    /**
     * The fish and shellfish band [b] could take from hex [h] in a week, with its skill and its weirs ([catchWithin]; less
     * plain where it is a stranger), as the game standing on the ground that would yield as much to its hunters: game
     * is met over the ground its party sweeps in a week ([sweptKm2]) and caught at [CATCH].
     */
    private fun fishWorth(g: HexGroup, b: Band, h: Hex): Float =
        catchWithin(g, b, h) * (if (b.knows(h)) 1f else 0.5f) / (gs.sweptKm2(g.species) * CATCH)

    /** The fish and shellfish band [b] (group [g]) could take from hex [h] in a week ([HexWater.bandCatchWithin]), and from a weir standing there that it may use ([HexWater.weirWithin]). */
    private fun catchWithin(g: HexGroup, b: Band, h: Hex): Float =
        gs.fauna.water.bandCatchWithin(h, b.fishing()) + gs.fauna.water.weirWithin(h, gs.weirAt(g, h))

    /**
     * What draws band [b] (group [g]) to place [p] in hex [h] or puts it off, beyond what any group weighs: its
     * appointment, its dead, its houses, raids and the homes of others, gatherings, and country it does not know.
     * (Kept apart from [planInner] so that the planner stays small enough for the JIT to compile.)
     */
    private fun bandPull(g: HexGroup, b: Band, month: Int, h: Hex, here: Hex, p: Place, keepOff: HexArray?, sensed: HexArray, reach: Float): Float {
        var score = 0f
        // Its appointment draws a band in its month (to the month with sky lore; within a month
        // either way, more weakly, without); its ancestors' places draw it a little always.
        val pull = gs.appointPull(g, b, month, h)
        if (pull != 0f) score += APPOINT_PULL * pull
        val dead = g.memory.deadAt(h)
        if (dead > 0f) score += ANCESTOR_PULL * min(1f, dead / 10f)
        // Houses it may use draw it (its own or any left empty); a camp where someone died lately is shunned by those who leave their dead.
        score += HOME_PULL * gs.housesAt(g, h)
        score += cropPull(g, b, h)
        score -= herdLoss(g, b, p)
        if (h == b.unluckyHex) score -= UNLUCKY
        // It keeps off the known homes of bands it is not close to (unless set on raiding them).
        if (b.raidHex != h && keepOff != null && h in keepOff) score -= TRESPASS_AVOID
        // A band set on a raid heads for its target.
        if (!b.raidHex.none) {
            val from = gs.hexKm(here, b.raidHex)
            if (from > 0.0) score += RAID_PULL * ((from - gs.hexKm(h, b.raidHex)) / from).toFloat().coerceIn(-1f, 1f)
        }
        // A band short of sodium heads for the nearest salt it knows of, as a hungry animal seeks a lick. What it gets there
        // lasts it years (it keeps [KEEP_YEARS] of [YEAR_NEED]): a year of weeks without the share of its food that salt lack
        // spoils ([saltFeeds]), a year being as far ahead as a band foresees ([foreseenSurvival]), paid for by the weeks the
        // walk there takes, at its reach a week.
        // (Not where it can get salt already: on the shore or a salt pan, as [saltFeedsAt] has it.)
        if (b.sodiumShort > 0f && !gs.hw.coast[here] && gs.land.salt[g.place] <= 0f) {
            val salt = gs.bands.saltKnown(b, here)
            val from = if (!salt.none) gs.hexKm(here, salt) else 0.0
            if (from > gs.hex.hexKm) {
                val weeks = max(1.0, from / reach).toFloat()
                score += (1f - saltFeeds(b.sodiumShort, 1f, gs.tuning.saltLack)) * gs.stepsPerYear / weeks *
                    ((from - gs.hexKm(h, salt)) / min(from, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
            }
        }
        // A band with food enough to feast others is drawn to where other bands are (gatherings).
        if (gs.peopleSlot >= 0 && b.holds(Tradition.FEASTS))
            gs.dialUse?.choice(DialSite.STORE_FEAST, b.storeShare, g.larder > b.storeAim * g.dailyNeedKg, g.larder > b.storeAimAt1 * g.dailyNeedKg)
        if (gs.peopleSlot >= 0 && b.holds(Tradition.FEASTS) && g.larder > b.storeAim * g.dailyNeedKg) {
            val others = knownHeads(gs.peopleSlot, p, h, sensed) - (if (p == g.place) g.size else 0)
            if (others > 0f) score += GATHER_PULL * min(1f, others / GATHER_HEADS)
        }
        // Bands keep to country they know; what lies beyond they know only as a sense of better land.
        // Strange country puts a band off, unless it is out to explore (then it draws it on).
        if (!b.knows(h)) score -= UNKNOWN_COUNTRY * (2f - b.explore)
        if (!b.knows(h)) gs.dialUse?.amount(DialSite.EXPLORE_SCORE, b.explore, UNKNOWN_COUNTRY * (2f - b.explore), UNKNOWN_COUNTRY)
        return score
    }

    /**
     * What the crop band [b] (group [g]) has sown and not yet reaped draws it to the hex it stands in: leaving it, the band
     * would lose its harvest (others reap a field no one keeps), a loss paid for over the weeks it keeps a camp ([Band.stayWeeks]),
     * as a move's lost days are. The harvest is its share of the field as the sim would ripen it now ([HexFood.fieldExpect]),
     * in weeks of its food.
     */
    private fun cropPull(g: HexGroup, b: Band, h: Hex): Float {
        val f = b.sownPlace
        if (f.none || gs.hw.placeHex[f.i] != h) return 0f
        val food = gs.fauna.food
        val kg = min(b.sownKg, food.field[f] * gs.land.m2(f))
        if (kg <= 0f) return 0f
        return kg * food.fieldExpect(f) * seedValue(b) / (g.dailyNeedKg.coerceAtLeast(1e-3f) * gs.days) / b.stayWeeks
    }

    /**
     * What a camp at [p] would cost band [b]'s (group [g]'s) kept animals a week, in weeks of its food: as far as they would go
     * short of forage there ([herdForage], the reckoning that feeds them), those that would die of it and the young that
     * would not be born ([keepHerds]' rule, its birth season taken over the year), at their meat.
     */
    private fun herdLoss(g: HexGroup, b: Band, p: Place): Float {
        var kg = 0f
        for (k in b.herd.indices) {
            val n = b.herd[k] + 0.5f * b.herdYoung.getOrElse(k) { 0f }
            if (n <= 0f) continue
            val s = gs.fauna.kinds[k]
            val short = 1f - gs.herdForage(p, s, n).fed
            if (short <= 0f) continue
            kg += n * short * (0.5f + s.youngPerFemale * herdMothers(s, b.herdTame[k])) * gs.years * s.massKg
        }
        return kg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale / (g.dailyNeedKg.coerceAtLeast(1e-3f) * gs.days)
    }

    /** Heads of kind slot [k] in place [p] as a group knows them: seen in its own hex and the six around, else expected. */
    private fun knownHeads(k: Int, p: Place, h: Hex, sensed: HexArray): Float = if (h in sensed) gs.headsHere[k][p].toFloat() else expectHeads[k][p]

    /**
     * The heads of its own kind per km² group [g] (of kind slot [kind]) foresees about it in hex [h]: its kind there as
     * it knows it ([knownHeads]) and itself, over the ground it would range from there (the hex, and the six round it
     * where its kind's home range reaches beyond one: [rangesWide]), as [liveWeek] counts its kin where it is.
     */
    internal fun kinAround(g: HexGroup, kind: Int, h: Hex, sensed: HexArray): Float {
        val s = g.species; val own = gs.hw.placeHex[g.place]
        var heads = 0f; var area = 0f
        for (d in -1 until (if (gs.rangesWide(s)) 6 else 0)) {
            val q = if (d < 0) h else gs.hex.neighbour(h, d)
            if (q.none) continue
            for (pp in gs.hw.placesOf(q)) { heads += knownHeads(kind, Place(pp), q, sensed); area += gs.land.areaKm2[pp] }
            if (q == own) heads -= g.size
        }
        return if (area > 0f) (max(0f, heads) + g.size) / area else 0f
    }

    /** Per thread: least effort to each hex, the hex it was reached from, the hexes reached, the queue. */
    private class Scratch(size: Int) {
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
    private class PlaceTerms {
        /** The cold or heat felt there now ([discomfort]), and what the place drains from the group ([drain]). */
        var discomfort = 0f; var drain = 0f
        /** The cold or heat coming there, in degrees beyond the kind's comfort (unscaled). */
        var aheadC = 0f
        /** How far drinking water lies, km (for people, including what they can dig for). */
        var waterKm = 0f
        /** How well the place's country suits the kind, as a log. */
        var suitsLn = 0f
    }

    private fun fillTerms(t: PlaceTerms, g: HexGroup, p: Place, weather: Weather, date: Date) {
        val s = g.species
        t.discomfort = gs.discomfort(s, p, weather)
        t.drain = gs.drain(g, p, weather)
        t.aheadC = s.discomfortC(weather.forecastTemp(p.i), date.month)
        t.waterKm = if (s.human) gs.land.drink.peopleWaterKm(p) else gs.land.drink.animalWaterKm(p)
        t.suitsLn = ln(gs.fauna.suits(s, p).coerceAtLeast(1e-3f))
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
    private class Reach(val reach: Float, val hexes: HexArray, val dist: FloatArray, val via: HexArray)

    @Transient private val reachCache = ConcurrentHashMap<Long, Reach>()

    /**
     * This week's effort of each crossing (hex × 6 + direction) for a kind's way of going and the height
     * band it walks in: the crossing's own effort, harder through lying snow, easier along a worn path.
     * The same for every group of those, so worked out once a week for each (see [planInner]); +∞ where
     * there is no way across.
     */
    @Transient private val stepEffort = ConcurrentHashMap<Int, FloatArray>()

    private fun stepEffort(travel: Int, band: Int, weather: Weather): FloatArray = stepEffort.computeIfAbsent(travel * 64 + band) {
        val cross = gs.hex.crossings[travel]
        val out = FloatArray(gs.hex.size * 6) { Float.POSITIVE_INFINITY }
        IntStream.range(0, gs.hex.size).parallel().forEachHex { h ->
            for (k in 0 until 6) {
                val nh = gs.hex.neighbour(h, k)
                if (nh.none || gs.hex.heart[nh] < 0) continue
                // Lying snow makes the going harder: about twice the effort in 15 cm, more in deeper. (Read at the height
                // it walks at: in the next hex, the ground nearest the low or high ground of this one it keeps to.)
                val np = gs.hw.placeIn(band, h).let { if (!it.none) gs.hw.placeNear(it, nh) else gs.hw.placeIn(band, nh) }
                val snowMm = if (!np.none) weather.snowpack[np] else 0f
                // A worn path makes the going easier, the more so where the country is broken.
                val worn = gs.wear.paths[gs.hex.edge(h, k)] * PATH_EASE * (0.5f + (if (!np.none) gs.land.ruggedness[np] else 0f))
                out[gs.hex.edge(h, k)] = cross[gs.hex.edge(h, k)] * (1f + snowMm / SNOW_EFFORT_MM) * (1f - min(0.7f, worn))
            }
        }
        out
    }

    /** Effort and hex packed in one number, ordered by effort (efforts are never negative). */
    private fun Long.withPlace(d: Float, h: Hex): Long = (d.toRawBits().toLong() shl 32) or h.i.toLong()

    /**
     * What each place offers each kind this week, worked out once for all its groups: the game it can
     * catch there, and for bands the plant food and the camp too ([lifewayValue]).
     */
    internal val placeValue by lazy { Array(gs.kinds.size) { FloatArray(gs.n) } }

    /** Heads of each kind a place may be expected to hold, by how well it suits the kind: what a group goes by where it has not looked. */
    private val expectHeads by lazy { Array(gs.kinds.size) { FloatArray(gs.n) } }

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

    /** Choices made in planning, by kind: (slot 0 unused: every group weighs every week), weighed and stayed, weighed and moved. */
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

    /** moveTrace: books a band's week ([moveCounts]): whether the hand-written score moved it, and if so, by what parts. */
    private fun bookMove(moved: Boolean, buf: Scratch) {
        moveCounts.incrementAndGet(MOVE_TRACE_WEEKS)
        if (!moved) return
        moveCounts.incrementAndGet(MOVE_TRACE_MOVES)
        var lead = 0; var leadGain = Float.NEGATIVE_INFINITY
        for (k in MoveTerm.entries.indices) {
            val gain = buf.bestParts[k] - buf.stayParts[k]
            moveCounts.addAndGet(MOVE_TRACE_SUMS + k, (1e4f * gain).roundToLong())
            if (gain > leadGain) { leadGain = gain; lead = k }
        }
        moveCounts.incrementAndGet(MOVE_TRACE_LEADS + lead)
    }
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
private object InputScale {
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

/** The weeks ahead a planning band reckons a camp over ([GroupPlanner.planCamp]): a move's effect on its food is 90-100% in by 8 to 13 weeks (the learning-signal finding of 2026-10-15). */
internal const val PLAN_CAMP_WEEKS = 13
