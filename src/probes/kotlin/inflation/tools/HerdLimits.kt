package inflation.tools

import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Skill
import inflation.ecology.headsAHerder
import inflation.ecology.herdForage
import inflation.ecology.herdIncrease
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.get
import inflation.world.WorldCache
import inflation.world.WorldConfig
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What would hold each band's kept herd, kind by kind: the herd's heads against the heads at which each of these would bind
 * (the cap's headroom, cap over heads now; 1 binds now):
 * - forage, old rule: grass at a tenth of the palatable herbage a week and browse, on the ground it grazes round the camp
 *   ([herdForage]);
 * - forage at the residents' reach: the grass and browse the residents left ([HexFauna.grazeLeft], [HexFauna.browseLeft]),
 *   on that ground; and the same with the fruit and roots stocks there besides;
 * - crowding: the heads at which the residents' crowding sickness ([inflation.ecology.Hazards.crowdDisease], well fed)
 *   would take the herd's yearly increase ([herdIncrease]), counted on that ground with the residents of its kind there;
 * - labour: the herders its leanest month leaves free ([inflation.ecology.Band.freeShare], its adults' days) times a
 *   herder's stock ([headsAHerder]), as if the band kept this kind alone.
 * Sampled at the first step of each month. For the herd drift design (PR 513).
 *
 * Usage: herd-limits (--world NAME | --seed N) [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "herd-limits (--world NAME | --seed N) [--years N]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed))
    val years = a.int("--years", 1)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.state?.let { HexSnapshot.load(sim, it); println("carrying on $it at ${sim.weather.date}") }
    val fauna = sim.fauna; val land = sim.land; val groups = sim.groups
    val days = cfg.daysPerStep
    val stepsY = Date.DAYS_PER_YEAR / days
    val names = listOf("old forage", "resident forage", "resident forage + fruit, roots", "crowding", "labour")
    println("month bands herds heads | headroom by cap (cap / heads now), heads-weighted median [p10, p90] | herds each cap binds first")
    var lastMonth = -1
    repeat(years * stepsY) {
        sim.step()
        val m = sim.weather.lastMonth
        if (m == lastMonth) return@repeat
        lastMonth = m
        val rows = ArrayList<Pair<Float, FloatArray>>()
        var bands = 0
        for (g in groups.groups) {
            val b = g.band ?: continue
            val p = g.place
            val young = b.herds.youngHerd()
            var any = false
            for (k in b.herds.herd.indices) {
                val n = b.herds.herd[k]; val y = young.getOrElse(k) { 0f }
                val heads = n + y
                if (heads < 1f) continue
                any = true
                val s = fauna.kinds[k]
                val area = land.areaKm2[p]
                val r = 0.5f * s.dailyTravelKm
                val grazedKm2 = min(area, PI.toFloat() * r * r); val share = grazedKm2 / area
                val need = groups.herdForage(p, s, n + 0.5f * y).need
                if (need <= 0f) continue
                val old = share * (0.1f * land.palatable[p] * land.plants.herb[p] + land.plants.browse(p))
                val res = share * (fauna.grazeLeft[p] + fauna.browseLeft[p])
                val fr = share * (fauna.food.fruit[p] + fauna.food.underground(p))
                val usual = HexFauna.usualDensity(s)
                val resident = fauna.adults[k][p] + fauna.young[k][p]
                val inc = max(1e-3f, groups.herdIncrease(b, k))
                val crowdAt = sqrt(inc / HexFauna.DISEASE)
                val crowdCap = max(0f, (crowdAt * HexFauna.CROWDED * usual - resident) * grazedKm2)
                val free = b.freeShare.takeIf { f -> f.all { it >= 0f } }?.min()
                val labourCap = free?.let { it * g.adults * (0.5f + 0.5f * min(1f, b.skill(Skill.TENDING))) * headsAHerder(s) } ?: Float.NaN
                rows += heads to floatArrayOf(old / need, res / need, (res + fr) / need, crowdCap / heads, labourCap / heads)
            }
            if (any) bands++
        }
        val herds = rows.size; val total = rows.sumOf { it.first.toDouble() }
        fun stat(i: Int): String {
            val v = rows.filter { !it.second[i].isNaN() }.sortedBy { it.second[i] }
            if (v.isEmpty()) return "n/a"
            val w = v.sumOf { it.first.toDouble() }
            fun q(f: Double): Float { var acc = 0.0; for ((h, x) in v) { acc += h; if (acc >= f * w) return x[i] }; return v.last().second[i] }
            return "%.2f [%.2f, %.2f]".format(q(0.5), q(0.1), q(0.9))
        }
        // (Which of forage at the residents' reach with fruit and roots, crowding and labour binds first: the old rule is main's.)
        val first = IntArray(names.size)
        for ((_, x) in rows) { val i = listOf(2, 3, 4).filter { !x[it].isNaN() }.minByOrNull { x[it] } ?: continue; first[i]++ }
        println("%2d %5d %5d %8.0f | %s | %s".format(m + 1, bands, herds, total,
            names.indices.joinToString("; ") { "${names[it]} ${stat(it)}" },
            listOf(2, 3, 4).joinToString(", ") { "${names[it]} ${first[it]}" }))
    }
}
