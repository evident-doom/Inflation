package inflation.ecology

import inflation.ecology.HexSmallLife.Companion.BIRD_INTAKE
import inflation.ecology.HexSmallLife.Companion.BIRDS
import inflation.ecology.HexSmallLife.Companion.DUNG
import inflation.ecology.HexSmallLife.Companion.FRASS
import inflation.ecology.HexSmallLife.Companion.HARES_BURNT
import inflation.ecology.HexSmallLife.Companion.HERP_COVER_KG
import inflation.ecology.HexSmallLife.Companion.HERP_DEATHS
import inflation.ecology.HexSmallLife.Companion.HERP_DORMANT_DEATHS
import inflation.ecology.HexSmallLife.Companion.HERP_HUNGER
import inflation.ecology.HexSmallLife.Companion.HERP_INTAKE
import inflation.ecology.HexSmallLife.Companion.HERP_MOST
import inflation.ecology.HexSmallLife.Companion.HERP_YOUNG
import inflation.ecology.HexSmallLife.Companion.RODENTS_BURNT
import inflation.ecology.HexSmallLife.Companion.SMALL_INTAKE
import inflation.Par
import inflation.Part
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.KgKm2
import inflation.KgM2
import inflation.min
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.min

// The small animals: rodents, reptiles and amphibians, small game, birds and tortoises, what they eat, fire among them, and their wandering.

/**
 * Rodents (a female raises some twenty young a year): they eat ~10% of their mass a day, and a mouse or vole
 * lasts only a few days without food, so the share that found none in the week dies. They breed on fresh growth
 * (young shoots and seeds carry the protein a litter needs), logistic on food; losses to cold and deep snow.
 * Fallen acorns and nuts they eat first, as far as there are any, then the herbs: wood mice took 99% of holm, cork and
 * kermes oak acorns laid out under Spanish oaks, half within a week (Pons and Pausas 2007, Ecol Res 22:535), and of holm
 * oak acorns rodents handled, 1.3% were alive the next spring (Gómez, Puerta-Piñero and Schupp 2008, Oecologia 155:529).
 */
@Part("small life: rodents")
internal fun HexSmallLife.stepRodents(p: Place, t: Float, food: Float, snow: Float) {
    var r = rodents[p]
    val need = KgKm2(r * 0.1f * days).perM2
    val nuts = takeMast?.invoke(p, need) ?: KgM2(0f)
    val eaten = nuts + land.plants.consume(p, LifeForm.HERB, need - nuts)
    litter[p] += FRASS * eaten.v
    ateM2(p, eaten.v); dropped(p, FRASS * eaten.v)
    val fed = if (need.v > 0f) eaten / need else 1f
    val fresh = food * land.plants.green[p]
    val birth = if (t > 5f) 10f * years * fed * fresh / (fresh + 0.05f) else 0f // (half the most at 0.05 kg/m² of green forage)
    val death = tuning.rodentDeath * years + (if (snow > 30f || t < -5f) 3f * years else 0f)
    val starved = r * (1f - fed)
    val ceil = 600f * food / (food + 0.3f)
    // (What a place holds for rodents wandering in: room on its food, less what the cold and deep snow kill; fire, below.)
    rodentRoom[p] = ceil * (1f - death).coerceAtLeast(0f)
    val bornR = r * birth * (1 - r / ceil.coerceAtLeast(1f))
    if (bornR > 0f) grew(p, bornR) else crowdLeak?.add(bornR * land.areaKm2[p].toDouble())
    r += bornR - r * death - starved
    // (Starving below none: those dead are all there were.)
    smallDead[p] += rodents[p] * death + starved + min(0f, r)
    rodents[p] = capped(p, r, 3000f)
    nest(p, Nest.RODENTS, rodents[p], max(0f, bornR))
}

/**
 * Reptiles and amphibians: cold-blooded, they need little food and turn over slowly. Out, they live on the
 * insects that are out (plant-eaters, pollinators, locusts, mosquitoes and midges), from each kind as it is met,
 * and breed as far as they are fed: what they find per head, not the plants, holds their numbers. In the cold
 * and in hot drought they lie up, losing few. (Toads and lizards take the dry country where frogs and newts
 * cannot.)
 */
@Part("small life: reptiles and amphibians")
internal fun HexSmallLife.stepHerps(p: Place, t: Float, soilWater: Float) {
    var h = herps[p]; var herpBorn = 0f
    if (herpsActive(t, soilWater)) {
        val need = h * HERP_INTAKE * days
        val ate = if (need > 0f) takeSwitching(p, need, 0.5f, insectFood, insectEaseAt(p)) else 0f
        ate(p, ate)
        val fed = if (need > 0f) ate / need else 1f
        val old = h * HERP_DEATHS * years; val hungry = h * HERP_HUNGER * (1f - fed) * years
        val died = old + hungry
        smallDead[p] += died
        // (Young crowd the place's breeding sites: tadpoles the pools, eggs the warm sheltered ground.)
        val born = h * HERP_YOUNG * fed * max(0f, 1f - h / herpSites(p).coerceAtLeast(1e-3f)) * years
        h += born - died; herpBorn = born
        grew(p, born)
        herpLedger[HerpLedger.BORN][p] += born; herpLedger[HerpLedger.DIED_OUT][p] += old; herpLedger[HerpLedger.HUNGER][p] += hungry
        herpLedger[HerpLedger.ATE][p] += ate; herpLedger[HerpLedger.NEEDED][p] += need
    } else { val died = h * HERP_DORMANT_DEATHS * years; smallDead[p] += died; h -= died; herpLedger[HerpLedger.LYING_UP][p] += died }
    herps[p] = capped(p, h, HERP_MOST)
    nest(p, Nest.HERPS, herps[p], herpBorn)
}

/**
 * Fire at place [p] kills what cannot flee, on the share [b] that burned.
 */
@Part("small life: fire")
internal fun HexSmallLife.burnSmallLife(p: Place, b: Float) {
    if (b > 0f) {
        smallDead[p] += insects[p] * 0.8f * b + herps[p] * 0.7f * b + rodents[p] * RODENTS_BURNT * b + pollinators[p] * 0.8f * b
        insects[p] *= 1f - 0.8f * b; herps[p] *= 1f - 0.7f * b; rodents[p] *= 1f - RODENTS_BURNT * b; pollinators[p] *= 1f - 0.8f * b
    }
}

/** Small game at place [p] this week: [t] its temperature, [burnt] the share that burned. */
@Part("small life: game birds and small game")
internal fun HexSmallLife.stepGame(p: Place, t: Float, month: Int, burnt: Float) {
    if (land.plants.biomass(p) <= 0f) { smallDead[p] += hares[p] + hyrax[p] + birdSum(p) + tortoises[p] + foxes[p]; hares[p] = 0f; hyrax[p] = 0f; birds[p] = 0f; for (b in bird) b[p] = 0f; tortoises[p] = 0f; foxes[p] = 0f; hareRoom[p] = 0f; return }
    val mild = t > 5f
    // Young raised a year per head, all born in the breeding months (so the rate in them is higher).
    hares[p] = logistic(p, hares[p], if (month in 3..9 && mild) inSeason(4f, 7) else 0f, 0.9f, hareCapacity(p), grazes = true, born = Nest.HARES)
    hyrax[p] = logistic(p, hyrax[p], if (month in 4..6) inSeason(1.2f, 3) else 0f, 0.4f, hyraxCapacity(p), grazes = true, born = Nest.HYRAX)
    var all = 0f
    for (k in bird.indices) { stepBird(k, p, month, mild); all += bird[k][p] }
    birds[p] = all
    nest(p, Nest.HARES, hares[p], 0f); nest(p, Nest.HYRAX, hyrax[p], 0f); nest(p, Nest.BIRDS, all, 0f)
    val active = stepTortoises(p, t, month)
    smallGrazing(p, all, active)
    stepFoxesAndOwls(p, month)
    if (eagleCrags[p] > 0f) stepEagles(p, month)
    // Fire: tortoises die where it burns; the rest bolt and many young die.
    if (burnt > 0f) {
        smallDead[p] += tortoises[p] * 0.6f * burnt + hares[p] * HARES_BURNT * burnt + birds[p] * 0.3f * burnt
        tortLedger[TortLedger.BURNT][p] += tortoises[p] * 0.6f * burnt
        tortoises[p] *= 1f - 0.6f * burnt; hares[p] *= 1f - HARES_BURNT * burnt; takeBirds(p, 0.3f * burnt)
    }
    hareRoom[p] = hareCapacity(p) * (1f - HARES_BURNT * burnt)
}

/**
 * Bird kind [k] at place [p] this week: migrants leave and come back, the rest feed on insects
 * as their kind does, breed in their months as fed ([mild] weather), and die more when short.
 */
private fun HexSmallLife.stepBird(k: Int, p: Place, month: Int, mild: Boolean) {
    val b = BIRDS[k]; val f = bird[k]
    val here = b.present?.contains(month) ?: true
    // The insects they eat (most of what the chicks are raised on): short of them, fewer young
    // are raised and more birds die.
    var fed = 1f
    if (here && b.insectShare > 0f && f[p] > 0f) {
        val need = f[p] * BIRD_INTAKE * days * b.insectShare
        // (A few birds' need can round to nothing.)
        if (need > 0f) {
            val ate = takeSwitching(p, need, 0.5f, insectFood, insectEaseAt(p))
            ate(p, ate)
            herpLedger[HerpLedger.EATEN_BY_BIRDS][p] += ate
            fed = ate / need
        }
    }
    f[p] = when {
        // Migrants leave for beyond the map at the end of their months here, and come back.
        !here -> { if (f[p] > 0f) { leaving[k][p] = f[p] * land.areaKm2[p]; birdLedger[k][BirdLedger.LEFT][p] += f[p] }; 0f }
        returning[k] > 0.0 && !b.followsInsects -> {
            val back = (returning[k] * birdCapacity(k, p)).toFloat()
            birdLedger[k][BirdLedger.BACK][p] += back
            f[p] + back
        }
        else -> {
            val hunger = b.insectShare * (1f - fed)
            // (Insect-eating migrants settling this week arrive where the insects boom, and live on as the rest.)
            val arrived = if (b.followsInsects && returning[k] > 0.0) (returning[k] * birdCapacity(k, p) * boom(p)).toFloat() else 0f
            birdLedger[k][BirdLedger.BACK][p] += arrived
            if (here && b.insectShare > 0f) {
                birdLedger[k][BirdLedger.FED_DAYS][p] += fed * days
                birdLedger[k][BirdLedger.INSECT_DAYS][p] += days
            }
            var x = logistic(p, f[p] + arrived, if (month in b.breedMonths && mild) inSeason(b.growth, b.breedMonths.count()) * (1f - hunger) else 0f,
                b.deaths * (1f + hunger), birdCapacity(k, p), grazes = true, grown = birdLedger[k][BirdLedger.GROWN], died = birdLedger[k][BirdLedger.DIED],
                crowdedTo = birdLedger[k][BirdLedger.CROWDED], born = Nest.BIRDS)
            // Insect-eating migrants leave a place where the insects have failed, for one where they boom.
            if (b.followsInsects && fed < 0.5f && x > 0f) {
                val go = x * (1f - 2f * fed)
                leaving[k][p] = go * land.areaKm2[p]
                birdLedger[k][BirdLedger.LEFT][p] += go
                x -= go
            }
            x
        }
    }
}

/** Tortoises at place [p]: out in the warm (when the soil is not dry), breeding in summer. Returns whether they are out. */
private fun HexSmallLife.stepTortoises(p: Place, t: Float, month: Int): Boolean {
    val active = t in 14f..33f && land.hydro.soil[p] > 8f
    tortoises[p] = logistic(p, tortoises[p], if (active && month in 4..8) inSeason(0.3f, 5) else 0f, if (active) 0.08f else 0.03f,
        tortoiseCapacity(p), grazes = true, grown = tortLedger[TortLedger.GROWN], died = tortLedger[TortLedger.DIED],
        crowdedTo = tortLedger[TortLedger.CROWDED], born = Nest.TORTOISES)
    nest(p, Nest.TORTOISES, tortoises[p], 0f)
    if (active) tortLedger[TortLedger.DAYS_OUT][p] += days; if (active && month in 4..8) tortLedger[TortLedger.BREEDING_DAYS][p] += days
    return active
}

/** The small plant-eaters at place [p] ([birdsHere] the birds, tortoises when [tortoisesOut]) eat what they grow on, and drop half of it again. */
private fun HexSmallLife.smallGrazing(p: Place, birdsHere: Float, tortoisesOut: Boolean) {
    // (A small share of their weight a day; tortoises only when out.)
    val eaters = hares[p] + hyrax[p] + birdsHere + (if (tortoisesOut) tortoises[p] else 0f) // kg/km²
    if (eaters > 0f) {
        val eaten = land.plants.consume(p, LifeForm.HERB, KgKm2(0.7f * SMALL_INTAKE * eaters * days).perM2) +
            land.plants.consume(p, LifeForm.SHRUB, KgKm2(0.3f * SMALL_INTAKE * eaters * days).perM2)
        dung[p] += DUNG * eaten.v
        ateM2(p, eaten.v); dropped(p, DUNG * eaten.v)
    }
}

/** Takes share [f] of every kind of bird at [p]. */
private fun HexSmallLife.takeBirds(p: Place, f: Float) {
    for ((k, b) in bird.withIndex()) { birdLedger[k][BirdLedger.BURNT][p] += b[p] * f; b[p] -= b[p] * f }
    birds[p] -= birds[p] * f
}

/**
 * The share of the reptiles and amphibians of place [p] a hunter can find: the rest are in cover,
 * in rock cracks and screes, under shrubs and in the leaf litter, and come out only to bask and
 * hunt close by it. Each kind of cover hides more as there is more of it: [HERP_COVER_KG] of shrub
 * or of litter to the m² hides half of them, and so does the most broken ground.
 */
fun HexSmallLife.herpExposed(p: Place) = rockHides[p] * HERP_COVER_KG / (HERP_COVER_KG + land.plants.shrub[p]) * HERP_COVER_KG / (HERP_COVER_KG + litter[p])

/**
 * How many reptiles and amphibians place [p] has breeding sites for, kg/km²: frogs, toads and newts
 * spawn in standing water (marsh, bog, floodplain), lizards, snakes and tortoises lay their eggs in
 * sheltered ground (cover, as [herpExposed]); taken as half of each. As they near it, the young
 * crowd the sites and fewer come through.
 */
fun HexSmallLife.herpSites(p: Place) = HERP_MOST * (0.5f * min(1f, wetland[p] + land.floodShare[p]) + 0.5f * (1f - herpExposed(p)))

/** Whether reptiles and amphibians are out: warm, and not baking in a drought. */
fun HexSmallLife.herpsActive(t: Float, soilWater: Float) = t in 12f..34f && !(t > 28f && soilWater < 10f)

/** Ages the young of nest kind [i] at [p] a week, adds [born] (and what [logistic] recorded), and holds them to the [stock]. */
internal fun HexSmallLife.nest(p: Place, i: Nest, stock: Float, born: Float) {
    val y = young[i]
    y[p] = min(stock, y[p] * max(0f, 1f - days / i.days) + born + newborn[i][p])
    newborn[i][p] = 0f
}

internal fun HexSmallLife.birdSum(p: Place): Float { var all = 0f; for (b in bird) all += b[p]; return all }

/**
 * Small animals wander between neighbouring places: as many leave one as arrive in the other, the
 * density difference over the smaller place; [where] keeps a kind to the places it can live in.
 */
internal fun HexSmallLife.spread(f: FloatArray, where: (Int) -> Boolean) {
    val old = f.copyOf()
    for (p in land.places) {
        if (!where(p.i)) continue
        val nb = land.neighbours[p.i]
        val w = spreadWeight[p.i]
        var delta = 0f
        for (i in nb.indices) { val q = nb[i]; if (where(q)) delta += w[i] * (old[q] - old[p]) }
        f[p] = max(0f, old[p] + delta)
    }
}

/** The week's wandering of small animals to neighbouring places: the spreaders anywhere, eagles between crags, rodents and hares toward room. */
@Part("small life: spreading to neighbours")
internal fun HexSmallLife.spreadToNeighbours() {
    spread(spreaders)
    spread(eagles) { eagleCrags[it] > 0f }
    spreadToward(rodents, rodentRoom); spreadToward(hares, hareRoom)
}

/** [spread] for kinds that can wander anywhere, all in one pass over the places. */
internal fun HexSmallLife.spread(fs: Array<FloatArray>) {
    val olds = Array(fs.size) { fs[it].copyOf() }
    Par.forEachPlace("small life: spread", n) { p ->
        val nb = land.neighbours[p.i]
        val w = spreadWeight[p.i]
        for (k in fs.indices) {
            val old = olds[k]
            var delta = 0f
            for (i in nb.indices) delta += w[i] * (old[nb[i]] - old[p])
            fs[k][p] = max(0f, old[p] + delta)
        }
    }
}

/**
 * Rodents and hares wander between neighbouring places as [spread] has small animals do, but toward room: what crosses
 * each way is as many as [spread] moves, weighed by the room ([room]) of the place they go to against the one they
 * leave, so that they settle where there is food and they live (each place as full as its room), not evenly. (As many
 * leave one place as arrive in the other.)
 */
internal fun HexSmallLife.spreadToward(f: FloatArray, room: FloatArray) {
    val old = f.copyOf()
    Par.forEachPlace("small life: spread toward", n) { p ->
        val nb = land.neighbours[p.i]
        val w = spreadWeight[p.i]
        var delta = 0f
        for (i in nb.indices) {
            val q = nb[i]; val sum = room[p] + room[q]
            delta += if (sum > 0f) w[i] * 2f * (old[q] * room[p] - old[p] * room[q]) / sum else w[i] * (old[q] - old[p])
        }
        f[p] = max(0f, old[p] + delta)
    }
}
