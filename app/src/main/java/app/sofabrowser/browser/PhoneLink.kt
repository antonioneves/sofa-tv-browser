package app.sofabrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.sofabrowser.R
import app.sofabrowser.browser.HomeServer.Companion.esc
import app.sofabrowser.browser.HomeServer.Response
import app.sofabrowser.data.Backup
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db

/**
 * Sofa on the phone: a tiny page on the home network (see [HomeServer]) to type on, to pick a bookmark or a recent
 * page, and to download the bookmarks as a file Chrome can import or add those of such a file. What is typed or
 * picked goes to [onText]; each of these is a tab of its own (Open, Bookmarks, History, Export), and how many bookmarks a file added, and the words saying so, to [onImported], both on
 * the main thread. Nothing
 * passes through anywhere but the home network.
 */
class PhoneLink(context: Context, private val onText: (String) -> Unit, private val onImported: (Int, String) -> Unit) {

    private val appContext = context.applicationContext
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
            r.path == "/$BACKUP_NAME" && r.method == "GET" -> Response("200 OK", "application/json; charset=utf-8",
                Backup.write(appContext).toByteArray(Charsets.UTF_8),
                listOf("Content-Disposition: attachment; filename=\"$BACKUP_NAME\""))
            r.path == "/restore" && r.method == "POST" -> {
                if (!Backup.restore(appContext, String(r.body, Charsets.UTF_8))) Response.text(words.notABackup, "422 Unprocessable Content")
                else {
                    main.post { onImported(1, words.restored) }
                    Response.text(words.restored)
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
        val open = c.getString(R.string.phone_tab_open)
        val bookmarks = c.getString(R.string.bookmarks)
        val noBookmarks = c.getString(R.string.bookmarks_empty)
        val history = c.getString(R.string.history)
        val noHistory = c.getString(R.string.history_empty)
        val export = c.getString(R.string.phone_tab_export)
        val file = c.getString(R.string.bookmarks_page_file)
        val fileText = c.getString(R.string.bookmarks_page_file_text)
        val download = c.getString(R.string.bookmarks_page_download)
        val choose = c.getString(R.string.bookmarks_page_choose)
        val working = c.getString(R.string.bookmarks_page_working)
        val notAFile = c.getString(R.string.bookmarks_not_a_file)
        val backup = c.getString(R.string.backup_page_title)
        val backupText = c.getString(R.string.backup_page_text)
        val backupDownload = c.getString(R.string.backup_download)
        val backupChoose = c.getString(R.string.backup_choose)
        val restored = c.getString(R.string.backup_restored)
        val notABackup = c.getString(R.string.backup_not_a_file)
        val failed = c.getString(R.string.bookmarks_send_failed)
        val none = c.getString(R.string.bookmarks_added_none)
        private val res = c.resources
        fun added(n: Int) = if (n == 0) none else res.getQuantityString(R.plurals.bookmarks_added, n, n)
    }

    /** Pages, each a button that sends its address like typing it would, coming back to the [tab] it was picked in. */
    private fun list(sites: List<Pair<String, String>>, tab: String): String = sites.joinToString("", "<form method=\"post\" action=\"#$tab\" class=\"list\">", "</form>") { (title, url) ->
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
#status, #status2 { margin-top: 12px; color: #e6e6e6; }
#file, #restore { display: none; }
label.button { margin-top: 12px; background: #2a2a2a; }
nav { position: sticky; top: 0; display: flex; gap: 6px; padding: 8px 0; margin-bottom: 16px; background: #121212; }
nav a { flex: 1; padding: 10px 4px; border-radius: 12px; background: #1e1e1e; color: #b0b0b0; text-align: center; text-decoration: none; font-size: 16px; }
nav a.on { background: #4f8cff; color: #fff; font-weight: 600; }
section > h2:first-child { margin-top: 0; }
</style></head>
<body><main><h1>${esc(words.title)}</h1>
${if (done != null) "<p class=\"done\">${esc(done)}</p>" else ""}
<nav><a href="#open">${esc(words.open)}</a><a href="#bookmarks">${esc(words.bookmarks)}</a><a href="#history">${esc(words.history)}</a><a href="#export">${esc(words.export)}</a></nav>
<section id="tab-open">
<form method="post" action="#open" accept-charset="utf-8">
<input name="t" type="text" ${if (done == null) "autofocus" else ""} autocomplete="off" autocapitalize="off" placeholder="${esc(words.hint)}">
<button type="submit">${esc(words.send)}</button>
</form>
</section>
<section id="tab-bookmarks">
${if (bookmarks.isEmpty()) "<p>${esc(words.noBookmarks)}</p>" else list(bookmarks, "bookmarks")}
</section>
<section id="tab-history">
${if (recent.isEmpty()) "<p>${esc(words.noHistory)}</p>" else list(recent, "history")}
</section>
<section id="tab-export">
<h2>${esc(words.file)}</h2>
<p>${esc(words.fileText)}</p>
<a class="button" id="download" download="$FILE_NAME">${esc(words.download)}</a>
<label class="button" for="file">${esc(words.choose)}</label>
<input id="file" type="file" accept=".html,.htm,text/html">
<p id="status" role="status"></p>
<h2>${esc(words.backup)}</h2>
<p>${esc(words.backupText)}</p>
<a class="button" id="backup" download="$BACKUP_NAME">${esc(words.backupDownload)}</a>
<label class="button" for="restore">${esc(words.backupChoose)}</label>
<input id="restore" type="file" accept=".json,application/json">
<p id="status2" role="status"></p>
</section>
</main>
<script>
// One tab at a time, the one in the address, so a page picked or a file sent comes back to where it was.
const tabs = ['open', 'bookmarks', 'history', 'export'];
const show = () => {
  const on = tabs.includes(location.hash.slice(1)) ? location.hash.slice(1) : 'open';
  for (const t of tabs) document.getElementById('tab-' + t).hidden = t !== on;
  for (const a of document.querySelectorAll('nav a')) a.classList.toggle('on', a.getAttribute('href') === '#' + on);
};
addEventListener('hashchange', () => { show(); scrollTo(0, 0); });
show();
// Loads the page anew in the same tab: a fresh address, as reloading would send again a page just picked.
const again = () => location.replace(location.pathname + '?' + Date.now() + location.hash);
const status = document.getElementById('status');
document.getElementById('download').href = location.pathname + '/$FILE_NAME';
document.getElementById('backup').href = location.pathname + '/$BACKUP_NAME';
document.getElementById('restore').onchange = async (e) => {
  const file = e.target.files[0];
  const out = document.getElementById('status2');
  if (!file) return;
  out.textContent = ${js(words.working)};
  try {
    const r = await fetch(location.pathname + '/restore', { method: 'POST', body: file });
    out.textContent = await r.text() || ${js(words.failed)};
    if (r.ok) setTimeout(again, 2000);
  } catch (_) {
    out.textContent = ${js(words.failed)};
  }
  e.target.value = '';
};
document.getElementById('file').onchange = async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  status.textContent = ${js(words.working)};
  try {
    const r = await fetch(location.pathname + '/import', { method: 'POST', body: file });
    status.textContent = await r.text() || ${js(words.failed)};
    // The bookmarks above are as they were when the page opened.
    if (r.ok) setTimeout(again, 2000);
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
        const val BACKUP_NAME = "sofa-backup.json"
        private const val RECENT = 20
        // Some browsers keep each site's icon inline in a bookmarks file, so a large one can run to a few megabytes.
        private const val MAX_BODY = 16 * 1024 * 1024
    }
}
