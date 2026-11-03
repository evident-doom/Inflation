package inflation.ecology

import inflation.Books
import inflation.ecology.HexSmallLife.DeadPool
import inflation.EnumFloats
import inflation.Kg
import inflation.world.Biome
import inflation.world.get
import inflation.world.set
import kotlin.math.max
import kotlin.math.min

// Eating the week's food: the meal a party makes of what it got, grease rendered from bones, and the tallies and books of what was eaten.

/**
 * The end of [w]'s week of [forage]: what it learnt, the week's tallies, and what it ate: a band eats from the
 * week's food and its stores and keeps the rest; a beast gorges on what is left of a kill and leaves the rest.
 */
internal fun HexGroups.eatTheWeek(w: ForageWeek) {
    val g = w.g; val s = w.s; val band = w.band; val weather = w.weather; val wantAll = w.wantAll
    val meat = w.meat; val smallGot = w.smallGot; val scraps = w.scraps; val plants = w.plants; val fish = w.fish
    // The resident bands it came on while it searched count towards its search image.
    if (w.searched > 0f) countResidentsMet(g, w.ground, w.met, w.searched, w.today)
    if (band != null) { practise(g, band, meat + w.killFat > g.meatMet, smallGot, plants, w.fished); recordFood(g, band, w) }
    // (All counted at what it feeds, see [ForageWeek.worth]: the meat eaten as far as the rest falls short.)
    val meatWorth = w.feeds(Food.BIG_GAME); val rest = w.fed - meat * meatWorth - w.killFat * w.fatFeeds
    // (What its plant food weighs: raw seed weighs more than it feeds, see [gather].)
    val plantMass = w.plantKg.sum()
    var meatEaten = min(meat, max(0f, (wantAll - rest) / meatWorth))
    note(g) {
        "foraged for %.0f kg wanted: meat %.0f (%.0f taken from other hunters), small game %.0f, carrion %.0f, plants %.0f, fish %.0f; busy %.1f of %d days; sight %.2f of a clear day in the open%s".format(
            wantAll, meat, g.meatMet, smallGot, scraps, plants, fish, w.busy, days, sight(g.place, weather),
            if (band != null) "; %.0f%% of adults scouting".format(100 * band.scoutShare) else "")
    }
    if (FORAGE_MEAL_TRACE.wants(g.id.toLong(), weather.date.absoluteDay, s.name)) System.err.println(
        "ate ${s.name} want $wantAll meat $meat small $smallGot scraps $scraps plants $plants fish $fish path ${g.path.size}")
    var eaten = meatEaten * meatWorth + rest
    tallyForage(w, meatEaten)
    var spare = meat - meatEaten
    if (band != null) {
        // This week's food: fresh meat and fish (small game and scraps with it), and plant food by kind.
        val got = w.plantKind; val plantKg = w.plantKg
        // (The small game's pelts are set aside as it is caught: they are not food, and go to the skins or the scavengers.)
        val pelts = SMALL_SKIN * smallGot
        // (Fresh food is kept at what it feeds, with what it weighs, as plant food is: see [ForageWeek.worth].)
        plantKg[Store.FRESH] = meat + smallGot - pelts + scraps + fish
        got[Store.FRESH] = w.worth(Food.BIG_GAME) * meat + w.worth(Food.SMALL_GAME) * (smallGot - pelts) + w.worth(Food.CARRION) * scraps + w.worth(Food.FISH) * fish
        // (The fat of its kills is eaten as grease is: it holds no protein.)
        plantKg[Store.GREASE] = w.grease + w.killFat; got[Store.GREASE] = w.greaseFed + w.killFat * w.fatFeeds
        val meal = Meal(wantAll)
        // (Salt hunger: its people eat meat for its sodium before anything else, as far as the kill and the larder have it. Salt
        // appetite is inborn: mammals short of sodium seek it out and prefer salty food unlearned, Denton 1982, The Hunger for Salt.)
        val sodiumNeed = SODIUM_A_PERSON_DAY * g.size * days
        val stored = g.larder
        meal.eatForSodium(sodiumNeed / SODIUM_PER_KG_MEAT, got, plantKg, g)
        meal.eat(got, plantKg, g)
        band.larderMoved(g, LarderMove.ATE, g.larder - stored)
        band.leanLost = meal.leanLost
        // (Its weanlings get gruel as far as there is seed, eaten this week or still in store: their share of the meals is
        // already in its need, so they eat seed and the rest eat the rest in its stead.)
        val weaned = band.gruel() * min(1f, (meal.ateSeed + g.stores[Store.SEED]) / (WEANING_SHARE * wantAll).coerceAtLeast(1e-3f))
        band.weaningFood += (weaned - band.weaningFood) / stepsPerYear
        val ateMeat = meal.ateMeat; val ateKg = meal.ateKg; val ateMeatKg = meal.ateMeatKg
        eaten = wantAll - meal.need
        land.books?.flow(Books.Stuff.MATTER, "eaten", -ateKg.toDouble())
        // (Fed in full, its people eat on of the week's food left over, as far as their reserves are short and the gut takes
        // it ([SURPLUS_EAT]), and lay it on as fat ([feedOnReserves]): fat in a good season, for the lean one. Not of the
        // stores: those are kept against need.)
        if (meal.need <= 0.01f * wantAll && g.fat < s.maxFatDays) {
            val room = (s.maxFatDays - g.fat) / FAT_EFFICIENCY * g.dailyNeedKg
            val more = Meal(min(SURPLUS_EAT * wantAll, room))
            more.eat(got, plantKg, null)
            val ate = more.wantAll - more.need
            if (ate > 0f) {
                g.surplus = ate / g.dailyNeedKg.coerceAtLeast(1e-3f)
                land.books?.flow(Books.Stuff.MATTER, "eaten", -more.ateKg.toDouble())
            }
        }
        // (People need sodium, which meat and fish carry and plant food hardly does; what the week's meat falls short of
        // they make up with salt, and short of that they are listless and weak, and the food does them the less good.)
        // (Salted meat and fish carry their salt: [SALT_PER_KG] a kg, the share of the dried that was salted.)
        if (eaten > 0f) {
            var short = max(0f, sodiumNeed - SODIUM_PER_KG_MEAT * ateMeatKg - meal.ateDriedKg * band.salted * SALT_PER_KG * SALT_SODIUM)
            if (short > 0f) {
                val had = min(short / SALT_SODIUM, band.goods[Material.SALT]); band.goods[Material.SALT] -= had
                short = max(0f, short - had * SALT_SODIUM)
            }
            band.sodiumShort = if (sodiumNeed > 0f) short / sodiumNeed else 0f
            eaten *= saltFeeds(short, sodiumNeed, tuning.saltLack)
        }
        stats.tally(Tally.of(s, Tally.OfKind.MEAT_EATEN), ateMeat.toInt())
        stats.tally(Tally.of(s, Tally.OfKind.PLANTS_EATEN), (eaten - ateMeat).toInt())
        keepAndSpoil(w, got, plantKg, pelts)
        band.seedCorn = band.seedCornIn(g)
        spare = 0f; meatEaten = ateMeatKg
    }
    // Gorging: what is left of a kill is eaten beyond need, as far as the gut holds ([EATING_RATE] every day of the week,
    // [ForageWeek.gutKg]) and reserves are short (the fat it would lay on, [FAT_EFFICIENCY], up to [Species.maxFatDays]).
    if (HUNT_DIAG && band == null) {
        diag(s, HuntStep.PACK_FAT_SHARE, g.fat / s.maxFatDays.coerceAtLeast(1e-3f)); diag(s, HuntStep.PACK_WANT_OVER_GUT, wantAll / w.gutKg.coerceAtLeast(1e-3f))
        if (spare > 0f) { diag(s, HuntStep.GORGE_WEEKS, 1f); diag(s, HuntStep.GORGE_SPARE_KG, spare); diag(s, HuntStep.GORGE_GUT_ROOM_KG, max(0f, w.gutKg - wantAll))
            if (g.fat >= s.maxFatDays) diag(s, HuntStep.GORGE_WEEKS_FAT_FULL, 1f)
            else diag(s, HuntStep.GORGE_FAT_ROOM_KG, (s.maxFatDays - g.fat) / FAT_EFFICIENCY * g.dailyNeedKg / meatWorth) }
    }
    if (spare > 0f && g.fat < s.maxFatDays && band == null) {
        val fatRoom = (s.maxFatDays - g.fat) / FAT_EFFICIENCY * g.dailyNeedKg / meatWorth
        val gorge = min(spare, min(max(0f, w.gutKg - wantAll), fatRoom))
        if (HUNT_DIAG) diag(s, HuntStep.GORGE_KG, gorge)
        g.surplus = gorge * meatWorth / g.dailyNeedKg.coerceAtLeast(1e-3f)
        spare -= gorge
        small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * gorge))
        land.books?.let { bookEaten(it, gorge * meatShare(null), HexSmallLife.DUNG * gorge) }
    }
    g.fed = (eaten / wantAll).coerceIn(0f, 1f)
    if (HUNT_DIAG && band == null) {
        val killed = meat + w.killFat > g.meatMet
        if (!killed) diag(s, HuntStep.PACK_WEEKS_NO_KILL, 1f)
        if (g.fed < WELL_FED) diag(s, if (killed) HuntStep.PACK_WEEKS_HUNGRY_WITH_KILL else HuntStep.PACK_WEEKS_HUNGRY_NO_KILL, 1f)
        diag(s, HuntStep.PACK_PASSES, w.passes.toFloat()); diag(s, HuntStep.PACK_SEARCH_DAYS, w.searchDays); diag(s, HuntStep.PACK_KILLS_EXPECTED, w.killsExpected)
        if (g.fed < WELL_FED && !killed) { diag(s, HuntStep.HUNGRY_NO_KILL_PASSES, w.passes.toFloat()); diag(s, HuntStep.HUNGRY_NO_KILL_SEARCH_DAYS, w.searchDays)
            diag(s, HuntStep.HUNGRY_NO_KILL_FAT_SHARE, g.fat / s.maxFatDays.coerceAtLeast(1e-3f))
            diag(s, HuntStep.HUNGRY_NO_KILL_KILLS_EXPECTED, w.killsExpected); diag(s, HuntStep.HUNGRY_NO_KILL_WORK_DAYS, w.workDays) }
        diag(s, HuntStep.PACK_KG_EATEN, eaten); diag(s, HuntStep.PACK_KG_LEFT, spare)
    }
    if (band != null) note(g) {
        "ate %.0f of %.0f kg wanted (fed %.2f); larder left %.0f kg (%s); fat %.1f days; sodium short %.0f%%, salt %.1f kg; lean lost %.0f%%".format(
            eaten, wantAll, g.fed, g.larder, Store.entries.joinToString(" ") { "${it.name.lowercase()} %.0f".format(g.stores[it]) },
            g.fat, 100f * band.sodiumShort, band.goods[Material.SALT], 100f * band.leanLost)
    }
    // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
    small.deposit(DeadPool.CARRION, g.place, Kg(spare))
    // (A band's meat eaten already holds the small game, scraps and fish it ate; what it stored is not yet eaten.)
    val animalEaten = if (band != null) meatEaten else meatEaten + smallGot + scraps + fish
    small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * animalEaten))
    land.books?.let { bookForage(it, band, meatEaten, smallGot + scraps + plantMass + fish, fish, spare, HexSmallLife.DUNG * animalEaten) }
    // (The meat it took in its meetings is eaten, stored or left by now.)
    g.meatSpent = true
}

/**
 * A band's eating of [wantAll] (kg of its need) from the week's food and then its stores, each in the order it spoils. Protein
 * feeds people only up to [Nourishment.PROTEIN_MOST] of their need: beyond that the liver can make no more of it, and lean
 * meat feeds by its fat alone ("rabbit starvation"). So it eats lean food first only as far as its protein can feed it, then
 * plant food and grease, and only when those run out more lean food, for its fat. What it ate is counted as it feeds (the
 * protein beyond the ceiling not at all), and also by weight; [leanLost] is the share of the protein it ate that went to waste.
 */
internal class Meal(val wantAll: Float) {
    var need = wantAll; var ateMeat = 0f; var ateKg = 0f; var ateMeatKg = 0f; var ateDriedKg = 0f; var ateSeed = 0f
    private var room = Nourishment.PROTEIN_MOST * wantAll; private var protein = 0f; private var lost = 0f
    val leanLost get() = if (protein > 0f) lost / protein else 0f

    /** Eats [got] (by [Store], kg of need, each weighing [gotKg]) and then [g]'s stores; with no [g], [got] alone. */
    fun eat(got: EnumFloats<Store>, gotKg: EnumFloats<Store>, g: HexGroup?) {
        for (beyond in 0..1) {
            for (st in EAT_ORDER) {
                if (got[st] <= 0f) continue
                val kgPer = gotKg[st] / got[st]; val e = take(st, got[st], kgPer, beyond == 1)
                got[st] -= e; gotKg[st] -= e * kgPer
            }
            if (g != null) for (st in EAT_ORDER) g.stores[st] -= take(st, g.stores[st], g.storeKgPer[st], beyond == 1)
        }
    }

    /**
     * Eats up to [kg] (by weight) of meat and fish, from [got] (by [Store], kg of need, each weighing [gotKg]) and then [g]'s
     * stores, each in the order it spoils, within the protein ceiling: the meat its people's sodium asks for.
     */
    fun eatForSodium(kg: Float, got: EnumFloats<Store>, gotKg: EnumFloats<Store>, g: HexGroup) {
        var left = kg
        for (st in EAT_ORDER) {
            if (!st.meat) continue
            if (left > 0f && got[st] > 0f) {
                val kgPer = gotKg[st] / got[st]; val e = take(st, min(got[st], left / kgPer), kgPer, false)
                got[st] -= e; gotKg[st] -= e * kgPer; left -= e * kgPer
            }
            if (left > 0f && g.stores[st] > 0f) {
                val kgPer = g.storeKgPer[st]; val e = take(st, min(g.stores[st], left / kgPer), kgPer, false)
                g.stores[st] -= e; left -= e * kgPer
            }
        }
    }

    /** Eats of [have] of [st] (weighing [kgPer] a kg of need): within the protein ceiling, or [beyond] it; what it ate. */
    private fun take(st: Store, have: Float, kgPer: Float, beyond: Boolean): Float {
        val p = st.protein
        if (have <= 0f || need <= 0f || (beyond && p <= 0f)) return 0f
        val e = if (!beyond) min(min(need, have), if (p > 0f) max(0f, room) / p else need) else min(have, need / (1f - p))
        if (e <= 0f) return 0f
        val fed = if (beyond) e * (1f - p) else e
        need -= fed; protein += e * p; if (beyond) lost += e * p else room -= e * p
        ateKg += e * kgPer; if (st.meat) { ateMeat += fed; ateMeatKg += e * kgPer }; if (st == Store.DRIED) ateDriedKg += e * kgPer
        if (st == Store.SEED) ateSeed += fed
        return e
    }
}

/** [forage]'s tallies of [w]'s week: its diet, [meatEaten] of its meat eaten, and for bands how they fared by the kind of country. */
private fun HexGroups.tallyForage(w: ForageWeek, meatEaten: Float) {
    val s = w.s; val band = w.band; val wantAll = w.wantAll
    val smallGot = w.smallGot; val scraps = w.scraps; val fish = w.fish
    // (Each at what it feeds, in kg of its need as asked: see [ForageWeek.worth].)
    val meatFed = meatEaten * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + fish * w.worth(Food.FISH)
    val smallFed = smallGot * w.worth(Food.SMALL_GAME); val scrapsFed = scraps * w.worth(Food.CARRION)
    val plantsFed = w.plantKind.sum()
    stats.tally(Tally.diet(s, Tally.DietPart.ASKED), wantAll.toInt())
    stats.tally(Tally.diet(s, Tally.DietPart.MEAT), meatFed.toInt())
    stats.tally(Tally.diet(s, Tally.DietPart.SMALL_PREY), smallFed.toInt())
    stats.tally(Tally.diet(s, Tally.DietPart.CARRION), scrapsFed.toInt())
    stats.tally(Tally.diet(s, Tally.DietPart.PLANTS), plantsFed.toInt())
    // For bands, the same by the kind of country they are in, and how they fared.
    if (band != null) tallyBandForage(w, band, smallFed, scrapsFed, plantsFed)
}

/** [tallyForage]'s part for a band (split off: one method with all of these tallies failed its C1 compile, "out of virtual registers"). */
private fun HexGroups.tallyBandForage(w: ForageWeek, band: Band, smallFed: Float, scrapsFed: Float, plantsFed: Float) {
    val g = w.g; val wantAll = w.wantAll; val meat = w.meat; val fish = w.fish
    val c = Biome.entries[biomeOfPlace[g.place]]
    stats.tally(Tally.bandsIn(c, Tally.Bands.ASKED), wantAll.toInt())
    stats.tally(Tally.bandsIn(c, Tally.Bands.MEAT), (meat * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + smallFed + scrapsFed).toInt())
    stats.tally(Tally.bandsIn(c, Tally.Bands.PLANTS), plantsFed.toInt())
    for (f in PlantFrom.entries) stats.tally(Tally.plantsFrom(f), w.plantFrom[f].toInt())
    stats.tally(Tally.bandsIn(c, Tally.Bands.FISH), (fish * w.worth(Food.FISH)).toInt())
    stats.tally(Tally.bandsIn(c, Tally.Bands.BAND_WEEKS), 1); stats.tally(Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS), g.size)
    stats.tally(Tally.bandsIn(c, Tally.Bands.HEARTH), (100f * band.hearthWood).toInt())
    stats.tally(Tally.bandsIn(c, Tally.Bands.FUEL_DAYS), (100f * w.fuelDays / days).toInt())
    // (And by whether it keeps a herd: what it foraged against what it wanted, and its hearth.)
    val herding = bandHerding(band, g.adults)
    stats.tally(Tally.bandsHerding(herding, Tally.Bands.ASKED), wantAll.toInt())
    stats.tally(Tally.bandsHerding(herding, Tally.Bands.MEAT), (meat * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + smallFed + scrapsFed + fish * w.worth(Food.FISH)).toInt())
    stats.tally(Tally.bandsHerding(herding, Tally.Bands.PLANTS), plantsFed.toInt())
    stats.tally(Tally.bandsHerding(herding, Tally.Bands.HEARTH), (100f * band.hearthWood).toInt())
    stats.tally(Tally.bandsHerding(herding, Tally.Bands.FUEL_DAYS), (100f * w.fuelDays / days).toInt())
}

/** Books [kg] eaten by a beast and the [dung] it dropped of it (see [Books]). */
private fun bookEaten(b: Books, kg: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "eaten", -kg.toDouble()); b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}

/**
 * Books [forage]'s week (see [Books]): the [fish] it caught come from the water life; a beast ate the meat of its [meatEaten]
 * (its need counted in the whole weight of its kills, see [creditShare]) and [rest], kg, and dropped [dung]; and the [spare]
 * it left lies as carrion at the whole weight credited, more than the meat it is (a known leak). A band's meal is booked as it eats.
 */
private fun GroupWorld.bookForage(b: Books, band: Band?, meatEaten: Float, rest: Float, fish: Float, spare: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "to and from the water life", fish.toDouble())
    if (band == null) {
        bookEaten(b, meatEaten * meatShare(null) + rest, 0f)
        if (spare > 0f) b.leak(Books.Stuff.MATTER, "beasts' leftovers left at their whole credited weight", (spare * (1f - meatShare(null))).toDouble())
    }
    b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}

/** [forage]'s count of the resident bands [g] came on unseen ([met] per animal, by place of its hunting [ground] and kind) in the [searched] share of its week. */
private fun GroupWorld.countResidentsMet(g: HexGroup, ground: HuntGround, met: FloatArray, searched: Float, today: Double) {
    val nk = fauna.kinds.size
    val counts = FloatArray(Catalogue.all.size)
    for (j in 0 until ground.size) {
        val p = ground.place[j]
        val swept = ground.km2[j]
        for (k in 0 until nk) counts[fauna.kinds[k].index] += (fauna.adults[k][p] + fauna.young[k][p]) * swept * met[j * nk + k] * searched
        if (HUNT_DIAG) for (k in 0 until nk) { val ps = fauna.kinds[k]
            diag(ps, HuntStep.RESIDENT_BANDS_MET, (fauna.adults[k][p] + fauna.young[k][p]) * swept * (0.5f + 0.5f * Niches.overlap(g.species, ps)) / ps.groupSize * searched) }
    }
    if (HUNT_DIAG) for (k in 0 until nk) diag(fauna.kinds[k], HuntStep.RESIDENT_BANDS_MET_UNSEEN, counts[fauna.kinds[k].index])
    g.metGame(counts, today)
}

/**
 * The grease a band renders from a kg of bones it gathers, kg: bones smashed small and boiled with hot stones, the fat skimmed
 * as it rises ([Skill.STONE_BOILING], in tight baskets or hides). Experiments find most of it comes in the first hour of
 * boiling, and the yield rises with how small the bones are broken (Church & Lyman 2003); the yield a kg here is a guess
 * (no figure a kg of bone found yet), and so are the days.
 */
internal fun greaseOf(band: Band) = if (band.knowsSkill[Skill.STONE_BOILING])
    GREASE_YIELD * band.skill(Skill.STONE_BOILING) * max(band.has(Tool.BASKET), 0.5f) else 0f

internal const val GREASE_YIELD = 0.05f

/** Person-days to smash and boil a kg of bones for their grease, with the fuel to heat the stones (a guess: "many hours"). */
internal const val BONE_RENDER_DAYS = 0.2f
