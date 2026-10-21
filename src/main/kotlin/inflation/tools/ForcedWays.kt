package inflation.tools

import kotlin.math.ln

/**
 * The network output that sets one of a band's ways to [value] times the scaffold's, for the tools that hold a way at a fixed
 * value for every band: a way is 2 / (1 + exp(-output)) times the scaffold's ([inflation.ecology.setWays]), so 1 is output 0
 * and 0.5 and 1.5 are outputs about -1.10 and +1.10. Only values between 0 and 2 can be set.
 */
internal fun forcedWayOutput(value: Float): Float {
    require(value > 0f && value < 2f) { "a way's value must lie between 0 and 2 (1 the scaffold's), not $value" }
    return -ln(2f / value - 1f)
}
