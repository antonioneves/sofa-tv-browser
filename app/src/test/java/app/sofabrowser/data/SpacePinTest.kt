package app.sofabrowser.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpacePinTest {

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun sameHashForSamePinAndSalt() = assertArrayEquals(SpacePin.hash("1234", salt, 10), SpacePin.hash("1234", salt, 10))

    @Test
    fun otherPinOrSaltGivesOtherHash() {
        val hash = SpacePin.hash("1234", salt, 10)
        assertFalse(hash.contentEquals(SpacePin.hash("1235", salt, 10)))
        assertFalse(hash.contentEquals(SpacePin.hash("1234", ByteArray(16), 10)))
    }

    @Test
    fun firstTriesAreFreeThenWaitsGrowToAnHour() {
        assertEquals(0, SpacePin.waitAfter(4))
        assertEquals(30_000, SpacePin.waitAfter(5))
        assertEquals(60_000, SpacePin.waitAfter(6))
        assertEquals(3_600_000, SpacePin.waitAfter(100))
    }
}
