package app.sofabrowser.ui

import app.sofabrowser.ui.MenuLayout.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLayoutTest {

    @Test
    fun theIdsNeverChange() {
        // Stored in Settings and written by a settings export: renaming one would lose that button's place.
        assertEquals(listOf("zoom", "watch", "auto_watch", "find", "translate", "reader", "hide_parts", "bookmark", "pin",
            "tv_shortcut", "close_tab", "bookmarks", "history", "downloads", "adblock", "phone_signin", "user_agent",
            "clear_site", "scroll", "pointer", "settings"), Item.entries.map { it.id })
    }

    @Test
    fun nothingStoredIsTheDefaultMenu() {
        assertEquals(MenuLayout.DEFAULT, MenuLayout.decode(null))
        assertEquals(MenuLayout.DEFAULT, MenuLayout.decode(""))
        assertEquals("", MenuLayout.DEFAULT.encode())
        assertEquals(Item.entries, MenuLayout.DEFAULT.shown)
    }

    @Test
    fun anOrderAndHiddenButtonsComeBackAsStored() {
        val l = MenuLayout.DEFAULT.move(Item.WATCH.ordinal, 0).show(Item.AUTO_WATCH.ordinal, false)
        assertEquals("watch,zoom,-auto_watch", l.encode().split(',').take(3).joinToString(","))
        val back = MenuLayout.decode(l.encode())
        assertEquals(l, back)
        assertEquals(Item.WATCH, back.shown.first())
        assertFalse(Item.AUTO_WATCH in back.shown)
        assertEquals(1, back.hiddenCount)
    }

    @Test
    fun goingBackToTheDefaultStoresNothing() {
        val l = MenuLayout.DEFAULT.move(3, 7).move(7, 3)
        assertTrue(l.isDefault)
        assertEquals("", l.encode())
        assertEquals("", MenuLayout.DEFAULT.show(2, false).show(2, true).encode())
    }

    @Test
    fun aButtonNewInALaterVersionTakesItsDefaultPlace() {
        // Stored by a version without Find: it comes back after Auto watch, the button before it by default.
        val stored = MenuLayout.DEFAULT.move(Item.SETTINGS.ordinal, 0).entries.filter { it.item != Item.FIND }
            .joinToString(",") { it.item.id }
        val l = MenuLayout.decode(stored)
        assertEquals(Item.SETTINGS, l.shown[0])
        assertEquals(l.shown.indexOf(Item.AUTO_WATCH) + 1, l.shown.indexOf(Item.FIND))
        assertEquals(Item.entries.size, l.entries.size)
        // With none of the buttons before it named, a new one goes first.
        assertEquals(Item.ZOOM, MenuLayout.decode("watch,settings").shown[0])
    }

    @Test
    fun unknownAndRepeatedIdsAreSkipped() {
        val l = MenuLayout.decode(" find , teleport,-find,zoom,, -nothing")
        assertEquals(Item.FIND, l.shown[0])
        assertEquals(1, l.shown.count { it == Item.FIND })
        assertEquals(Item.entries.size, l.entries.size)
        assertEquals(0, l.hiddenCount)
    }

    @Test
    fun settingsCanNeverBeHidden() {
        val stored = MenuLayout.decode("-settings,-find")
        assertTrue(Item.SETTINGS in stored.shown)
        assertFalse(Item.FIND in stored.shown)
        val l = MenuLayout.DEFAULT
        assertSame(l, l.show(Item.SETTINGS.ordinal, false))
        assertFalse(Item.WATCH in l.show(Item.WATCH.ordinal, false).shown)
    }

    @Test
    fun movesOutOfRangeChangeNothing() {
        val l = MenuLayout.DEFAULT
        assertSame(l, l.move(0, -1))
        assertSame(l, l.move(0, Item.entries.size))
        assertSame(l, l.move(-1, 2))
        assertSame(l, l.show(99, false))
    }

    @Test
    fun aMoveShiftsTheButtonsBetween() {
        val l = MenuLayout.DEFAULT.move(0, 2)
        assertEquals(listOf(Item.WATCH, Item.AUTO_WATCH, Item.ZOOM, Item.FIND), l.shown.take(4))
    }
}
