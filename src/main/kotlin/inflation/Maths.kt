package inflation

/** [x] against [half]: 0 at none, a half at [half], towards 1 at plenty (a saturating response). */
fun saturate(x: Float, half: Float) = x / (x + half)
