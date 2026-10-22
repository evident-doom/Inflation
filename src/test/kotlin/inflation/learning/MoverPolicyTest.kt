package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.Place
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoverPolicyTest {
    private val gazelle = Catalogue.all.first { it.name == "gazelle" }

    /** Four places: the first is where the group stands; place [bad] has a hand-written score that is not a number. */
    private fun places(bad: Int, badScore: Float) = Pair(
        Array(4) { k -> FloatArray(HexFeatures.SIZE).also { if (k == 0) it[HexFeatures.STAYS] = 1f; it[HexFeatures.FOOD] = 0.1f * k } },
        floatArrayOf(0f, 1f, 2f, 3f).also { it[bad] = badScore })

    @Test
    fun aPlaceThatIsNotANumberIsNeverTakenNorLearntFrom() {
        val mover = SharedMover.random(8, Random(3)).also { it.residual = 1f }
        val record = ConcurrentLinkedQueue<Choice>()
        // Every choice recorded (drawn by chance), so that the draws reach every place.
        val policy = MoverPolicy(mover, record, { 1f }, { 0 })
        val g = HexGroup(1, gazelle, Place(0), 40, 10)
        for (badScore in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) for (bad in 0 until 4) {
            record.clear()
            val (features, baseline) = places(bad, badScore)
            repeat(200) { i -> assertTrue(policy.choose(g, features, baseline, Random(i)) != bad) }
            for (c in record) {
                assertEquals(3, c.inputs.size); assertTrue(c.baseline.all { it.isFinite() } && c.logp.isFinite())
                assertTrue(c.taken in 0 until 3)
            }
            // (Not drawn: the best of the places that can be weighed.)
            assertTrue(MoverPolicy(mover, null, { 0f }, { 0 }).choose(g, features, baseline, Random(1)) != bad)
        }
        assertTrue(NonFinite.take().contains("gazelle: hand-written score"))
    }

    @Test
    fun aFeatureThatIsNotANumberKeepsItsPlaceOut() {
        val mover = SharedMover.random(8, Random(4)).also { it.residual = 1f }
        val record = ConcurrentLinkedQueue<Choice>()
        val policy = MoverPolicy(mover, record, { 1f }, { 0 })
        val g = HexGroup(2, gazelle, Place(0), 40, 10)
        val (features, baseline) = places(0, 0f)
        features[2][HexFeatures.KILL_FEAR] = Float.POSITIVE_INFINITY
        repeat(100) { i -> assertTrue(policy.choose(g, features, baseline, Random(i)) != 2) }
        assertTrue(record.all { c -> c.inputs.size == 3 && c.inputs.all { f -> f.all { it.isFinite() } } })
        assertTrue(NonFinite.take().contains("gazelle: feature kill fear"))
    }

    @Test
    fun withNoPlaceToWeighTheGroupStays() {
        val mover = SharedMover.random(8, Random(5)).also { it.residual = 1f }
        val record = ConcurrentLinkedQueue<Choice>()
        val g = HexGroup(3, gazelle, Place(0), 40, 10)
        val (features, _) = places(0, 0f)
        features.reverse()
        assertEquals(3, MoverPolicy(mover, record, { 1f }, { 0 }).choose(g, features, FloatArray(4) { Float.NaN }, Random(1)))
        assertTrue(record.isEmpty())
        NonFinite.take()
    }

    @Test
    fun aChoiceNotDrawnCanBeRecordedForTheCriticAloneAndTheBestIsStillTaken() {
        val mover = SharedMover.random(8, Random(6)).also { it.residual = 1f }
        val g = HexGroup(4, gazelle, Place(0), 40, 10)
        val (features, baseline) = places(0, 0f)
        val plain = MoverPolicy(mover, null, { 0f }, { 0 })
        val record = ConcurrentLinkedQueue<Choice>()
        // Never drawn, every one of them recorded for the critic.
        val policy = MoverPolicy(mover, record, { 0f }, { 0 }, valueRate = { 1f })
        repeat(20) { i -> assertEquals(plain.choose(g, features, baseline, Random(i)), policy.choose(g, features, baseline, Random(i))) }
        assertEquals(20, record.size)
        assertTrue(record.all { it.valueOnly && it.inputs.size == 1 && it.inputs[0][HexFeatures.STAYS] > 0.5f && it.group != null })
        // None wanted: none recorded.
        val none = ConcurrentLinkedQueue<Choice>()
        repeat(20) { i -> MoverPolicy(mover, none, { 0f }, { 0 }).choose(g, features, baseline, Random(i)) }
        assertTrue(none.isEmpty())
    }
}
