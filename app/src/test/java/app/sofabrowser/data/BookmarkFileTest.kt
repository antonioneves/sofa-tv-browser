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
    fun chromeFilesAreReadInOrderWithTheirFolders() {
        assertEquals(listOf(
            // The bookmarks bar is opened up into the top; its folders stay folders.
            Entry("YouTube", "https://www.youtube.com/", 1_700_000_001_000),
            Entry("Fish & chips <3", "https://example.com/?a=1&b=2", 1_700_000_003_000, listOf("Cooking")),
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
    fun foldersAreWrittenAndReadBack() {
        val entries = listOf(
            Entry("Top", "https://top.example/", 1_700_000_000_000),
            Entry("Tech", "https://tech.example/", 1_700_000_001_000, listOf("News", "Tech")),
            Entry("News", "https://news.example/", 1_700_000_002_000, listOf("News")),
        )
        val file = BookmarkFile.write(entries, listOf(BookmarkFile.Folder(listOf("Empty"), 1_700_000_000_000)))
        assertTrue("<H3 ADD_DATE=\"1700000000\">Empty</H3>" in file)
        // Folders come before a list's bookmarks, so what is read back is in the file's order.
        assertEquals(listOf(entries[2], entries[1], entries[0]).sortedBy { it.url }, BookmarkFile.read(file)!!.sortedBy { it.url })
    }

    @Test
    fun aFolderWithoutANameOrAnUnclosedNameIsHandled() {
        val file = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<DL><p>\n" +
            "<DT><H3></H3>\n<DL><p>\n<DT><A HREF=\"https://a.example/\">A</A>\n</DL><p>\n" +
            "<DT><H3>Films\n<DL><p>\n<DT><A HREF=\"https://b.example/\">B</A>\n</DL><p>\n" +
            "<DT><A HREF=\"https://c.example/\">C</A>\n</DL>"
        assertEquals(listOf(
            Entry("A", "https://a.example/", 0),
            Entry("B", "https://b.example/", 0, listOf("Films")),
            Entry("C", "https://c.example/", 0),
        ), BookmarkFile.read(file))
    }

    @Test
    fun otherFilesAreRefused() {
        assertNull(BookmarkFile.read("<!DOCTYPE html><html><a href=\"https://example.com/\">x</a></html>"))
        assertNull(BookmarkFile.read(""))
    }

    /** Trimmed from files Firefox 160 and Chromium 158 wrote themselves (example data). */
    private val firefoxWritten = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks Menu</H1>

        <DL><p>
            <DT><H3 ADD_DATE="1795280814" LAST_MODIFIED="1795280814">News &amp; weather</H3>
            <DL><p>
                <DT><A HREF="https://example.org/news?a=1&amp;b=2" ADD_DATE="1795280814" LAST_MODIFIED="1795280814">News &quot;quoted&quot; &lt;tag&gt;</A>
                <HR>        <DT><H3 ADD_DATE="1795280814" LAST_MODIFIED="1795280814">Deep</H3>
                <DL><p>
                    <DT><A HREF="https://example.net/deep" ADD_DATE="1795280814" LAST_MODIFIED="1795280814">Ünïcödé ✓ 日本</A>
                    <DT><H3 ADD_DATE="1795280814" LAST_MODIFIED="1795280814">Empty folder</H3>
                    <DL><p>
                    </DL><p>
                </DL><p>
            </DL><p>
            <DT><H3 ADD_DATE="1795280739" LAST_MODIFIED="1795280833" UNFILED_BOOKMARKS_FOLDER="true">Other Bookmarks</H3>
            <DL><p>
                <DT><H3 ADD_DATE="1795280833" LAST_MODIFIED="1795280833">Other folder</H3>
                <DL><p>
                    <DT><A HREF="https://example.com/other/inner" ADD_DATE="1795280833" LAST_MODIFIED="1795280833"></A>
                </DL><p>
            </DL><p>
        </DL>
    """.trimIndent()

    private val chromiumWritten = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1795280864" LAST_MODIFIED="1795280878" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
            <DL><p>
                <DT><H3 ADD_DATE="1795280878" LAST_MODIFIED="1795280878">News &amp; weather</H3>
                <DL><p>
                    <DT><A HREF="https://example.org/news?a=1&b=2" ADD_DATE="1795280878">News &quot;quoted&quot; &lt;tag&gt;</A>
                </DL><p>
                <DT><A HREF="javascript:alert(1)" ADD_DATE="1795280878">A bookmarklet</A>
            </DL><p>
            <DT><A HREF="https://example.com/other" ADD_DATE="1795280878">Other one</A>
        </DL><p>
    """.trimIndent()

    @Test fun filesTheBrowsersWroteThemselves() {
        // Firefox puts the next line after a separator on the separator's own line.
        val firefox = BookmarkFile.read(firefoxWritten)!!
        assertEquals(listOf(
            Triple("News \"quoted\" <tag>", "https://example.org/news?a=1&b=2", listOf("News & weather")),
            Triple("Ünïcödé ✓ 日本", "https://example.net/deep", listOf("News & weather", "Deep")),
            Triple("https://example.com/other/inner", "https://example.com/other/inner", listOf("Other Bookmarks", "Other folder")),
        ), firefox.map { Triple(it.title, it.url, it.folder) })
        assertEquals(1_795_280_814_000L, firefox.first().created)
        // Chromium leaves "&" in an address as it is, and writes "Other bookmarks" at the top without a folder.
        val chromium = BookmarkFile.read(chromiumWritten)!!
        assertEquals(listOf(
            Triple("News \"quoted\" <tag>", "https://example.org/news?a=1&b=2", listOf("News & weather")),
            Triple("Other one", "https://example.com/other", emptyList()),
        ), chromium.map { Triple(it.title, it.url, it.folder) })
    }
}
