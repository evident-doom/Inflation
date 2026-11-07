package inflation.ecology

import inflation.world.Place
import inflation.world.get
import kotlin.math.roundToInt

/**
 * Where a band's salt comes from and where it goes, for the census only ([WeekStats.bandSplits]): what each flow
 * moved, kg, kept on the band as it happens ([Band.saltFlow]) and booked each week by where the band stands ([SaltWhere]).
 * Report-only: nothing reads it back.
 */
enum class SaltFlow(val label: String) {
    /** Scraped from a dry lake bed or pan along the way ([gatherMaterials]). */
    PAN("scraped at pans"),
    /** Gathered from the shore. */
    SHORE("from the shore"),
    /** Given it by other bands at meetings: of their spare ([TRADED]) and salt to eat. */
    GIVEN_IN("given by others"),
    /** Given to other bands at meetings. */
    GIVEN_OUT("given to others"),
    /** Eaten to make up the sodium its food lacked ([Meals]). */
    EATEN("eaten"),
    /** Rubbed into meat and fish as it dried them ([SALT_PER_KG]). */
    CURED("curing"),
    /** Fed to its kept beasts ([feedHerdSalt]). */
    HERDS("fed to herds"),
    /**
     * Every other change in what it held: left behind for weight, taken by an offshoot or brought by a joining band, lost
     * with its dead. The week's change less the flows above.
     */
    OTHER("other change"),
}

/** Where a band stands as its week's salt is booked: on the coast, where salt crops out, or neither. */
enum class SaltWhere(val label: String) { COAST("coast"), PAN("salt pan"), INLAND("neither") }

/** What the salt census counts besides the flows: people-weeks, salt held (g a person, summed over band-weeks), and the person-days spent gathering salt, tenths. */
enum class SaltCount(val label: String) { BAND_WEEKS("band-weeks"), PEOPLE_WEEKS("people-weeks"), HELD("held, g a person"), DAYS("gathering, person-days 1/10") }

@OptIn(RawKey::class)
internal fun saltFlowKey(w: SaltWhere, f: SaltFlow) = TallyKey.raw("salt at ${w.label}:${f.label}, g")

@OptIn(RawKey::class)
internal fun saltCountKey(w: SaltWhere, c: SaltCount) = TallyKey.raw("salt at ${w.label}:${c.label}")

/** Notes [kg] of salt moved by [flow] on band [b], for the census ([bookSalt]). */
internal fun Band.noteSalt(flow: SaltFlow, kg: Float) { if (kg != 0f) saltFlow[flow.ordinal] += kg }

/**
 * Books band [b]'s salt flows since the last booking, at its group [g] of [people] people standing at [p], under where it
 * stands; and what else changed what it holds, as [SaltFlow.OTHER]. Then starts the next week's afresh. Called every week
 * for every band; it books only for the census.
 */
internal fun HexGroups.bookSalt(b: Band, p: Place, people: Int) {
    val held = b.goods[Material.SALT]
    if (stats.bandSplits) {
        val coast = hw.coast[hw.placeHex[p]]
        val w = when { coast -> SaltWhere.COAST; land.salt[p] > 0f -> SaltWhere.PAN; else -> SaltWhere.INLAND }
        // (A band's first week, or its first after an offshoot, has nothing to set the change against.)
        if (!b.saltSeen.isNaN()) {
            var flows = 0f
            for (f in SaltFlow.entries) if (f != SaltFlow.OTHER) {
                val kg = b.saltFlow[f.ordinal]
                flows += if (f == SaltFlow.PAN || f == SaltFlow.SHORE || f == SaltFlow.GIVEN_IN) kg else -kg
            }
            b.noteSalt(SaltFlow.OTHER, held - b.saltSeen - flows)
            for (f in SaltFlow.entries) stats.tally(saltFlowKey(w, f), (1000f * b.saltFlow[f.ordinal]).roundToInt())
            stats.tally(saltCountKey(w, SaltCount.BAND_WEEKS), 1)
            stats.tally(saltCountKey(w, SaltCount.PEOPLE_WEEKS), people)
            stats.tally(saltCountKey(w, SaltCount.HELD), (1000f * held / maxOf(1, people)).roundToInt())
            stats.tally(saltCountKey(w, SaltCount.DAYS), (10f * b.saltDays).roundToInt())
        }
    }
    b.saltFlow.fill(0f); b.saltDays = 0f; b.saltSeen = held
}
