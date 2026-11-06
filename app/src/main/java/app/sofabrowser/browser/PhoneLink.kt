package app.sofabrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.sofabrowser.R
import app.sofabrowser.browser.HomeServer.Companion.esc
import app.sofabrowser.browser.HomeServer.Response
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db

/**
 * Sofa on the phone: a tiny page on the home network (see [HomeServer]) to type on, to pick a bookmark or a recent
 * page, and to download the bookmarks as a file Chrome can import or add those of such a file. What is typed or
 * picked goes to [onText], and how many bookmarks a file added, and the words saying so, to [onImported], both on
 * the main thread. Nothing
 * passes through anywhere but the home network.
 */
class PhoneLink(context: Context, private val onText: (String) -> Unit, private val onImported: (Int, String) -> Unit) {

    private val db = Db.get(context)
    private val words = Words(context)
    private val main = Handler(Looper.getMainLooper())
    private val server = HomeServer(MAX_BODY) { r ->
        when {
            r.path.isEmpty() && r.method == "GET" -> Response.html(html(null))
            r.path.isEmpty() && r.method == "POST" -> {
                val text = HomeServer.formField(String(r.body, Charsets.UTF_8), "t")?.trim().orEmpty()
                if (text.isEmpty()) Response.html(html(null))
                else Response.html(html(words.sent)).also { main.post { onText(text) } }
            }
            r.path == "/$FILE_NAME" && r.method == "GET" -> Response("200 OK", "text/html; charset=utf-8",
                BookmarkFile.write(db.bookmarkEntries()).toByteArray(Charsets.UTF_8),
                listOf("Content-Disposition: attachment; filename=\"$FILE_NAME\""))
            r.path == "/import" && r.method == "POST" -> {
                val entries = BookmarkFile.read(String(r.body, Charsets.UTF_8))
                if (entries == null) Response.text(words.notAFile, "422 Unprocessable Content")
                else {
                    val added = db.importBookmarks(entries)
                    val message = words.added(added)
                    main.post { onImported(added, message) }
                    Response.text(message)
                }
            }
            else -> Response.status("404 Not Found")
        }
    }

    /** Starts serving; the address for the phone, or null without a home network. */
    fun start(context: Context): String? = server.start(context)

    fun stop() = server.stop()

    /** The page's words, in the TV's language, read once on the main thread. */
    private class Words(c: Context) {
        val title = c.getString(R.string.phone_page_title)
        val hint = c.getString(R.string.search_hint)
        val send = c.getString(R.string.phone_send)
        val sent = c.getString(R.string.phone_sent)
        val bookmarks = c.getString(R.string.bookmarks)
        val noBookmarks = c.getString(R.string.bookmarks_empty)
        val recent = c.getString(R.string.bookmarks_page_recent)
        val file = c.getString(R.string.bookmarks_page_file)
        val fileText = c.getString(R.string.bookmarks_page_file_text)
        val download = c.getString(R.string.bookmarks_page_download)
        val choose = c.getString(R.string.bookmarks_page_choose)
        val working = c.getString(R.string.bookmarks_page_working)
        val notAFile = c.getString(R.string.bookmarks_not_a_file)
        val failed = c.getString(R.string.bookmarks_send_failed)
        val none = c.getString(R.string.bookmarks_added_none)
        private val res = c.resources
        fun added(n: Int) = if (n == 0) none else res.getQuantityString(R.plurals.bookmarks_added, n, n)
    }

    /** Pages, each a button that sends its address like typing it would. */
    private fun list(sites: List<Pair<String, String>>): String = sites.joinToString("", "<form method=\"post\" class=\"list\">", "</form>") { (title, url) ->
        "<button name=\"t\" value=\"${esc(url)}\"><b>${esc(title.ifBlank { url })}</b><span>${esc(UrlInput.display(url))}</span></button>"
    }

    private fun html(done: String?): String {
        val bookmarks = db.bookmarks().map { it.title to it.url }
        val recent = db.history(RECENT * 4).asSequence()
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .distinctBy { it.url }.take(RECENT).map { it.title to it.url }.toList()
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(words.title)}</title>
<style>${HomeServer.STYLE}
.done { color: #e6e6e6; background: #1d3a24; border-radius: 12px; padding: 12px 16px; }
.list button { display: flex; flex-direction: column; align-items: flex-start; text-align: start; margin-bottom: 8px; background: #1e1e1e; color: inherit; font-weight: normal; }
.list b, .list span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.list span { font-size: 15px; color: #9a9a9a; }
#status { margin-top: 12px; color: #e6e6e6; }
#file { display: none; }
label.button { margin-top: 12px; background: #2a2a2a; }
</style></head>
<body><main><h1>${esc(words.title)}</h1>
${if (done != null) "<p class=\"done\">${esc(done)}</p>" else ""}
<form method="post" accept-charset="utf-8">
<input name="t" type="text" ${if (done == null) "autofocus" else ""} autocomplete="off" autocapitalize="off" placeholder="${esc(words.hint)}">
<button type="submit">${esc(words.send)}</button>
</form>
<h2>${esc(words.bookmarks)}</h2>
${if (bookmarks.isEmpty()) "<p>${esc(words.noBookmarks)}</p>" else list(bookmarks)}
${if (recent.isEmpty()) "" else "<h2>${esc(words.recent)}</h2>" + list(recent)}
<h2>${esc(words.file)}</h2>
<p>${esc(words.fileText)}</p>
<a class="button" id="download" download="$FILE_NAME">${esc(words.download)}</a>
<label class="button" for="file">${esc(words.choose)}</label>
<input id="file" type="file" accept=".html,.htm,text/html">
<p id="status" role="status"></p>
</main>
<script>
const status = document.getElementById('status');
document.getElementById('download').href = location.pathname + '/$FILE_NAME';
document.getElementById('file').onchange = async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  status.textContent = ${js(words.working)};
  try {
    const r = await fetch(location.pathname + '/import', { method: 'POST', body: file });
    status.textContent = await r.text() || ${js(words.failed)};
    // The bookmarks above are as they were when the page opened.
    if (r.ok) setTimeout(() => location.replace(location.pathname), 2000);
  } catch (_) {
    status.textContent = ${js(words.failed)};
  }
  e.target.value = '';
};
</script></body></html>"""
    }

    /** [s] as a JavaScript string inside the page's script. */
    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")
        .replace("\n", "\\n") + "\""

    companion object {
        const val FILE_NAME = "bookmarks.html"
        private const val RECENT = 20
        // Some browsers keep each site's icon inline in a bookmarks file, so a large one can run to a few megabytes.
        private const val MAX_BODY = 16 * 1024 * 1024
    }
}
