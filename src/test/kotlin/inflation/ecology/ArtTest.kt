package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Art's worth to a taker by the strangeness of its style ([perceivedWorth]), the style mix pieces carry ([Band.artMix]), and debts paid in it ([artRepaid]). */
class ArtTest {
    private fun band(vararg style: Int) = Band(1).also { b -> style.forEachIndexed { t, v -> b.style[t] = v } }

    @Test
    fun `a piece in the taker's own style is worth its days, one wholly strange twice`() {
        val a = band(0, 0, 0, 0); val same = band(0, 0, 0, 0); val other = band(1, 1, 1, 1); val half = band(0, 0, 1, 1)
        a.ornamentWorth = 10f
        assertEquals(10f, perceivedWorth(10f, a, same), 1e-5f)
        assertEquals(10f * (1f + ART_STRANGE), perceivedWorth(10f, a, other), 1e-5f)
        assertEquals(10f * (1f + 0.5f * ART_STRANGE), perceivedWorth(10f, a, half), 1e-5f)
    }

    @Test
    fun `pieces keep their makers' style as they pass on, blended by worth`() {
        val maker = band(1, 1, 1, 1); val taker = band(0, 0, 0, 0)
        maker.addOwnArt(10f); maker.ornamentWorth = 10f
        taker.addOwnArt(10f); taker.ornamentWorth = 10f
        taker.blendArt(maker.artMix, taker.ornamentWorth, 10f); taker.ornamentWorth = 20f
        // (Half its worth is now in the maker's style: to a third band of the maker's style its pieces are half strange.)
        assertEquals(0.5f, artStrangeness(taker, band(1, 1, 1, 1)), 1e-5f)
        assertEquals(0.5f, artStrangeness(taker, taker), 1e-5f)
    }

    @Test
    fun `a debtor gives as many pieces as their worth to the creditor meets the debt, at most half of what it holds`() {
        val debtor = band(1, 1, 1, 1); val creditor = band(0, 0, 0, 0)
        debtor.ornaments = 10f; debtor.ornamentWorth = 40f; debtor.ensureArt()
        debtor.will[BandWay.REPAYING] = 1f
        // (A piece is 4 days' work, wholly strange to the creditor: worth 8 of its days.)
        assertEquals(2f, artRepaid(debtor, creditor, 16f, 1f), 1e-4f)
        assertEquals(ART_REPAY_SHARE * 10f, artRepaid(debtor, creditor, 1000f, 1f), 1e-4f)
        assertEquals(0f, artRepaid(debtor, creditor, 0f, 1f))
        assertTrue(artRepaid(debtor, band(1, 1, 1, 1), 16f, 1f) > 2f, "in its own style the creditor values each piece less, so it takes more of them")
    }

    @Test
    fun `soft pieces wear out faster than stone and bone`() {
        val b = Band(1); b.ornaments = 10f; b.soft = 5f; b.ornamentWorth = 10f
        wearOrnaments(b, 1f)
        assertEquals(5f * (1f - SOFT_WEAR), b.soft, 1e-4f)
        assertEquals(10f - 10f * FINERY_WEAR - 5f * (SOFT_WEAR - FINERY_WEAR), b.ornaments, 1e-4f)
    }
}
