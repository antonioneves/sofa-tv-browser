package app.sofabrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopTest {
    private var now = 1_000L
    private val loop = CrashLoop { now }

    @Test
    fun aPageIsLoadedAgainOnceAndLeftAloneWhenItCrashesAgainSoonAfter() {
        assertTrue(loop.reload())
        now += 25_000L
        assertFalse(loop.reload())
    }

    @Test
    fun aCrashLongAfterTheLastOneIsJustAnotherFirstCrash() {
        assertTrue(loop.reload())
        now += CrashLoop.WINDOW
        assertTrue(loop.reload())
        now += CrashLoop.WINDOW - 1
        assertFalse(loop.reload())
    }

    @Test
    fun afterGivingUpThePageGetsOneMoreReloadWhenItIsOpenedAgain() {
        assertTrue(loop.reload())
        now += 20_000L
        assertFalse(loop.reload())
        now += 20_000L
        assertTrue(loop.reload())
        now += 20_000L
        assertFalse(loop.reload())
    }
}
