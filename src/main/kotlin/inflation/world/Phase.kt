package inflation.world

import inflation.Books
import inflation.Profile

/**
 * A named part of the week, run with the week's [Weather]: timed ([Profile]) and, when the books are kept, checked
 * against them ([Books.check]) under the one name, so the profile and the books audit name the parts alike.
 */
class Phase(val name: String, val run: (Weather) -> Unit)

/** Runs [phases] in their order, each timed and checked against [books] under its name. */
fun runPhases(phases: List<Phase>, weather: Weather, books: Books?) {
    for (p in phases) {
        Profile.time(p.name) { p.run(weather) }
        books?.check(p.name)
    }
}
