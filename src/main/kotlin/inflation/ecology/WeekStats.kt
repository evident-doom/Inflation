package inflation.ecology

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * The groups' tallies for the week, by free-form key: what the reports and checks read back.
 * (Concurrent and sorted: hunting runs region by region on all cores.)
 */
class WeekStats {
    /** Kills this week by prey kind. */
    val kills = ConcurrentSkipListMap<String, Int>()
    /** Meetings this week by kind of meeting. */
    val meetings = ConcurrentSkipListMap<String, Int>()
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<String, Int>()
    /** This week's hunting by kind of hunter: chases, kills from herds and of residents, herds that saw it first. */
    val hunting = ConcurrentSkipListMap<String, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<String, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<String, Int>()

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear() }
}
