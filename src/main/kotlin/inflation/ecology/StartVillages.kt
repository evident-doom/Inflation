package inflation.ecology

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
 */
internal fun HexGroups.startVillages() {
    val bands = groups.filter { it.alive && it.species.human && it.band != null }
    val farm = bands.filter { farmWorth(it.place) > 0f }.sortedByDescending { farmWorth(it.place) }
    for (g in farm.take(min(farm.size, (tuning.startSettled * bands.size).roundToInt()))) g.band?.let { startVillage(g, it) }
}

/** How good place [p] is to farm: the share of its grass that is wild cereal, where drinking water is within [VILLAGE_WATER_KM]; else none. */
private fun HexGroups.farmWorth(p: Place) = if (land.drink.waterKm[p] < VILLAGE_WATER_KM) fauna.food.cereal[p] else 0f

/**
 * Band [g] ([b]) starts as a village where it stands: pit houses for its grown people standing at its camp, kept a year
 * already; the ways of living in houses known ([VILLAGE_SKILLS]); sickle and grinding stones; [VILLAGE_STORE_DAYS] of
 * its food in grain, of ears that hold their grain; and last autumn's sowing in the ground, enough to ripen to
 * [VILLAGE_FIELD_DAYS] of its food (or, where this year's grain has ripened already, that harvest in store).
 */
private fun HexGroups.startVillage(g: HexGroup, b: Band) {
    val p = g.place
    val h = hw.placeHex[p]
    for (s in VILLAGE_SKILLS) { b.knowsSkill[s] = true; b.skills[s] = max(b.skills[s], VILLAGE_SKILL); b.lastUsed[s] = b.weeks }
    for (t in listOf(Tool.SICKLE, Tool.GRINDER)) { b.knows[t] = true; b.kit[t] = 1f }
    built += Structure(Built.HOUSES, h, Skill.PIT_HOUSE.ordinal, g.id, g.adults.toFloat()).also { buildOn(it, 1f); it.user = g.id }
    b.houseHex = Hex(h); b.house = housesAt(g, h); b.atHome = true
    b.campPlace = p; b.campWeeks = stepsPerYear.roundToInt()
    b.seedDomestic = 1f
    // Its field ripens with the grass this summer; where the grain has ripened already this year, the harvest is in store.
    val food = fauna.food
    val ripening = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f) < HexFood.SEED_TO
    val harvest = VILLAGE_FIELD_DAYS * g.dailyNeedKg
    if (ripening) food.sow(p, land.spread(p, Kg(harvest / HexFood.FIELD_YIELD)), 1f)
    // (Ground and baked, a kilo of grain feeds as a kilo of need: [seedValue] with grinding stones.)
    val stored = VILLAGE_STORE_DAYS * g.dailyNeedKg + if (ripening) 0f else harvest
    addFood(g.stores, g.storeKgPer, Store.SEED, stored * seedValue(b), stored)
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
