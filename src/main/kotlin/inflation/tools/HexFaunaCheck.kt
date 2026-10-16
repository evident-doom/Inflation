package inflation.tools

import inflation.tools.fauna.FaunaCheck
import inflation.tools.fauna.FaunaCheckOptions

/**
 * Runs the resident plant-eaters on the hexes for some years over weather, water and plants, and
 * prints each kind's numbers year by year with the main causes of death, and the cost of a step.
 * The run and its reports are in [inflation.tools.fauna]; [TestWorlds] runs this for its A/B tests
 * and reads its lines, so a line's wording is part of what it promises.
 *
 * Usage (--history FILE writes the week-by-week history as CSV for the graphs; --save FILE saves the
 * world every five years and at the end, --load FILE carries on a saved one; --world NAME carries on
 * one of the settled test worlds, see [TestWorlds]; --replicate R draws the weather afresh after a
 * load, for another run from the same start): hex-fauna-check [--seed N] [--width KM] [--height KM]
 * [--years N] [--save FILE] [--load FILE] [--world NAME] [--replicate R]
 */
fun main(args: Array<String>) = faunaCheck(args)

fun faunaCheck(args: Array<String>) {
    val options = FaunaCheckOptions.parse(args)
    FaunaCheck(options.start(), options).run()
}
