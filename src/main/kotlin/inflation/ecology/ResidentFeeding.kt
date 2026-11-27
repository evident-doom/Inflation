package inflation.ecology

import inflation.ecology.HexFauna.Companion.FED_MEMORY
import inflation.ecology.HexFauna.Companion.PROTEIN_DRY_BROWSE
import inflation.ecology.HexFauna.Companion.PROTEIN_FRUIT
import inflation.ecology.HexFauna.Companion.PROTEIN_MEMORY
import inflation.ecology.HexFauna.Companion.PROTEIN_NEED
import inflation.ecology.HexFauna.Companion.REACH
import inflation.ecology.HexFauna.Companion.RESIDENT_TRAMPLE
import inflation.ecology.HexFauna.Companion.SEED_KEEP
import inflation.ecology.HexFauna.Companion.SEED_KG
import inflation.ecology.HexFauna.Companion.SLEEP_NEED
import inflation.ecology.HexFauna.Grazing
import inflation.Par
import inflation.Profile
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.KgM2
import inflation.min
import inflation.world.HexLand
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The residents' feeding: every kind's share of each place's plants, its animal food, the protein in what it ate, and what the eaters do to the place.

/** Protein of the grass and of the browse at place [p], against fresh growth: full when green and leafy, little when cured or ripe. */
fun HexFauna.grassProtein(p: Place): Float {
    // (Cured grass is leached toward straw as it weathers, see [HexPlants.hayFresh].)
    val dry = tuning.proteinStraw + (tuning.proteinDryGrass - tuning.proteinStraw) * land.plants.hayFresh[p]
    return dry + (1f - dry) * land.plants.leafy(p)
}

fun HexFauna.browseProtein(p: Place) = PROTEIN_DRY_BROWSE + (1f - PROTEIN_DRY_BROWSE) * land.plants.green[p]

/**
 * [s]'s diet at place [p] this week, a kind that eats both grass and browse leaning toward whichever is the better of
 * them now, by the square of their protein ([grassProtein], [browseProtein]), its grass and browse together as much as
 * ever: grazing the fresh grass of spring, browsing the shoots through the dry season and the winter, when the grass has
 * cured to straw and the shoots keep their protein. The mixed feeders' edge (fallow and red deer, gazelle, goats and
 * sheep shift from grass to browse with the season; Hofmann 1989, from memory). With their shares fixed, a deer ate half
 * cured grass through a dry summer and lost out to the small selective kinds and the big bulk grazers alike.
 */
internal fun HexFauna.leanDiet(s: Species, p: Place): Diet {
    val d = s.diet
    if (d.grass <= 0f || d.browse <= 0f) return d
    val gp = grassProtein(p); val bp = browseProtein(p)
    val wg = d.grass * gp * gp; val wb = d.browse * bp * bp
    if (!(wg + wb > 0f)) return d
    val both = d.grass + d.browse; val g = both * wg / (wg + wb)
    return d.copy(grass = g, browse = both - g)
}

/** How well a diet with [share] of fresh growth's protein meets a mother's and a growing young's need, 0..1. */
fun HexFauna.proteinMet(share: Float) = min(1f, share / PROTEIN_NEED)

/** The plants an animal of kind [k] at place [p] wants this week, kg per m² for one animal per km²: none asleep, more in the cold ([coldNeed]). */
internal fun HexFauna.plantWant(k: FaunaKind, p: Place, weather: Weather): Float {
    val s = kinds[k]
    return if (asleep(k, p)) 0f else s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
        waterWalkNeed(s, p) * days / 1e6f * plantShare(s)
}

/**
 * How often an animal of kind [s] at place [p] goes to water and back, times a day: as often as it goes dry ([Hazards.dryDays]:
 * less the more water its green food holds) over half the days it can last without drinking, and at least daily (cattle
 * drink once or twice a day, camels go a week and more: from memory). None for a kind that lives at the water's edge, or
 * that eats snow for its water ([HexDrinkingWater.animalWaterKm] is then none).
 */
internal fun HexFauna.waterTrips(s: Species, p: Place): Float {
    if (s.maxWaterKm.isFinite()) return 0f
    val dry = max(0f, 1f - s.waterFromFood * (0.5f + 0.5f * land.plants.green[p]))
    return dry / max(1f, s.daysWithoutWater / 2f)
}

/** The km an animal of kind [s] at place [p] walks a day to water and back ([waterTrips]), from the place's ground on average. */
internal fun HexFauna.waterWalkKm(s: Species, p: Place) = 2f * land.drink.animalWaterKm(p) * waterTrips(s, p)

/**
 * The share of its feeding day kind [s] keeps at place [p] for its walks to water ([waterWalkKm]): walking at [WATER_WALK_KMH]
 * out of a feeding day of [FEED_HOURS]. Cattle far from water graze less and walk more (the piosphere: from memory of the
 * rangeland record). Not counted: what they crop on the way.
 */
internal fun HexFauna.atWater(s: Species, p: Place): Float = (1f - waterWalkKm(s, p) / (WATER_WALK_KMH * FEED_HOURS)).coerceIn(0f, 1f)

/**
 * What its walks to water ([waterWalkKm]) add to the food kind [s] needs at place [p], as a factor: walking on the level costs
 * 10.7 M^-0.316 J per kg per m (Taylor, Heglund & Maloiy 1982), against its day's need ([Species.dailyIntakeKg], fed at
 * [Species.needScale] of [Nourishment.UNIT_KCAL] a kg). For an aurochs walking 10 km a day, some 8%.
 */
internal fun HexFauna.waterWalkNeed(s: Species, p: Place): Float {
    val km = waterWalkKm(s, p)
    if (!(km > 0f)) return 1f
    val kcalPerKm = 10.7f * s.massKg.pow(0.684f) * 1000f / 4184f
    return 1f + km * kcalPerKm / (s.dailyIntakeKg / s.needScale * Nourishment.UNIT_KCAL)
}

/** [atWater]: the pace of a walk to water, km/h; the hours of a grazer's feeding day (cattle graze some 8-11 h: from memory). Estimates. */
internal const val WATER_WALK_KMH = 4f
internal const val FEED_HOURS = 10f

/** How much of the food at place [p] kind [s] can get at: cliffs keep all but climbers off some of it, a kind that must drink often uses only the ground within reach of water, and biting insects cost it feeding time ([unbitten]). */
internal fun HexFauna.reachOf(s: Species, p: Place) = HexFood.access(s, land.crag[p]) * tether(s, p) * unbitten(p)

/**
 * The share of its feeding time kind [s] keeps at place [p] for the cover there: all for a kind that hides in thickets
 * or trees, escapes up crags (the wild goats and sheep, as much of the oak scrub as of the cliffs) or stands and fights
 * ([COVER_DEFENCE]); for one that escapes by seeing danger early and outrunning it
 * (gazelle, wild asses and horses, oryx, ostriches), less the thicker the cover, by [inflation.Tuning.coverWatch] at full cover. In the
 * woods it cannot see a stalker coming and spends its time watching, and it leaves such ground to the hiders (the
 * runners of the open plains keep to them: from memory of the vigilance record). Without it onagers and ostriches stood
 * thickest in the woodland and the swamp forest.
 */
internal fun HexFauna.inSight(s: Species, p: Place): Float = if (watchesInOpen(s)) 1f - tuning.coverWatch * land.plants.cover(p) else 1f
/** Whether [s] escapes by seeing danger early and outrunning it, the runners of the open plains ([inSight]), not by hiding, climbing or fighting. */
internal fun watchesInOpen(s: Species) = s.runsInOpen

/** [inSight]: the defence from which a kind stands its ground in cover rather than watching for a stalker. */
internal const val COVER_DEFENCE = 0.5f

/** The share of its feeding time biting insects leave an animal at place [p]: the part of [reachOf] that is not the ground. */
internal fun HexFauna.unbitten(p: Place) = 1f - HexSmallLife.HARASS_FEED * small.harassment(p)

/**
 * Small animal food for resident kind [k] at place [p], up to [want]; returns what was eaten. A kind that
 * roots for its food (roots in its diet: boar, bear) finds it mostly by digging, the grubs in the
 * soil first, then what is out, and of the small vertebrates only the young in the nest; the
 * others stalk it ([HexSmallLife.catchSmall], [HexSmallLife.reach]).
 */
private fun HexFauna.smallFood(k: FaunaKind, p: Place, want: KgM2, weather: Weather): KgM2 {
    if (want.v <= 0f) return KgM2.ZERO
    val roots = kinds[k].diet.roots > 0f
    // (A rooter digs its small food, the grubs and the nests, only as far as the ground gives, as it digs roots:
    // [HexFood.digShare], none while it is frozen, part where it is baked dry. Cutting the grubs alone, the boar dug out
    // nests for the rest and took the rodents the jackals and hyenas live on.)
    val w = if (roots) want * food.digShare(p) else want
    if (w.v <= 0f) return KgM2.ZERO
    val dug = if (roots) small.digGrubs(p, w.perKm2).perM2 else KgM2.ZERO
    // (At most half of the small game there a tick ([catchSmall]): over a step of several ticks, compounded.)
    return dug + small.catchSmall(p, (w - dug).perKm2, weather, most = small.overTicks(0.5f), who = Taker.kind(k.i), style = if (roots) SmallHunt.ROOT else SmallHunt.STALK).perM2
}

/**
 * Every kind takes its share of the place's grass, browse, fruit and roots, in proportion to
 * what each wants and can get at (cliffs keep all but climbers off part of it). The standing straw is the
 * cured grass, grazed as grass ([HexPlants.nourishment], [grassProtein]).
 */
internal fun HexFauna.graze(weather: Weather) {
    sleepOrWake(weather)
    // (Where the food came from, tallied on each core apart and added into [fedFrom] after.)
    val tallies = java.util.concurrent.ConcurrentLinkedQueue<DoubleArray>()
    val tally = ThreadLocal.withInitial { DoubleArray(kinds.size * FED).also { tallies.add(it) } }
    Par.forEachPlace("residents: graze", n) { p ->
        val z = grazings.get().also { it.clear() }
        // What can be eaten this week without eating the place bare, kg per m2: green or cured, standing grass is
        // grazed alike (the steppe's herds live through the dry season on its standing hay). What makes the dry
        // season lean is the hay itself: poor in nourishment and protein, weathering away and not growing back
        // until the rains. Supply against demand:
        // (A week's reach a tick: over a step of several ticks, what they can take of it compounds, 1 - (1 - REACH)^ticks.)
        val reach = overTicks(REACH)
        // (Only the edible share of the plants, see [HexLand.palatable].)
        val grass = reach * land.palatable[p] * land.plants.herb[p]; val browse = land.plants.browse(p)
        // (Browse is the shoots the shrubs and trees put out within reach, see [HexPlants.shrubShoots].)
        eatPlants(p, weather, grass, browse, z)
        feedKinds(p, weather, z, tally.get())
        placeEffects(p, grass, browse, z)
    }
    for (t in tallies) for (k in kindIds) for (f in 0 until FED) fedFrom[k][f].add(t[k.i * FED + f])
}

/**
 * The shares of its want of grass and of browse kind [s] can crop at place [p] ([cropRate]), by how thick they stand:
 * the edible herbs, and the shoots within reach counted as the standing plants that would give as much in a week ([REACH]);
 * less in cover for a kind that watches for stalkers there rather than feeding ([inSight]), and the less of its day it walks
 * to water ([atWater]).
 */
internal fun HexFauna.cropGrass(s: Species, p: Place) = cropRate(s, land.palatable[p] * land.plants.herb[p]) * inSight(s, p) * atWater(s, p)
internal fun HexFauna.cropBrowse(s: Species, p: Place) = cropRate(s, land.plants.browse(p) / REACH) * inSight(s, p) * atWater(s, p)

/**
 * [graze]'s plants at place [p] ([grass] and [browse] of them within reach), into [z], shared out among the kinds there by
 * what each can crop ([forage], [cropRate]), not by rank: first each kind asks what it would eat of each food as it stands;
 * where they ask more of a food than there is, each gets that food's share of what it asked (exploitative competition:
 * none drives the others off, but each eats the food down for all). What fell short of one food it makes up from the
 * others as far as its shares go (diet switching). Then those fed, with reserves short, eat more grass and browse of
 * what is left and lay the surplus on.
 */
private fun HexFauna.eatPlants(p: Place, weather: Weather, grass: Float, browse: Float, z: Grazing) {
    val quality = foragedQuality(p)
    val need = z.need; val each = z.each; val avail = z.avail
    for ((k, s) in kinds.withKinds()) {
        killRate[k]?.set(p, 0f)
        val heads = adults[k][p] + 0.5f * young[k][p]
        // (A kind not there wants nothing and eats nothing: its want and reach are read only where it is.)
        if (!(heads > 0f)) { need[k] = 0f; continue }
        // Bears asleep through the cold eat nothing.
        // (Only the plant part of the diet here; omnivores' small prey and carrion below.)
        // (More in the cold, see [coldNeed].)
        each[k] = plantWant(k, p, weather)
        need[k] = heads * each[k]
        // (Biting insects harassing them cost them feeding time.)
        avail[k] = reachOf(s, p)
    }
    // (Fruit off the shrubs, the fallen mast picked up off the ground, and the roots dug: three stocks, each shared by those
    // that eat it. The place's food is shared out patch by patch, [splitPatches]: all of it as one with [Tuning.patches] at 0.)
    val np = splitPatches(p, grass, browse, z)
    val b = z.b
    val gp = grassProtein(p); val bp = browseProtein(p)
    // (Each kind's crop of the grass and browse as they stand, and its depth of snow, the same in every turn.)
    val snow = weather.snowpack[p]
    // (The roots to be dug in the patch as they lie before anyone digs this week: what each kind finds by its search, [rootingMost].)
    var roots0 = 0f
    // (Each kind's want in the patch being shared out, and its share of its feeding there.)
    val pneed = z.pneed
    fun eat(k: Int, want: Float, w: Float) {
        val s = kinds[k]
        z.kind = k; z.access = avail[k]
        val dig = if (s.diet.roots > 0f) rootingMost(s, w * (adults[k][p] + young[k][p]), avail[k] * roots0, days, tuning.rootingM2PerDay) else Float.POSITIVE_INFINITY
        forage(s, leanDiet(s, p), quality, want, grassUnderSnow(s, snow), z, b, cropGrass(s, p), cropBrowse(s, p), dig)
    }
    // Each food shared by what was asked of it.
    fun share(asked: FloatArray, cap: FloatArray, spare: Float) {
        var sum = 0f; for (k in byMass) sum += asked[k]
        val r = if (sum > spare && sum > 0f) spare / sum else 1f
        for (k in byMass) cap[k] = r * asked[k]
    }
    // (Grass and browse short: the kinds that crop thin pickings best get the more of what they asked ([Tuning.swardEdge]):
    // on a sward eaten short a small grazer still fills its mouth bite by bite where a big one walks between bites and goes
    // short, Illius and Gordon 1987: at each kind's [Species.cropHalfKgM2] against a 100 kg kind's walking 5 km a day's, to
    // that power, each gets the same share r of its asked times that edge, up to all it asked, r such that it all goes.)
    val edge = tuning.swardEdge
    fun shareByEdge(asked: FloatArray, cap: FloatArray, spare: Float) {
        var sum = 0f; for (k in byMass) sum += asked[k]
        if (edge <= 0f || sum <= spare || !(sum > 0f)) { share(asked, cap, spare); return }
        val c = z.edge
        var hi = 0f
        for (k in byMass) { c[k] = (Species.CROP_HALF / kinds[k].cropHalfKgM2).pow(edge); if (asked[k] > 0f) hi = max(hi, 1f / c[k]) }
        var lo = 0f
        repeat(30) { val r = 0.5f * (lo + hi); var got = 0f; for (k in byMass) got += asked[k] * min(1f, r * c[k]); if (got > spare) hi = r else lo = r }
        for (k in byMass) cap[k] = asked[k] * min(1f, lo * c[k])
    }
    // One round of shares of the food now in [z]'s spare: each kind with [pneed] of it ([w] of its heads feeding there)
    // asks, each food is shared by what was asked, and each eats its share.
    fun shareRound(w: (Int) -> Float) {
        roots0 = z.spareR
        z.askG.fill(0f); z.askB.fill(0f); z.askF.fill(0f); z.askM.fill(0f); z.askR.fill(0f)
        // What each asks of each food.
        z.mode = Grazing.ASKING
        for (k in byMass) if (pneed[k] > 0f) eat(k, pneed[k], w(k))
        shareByEdge(z.askG, z.capG, z.spareG); shareByEdge(z.askB, z.capB, z.spareB); share(z.askF, z.capF, z.spareF); share(z.askM, z.capM, z.spareM); share(z.askR, z.capR, z.spareR)
        z.mode = Grazing.SHARES
        for (k in byMass) {
            val s = kinds[k]
            // (Each share of its need counted against all its need, so the rounds' shares add up to the week's.)
            val want = need[k]
            if (pneed[k] <= 0f) continue
            eat(k, pneed[k], w(k))
            z.got[k] += b.got / want
            z.gotFruit[k] += (b.fruit + b.fruit2) * Nourishment.FRUIT * s.needScale / want
            z.gotUnder[k] += ((b.roots + b.roots2) * Nourishment.ROOTS + (b.mast + b.mast2) * Nourishment.MAST) * s.needScale / want
            z.gotGrass[k] += (b.grass + b.grass2 * s.fibreDigestion) * b.q * s.needScale / want
            z.gotBrowse[k] += (b.browse + b.browse2 * s.fibreDigestion) * b.q * s.needScale / want
            z.browseKg[k] += b.browse + b.browse2
            z.eatG += b.grass + b.grass2; z.eatB += b.browse + b.browse2
            z.eatF += b.fruit + b.fruit2; z.eatM += b.mast + b.mast2; z.eatR += b.roots + b.roots2
            if (s.diet.roots > 0f) z.eatMRooting += b.mast + b.mast2
            z.ateKg[k] += b.eaten; z.proteinKg[k] += protein(b, s.selected(gp), s.selected(bp))
        }
    }
    // Each patch's food shared among those feeding there, each kind by its share of its feeding in it ([splitPatches]).
    for (q in 0 until np) {
        z.spareG = z.patchG[q]; z.spareB = z.patchB[q]; z.spareF = z.patchF[q]; z.spareM = z.patchM[q]; z.spareR = z.patchR[q]
        for (k in byMass) pneed[k] = need[k] * z.weight(k, q)
        shareRound { z.weight(it, q) }
        z.leftG[q] = z.spareG; z.leftB[q] = z.spareB; z.leftF[q] = z.spareF; z.leftM[q] = z.spareM; z.leftR[q] = z.spareR
    }
    // Then what each still lacks it seeks in what is left of the other patches it reaches, all of them as one: its own
    // ground first, the rest after those whose ground it is (the high browse stays the tall's alone). So a patch is the
    // first call of those it suits, not a fence.
    var highG = 0f; var highB = 0f; var highF = 0f; var highM = 0f; var highR = 0f
    if (np > 1) {
        z.spareG = 0f; z.spareB = 0f; z.spareF = 0f; z.spareM = 0f; z.spareR = 0f
        for (q in 0 until np) {
            if (q == PATCH_CANOPY) { highG = z.leftG[q]; highB = z.leftB[q]; highF = z.leftF[q]; highM = z.leftM[q]; highR = z.leftR[q]; continue }
            z.spareG += max(0f, z.leftG[q]); z.spareB += max(0f, z.leftB[q]); z.spareF += max(0f, z.leftF[q]); z.spareM += max(0f, z.leftM[q]); z.spareR += max(0f, z.leftR[q])
        }
        for (k in byMass) pneed[k] = if (need[k] > 0f) need[k] * max(0f, 1f - z.got[k]) else 0f
        shareRound { if (need[it] > 0f) pneed[it] / need[it] else 0f }
    }
    // Then the fed lay on fat from what is left.
    z.mode = Grazing.FREE
    var leftG = highG; var leftB = highB; var leftF = highF; var leftM = highM; var leftR = highR
    run {
        if (np == 1) { z.spareG = z.leftG[0]; z.spareB = z.leftB[0]; z.spareF = z.leftF[0]; z.spareM = z.leftM[0]; z.spareR = z.leftR[0] }
        for (k in byMass) {
            val s = kinds[k]
            val want = need[k]
            if (want <= 0f || z.got[k] < 1f || reserve[k][p] >= s.maxFatDays) continue
            val d = s.diet
            z.access = avail[k]
            // (In energy, as [forage] works: see [Species.needScale]; as far as it can crop them, as it fed.)
            val extra = surplusShare(FaunaKind(k), p) * want / s.needScale
            val gb = (d.grass + d.browse).coerceAtLeast(1e-6f)
            val qs = s.selected(quality)
            val grass3 = if (d.grass > 0f) z.grass(cropGrass(s, p) * grassUnderSnow(s, snow) * extra / qs * d.grass / gb) else 0f
            val browse3 = if (d.browse > 0f) z.browse(cropBrowse(s, p) * extra / qs * d.browse / gb) else 0f
            z.surplus[k] += (grass3 + browse3) * qs * s.needScale / want
            z.eatG += grass3; z.eatB += browse3
        }
        leftG += max(0f, z.spareG); leftB += max(0f, z.spareB); leftF += max(0f, z.spareF); leftM += max(0f, z.spareM); leftR += max(0f, z.spareR)
    }
    // What is left, for [roomFor] (how well a band coming here would be fed on it) to reckon on when they move.
    spareGrass[p] = leftG; spareBrowse[p] = leftB; spareFruit[p] = leftF; spareMast[p] = leftM; spareRoots[p] = leftR
}

/**
 * [eatPlants]' patches of place [p] ([Tuning.patches]): the glade's open ground, the wood's floor, the wet floor of its
 * floodplain, and the browse high in the trees' crowns, each with its own grass, browse, fruit, mast and roots out of the
 * place's ([grass] and [browse] within reach), into [z]; and each kind's share of its feeding in each ([Grazing.weight]):
 * by how it likes each ground ([Habitat.cover], [Habitat.wet]) times the food of its diet there. The high browse is for
 * the tall alone ([TALL_KG]). Shared out patch by patch, the kinds of the open and of the wood each meet mostly their own
 * kind at their food. With [Tuning.patches] at 0, one patch of all of it, as before. Returns the number of patches.
 */
internal fun HexFauna.splitPatches(p: Place, grass: Float, browse: Float, z: Grazing): Int {
    val fruit = food.fruit[p]; val mast = food.mast[p]; val roots = food.diggable(p)
    if (tuning.patches <= 0f) {
        z.patchG[0] = grass; z.patchB[0] = browse; z.patchF[0] = fruit; z.patchM[0] = mast; z.patchR[0] = roots
        for (k in kinds.indices) z.patchW[k * PATCHES] = 1f
        return 1
    }
    val pl = land.plants
    val g = pl.gladeArea(p); val m = pl.gladeUnderstory(p); val wet = land.floodShare[p].coerceIn(0f, 1f); val dry = 1f - wet
    val shrubs = max(0f, pl.shrubShoots[p]); val trees = max(0f, pl.treeShoots[p])
    val scale = if (shrubs + trees > 0f) browse / (shrubs + trees) else 0f
    val sb = shrubs * scale; val high = CANOPY_HIGH * trees * scale; val low = trees * scale - high
    val area = z.patchArea
    area[PATCH_OPEN] = dry * g; area[PATCH_WOOD] = dry * (1f - g); area[PATCH_WET] = wet; area[PATCH_CANOPY] = 0f
    z.patchG[PATCH_OPEN] = dry * m * grass; z.patchG[PATCH_WOOD] = dry * (1f - m) * grass; z.patchG[PATCH_WET] = wet * grass; z.patchG[PATCH_CANOPY] = 0f
    z.patchB[PATCH_OPEN] = dry * g * sb; z.patchB[PATCH_WOOD] = dry * ((1f - g) * sb + low); z.patchB[PATCH_WET] = wet * (sb + low); z.patchB[PATCH_CANOPY] = high
    for (q in 0 until PATCHES) { z.patchF[q] = area[q] * fruit; z.patchM[q] = area[q] * mast; z.patchR[q] = area[q] * roots }
    val like = z.patchLike; val sharp = tuning.patches
    for (k in kinds.indices) {
        val s = kinds[k]; val h = s.habitat; val d = s.diet
        like[PATCH_OPEN] = max(LIKE_LEAST, 0.5f * (1f - h.cover)); like[PATCH_WOOD] = max(LIKE_LEAST, 0.5f * (1f + h.cover))
        like[PATCH_WET] = max(LIKE_LEAST, 0.5f * (1f + h.wet)); like[PATCH_CANOPY] = if (s.massKg >= TALL_KG) like[PATCH_WOOD] else 0f
        // (How strongly each keeps to the ground it likes: the likings to the power [Tuning.patches].)
        if (sharp != 1f) for (q in 0 until PATCHES) like[q] = like[q].pow(sharp)
        var sum = 0f
        for (q in 0 until PATCHES) {
            val eats = d.grass * z.patchG[q] + d.browse * z.patchB[q] + d.fruit * z.patchF[q] + d.mast * z.patchM[q] + d.roots * z.patchR[q]
            val w = like[q] * eats; z.patchW[k * PATCHES + q] = w; sum += w
        }
        if (sum > 0f) { for (q in 0 until PATCHES) z.patchW[k * PATCHES + q] /= sum; continue }
        // (Nothing of its food anywhere: by how it likes the ground alone.)
        sum = 0f; for (q in 0 until PATCHES) { val w = like[q] * area[q]; z.patchW[k * PATCHES + q] = w; sum += w }
        for (q in 0 until PATCHES) z.patchW[k * PATCHES + q] = if (sum > 0f) z.patchW[k * PATCHES + q] / sum else if (q == PATCH_OPEN) 1f else 0f
    }
    return PATCHES
}

/** [splitPatches]: the patches, in order. */
internal const val PATCHES = 4
internal const val PATCH_OPEN = 0; internal const val PATCH_WOOD = 1; internal const val PATCH_WET = 2; internal const val PATCH_CANOPY = 3
/** [splitPatches]: the share of the trees' shoots out of reach of all but the tall (above some 2 m); an estimate. */
internal const val CANOPY_HIGH = 0.5f
/** [splitPatches]: the kinds that reach the high browse: an elephant's weight and over (du Toit 1990, browse height by body size; from memory). */
internal const val TALL_KG = 2000f
/** [splitPatches]: the least a kind feeds on ground it dislikes, against its liking for it. An estimate. */
internal const val LIKE_LEAST = 0.05f

/**
 * [graze]'s week for each kind at place [p], after the plants ([z]): the protein in what it ate, its animal food, sleeping
 * on its fat, reserves drawn on or laid on, and how well fed it was ([fed], [fedByMonth], [autumnFed]); where the food came
 * from into [ff].
 */
private fun HexFauna.feedKinds(p: Place, weather: Weather, z: Grazing, ff: DoubleArray) {
    var t = Profile.mark()
    // (Their rate at the carrion is noted afresh as they feed: [HexSmallLife.residentCarrionRate].)
    small.residentCarrionRate[p] = 0f
    val found = foundShares(p, weather, z)
    for ((k, s) in kinds.withKinds()) {
        // (And how rich in protein all it ate was; animal food is rich in it.)
        val eaten = z.ateKg[k]
        if (eaten > 0f) {
            val ps = plantShare(s)
            protein[k][p] += (proteinMet(ps * z.proteinKg[k] / eaten + (1f - ps)) - protein[k][p]) * overTicks(PROTEIN_MEMORY)
        }
        val sleeping = asleep(k, p)
        var f = if (sleeping) {
            // Asleep, it lives on its fat at a quarter of its waking need; fed as far as the fat lasts.
            val want = SLEEP_NEED * days
            val draw = min(reserve[k][p], want)
            reserve[k][p] -= draw
            draw / want
        } else {
            val ps = plantShare(s)
            val heads = adults[k][p] + 0.5f * young[k][p]
            // (With none there, nothing is sought and nothing is eaten: animalFood would find no need.)
            val animal = if (heads > 0f) animalFood(k, p, weather, z, ps, found) else 0f
            if (heads > 0f) {
                val i = k.i * FED
                ff[i + Fed.PLANTS.ordinal] += (min(1f, z.got[k]) * ps * heads).toDouble()
                // (Fruit and roots as shares of what the plants met, so the parts add up to it.)
                val part = if (z.got[k] > 0f) min(1f, z.got[k]) * ps * heads / z.got[k] else 0f
                ff[i + Fed.FRUIT.ordinal] += (z.gotFruit[k] * part).toDouble(); ff[i + Fed.UNDERGROUND.ordinal] += (z.gotUnder[k] * part).toDouble()
                ff[i + Fed.GRASS.ordinal] += (z.gotGrass[k] * part).toDouble(); ff[i + Fed.BROWSE.ordinal] += (z.gotBrowse[k] * part).toDouble()
                ff[i + Fed.BROWSE_KG.ordinal] += (z.browseKg[k] * land.areaM2[p]).toDouble()
                ff[i + Fed.ANIMAL.ordinal] += (min(1f, animal) * (1f - ps) * heads).toDouble(); ff[i + Fed.HEADS.ordinal] += heads.toDouble()
                ff[i + Fed.MEAT.ordinal] += (z.gotMeat[k.i] * heads).toDouble(); ff[i + Fed.SMALL_PREY.ordinal] += (z.gotSmall[k.i] * heads).toDouble()
                ff[i + Fed.CARRION.ordinal] += (z.gotCarrion[k.i] * heads).toDouble()
                ff[i + Fed.CARRION_KG.ordinal] += (z.carrionKg[k.i] * land.areaKm2[p]).toDouble(); ff[i + Fed.NEED_KG.ordinal] += (z.needKg[k.i] * land.areaKm2[p]).toDouble()
            }
            // (Animal food beyond its share makes up for plants that fell short.)
            (z.got[k] * ps + animal * (1f - ps)).coerceIn(0f, 1f)
        }
        // Reserves carry them through lean weeks; they are laid on from what was eaten beyond need. (Any week short of
        // its need is paid from the fat, however little short: the body's balance has no slack in it.)
        val r = reserve[k]
        fedByFood[k][p] = f
        if (!sleeping) {
            if (f < 1f) { val draw = min(r[p], (1f - f) * days); r[p] -= draw; f = min(1f, f + draw / days)
                val heads = adults[k][p] + 0.5f * young[k][p]; if (heads > 0f) ff[k.i * FED + Fed.RESERVES.ordinal] += (draw / days * heads).toDouble() }
            else { var gain = FAT_EFFICIENCY * z.surplus[k] * days
                // (What they ate beyond their need wins back the body they burnt before it lays on fat.)
                val back = min(spent[k][p], gain); spent[k][p] -= back; gain -= back
                r[p] = min(s.maxFatDays, r[p] + gain) }
        }
        fed[k][p] = f
        val fm = fedByMonth[k]; val i = p.i * 12 + weather.date.month
        // (What the young would be born to: food, and the protein their mothers' milk and their growth need.)
        fm[i] += (fed[k][p] * protein[k][p] - fm[i]) * overTicks(FED_MEMORY)
        // Laying on fat for the winter: how well fed through late summer and autumn.
        if (s.hibernates && land.localMonth(p, weather.date.month) in 7..11 && !asleep(k, p)) autumnFed[k][p] += (fed[k][p] - autumnFed[k][p]) * overTicks(0.2f)
        t = Profile.kindLap("residents: feeding", s.name, t)
    }
    small.settleResidentCarrion(p)
}

/**
 * The share of its need beyond the need a fed resident of kind [k] at place [p] eats over the step to lay on fat: it
 * eats [SURPLUS_EAT] more a day for as many of the step's days as it takes to fill the room its reserves have below
 * [Species.maxFatDays] (the body it burnt won back first, as [graze] lays it on), and no more: a rate over days, the
 * same at any step length (plan section 52.B.1). A fuller body eats less of the step's days' surplus, not a whole
 * week's for a reserve a day short of full (the weekly fault: fat thrown away at the cap).
 */
private fun HexFauna.surplusShare(k: FaunaKind, p: Place): Float {
    val room = max(0f, kinds[k].maxFatDays - reserve[k][p] + spent[k][p])
    return SURPLUS_EAT * min(1f, room / (FAT_EFFICIENCY * SURPLUS_EAT * days))
}

/**
 * [graze]'s animal food for omnivore or hunter kind [k] at place [p], [ps] of its diet plants: the rest is game it
 * kills, small animals it catches and carrion it finds. Each food is sought for its share first; what falls short, of
 * these or of the plants ([z]), is made up from whichever of them is to be had (as a pack does). Returns the share of
 * its animal need met; what it ate beyond, laid on, goes to [Grazing.surplus].
 */
/** Each kind's share of what the residents of a place found of its carrion as it lay ([HexSmallLife.residentCarrion]), by kind index ([foundShares]); none when nothing is held. */
private class Found(val share: FloatArray?)

/**
 * All of a kind's diet over the step at place [p], in kg of prey per m2 that would feed it ([Nourishment.PREY]): its heads,
 * the young at half, at its daily intake as the cold raises it.
 */
private fun HexFauna.needAll(k: FaunaKind, p: Place, weather: Weather): Float {
    val s = kinds[k]
    return (adults[k][p] + 0.5f * young[k][p]) * s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
        days / 1e6f / (Nourishment.PREY * s.needScale)
}

/**
 * How what the residents at place [p] found of the carrion as it lay is shared among the kinds, before any feeds: by the
 * weekly rule on a tick's worth, in the order they feed. Each tick, as weekly, each kind in turn eats what it would of
 * the carrion (its animal need and the plants it went short of, [z], over a tick), at most half of what still lies;
 * a tick's worth lying is twice a tick's find (the find rate is half of what lies a tick). The tick's takes, over the
 * step's ticks, are each kind's share, scaled down if they come to more than was found. Water-filled by search, capped
 * only at want, the carnivores lived almost wholly on carrion and prey kinds rose 5-16% (carr789); by search alone about
 * half went back on the ground (flow786-787); by want alone the boar took it from the carnivores (flow785).
 */
private fun HexFauna.foundShares(p: Place, weather: Weather, z: Grazing): Found {
    val held = small.residentCarrion[p]
    if (!(held > 0f)) return Found(null)
    val ticks = clock.ticks
    val share = FloatArray(kinds.size)
    var lying = 2f * held / ticks
    var sum = 0f
    for ((k, s) in kinds.withKinds()) {
        val ps = plantShare(s)
        if (adults[k][p] + 0.5f * young[k][p] <= 0f || ps >= 1f || s.diet.carrion <= 0f || asleep(k, p)) continue
        val na = needAll(k, p, weather)
        val want = (na * (1f - ps) + (if (ps > 0f) na * ps * max(0f, 1f - z.got[k]) else 0f)) / ticks
        if (!(want > 0f)) continue
        val take = min(want, 0.5f * lying)
        lying -= take
        share[k.i] = take * ticks; sum += take * ticks
    }
    if (sum > held) { val f = held / sum; for (i in share.indices) share[i] *= f }
    return Found(share)
}

private fun HexFauna.animalFood(k: FaunaKind, p: Place, weather: Weather, z: Grazing, ps: Float, found: Found): Float {
    val s = kinds[k]; val avail = z.avail; val surplus = z.surplus
    var animal = 0f
    val needAll = needAll(k, p, weather)
    if (ps < 1f && needAll > 0f) {
        val d = s.diet; val all = d.total
        val animalNeed = needAll * (1f - ps)
        val plantGap = if (ps > 0f) needAll * ps * max(0f, 1f - z.got[k]) else 0f
        // Carrion first, where it eats any: a carcass lying there costs no chase and no digging, so
        // it eats what it finds (at most half of what lies there) before it hunts or makes up plants;
        // but only what is fresh enough for it ([freshShare]): a lynx passes up a carcass days old.
        // (What they found as it lay goes to each kind by the weekly rule on a tick's worth: [foundShares].)
        var mine = found.share?.get(k.i) ?: 0f
        val fresh = if (d.carrion > 0f) small.freshShare(s, p, weather.temperature[p]) else 0f
        fun scavengeMine(want: Float): Float {
            val (got, held) = small.scavengeHeld(p, min(want, fresh * (mine + 0.5f * small.carrion[p])), mine); mine -= held; return got }
        var gotCarrion = if (d.carrion > 0f) scavengeMine(animalNeed + plantGap) else 0f
        // (Over a step of several ticks, taken whole: the residents that eat carrion come on it as it lies at the rate their
        // weekly feeding takes it, half of what lies each tick (ln 2 a tick), for the step's soil ([stepSoil]); at one tick a
        // step, as before, only what lies as they feed. Rates by search or held for a single tick moved weekly diets far
        // from main's, cf6-776..share780.)
        if (d.carrion > 0f && clock.ticks > 1) small.residentCarrionRate[p] = 0.6931472f / clock.tickDays
        var left = animalNeed + plantGap - gotCarrion
        // (A bone-cracker eats the bones lying there too, at most half of them, at what a kilo of bone feeds it against
        // a kilo of prey: [Food.BONES], as a pack forages them.)
        val boneWorth = if (s.cracksBones) Food.BONES.energy / Nourishment.PREY else 0f
        val bonesKg = if (boneWorth > 0f && left > 0f) small.crackBones(p, KgM2(min(left / boneWorth, 0.5f * small.bones[p]))).v else 0f
        val gotBones = bonesKg * boneWorth
        left -= gotBones
        val huntDays = FloatArray(1)
        var gotMeat = if (left > 0f) huntResidents(k, p, KgM2(min(left, needAll * d.meat / all) * avail[k]), weather, huntDays).v else 0f
        left -= gotMeat
        var gotSmall = if (left > 0f) smallFood(k, p, KgM2(min(left, needAll * d.smallPrey / all) * avail[k]), weather).v else 0f
        var gap = left - gotSmall
        // (The shortfall is made up first from the food it mostly lives on, then from the others, as far as the place
        // has them: a hungry hunter takes what of its foods it finds, but no more game it kills than (1 + SWITCH) times its
        // usual share, as the surplus it lays on as fat: a bear short of plants in spring does not turn wolf (small prey
        // and carrion stay uncapped: capping all, bearcap, left boar at 2%, as they live through spring on them) (preydiet1125:
        // bears killed twice the boar the wolves did, 330,000 hoofed kills by 27,000 bears in a year).)
        val most = (1f + SWITCH) * needAll / all
        fun share(food: AnimalFood) = when (food) { AnimalFood.MEAT -> d.meat; AnimalFood.SMALL_PREY -> d.smallPrey; AnimalFood.CARRION -> d.carrion }
        val order = AnimalFood.entries.filter { share(it) > 0f }.sortedByDescending { share(it) }
        for (food in order) {
            if (gap <= 0f) break
            val had = when (food) { AnimalFood.MEAT -> gotMeat; AnimalFood.SMALL_PREY -> gotSmall; AnimalFood.CARRION -> gotCarrion }
            val want = if (food == AnimalFood.MEAT) min(gap, max(0f, most * share(food) - had)) else gap
            val x = when (food) {
                AnimalFood.MEAT -> want.let { if (it > 0f) huntResidents(k, p, KgM2(it * avail[k]), weather, huntDays).v else 0f }
                AnimalFood.SMALL_PREY -> want.let { if (it > 0f) smallFood(k, p, KgM2(it * avail[k]), weather).v else 0f }
                AnimalFood.CARRION -> if (want > 0f) scavengeMine(want) else 0f
            }
            when (food) { AnimalFood.MEAT -> gotMeat += x; AnimalFood.SMALL_PREY -> gotSmall += x; AnimalFood.CARRION -> gotCarrion += x }
            gap -= x
        }
        // With its need met and reserves short, it eats more and lays the surplus on (as from plants).
        if (gap <= 0f && reserve[k][p] < s.maxFatDays) {
            // (No more than lays on what the reserves still have room for over the step's days: [surplusShare].)
            var more = min(SURPLUS_EAT * animalNeed, max(0f, surplusShare(k, p) - surplus[k]) * needAll)
            val moreMeat = min(more, most * d.meat - gotMeat)
            if (moreMeat > 0f) { val x = huntResidents(k, p, KgM2(moreMeat * avail[k]), weather, huntDays).v; surplus[k] += x / needAll; more -= x }
            val moreSmall = min(more, most * d.smallPrey - gotSmall)
            if (moreSmall > 0f) { val x = smallFood(k, p, KgM2(moreSmall * avail[k]), weather).v; surplus[k] += x / needAll
                ateBook?.add(-x * land.areaM2[p].toDouble()) }
        }
        animal = (gotMeat + gotSmall + gotCarrion + gotBones) / animalNeed
        z.gotMeat[k.i] = gotMeat / needAll; z.gotSmall[k.i] = gotSmall / needAll; z.gotCarrion[k.i] = (gotCarrion + gotBones) / needAll
        z.carrionKg[k.i] = gotCarrion; z.needKg[k.i] = needAll
        small.deposit(DeadPool.DUNG, p, KgM2(HexSmallLife.DUNG * (gotMeat + gotSmall + gotCarrion + bonesKg)))
        if (ateBook != null) { val m2 = land.areaM2[p].toDouble()
            ateBook!!.add(-(gotSmall + gotCarrion + bonesKg) * m2); droppedBook!!.add(HexSmallLife.DUNG * (gotMeat + gotSmall + gotCarrion + bonesKg) * m2) }
    }
    return animal
}

/**
 * What [graze]'s eaters ([z]) do to place [p], of whose [grass] and [browse] they ate: the plants they ate taken off,
 * the seed they spread, the plants trampled and trodden, their dung, and the week's grazing, browsing and rooting.
 */
private fun HexFauna.placeEffects(p: Place, grass: Float, browse: Float, z: Grazing) {
    val eatG = z.eatG; val eatB = z.eatB
    // What is left of the week's grazing and browsing ([grazeLeft]).
    grazeLeft[p] = max(0f, grass - eatG); browseLeft[p] = max(0f, browse - eatB)
    val dug = food.take(food.roots, p, KgM2(z.eatR)).v
    val gleaned = food.takeMast(HexFood.MastFate.ANIMALS, p, KgM2(z.eatM)).v
    val ate = food.take(food.fruit, p, KgM2(z.eatF)).v
    val eaten = land.plants.consume(p, LifeForm.HERB, KgM2(eatG)).v +
        land.plants.eatBrowse(p, KgM2(eatB)).v +
        ate + gleaned + dug
    // Fruit-eaters drop the seed where they go: shrubs and trees come up away from any parent.
    // (Over a step of several ticks the seed is dropped tick by tick and fades as it would each tick: S k^n + I (1 - k^n) / (1 - k), I a tick's.)
    land.plants.animalSeed[p] = min(1f, if (clock.ticks == 1) land.plants.animalSeed[p] * SEED_KEEP + ate * 1e6f / SEED_KG
        else SEED_KEEP.pow(clock.ticks).let { kn -> land.plants.animalSeed[p] * kn + ate * 1e6f / SEED_KG / clock.ticks * (1f - kn) / (1f - SEED_KEEP) })
    // Their feet beat down the plants and wear paths: tonnes of animal going about the place.
    var tonnes = 0f
    for ((k, s) in kinds.withKinds()) tonnes += (adults[k][p] + young[k][p]) * s.massKg / 1000f
    land.plants.trample(p, RESIDENT_TRAMPLE * tonnes * land.areaKm2[p])
    // (And their going about it each day steps on the ground: see [HexPlants.tread]. Heads are per km².)
    // (A tick's walking: the seedlings take it each tick of the step, [HexPlants.step].)
    for ((k, s) in kinds.withKinds()) land.plants.tread(p, (adults[k][p] + young[k][p]) * land.areaKm2[p], s.massKg, s.dailyTravelKm * clock.tickDays)
    small.deposit(DeadPool.DUNG, p, KgM2(HexSmallLife.DUNG * eaten))
    if (ateBook != null) { val m2 = land.areaM2[p].toDouble(); ateBook!!.add(-eaten * m2); droppedBook!!.add(HexSmallLife.DUNG * eaten * m2) }
    land.plants.grazing[p] = eatG * 1e6f / days; land.plants.browsing[p] = eatB * 1e6f / days
    // Rooting churns the ground: kg of roots dug per km2 a day, and of the mast the rooters turned the litter over for.
    land.plants.rooting[p] = (dug + z.eatMRooting) * 1e6f / days
}

/** How well kind [s]'s diet at place [p] would meet its need for protein, 0..1 (see [proteinMet]). */
fun HexFauna.forageProtein(s: Species, p: Place): Float {
    val d = leanDiet(s, p); val plants = d.plants
    if (plants <= 0f) return 1f
    val share = (d.grass * s.selected(grassProtein(p)) + d.browse * s.selected(browseProtein(p)) + d.stocks * PROTEIN_FRUIT) / plants
    val ps = plantShare(s)
    return proteinMet(ps * share + (1f - ps))
}
