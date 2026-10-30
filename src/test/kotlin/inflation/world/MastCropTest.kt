package inflation.world

import inflation.gaussian
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The year's mast crop ([Weather.mastCrop]): an average year's crop on the mean, swinging as oak crops do. */
class MastCropTest {
    @Test
    fun `mast crops average one and swing by the oaks' coefficient of variation`() {
        val r = Random(1)
        val n = 200_000
        var sum = 0.0; var sq = 0.0
        repeat(n) { val c = Weather.mastFrom(gaussian(r)).toDouble(); sum += c; sq += c * c }
        val mean = sum / n
        val cv = sqrt(sq / n - mean * mean) / mean
        assertTrue(abs(mean - 1.0) < 0.03, "mean crop $mean")
        assertTrue(abs(cv - Weather.MAST_CV) < 0.1, "crop CV $cv against ${Weather.MAST_CV}")
    }

    @Test
    fun `a group of several genera swings less than one genus, and a group's crop still averages one`() {
        for (g in MastTrees.entries) assertTrue(Weather.mastCv(g) < Weather.MAST_CV, "${g.name} CV ${Weather.mastCv(g)}")
        val g = MastTrees.TEMPERATE
        val r = Random(2)
        val n = 200_000
        var sum = 0.0; var sq = 0.0
        repeat(n) { val c = Weather.mastFrom(gaussian(r), Weather.mastCv(g)).toDouble(); sum += c; sq += c * c }
        val mean = sum / n
        val cv = sqrt(sq / n - mean * mean) / mean
        assertTrue(abs(mean - 1.0) < 0.03, "mean crop $mean")
        assertTrue(abs(cv - Weather.mastCv(g)) < 0.1, "crop CV $cv against ${Weather.mastCv(g)}")
    }
}
