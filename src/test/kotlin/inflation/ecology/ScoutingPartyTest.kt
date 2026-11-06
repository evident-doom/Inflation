package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoutingPartyTest {
    @Test fun aPartyIsAShareOfTheAbleAndLeavesABand() {
        // 20 grown, 2 elders, at the scaffold's will: 15% of 18 is 2.7, so 3 go.
        assertEquals(3, partySize(20, 2, 1f))
        // Never fewer than two.
        assertEquals(PARTY_MIN, partySize(12, 0, 0.3f))
        // None if the band left behind would be too small to stay a band.
        assertEquals(0, partySize(SMALL_BAND + 1, 0, 1f))
    }
}
