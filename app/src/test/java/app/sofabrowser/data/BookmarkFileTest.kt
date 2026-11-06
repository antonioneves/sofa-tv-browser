package app.sofabrowser.data

import app.sofabrowser.data.BookmarkFile.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkFileTest {

    // As Chrome exports it: folders, the bookmarks bar, icons and a javascript: bookmarklet.
    private val chrome = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
<!-- This is an automatically generated file.
     It will be read and overwritten.
     DO NOT EDIT! -->
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks</H1>
<DL><p>
    <DT><H3 ADD_DATE="1700000000" LAST_MODIFIED="1700000100" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
    <DL><p>
        <DT><A HREF="https://www.youtube.com/" ADD_DATE="1700000001" ICON="data:image/png;base64,iVBORw0KGgo=">YouTube</A>
        <DT><H3 ADD_DATE="1700000002" LAST_MODIFIED="0">Cooking</H3>
        <DL><p>
            <DT><A HREF="https://example.com/?a=1&amp;b=2" ADD_DATE="1700000003">Fish &amp; chips &lt;3</A>
        </DL><p>
        <DT><A HREF="javascript:alert(1)" ADD_DATE="1700000004">Bookmarklet</A>
    </DL><p>
    <DT><A HREF="https://www.youtube.com/" ADD_DATE="1700000005">YouTube again</A>
    <DT><A HREF="http://192.168.1.20:8096/" ADD_DATE="1700000006"></A>
</DL><p>
"""

    @Test
    fun chromeFilesAreReadFlatInOrder() {
        assertEquals(listOf(
            Entry("YouTube", "https://www.youtube.com/", 1_700_000_001_000),
            Entry("Fish & chips <3", "https://example.com/?a=1&b=2", 1_700_000_003_000),
            Entry("http://192.168.1.20:8096/", "http://192.168.1.20:8096/", 1_700_000_006_000),
        ), BookmarkFile.read(chrome))
    }

    @Test
    fun whatSofaWritesReadsBackTheSame() {
        val entries = listOf(
            Entry("Fish & chips \"quoted\" <3", "https://example.com/?a=1&b=2", 1_700_000_003_000),
            Entry("Plex", "https://app.plex.tv/desktop/#!/", 0),
        )
        val file = BookmarkFile.write(entries)
        assertTrue(file.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue("ADD_DATE=\"1700000003\"" in file)
        assertEquals(entries, BookmarkFile.read(file))
    }

    @Test
    fun otherToolsDatesAndQuotesAreUnderstood() {
        val file = "\uFEFF<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<DL><p>\n" +
            "<DT><a href='https://a.example/' add_date='1700000000000'>A</a>\n" +
            "<DT><A HREF=\"https://b.example/\" ADD_DATE=\"1700000000000000\">B</A>\n" +
            "<DT><A HREF=\"https://c.example/\">C &#233;t&#xE9;</A>\n</DL>"
        assertEquals(listOf(
            Entry("A", "https://a.example/", 1_700_000_000_000),
            Entry("B", "https://b.example/", 1_700_000_000_000),
            Entry("C été", "https://c.example/", 0),
        ), BookmarkFile.read(file))
    }

    @Test
    fun otherFilesAreRefused() {
        assertNull(BookmarkFile.read("<!DOCTYPE html><html><a href=\"https://example.com/\">x</a></html>"))
        assertNull(BookmarkFile.read(""))
    }
}
