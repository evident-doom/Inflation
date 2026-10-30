package inflation

/** [x] against [half]: 0 at none, a half at [half], towards 1 at plenty (a saturating response). */
fun saturate(x: Float, half: Float) = x / (x + half)

/** The mean of [x] and [y] weighted by [wx] and [wy] (their weights not both zero). */
fun weightedMean(x: Float, wx: Float, y: Float, wy: Float) = (wx * x + wy * y) / (wx + wy)
