package app.sofabrowser.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStackLayoutTest {

    private val slot = 96
    private val square = 68

    @Test fun `icons that fit sit a slot apart`() {
        assertArrayEquals(intArrayOf(0, 96, 192), TabStackLayout.tops(3, 0, 1000, slot, square, 12))
        assertEquals(260, TabStackLayout.natural(3, slot, square))
        assertArrayEquals(intArrayOf(0, 96, 192), TabStackLayout.tops(3, 2, 260, slot, square, 12))
    }

    @Test fun `one icon never moves`() {
        assertArrayEquals(intArrayOf(0), TabStackLayout.tops(1, 0, 40, slot, square, 12))
    }

    @Test fun `a stack too short overlaps and still ends at its foot`() {
        for (n in 3..20) for (f in 0 until n) {
            val tops = TabStackLayout.tops(n, f, 220, slot, square, 12)
            assertEquals("n=$n f=$f", 220 - square, tops.last())
            for (i in 1 until n) assertTrue("n=$n f=$f i=$i", tops[i] >= tops[i - 1])
        }
    }

    @Test fun `the focused icon's neighbours move off it`() {
        val tops = TabStackLayout.tops(6, 2, 220, slot, square, 12)
        val gaps = (1 until 6).map { tops[it] - tops[it - 1] }
        // Next to the focused icon (the gaps above and below it) the room is bigger than anywhere else.
        assertTrue(gaps.toString(), gaps[1] > gaps[0] && gaps[2] > gaps[3] && gaps[2] > gaps[4])
        assertTrue(gaps.toString(), gaps[0] >= 12 && gaps[3] >= 12)
    }

    @Test fun `the focus moving slides the icons`() {
        val a = TabStackLayout.tops(5, 0, 220, slot, square, 12)
        val b = TabStackLayout.tops(5, 4, 220, slot, square, 12)
        // At the top, the second icon moves well off the first; at the foot, the one before the last moves off it.
        assertTrue(a[1] - a[0] > a[2] - a[1])
        assertTrue(b[4] - b[3] > b[3] - b[2])
        assertEquals(a.last(), b.last())
    }

    @Test fun `many icons share the room evenly`() {
        val tops = TabStackLayout.tops(40, 10, 220, slot, square, 12)
        assertEquals(220 - square, tops.last())
        assertTrue(tops.toList().zipWithNext().all { (x, y) -> y - x in 3..5 })
    }
}
