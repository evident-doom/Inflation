package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.tools.herps.HerpRegions
import inflation.tools.herps.HerpReport
import inflation.tools.herps.HerpTally
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Probe: reptiles and amphibians by climate class: the yearly mean each year, and over the last [show]
 * years where they come from and go (born, dying out, of hunger, lying up, taken by hunters, and the
 * rest: spreading, fire, the cap), as shares of their mean a year; how well fed they are while out; and
 * the insects they and the birds eat. Also, over the whole map, the yearly mean of the other small game
 * and its hunters (and wild boar), and the rodents in March and September. The counting and the report
 * are in [inflation.tools.herps].
 */
fun main(args: Array<String>) {
    val a = Args(args, "herp-check [--seed N] [--width KM] [--height KM] [--years N] [--show N]")
    val base = WorldConfig(seed = 1)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val years = a.int("--years", 5); val show = a.int("--show", 2)
    a.done()
    val run = HexLearning.Run(cfg, Catalogue.learned)
    val regions = HerpRegions(run)
    val tally = HerpTally(run, regions, years, show, Date.DAYS_PER_YEAR / cfg.daysPerStep)
    repeat(years * tally.perYear) { w ->
        tally.beforeStep(w)
        run.step()
        tally.afterStep(w)
    }
    HerpReport(run, regions, tally).print()
}
