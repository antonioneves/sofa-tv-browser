package app.sofabrowser

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinMemoryTest {
    private var now = 1_000L

    @After fun reset() { PinMemory.forget(); PinMemory.clock = { android.os.SystemClock.elapsedRealtime() } }

    @Test
    fun theEnteredPinCountsForTheChosenTimeOnly() {
        PinMemory.clock = { now }
        assertFalse(PinMemory.valid(5))
        PinMemory.remember()
        now += 4 * 60_000L
        assertTrue(PinMemory.valid(5))
        now += 60_000L
        assertFalse(PinMemory.valid(5))
    }

    @Test
    fun zeroMeansAskEveryTimeAndForgetEndsIt() {
        PinMemory.clock = { now }
        PinMemory.remember()
        assertFalse(PinMemory.valid(0))
        assertTrue(PinMemory.valid(15))
        PinMemory.forget()
        assertFalse(PinMemory.valid(15))
    }
}
