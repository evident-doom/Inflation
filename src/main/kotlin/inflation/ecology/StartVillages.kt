package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.Kg
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A world begun once farming is known ([inflation.Tuning.startSettled]): of people's bands, that share, taken from those on
 * the best farm ground ([farmWorth]), start as villages ([startVillage]); the rest start as foragers, as in any world. Fewer
 * start so where too few bands stand on farm ground. Nothing keeps a village a village afterwards: it stays, moves, sows or
 * splits by the same choices as any band, and the ways of living in houses pass to its neighbours as other ways do.
 * A fresh world starts them as it makes its bands; a world carried on from a save starts them when it is taken up (so a
 * save of a world that had villages, carried on with the key set again, starts more).
 */
internal fun HexGroups.startVillages() {
    val bands = groups.filter { it.alive && it.species.human && it.band != null }
    val farm = bands.filter { farmWorth(it.place) > 0f }.sortedByDescending { farmWorth(it.place) }
    val started = farm.take(min(farm.size, (tuning.startSettled * bands.size).roundToInt())).map { g -> g.band?.let { startVillage(g, it) } }
    println("villages started: ${started.size} of ${bands.size} bands, with herds " + started.filterNotNull().groupBy { it.first }.entries.joinToString(", ") { (k, l) ->
        "%s %.0f head in %d".format(fauna.kinds[k].name, l.sumOf { it.second.toDouble() }, l.size) }.ifEmpty { "none" })
}

/**
 * A world begun once herding is known ([inflation.Tuning.startHerders]): of people's mobile bands, that share, taken from those
 * whose camps best feed a herd ([herdForage] of the herd they would start with), start with a herd ([startHerd]); the rest as
 * any band. Like a village, nothing keeps a herding band herding: it keeps, culls, loses or eats its animals by its own choices.
 * Started as [startVillages] is, on a fresh world or on one carried on from a save.
 */
internal fun HexGroups.startHerders() {
    val bands = groups.filter { it.alive && it.species.human && it.band?.atHome == false }
    val worth = bands.associateWith { g -> herdKind(g.place)?.let { k -> herdForage(g.place, fauna.kinds[k], startHerdSize(g, k)).fed } ?: 0f }
    val ground = bands.filter { worth.getValue(it) > 0f }.sortedByDescending { worth.getValue(it) }
    val started = ground.take(min(ground.size, (tuning.startHerders * bands.size).roundToInt())).mapNotNull { g -> g.band?.let { startHerd(g, it) } }
    println("herds started: ${started.size} of ${bands.size} mobile bands, " + started.groupBy { it.first }.entries.joinToString(", ") { (k, l) ->
        "%s %.0f head in %d".format(fauna.kinds[k].name, l.sumOf { it.second.toDouble() }, l.size) }.ifEmpty { "none" })
}

/**
 * The kind a band at [p] would keep: of the plant-eaters living there, the one whose worst of Diamond's criteria
 * ([Species.domestication]) is best, since a kind fails as stock on any one of them (Diamond 1997); null where none live there.
 */
private fun GroupWorld.herdKind(p: Place): Int? = fauna.kinds.indices
    .filter { k -> fauna.kinds[k].let { it.diet.grass + it.diet.browse > 0.5f } && fauna.adults[k][p] * land.areaKm2[p] >= 1f }
    .maxByOrNull { k -> fauna.kinds[k].domestication.min() }

/** The head of kind [k] band [g] starts its herd with: [HERD_START_KG] on the hoof for each of its people. */
private fun GroupWorld.startHerdSize(g: HexGroup, k: Int) = HERD_START_KG * g.size / fauna.kinds[k].massKg

/**
 * Band [g] ([b]) starts as herders where it stands (the kind and head it started with; null, none): a herd of the kind best kept there ([herdKind]), taken from the wild
 * there (no more than live there), grown [HERD_START_TAME] tame, and herding known ([HERD_SKILL]).
 */
private fun GroupWorld.startHerd(g: HexGroup, b: Band): Pair<Int, Float>? {
    val p = g.place
    val k = herdKind(p) ?: return null
    val area = land.areaKm2[p]
    val n = min(startHerdSize(g, k), fauna.adults[k][p] * area)
    if (n <= 0f) return null
    fauna.adults[k][p] -= n / area
    b.herds.herdTame[k] = (b.herds.herdTame[k] * b.herds.herd[k] + HERD_START_TAME * n) / (b.herds.herd[k] + n)
    b.herds.herd[k] += n
    b.knowsSkill[Skill.TENDING] = true; b.skills[Skill.TENDING] = max(b.skills[Skill.TENDING], HERD_SKILL); b.lastUsed[Skill.TENDING] = b.weeks
    return k to n
}

/** How good place [p] is to farm: the share of its grass that is wild cereal, where drinking water is within [VILLAGE_WATER_KM]; else none. */
private fun GroupWorld.farmWorth(p: Place) = if (land.drink.waterKm[p] < VILLAGE_WATER_KM) fauna.food.cereal[p] else 0f

/**
 * Band [g] ([b]) starts as a village where it stands: pit houses for its grown people standing at its camp, kept a year
 * already; the ways of living in houses known ([VILLAGE_SKILLS]); sickle and grinding stones; [VILLAGE_STORE_DAYS] of
 * its food in grain, of ears that hold their grain; and last autumn's sowing in the ground, enough to ripen to
 * [VILLAGE_FIELD_DAYS] of its food (or, where this year's grain has ripened already, that harvest in store).
 */
private fun HexGroups.startVillage(g: HexGroup, b: Band): Pair<Int, Float>? {
    val p = g.place
    val h = hw.placeHex[p]
    for (s in VILLAGE_SKILLS) { b.knowsSkill[s] = true; b.skills[s] = max(b.skills[s], VILLAGE_SKILL); b.lastUsed[s] = b.weeks }
    for (t in listOf(Tool.SICKLE, Tool.GRINDER)) { b.knows[t] = true; b.kit[t] = 1f }
    bands.built += Structure(Built.HOUSES, h, Skill.PIT_HOUSE.ordinal, g.id, g.adults.toFloat()).also { buildOn(it, 1f); it.user = g.id }
    b.houseHex = h; b.house = housesAt(g, h); b.atHome = true
    b.campPlace = p; b.campWeeks = stepsPerYear.roundToInt()
    b.seedDomestic = 1f
    // Its field ripens with the grass this summer; where the grain has ripened already this year, the harvest is in store.
    val food = fauna.food
    val ripening = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f) < HexFood.SEED_TO
    val harvest = VILLAGE_FIELD_DAYS * g.dailyNeedKg
    if (ripening) { food.sow(p, land.spread(p, Kg(harvest / HexFood.FIELD_YIELD)), 1f); b.sownPlace = p; b.sownKg = harvest / HexFood.FIELD_YIELD }
    // (Ground and baked, a kilo of grain feeds as a kilo of need: [seedValue] with grinding stones.)
    val stored = VILLAGE_STORE_DAYS * g.dailyNeedKg + if (ripening) 0f else harvest
    addFood(g.stores, g.storeKgPer, Store.SEED, stored * seedValue(b), stored)
    b.seedCorn = g.stores[Store.SEED]
    // (Villages kept goats and sheep from the first: the herd a herding band starts with.)
    return startHerd(g, b)
}

/** Ways a village starts knowing: storage bins sealed with clay, pit houses, gruel boiled with hot stones; and how well it knows them. */
private val VILLAGE_SKILLS = listOf(Skill.SEALING, Skill.PIT_HOUSE, Skill.STONE_BOILING)
private const val VILLAGE_SKILL = 0.5f
/**
 * Farthest a village stands from drinking water, km; the grain it starts with in store, and the grain its sown field will ripen
 * to, each in days of its own food (estimates: a store that carries it to the harvest, a field that gives half its year's food;
 * no source for either).
 */
private const val VILLAGE_WATER_KM = 3f
private const val VILLAGE_STORE_DAYS = 180f
private const val VILLAGE_FIELD_DAYS = Date.DAYS_PER_YEAR / 2f
/**
 * A starting herd, kg on the hoof for each of the band's people: a third of the some 4.5 tropical livestock units (1100 kg)
 * a head that people living on their herds alone keep (Dahl and Hjort 1976, from memory, to check), since the first herders
 * still hunted and gathered (the third an estimate); how tame it starts, and how well its band knows herding (estimates).
 */
private const val HERD_START_KG = 370f
private const val HERD_START_TAME = 0.8f
private const val HERD_SKILL = 0.5f
