package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unwatched kept animals come home as tame as they are ([unherdedStrays]); herded ones stray as before ([straying]). */
class HerdStrayTest {
    @Test
    fun `unwatched animals come home as tame as they are`() {
        for (s in Catalogue.all) {
            assertEquals(1f, unherdedStrays(s, 0f), 1e-6f, "${s.name}: a wild herd left unwatched does not come back")
            assertTrue(unherdedStrays(s, 1f) <= unherdedStrays(s, 0.5f), "${s.name}: tamer strays no more")
            // (A week unwatched loses at least as much as a year herded, as wild as they are.)
            for (t in listOf(0f, 0.4f, 0.8f, 1f)) assertTrue(unherdedStrays(s, t) >= straying(s, t), "${s.name} at $t")
        }
    }
}
