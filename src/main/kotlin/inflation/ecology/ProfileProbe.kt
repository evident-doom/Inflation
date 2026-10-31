package inflation.ecology

import inflation.Profile

/** What a slow job ([Profile.slow], with profile=fine) was about: the size of the group and of what its band knows. For reading the report only. */
internal fun HexGroup.probe(): String {
    val b = band
    val base = "g#$id ${species.name} size=$size path=${path.size}"
    if (b == null) return base
    val known = b.known.sumOf { java.lang.Long.bitCount(it) }
    return "$base adults=$adults known=$known homes=${b.knownHomes.size} ties=${b.ties.size} campWeeks=${b.campWeeks} larder=${"%.0f".format(larder)}kg"
}
