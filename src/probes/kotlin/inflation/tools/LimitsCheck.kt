package inflation.tools

import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Lifeway
import inflation.ecology.Species
import inflation.ecology.Tally
import inflation.learning.LearnedModel
import inflation.run.Nominal
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.Place
import inflation.world.WorldCache
import inflation.world.WorldConfig
import inflation.Tuning

/**
 * What holds each kind where it is, from a test world over [weeks]: its heads per km² over the country it lives in most
 * against the field ([Species.fieldKm2], within [Nominal.FIELD_SLACK] or not); for a resident, the share of its head-weeks
 * past its crowding sickness ([HexFauna.CROWDED] times its usual numbers at the place, [HexFauna.usualAt]) and, for a
 * hunter, past what its territories hold ([HexFauna.STRIFE_CROWD] times them); how well fed; and its deaths over the run
 * by what killed them: food and weather (hunger, thirst, cold, heat, snow, fire, flood, short of protein), sickness, the
 * beasts of prey (resident hunters, packs, crocodiles, prey that fought back), the small hunters on the young (foxes,
 * eagles), people, its own kind's rivals, and age. The kills of packs and bands are in the groups' ledger
 * ([inflation.ecology.WeekStats.kills], people's part [Tally.OfKind.KILLED_BY_PEOPLE]), not the residents' deaths. The limit is the biggest of them but age. Then the small life against
 * the field (rodents, hares, foxes, owls, root diggers, kg/km² over all the land), and the hares against their room.
 *
 * On a fresh world instead of a test world, [--seed] and [--warm] years first (the old test worlds' saves do not load
 * once the kinds change): the world as [HexSim] starts it, carried on that long before the weeks are counted.
 *
 * Usage: limits-check --world NAME [--weeks N] | limits-check --seed N --warm YEARS [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "limits-check --world NAME [--weeks N] | limits-check --seed N --warm YEARS [--weeks N]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    val cfg = named?.config ?: WorldConfig(seed = a.long("--seed", 1L))
    val warm = a.int("--warm", 0)
    val weeks = a.int("--weeks", Date.DAYS_PER_YEAR / cfg.daysPerStep)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    if (named != null) { TestWorlds.check(named, sim); HexSnapshot.load(sim, named.state); println("carrying on ${named.state} at ${sim.weather.date}") }
    Tuning.changed().takeIf { it.isNotEmpty() }?.let { println("tuning: $it") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    if (warm > 0) { repeat(warm * Date.DAYS_PER_YEAR / cfg.daysPerStep) { sim.step() }; println("fresh world seed ${cfg.seed} warmed $warm years, to ${sim.weather.date}") }
    val land = sim.land; val fauna = sim.fauna; val sl = fauna.small; val n = land.n
    val nk = fauna.kinds.size
    val deaths = Array(nk) { HashMap<Cause, Double>() }
    val headWeeks = DoubleArray(nk); val crowded = DoubleArray(nk); val strife = DoubleArray(nk); val fedW = DoubleArray(nk)
    val byName = fauna.kinds.withIndex().associate { (k, s) -> s.name to k }
    val small = DoubleArray(SMALL.size); var hareRoomShare = 0.0; var hareWeeks = 0.0
    repeat(weeks) {
        sim.step()
        for ((key, v) in fauna.deaths) {
            val k = byName[Tally.kindOf(key)] ?: continue
            deaths[k].merge(cause(Tally.whatOf(key)), v, Double::plus)
        }
        val gs = sim.groups.stats
        for ((ps, v) in gs.kills) { val k = byName[ps.name] ?: continue
            val people = (gs.hunting[Tally.of(ps, Tally.OfKind.KILLED_BY_PEOPLE)] ?: 0).toDouble()
            deaths[k].merge(Cause.PEOPLE, people, Double::plus); deaths[k].merge(Cause.PREDATORS, v - people, Double::plus)
        }
        for (k in 0 until nk) {
            val s = fauna.kinds[k]
            for (p in 0 until n) {
                val d = fauna.adults[k][p] + fauna.young[k][p]; if (d <= 0f) continue
                val h = (d * land.areaKm2[p]).toDouble(); val u = fauna.usualAt(k, Place(p))
                headWeeks[k] += h; fedW[k] += h * fauna.fed[k][p]
                if (d > HexFauna.CROWDED * u) crowded[k] += h
                if (s.carnivore && d > HexFauna.STRIFE_CROWD * u) strife[k] += h
            }
        }
        for (p in 0 until n) {
            val a2 = land.areaKm2[p].toDouble()
            small[0] += sl.rodents[p] * a2; small[1] += sl.hares[p] * a2; small[2] += sl.foxes[p] * a2; small[3] += sl.owls[p] * a2; small[4] += sl.diggers[p] * a2
            val room = sl.hareCapacity(Place(p)); if (room > 0f) { hareRoomShare += sl.hares[p] / room * a2; hareWeeks += a2 }
        }
    }
    val field = Nominal.fieldDensity(sim)
    fun pct(x: Double, of: Double) = if (of > 0.0) 100.0 * x / of else 0.0
    println("\nkind            heads/km² where most | field (x${"%.0f".format(Nominal.FIELD_SLACK)} slack) | fed | crowd-sick / past territories, head-weeks | deaths: food&weather sickness predators foxes&eagles people rivals age | held by")
    for (k in 0 until nk) {
        val s = fauna.kinds[k]; val fd = field[s] ?: continue
        val d = deaths[k]; val all = d.values.sum()
        val food = d[Cause.FOOD] ?: 0.0; val sick = d[Cause.SICKNESS] ?: 0.0; val pred = d[Cause.PREDATORS] ?: 0.0
        val smallH = d[Cause.SMALL_HUNTERS] ?: 0.0; val ppl = d[Cause.PEOPLE] ?: 0.0
        val riv = d[Cause.RIVALS] ?: 0.0; val age = d[Cause.AGE] ?: 0.0
        val held = listOf("food and weather" to food, "sickness" to sick, "beasts of prey" to pred, "foxes and eagles" to smallH,
            "people" to ppl, "rivals" to riv).maxByOrNull { it.second }!!.first
        println("%-14s %8.3f %-20s | %s | %.2f | %3.0f%% / %s | %3.0f%% %3.0f%% %3.0f%% %3.0f%% %3.0f%% %3.0f%% %3.0f%% | %s".format(s.name, fd.perKm2, fd.country,
            verdict(s, fd.perKm2), fedW[k] / headWeeks[k].coerceAtLeast(1e-9), pct(crowded[k], headWeeks[k]),
            if (s.carnivore) "%3.0f%%".format(pct(strife[k], headWeeks[k])) else "  -", pct(food, all), pct(sick, all), pct(pred, all),
            pct(smallH, all), pct(ppl, all), pct(riv, all), pct(age, all), held))
    }
    for (s in sim.groups.kinds) if (s.lifeway == Lifeway.PACK && s !in fauna.kinds) { val fd = field[s] ?: continue
        println("%-14s %8.4f %-20s | %s | (packs)".format(s.name, fd.perKm2, fd.country, verdict(s, fd.perKm2))) }
    // The three marks of a food web held from above: hunters' weight against the plant-eaters', the hunters' share of the
    // deaths of prey under [SMALL_PREY_KG], and how little of the plant-eaters' time stands past their crowding sickness.
    run {
        var plantKg = 0.0; var hunterKg = 0.0
        for (k in 0 until nk) { val s = fauna.kinds[k]; if (s.human) continue
            val kg = (0 until n).sumOf { ((fauna.adults[k][it] + fauna.young[k][it]) * land.areaKm2[it]).toDouble() } * s.massKg
            if (s.carnivore) hunterKg += kg else if (s.diet.animal < s.diet.plants) plantKg += kg }
        for (g in sim.groups.groups) if (g.alive && !g.species.human) hunterKg += g.size * g.species.massKg.toDouble()
        var died = 0.0; var hunted = 0.0; var sickW = 0.0; var allW = 0.0
        for (k in 0 until nk) { val s = fauna.kinds[k]; if (s.carnivore || s.human) continue
            if (s.massKg < SMALL_PREY_KG) { died += deaths[k].values.sum()
                hunted += listOf(Cause.PREDATORS, Cause.SMALL_HUNTERS, Cause.PEOPLE).sumOf { deaths[k][it] ?: 0.0 } }
            sickW += crowded[k]; allW += headWeeks[k] }
        println("\nheld from above? (marks of a food web whose hunters hold their prey):")
        println("  plant-eaters' weight to the hunters': %.0f to 1 (the field some 100-200 to 1: Carbone and Gittleman 2002, about 90 kg of each carnivore kind for 10,000 kg of its prey)".format(plantKg / hunterKg.coerceAtLeast(1e-9)))
        println("  deaths of plant-eaters under %.0f kg by hunters and people: %.0f%% (the field: most of them, Sinclair, Mduma and Brashares 2003)".format(SMALL_PREY_KG, pct(hunted, died)))
        println("  plant-eaters' head-weeks past their crowding sickness: %.0f%% (a backstop: a few at most)".format(pct(sickW, allW)))
    }
    val landKm2 = (0 until n).sumOf { land.areaKm2[it].toDouble() }
    println("\nsmall life, kg/km² over all the land (mean over the run) against the field:")
    for ((i, sm) in SMALL.withIndex()) { val v = small[i] / weeks / landKm2
        println("  %-8s %8.2f | field %s..%s kg/km² (%s) | %s".format(sm.name, v, sm.field.start, sm.field.endInclusive, sm.source,
            when { v < sm.field.start / Nominal.FIELD_SLACK -> "LOW"; v > sm.field.endInclusive * Nominal.FIELD_SLACK -> "HIGH"; else -> "in range" })) }
    println("  hares at %.0f%% of their room on average".format(100 * hareRoomShare / hareWeeks.coerceAtLeast(1e-9)))
}

/** Prey under this weight, kg, are held by their hunters where hunters of many sizes take them (Sinclair et al. 2003, Nature 425:288). */
private const val SMALL_PREY_KG = 150f

/** [s]'s density [d] against its field range: in range, low or high, past [Nominal.FIELD_SLACK]; and the range. */
private fun verdict(s: Species, d: Double): String {
    val f = s.fieldKm2 ?: return "no field figure      "
    val v = when { d < f.start / Nominal.FIELD_SLACK -> "LOW  %4.0fx under".format(f.start / d); d > f.endInclusive * Nominal.FIELD_SLACK -> "HIGH %4.0fx over ".format(d / f.endInclusive); else -> "in range       " }
    return "%-16s %6s..%-6s".format(v, f.start, f.endInclusive)
}

/** What killed them, in groups. */
private enum class Cause { FOOD, SICKNESS, PREDATORS, SMALL_HUNTERS, PEOPLE, RIVALS, AGE }

private fun cause(label: String): Cause = when {
    label == "age" || label == "old age" -> Cause.AGE
    label == "disease" || label == "outbreak" -> Cause.SICKNESS
    "rivals" in label -> Cause.RIVALS
    label == "eagles" || label == "foxes" -> Cause.SMALL_HUNTERS
    label.startsWith("young killed by") || label.startsWith("killed by") || label.startsWith("taken by") || label == "resident hunters" ||
        label == "crocodiles" || label == "prey fought back" -> Cause.PREDATORS
    else -> Cause.FOOD
}

/**
 * The small life against the field, kg/km² over all the land, as the record has it (each with its source; "from memory"
 * where recalled).
 */
private class SmallField(val name: String, val field: ClosedFloatingPointRange<Double>, val source: String)
private val SMALL = listOf(
    SmallField("rodents", 50.0..500.0, "forest mice and voles 10-200 a ha of some 25 g, from memory"),
    SmallField("hares", 10.0..100.0, "brown hares 3-60 a km² on farmland, of 3.5 kg, fewer on poor and wooded land"),
    SmallField("foxes", 1.0..18.0, "red foxes 0.2-3 a km², Eurasian winter mean 0.21, of 6 kg"),
    SmallField("owls", 0.2..2.0, "owls and small hawks, from memory"),
    SmallField("diggers", 10.0..150.0, "blind mole-rats 1-15 a ha of 150 g where they live, porcupines 0.5-2 a km² of 13 kg, from memory"),
)
