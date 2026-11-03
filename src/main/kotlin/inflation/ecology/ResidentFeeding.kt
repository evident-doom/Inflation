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

// The residents' feeding: every kind's share of each place's plants, its animal food, the protein in what it ate, and what the eaters do to the place.

/** Protein of the grass and of the browse at place [p], against fresh growth: full when green and leafy, little when cured or ripe. */
fun HexFauna.grassProtein(p: Place): Float {
    // (Cured grass is leached toward straw as it weathers, see [HexPlants.hayFresh].)
    val dry = tuning.proteinStraw + (tuning.proteinDryGrass - tuning.proteinStraw) * land.plants.hayFresh[p]
    return dry + (1f - dry) * land.plants.leafy(p)
}

fun HexFauna.browseProtein(p: Place) = PROTEIN_DRY_BROWSE + (1f - PROTEIN_DRY_BROWSE) * land.plants.green[p]

/** How well a diet with [share] of fresh growth's protein meets a mother's and a growing young's need, 0..1. */
fun HexFauna.proteinMet(share: Float) = min(1f, share / PROTEIN_NEED)

/** The plants an animal of kind [k] at place [p] wants this week, kg per m² for one animal per km²: none asleep, more in the cold ([coldNeed]). */
internal fun HexFauna.plantWant(k: FaunaKind, p: Place, weather: Weather): Float {
    val s = kinds[k]
    return if (asleep(k, p)) 0f else s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
        days / 1e6f * plantShare(s)
}

/** How much of the food at place [p] kind [s] can get at: cliffs keep all but climbers off some of it, a kind that must drink often uses only the ground within reach of water, and biting insects cost it feeding time ([unbitten]). */
internal fun HexFauna.reachOf(s: Species, p: Place) = HexFood.access(s, land.crag[p]) * tether(s, p) * unbitten(p)

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
    val dug = if (roots) small.digGrubs(p, want.perKm2).perM2 else KgM2.ZERO
    return dug + small.catchSmall(p, (want - dug).perKm2, weather, who = Taker.kind(k.i), style = if (roots) SmallHunt.ROOT else SmallHunt.STALK).perM2
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
        val z = Grazing(kinds.size)
        // What can be eaten this week without eating the place bare, kg per m2: green or cured, standing grass is
        // grazed alike (the steppe's herds live through the dry season on its standing hay). What makes the dry
        // season lean is the hay itself: poor in nourishment and protein, weathering away and not growing back
        // until the rains. Supply against demand:
        val reach = REACH
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
 * [graze]'s plants at place [p] ([grass] and [browse] of them within reach), into [z]: each kind in turn by body mass
 * eats by the one feeding rule ([forage]), as far as it can get at what the bigger ones left: where food is short the
 * bigger kinds drive the smaller off it (a hippo or an aurochs gives way to nothing smaller). What fell short it makes
 * up from the other foods (diet switching); with food to spare and reserves short, it
 * eats more grass and browse and lays the surplus on.
 */
private fun HexFauna.eatPlants(p: Place, weather: Weather, grass: Float, browse: Float, z: Grazing) {
    val quality = land.plants.nourishment(p)
    val need = z.need; val each = z.each; val avail = z.avail
    for ((k, s) in kinds.withKinds()) {
        killRate[k]?.set(p, 0f)
        val heads = adults[k][p] + 0.5f * young[k][p]
        // Bears asleep through the cold eat nothing.
        // (Only the plant part of the diet here; omnivores' small prey and carrion below.)
        // (More in the cold, see [coldNeed].)
        each[k] = plantWant(k, p, weather)
        need[k] = heads * each[k]
        // (Biting insects harassing them cost them feeding time.)
        avail[k] = reachOf(s, p)
    }
    z.spareG = grass; z.spareB = browse; z.spareF = food.fruit[p]; z.spareR = food.underground(p)
    val b = z.b
    val gp = grassProtein(p); val bp = browseProtein(p)
    for (k in byMass) {
        val s = kinds[k]
        val want = need[k]
        if (want <= 0f) continue
        val d = s.diet
        val underSnow = grassUnderSnow(s, weather.snowpack[p])
        z.access = avail[k]
        forage(s, d, quality, want, underSnow, z, b)
        z.got[k] = b.got / want
        z.gotFruit[k] = (b.fruit + b.fruit2) * Nourishment.FRUIT * s.needScale / want
        z.gotUnder[k] = (b.roots + b.roots2) * Nourishment.ROOTS * s.needScale / want
        var grass3 = 0f; var browse3 = 0f
        if (b.got >= want && reserve[k][p] < s.maxFatDays) {
            // (In energy, as [forage] works: see [Species.needScale].)
            val extra = SURPLUS_EAT * want / s.needScale
            if (d.grass > 0f) grass3 = z.grass(underSnow * extra / quality * d.grass / (d.grass + d.browse).coerceAtLeast(1e-6f))
            if (d.browse > 0f) browse3 = z.browse(extra / quality * d.browse / (d.grass + d.browse).coerceAtLeast(1e-6f))
            z.surplus[k] = (grass3 + browse3) * quality * s.needScale / want
        }
        z.eatG += b.grass + b.grass2 + grass3; z.eatB += b.browse + b.browse2 + browse3
        z.eatF += b.fruit + b.fruit2; z.eatR += b.roots + b.roots2
        z.ateKg[k] = b.eaten; z.proteinKg[k] = protein(b, gp, bp)
    }
    // What is left, for [roomFor] (how well a band coming here would be fed on it) to reckon on when they move.
    spareGrass[p] = max(0f, z.spareG); spareBrowse[p] = max(0f, z.spareB); spareFruit[p] = max(0f, z.spareF)
    spareRoots[p] = max(0f, z.spareR)
}

/**
 * [graze]'s week for each kind at place [p], after the plants ([z]): the protein in what it ate, its animal food, sleeping
 * on its fat, reserves drawn on or laid on, and how well fed it was ([fed], [fedByMonth], [autumnFed]); where the food came
 * from into [ff].
 */
private fun HexFauna.feedKinds(p: Place, weather: Weather, z: Grazing, ff: DoubleArray) {
    var t = Profile.mark()
    for ((k, s) in kinds.withKinds()) {
        // (And how rich in protein all it ate was; animal food is rich in it.)
        val eaten = z.ateKg[k]
        if (eaten > 0f) {
            val ps = plantShare(s)
            protein[k][p] += (proteinMet(ps * z.proteinKg[k] / eaten + (1f - ps)) - protein[k][p]) * PROTEIN_MEMORY
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
            val animal = animalFood(k, p, weather, z, ps)
            val heads = adults[k][p] + 0.5f * young[k][p]
            if (heads > 0f) {
                val i = k.i * FED
                ff[i + Fed.PLANTS.ordinal] += (min(1f, z.got[k]) * ps * heads).toDouble()
                // (Fruit and roots as shares of what the plants met, so the parts add up to it.)
                val part = if (z.got[k] > 0f) min(1f, z.got[k]) * ps * heads / z.got[k] else 0f
                ff[i + Fed.FRUIT.ordinal] += (z.gotFruit[k] * part).toDouble(); ff[i + Fed.UNDERGROUND.ordinal] += (z.gotUnder[k] * part).toDouble()
                ff[i + Fed.ANIMAL.ordinal] += (min(1f, animal) * (1f - ps) * heads).toDouble(); ff[i + Fed.HEADS.ordinal] += heads.toDouble()
            }
            // (Animal food beyond its share makes up for plants that fell short.)
            (z.got[k] * ps + animal * (1f - ps)).coerceIn(0f, 1f)
        }
        // Reserves carry them through lean weeks; they are laid on from what was eaten beyond need. (Any week short of
        // its need is paid from the fat, however little short: the body's balance has no slack in it.)
        val r = reserve[k]
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
        fm[i] += (fed[k][p] * protein[k][p] - fm[i]) * FED_MEMORY
        // Laying on fat for the winter: how well fed through late summer and autumn.
        if (s.hibernates && weather.date.month in 7..11 && !asleep(k, p)) autumnFed[k][p] += (fed[k][p] - autumnFed[k][p]) * 0.2f
        t = Profile.kindLap("residents: feeding", s.name, t)
    }
}

/**
 * [graze]'s animal food for omnivore or hunter kind [k] at place [p], [ps] of its diet plants: the rest is game it
 * kills, small animals it catches and carrion it finds. Each food is sought for its share first; what falls short, of
 * these or of the plants ([z]), is made up from whichever of them is to be had (as a pack does). Returns the share of
 * its animal need met; what it ate beyond, laid on, goes to [Grazing.surplus].
 */
private fun HexFauna.animalFood(k: FaunaKind, p: Place, weather: Weather, z: Grazing, ps: Float): Float {
    val s = kinds[k]; val avail = z.avail; val surplus = z.surplus
    var animal = 0f
    val needAll = (adults[k][p] + 0.5f * young[k][p]) * s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
        days / 1e6f / (Nourishment.PREY * s.needScale) // all of the diet, in kg of prey per m2 that would feed it ([Nourishment.PREY])
    if (ps < 1f && needAll > 0f) {
        val d = s.diet; val all = d.total
        val animalNeed = needAll * (1f - ps)
        val plantGap = if (ps > 0f) needAll * ps * max(0f, 1f - z.got[k]) else 0f
        // Carrion first, where it eats any: a carcass lying there costs no chase and no digging, so
        // it eats what it finds (at most half of what lies there) before it hunts or makes up plants.
        var gotCarrion = if (d.carrion > 0f) small.scavenge(p, KgM2(min(animalNeed + plantGap, 0.5f * small.carrion[p]))).v else 0f
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
        // (The shortfall is made up first from the food it mostly lives on, then from the others,
        // each to at most (1 + SWITCH) times its usual share, as with the plants.)
        val most = (1f + SWITCH) * needAll / all
        fun share(food: AnimalFood) = when (food) { AnimalFood.MEAT -> d.meat; AnimalFood.SMALL_PREY -> d.smallPrey; AnimalFood.CARRION -> d.carrion }
        val order = AnimalFood.entries.filter { share(it) > 0f }.sortedByDescending { share(it) }
        for (food in order) {
            if (gap <= 0f) break
            val x = when (food) {
                AnimalFood.MEAT -> min(gap, most * d.meat - gotMeat).let { if (it > 0f) huntResidents(k, p, KgM2(it * avail[k]), weather, huntDays).v else 0f }
                AnimalFood.SMALL_PREY -> min(gap, most * d.smallPrey - gotSmall).let { if (it > 0f) smallFood(k, p, KgM2(it * avail[k]), weather).v else 0f }
                AnimalFood.CARRION -> min(gap, most * d.carrion - gotCarrion).let { if (it > 0f) small.scavenge(p, KgM2(min(it, 0.5f * small.carrion[p]))).v else 0f }
            }
            when (food) { AnimalFood.MEAT -> gotMeat += x; AnimalFood.SMALL_PREY -> gotSmall += x; AnimalFood.CARRION -> gotCarrion += x }
            gap -= x
        }
        // With its need met and reserves short, it eats more and lays the surplus on (as from plants).
        if (gap <= 0f && reserve[k][p] < s.maxFatDays) {
            var more = SURPLUS_EAT * animalNeed
            val moreMeat = min(more, most * d.meat - gotMeat)
            if (moreMeat > 0f) { val x = huntResidents(k, p, KgM2(moreMeat * avail[k]), weather, huntDays).v; surplus[k] += x / needAll; more -= x }
            val moreSmall = min(more, most * d.smallPrey - gotSmall)
            if (moreSmall > 0f) { val x = smallFood(k, p, KgM2(moreSmall * avail[k]), weather).v; surplus[k] += x / needAll
                ateBook?.add(-x * land.areaM2[p].toDouble()) }
        }
        animal = (gotMeat + gotSmall + gotCarrion + gotBones) / animalNeed
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
    val dug = food.takeUnderground(p, KgM2(z.eatR)).v
    val ate = food.take(food.fruit, p, KgM2(z.eatF)).v
    val eaten = land.plants.consume(p, LifeForm.HERB, KgM2(eatG)).v +
        land.plants.eatBrowse(p, KgM2(eatB)).v +
        ate + dug
    // Fruit-eaters drop the seed where they go: shrubs and trees come up away from any parent.
    land.plants.animalSeed[p] = min(1f, land.plants.animalSeed[p] * SEED_KEEP + ate * 1e6f / SEED_KG)
    // Their feet beat down the plants and wear paths: tonnes of animal going about the place.
    var tonnes = 0f
    for ((k, s) in kinds.withKinds()) tonnes += (adults[k][p] + young[k][p]) * s.massKg / 1000f
    land.plants.trample(p, RESIDENT_TRAMPLE * tonnes * land.areaKm2[p])
    // (And their going about it each day steps on the ground: see [HexPlants.tread]. Heads are per km².)
    for ((k, s) in kinds.withKinds()) land.plants.tread(p, (adults[k][p] + young[k][p]) * land.areaKm2[p], s.massKg, s.dailyTravelKm * days)
    small.deposit(DeadPool.DUNG, p, KgM2(HexSmallLife.DUNG * eaten))
    if (ateBook != null) { val m2 = land.areaM2[p].toDouble(); ateBook!!.add(-eaten * m2); droppedBook!!.add(HexSmallLife.DUNG * eaten * m2) }
    land.plants.grazing[p] = eatG * 1e6f / days; land.plants.browsing[p] = eatB * 1e6f / days
    // Rooting churns the ground: kg of roots dug per km2 a day.
    land.plants.rooting[p] = dug * 1e6f / days
}

/** How well kind [s]'s diet at place [p] would meet its need for protein, 0..1 (see [proteinMet]). */
fun HexFauna.forageProtein(s: Species, p: Place): Float {
    val d = s.diet; val plants = d.grass + d.browse + d.fruit + d.roots
    if (plants <= 0f) return 1f
    val share = (d.grass * grassProtein(p) + d.browse * browseProtein(p) + (d.fruit + d.roots) * PROTEIN_FRUIT) / plants
    val ps = plantShare(s)
    return proteinMet(ps * share + (1f - ps))
}
