package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class CriticTest {
    private val gazelle = Catalogue.all.first { it.name == "gazelle" }

    @Test
    fun `the critic learns a return that hangs on the group's own state, and judges by its estimate where the group stood weeks on`() {
        val model = SharedMover.random(16, Random(1))
        val critic = Critic(Mlp.random(intArrayOf(model.inputs + Critic.GROUP, Critic.HIDDEN, Critic.HIDDEN, 1), Random(91)))
        val rnd = Random(2)
        fun choice(): Choice {
            val here = FloatArray(HexFeatures.SIZE).also { it[HexFeatures.STAYS] = 1f }
            val fed = rnd.nextFloat()
            return Choice(gazelle, 1, 10, 0, arrayOf(here), 0, 0f).also {
                it.group = floatArrayOf(0.5f, 0.2f, fed, 0f, 1f, 0f, 1f)
                // Better fed groups fare better, with a little chance on top.
                it.ret = 0.8f + 0.2f * fed + 0.01f * (rnd.nextFloat() - 0.5f)
            }
        }
        repeat(6) { round -> critic.learn(model, List(2000) { choice() }, round) }
        val fresh = List(1000) { choice() }
        val explained = critic.explained(model, fresh).getValue(gazelle)
        assertTrue(explained > 0.6, "explained $explained")
        // With a sight of the group weeks on, the judgement is its first weeks plus the estimate from there.
        val c = fresh[0].also { it.part = 0.3f; it.boot = 0.5f; it.probe = Probe(it.inputs[0], null, null, it.group!!) }
        val t = critic.target(model, c)
        assertTrue(kotlin.math.abs(t - (0.3f + 0.5f * critic.value(model, c))) < 1e-4f)
    }
}
