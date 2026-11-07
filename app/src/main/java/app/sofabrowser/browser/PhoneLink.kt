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
import app.sofabrowser.data.Favicons
import app.sofabrowser.ui.Ui
import java.util.Locale

/**
 * Sofa on the phone: a tiny page on the home network (see [HomeServer]) to type on, to pick a bookmark or a recent
 * page, and to download the bookmarks as a file Chrome can import or add those of such a file. What is typed or
 * picked goes to [onText]; each of these is a tab of its own (Open, Bookmarks, History, Export), and how many bookmarks a file added, and the words saying so, to [onImported], both on
 * the main thread. A bookmark or page swiped aside on the phone is removed, and [onChanged] told, also on the main
 * thread. The page looks like the TV's screens, in their theme, with each site's icon from [Favicons].
 * Nothing passes through anywhere but the home network.
 */
class PhoneLink(
    context: Context,
    private val onText: (String) -> Unit,
    private val onImported: (Int, String) -> Unit,
    private val onChanged: () -> Unit = {},
) {

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
            r.path == "/remove" && r.method == "POST" -> {
                val body = String(r.body, Charsets.UTF_8)
                val url = HomeServer.formField(body, "u").orEmpty()
                val removed = when (HomeServer.formField(body, "k")) {
                    "bookmarks" -> { db.removeBookmark(url); true }
                    "history" -> { db.removeHistoryOf(url); true }
                    else -> false
                }
                if (removed) main.post { onChanged() }
                Response.status(if (removed) "204 No Content" else "400 Bad Request")
            }
            r.path.startsWith("/icon/") && r.method == "GET" -> {
                val site = r.path.removePrefix("/icon/").removeSuffix(".png")
                Favicons.get(appContext).png("https://$site/")
                    ?.let { Response("200 OK", "image/png", it, listOf("Cache-Control: max-age=86400")) }
                    ?: Response.status("404 Not Found")
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
        val swipeHint = c.getString(R.string.phone_swipe_hint)
        val removed = c.getString(R.string.phone_removed)
        val undo = c.getString(R.string.phone_undo)
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

    /**
     * Pages, each a button that sends its address like typing it would, coming back to the [tab] it was picked in;
     * swiped aside, it is removed from that tab's list. [empty] stands in once none is left.
     */
    private fun list(sites: List<Pair<String, String>>, tab: String, empty: String): String {
        val none = "<p class=\"empty\"${if (sites.isEmpty()) "" else " hidden"}>${esc(empty)}</p>"
        if (sites.isEmpty()) return none
        return sites.joinToString("", "<p class=\"hint\">${esc(words.swipeHint)}</p>$none<form method=\"post\" action=\"#$tab\" class=\"list\" data-k=\"$tab\">", "</form>") { (title, url) ->
            "<div class=\"row\">$DELETE_ICON$DELETE_ICON<button name=\"t\" value=\"${esc(url)}\">${tile(title, url)}<span class=\"t\"><b>${esc(title.ifBlank { url })}</b><span>${esc(UrlInput.display(url))}</span></span></button></div>"
        }
    }

    /** The site's letter tile, as the TV draws it, under its icon if the TV has one (see the script). */
    private fun tile(title: String, url: String): String {
        val site = Favicons.key(url)
        val letter = (title.firstOrNull { it.isLetterOrDigit() } ?: site?.firstOrNull() ?: '?').uppercaseChar()
        val icon = if (site != null) "<img data-i=\"$site\" alt=\"\">" else ""
        return "<i class=\"tile\" style=\"background:${"#%06x".format(Favicons.tileColor(url) and 0xFFFFFF)}\">${esc(letter.toString())}$icon</i>"
    }

    /** The page's colours: the TV's, in its theme, light or dark. */
    private fun colors(): String {
        val p = Ui.p
        return ":root { color-scheme: ${if (p.dark) "dark" else "light"}; --top: ${css(p.bgTop)}; --middle: ${css(p.bgMid)}; " +
            "--bottom: ${css(p.bgBottom)}; --bar: ${css(p.bgMid and 0xFFFFFF or (0xE6 shl 24))}; --text: ${css(p.text)}; " +
            "--mid: ${css(p.textMid)}; --weak: ${css(p.textWeak)}; --hint: ${css(p.textHint)}; --card: ${css(p.card)}; " +
            "--card-on: ${css(p.cardFocused)}; --focus: ${css(p.focus)}; --on-focus: ${css(p.onFocus)}; --border: ${css(p.border)}; " +
            "--brand: ${css(Ui.BRAND)}; }"
    }

    private fun css(c: Int) = "rgba(${c shr 16 and 255}, ${c shr 8 and 255}, ${c and 255}, ${"%.3f".format(Locale.US, (c ushr 24) / 255f)})"

    private fun html(done: String?): String {
        val bookmarks = db.bookmarks().map { it.title to it.url }
        val recent = db.history(RECENT * 4).asSequence()
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .distinctBy { it.url }.take(RECENT).map { it.title to it.url }.toList()
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="${css(Ui.p.bgTop)}">
<title>${esc(words.title)}</title>
<style>${colors()}
$STYLE
</style></head>
<body><main>
<header>$LOGO<h1>${esc(words.title)}</h1></header>
${if (done != null) "<p class=\"done\">${esc(done)}</p>" else ""}
<nav><a href="#open">$SEARCH_ICON<span>${esc(words.open)}</span></a><a href="#bookmarks">$BOOKMARKS_ICON<span>${esc(words.bookmarks)}</span></a><a href="#history">$HISTORY_ICON<span>${esc(words.history)}</span></a><a href="#export">$EXPORT_ICON<span>${esc(words.export)}</span></a></nav>
<section id="tab-open">
<form method="post" action="#open" accept-charset="utf-8">
<div class="search">$SEARCH_ICON<input name="t" type="text" ${if (done == null) "autofocus" else ""} autocomplete="off" autocapitalize="off" placeholder="${esc(words.hint)}"></div>
<button type="submit">${esc(words.send)}</button>
</form>
</section>
<section id="tab-bookmarks">
${list(bookmarks, "bookmarks", words.noBookmarks)}
</section>
<section id="tab-history">
${list(recent, "history", words.noHistory)}
</section>
<section id="tab-export">
<div class="card">
<h2>${esc(words.file)}</h2>
<p>${esc(words.fileText)}</p>
<a class="button" id="download" download="$FILE_NAME">${esc(words.download)}</a>
<label class="button" for="file">${esc(words.choose)}</label>
<input id="file" type="file" accept=".html,.htm,text/html">
<p id="status" role="status"></p>
</div>
<div class="card">
<h2>${esc(words.backup)}</h2>
<p>${esc(words.backupText)}</p>
<a class="button" id="backup" download="$BACKUP_NAME">${esc(words.backupDownload)}</a>
<label class="button" for="restore">${esc(words.backupChoose)}</label>
<input id="restore" type="file" accept=".json,application/json">
<p id="status2" role="status"></p>
</div>
</section>
<div id="undo" role="status" hidden><span>${esc(words.removed)}</span><button type="button">${esc(words.undo)}</button></div>
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
// Each site's icon, if the TV has one; its letter stays otherwise. Those of hidden tabs load once shown.
for (const img of document.querySelectorAll('img[data-i]')) {
  img.loading = 'lazy';
  img.onload = () => img.parentNode.classList.add('icon');
  img.onerror = () => img.remove();
  img.src = location.pathname + '/icon/' + img.dataset.i + '.png';
}
// A page swiped aside goes from its list; it is removed on the TV a few seconds later, so Undo can bring it back.
const bar = document.getElementById('undo');
let pending = null, timer = 0;
const settle = () => {
  if (!pending) return;
  const { row, list, url } = pending;
  pending = null;
  clearTimeout(timer);
  bar.hidden = true;
  navigator.sendBeacon(location.pathname + '/remove', new URLSearchParams({ k: list.dataset.k, u: url }));
  row.remove();
  if (!list.querySelector('.row')) {
    list.parentNode.querySelector('.empty').hidden = false;
    list.parentNode.querySelector('.hint').hidden = true;
  }
};
const remove = (row, dx) => {
  settle();
  const b = row.querySelector('button');
  b.style.transform = 'translateX(' + (dx < 0 ? -110 : 110) + '%)';
  row.style.height = row.offsetHeight + 'px';
  row.offsetHeight;
  row.classList.add('gone');
  pending = { row, list: row.parentNode, url: b.value };
  bar.hidden = false;
  timer = setTimeout(settle, 5000);
};
bar.querySelector('button').onclick = () => {
  if (!pending) return;
  const { row } = pending;
  pending = null;
  clearTimeout(timer);
  bar.hidden = true;
  row.classList.remove('gone', 'far', 'sliding');
  row.style.height = '';
  row.querySelector('button').style.transform = '';
};
addEventListener('pagehide', settle);
for (const row of document.querySelectorAll('.row')) {
  const b = row.querySelector('button');
  let x0 = null, y0 = 0, dx = 0, sliding = false;
  b.addEventListener('pointerdown', (e) => { x0 = e.clientX; y0 = e.clientY; dx = 0; sliding = false; });
  b.addEventListener('pointermove', (e) => {
    if (x0 === null) return;
    dx = e.clientX - x0;
    if (!sliding) {
      if (Math.abs(dx) < 12) return;
      // Mostly up or down: that's scrolling, not a swipe.
      if (Math.abs(e.clientY - y0) > Math.abs(dx)) { x0 = null; return; }
      sliding = true;
      b.setPointerCapture(e.pointerId);
      row.classList.add('sliding');
    }
    b.style.transform = 'translateX(' + dx + 'px)';
    row.classList.toggle('far', Math.abs(dx) > row.offsetWidth * 0.35);
  });
  const end = () => {
    if (x0 === null) return;
    x0 = null;
    if (!sliding) return;
    if (Math.abs(dx) > row.offsetWidth * 0.35) return remove(row, dx);
    row.classList.remove('sliding', 'far');
    b.style.transform = '';
  };
  b.addEventListener('pointerup', end);
  b.addEventListener('pointercancel', end);
  // A swipe is not a tap: it doesn't send the page to the TV.
  b.addEventListener('click', (e) => { if (sliding) { e.preventDefault(); sliding = false; } });
}
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

        /** The TV's look on a phone: its background, cards and white focus, large and easy to tap. */
        private const val STYLE = """
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html { min-height: 100%; background: var(--bottom) radial-gradient(140% 80% at 50% 0, var(--top), var(--middle) 60%, var(--bottom)) no-repeat fixed; }
body { margin: 0; padding: 20px 16px 88px; color: var(--text); font: 17px/1.45 Roboto, system-ui, sans-serif; }
main { max-width: 560px; margin: 0 auto; }
svg { fill: currentColor; flex: none; }
header { display: flex; align-items: center; gap: 12px; margin: 0 4px 20px; }
header svg { width: 36px; height: 36px; color: var(--brand); }
h1 { font-size: 21px; font-weight: 500; margin: 0; }
h2 { font-size: 18px; font-weight: 500; margin: 0 0 6px; }
p { margin: 0 0 14px; color: var(--mid); }
.empty { margin: 24px 4px; text-align: center; }
.done { margin: 0 0 16px; padding: 12px 16px; border-radius: 16px; background: var(--card); color: var(--text); border-inline-start: 4px solid var(--brand); }
nav { position: sticky; top: 8px; z-index: 1; display: flex; gap: 4px; padding: 4px; margin: 0 0 16px; border-radius: 22px; background: var(--bar); -webkit-backdrop-filter: blur(16px); backdrop-filter: blur(16px); }
nav a { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 8px 2px 6px; border-radius: 18px; color: var(--mid); text-decoration: none; font-size: 13px; }
nav a span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
nav a svg { width: 24px; height: 24px; }
nav a.on { background: var(--focus); color: var(--on-focus); font-weight: 500; }
.search { position: relative; margin-bottom: 12px; }
.search svg { position: absolute; inset-inline-start: 18px; top: 50%; width: 24px; height: 24px; margin-top: -12px; color: var(--weak); pointer-events: none; }
input { display: block; width: 100%; font: inherit; color: inherit; margin: 0; padding: 14px 20px; padding-inline-start: 54px; border: 2px solid transparent; border-radius: 999px; background: var(--card); outline: none; }
input:focus { border-color: var(--border); background: var(--card-on); }
input::placeholder { color: var(--hint); }
button, .button { display: block; width: 100%; font: inherit; font-weight: 500; margin: 0; padding: 14px 20px; border: 0; border-radius: 999px; background: var(--focus); color: var(--on-focus); text-align: center; text-decoration: none; cursor: pointer; }
label.button { margin-top: 10px; background: var(--card); color: var(--text); }
button:active, .button:active { opacity: .8; }
.hint { margin: 0 4px 10px; font-size: 14px; color: var(--weak); }
.row { position: relative; margin-bottom: 8px; border-radius: 16px; overflow: hidden; transition: height .2s, margin .2s, opacity .2s; }
.row > svg { position: absolute; top: 50%; width: 24px; height: 24px; margin-top: -12px; color: #fff; opacity: 0; }
.row > svg:first-child { left: 20px; }
.row > svg:nth-child(2) { right: 20px; }
.row.sliding { background: #a8323a; }
.row.sliding > svg { opacity: .6; }
.row.far { background: #d93a44; }
.row.far > svg { opacity: 1; }
.row.gone { height: 0 !important; margin: 0; opacity: 0; }
.list button { position: relative; display: flex; align-items: center; gap: 14px; padding: 10px 14px; border-radius: 16px; background: var(--card); color: var(--text); font-weight: normal; text-align: start; touch-action: pan-y; transition: transform .2s; user-select: none; -webkit-user-select: none; }
.row.sliding:not(.gone) button { transition: none; }
.row.sliding button { background: linear-gradient(var(--card-on), var(--card-on)), var(--middle); }
#undo { position: fixed; left: 16px; right: 16px; bottom: 16px; z-index: 2; max-width: 528px; margin: 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 6px 6px 20px; border-radius: 999px; background: var(--focus); color: var(--on-focus); box-shadow: 0 6px 24px rgba(0, 0, 0, .35); }
#undo[hidden] { display: none; }
#undo button { width: auto; padding: 10px 18px; background: none; color: inherit; font-weight: 600; }
.list button:active, .list button:focus-visible { opacity: 1; background: var(--card-on); outline: none; }
.t { display: flex; flex-direction: column; min-width: 0; }
.t b, .t span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.t b { font-weight: 500; }
.t span { font-size: 14px; color: var(--weak); }
.tile { position: relative; flex: none; display: grid; place-items: center; width: 40px; height: 40px; border-radius: 9px; overflow: hidden; color: #fff; font: 500 22px/1 Roboto, system-ui, sans-serif; }
.tile img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.tile.icon { background: none !important; color: transparent; }
.card { margin-bottom: 12px; padding: 18px 16px 16px; border-radius: 20px; background: var(--card); }
.card label.button { background: var(--card-on); }
#status, #status2 { margin: 12px 0 0; color: var(--text); }
#status:empty, #status2:empty { display: none; }
#file, #restore { display: none; }
"""

        // The TV's own icons (res/drawable), to sit in the page.
        private const val LOGO = """<svg viewBox="40 40 880 880" aria-hidden="true"><path d="M160,800Q110,800 75,765Q40,730 40,680L40,480Q40,447 63.5,423.5Q87,400 120,400Q153,400 176.5,423.5Q200,447 200,480L200,640L760,640L760,480Q760,447 783.5,423.5Q807,400 840,400Q873,400 896.5,423.5Q920,447 920,480L920,680Q920,730 885,765Q850,800 800,800L160,800ZM280,560L280,480Q280,427 245.5,387Q211,347 160,332L160,280Q160,230 195,195Q230,160 280,160L680,160Q730,160 765,195Q800,230 800,280L800,332Q747,343 713.5,384.5Q680,426 680,480L680,560L280,560Z"/></svg>"""
        private const val SEARCH_ICON = """<svg viewBox="0 0 960 960" aria-hidden="true"><path d="M784,840L532,588Q502,612 463,626Q424,640 380,640Q271,640 195.5,564.5Q120,489 120,380Q120,271 195.5,195.5Q271,120 380,120Q489,120 564.5,195.5Q640,271 640,380Q640,424 626,463Q612,502 588,532L840,784L784,840ZM380,560Q455,560 507.5,507.5Q560,455 560,380Q560,305 507.5,252.5Q455,200 380,200Q305,200 252.5,252.5Q200,305 200,380Q200,455 252.5,507.5Q305,560 380,560Z"/></svg>"""
        private const val BOOKMARKS_ICON = """<svg viewBox="0 0 960 960" aria-hidden="true"><path d="M120,920L120,280Q120,247 143.5,223.5Q167,200 200,200L600,200Q633,200 656.5,223.5Q680,247 680,280L680,920L400,800L120,920ZM200,798L400,712L600,798L600,280L200,280L200,798ZM760,800L760,120L240,120L240,40L760,40Q793,40 816.5,63.5Q840,87 840,120L840,800L760,800Z"/></svg>"""
        private const val HISTORY_ICON = """<svg viewBox="0 0 960 960" aria-hidden="true"><path d="M480,840Q342,840 239.5,748.5Q137,657 122,520L204,520Q218,624 296.5,692Q375,760 480,760Q597,760 678.5,678.5Q760,597 760,480Q760,363 678.5,281.5Q597,200 480,200Q411,200 351,232Q291,264 250,320L360,320L360,400L120,400L120,160L200,160L200,254Q251,190 324.5,155Q398,120 480,120Q555,120 620.5,148.5Q686,177 734.5,225.5Q783,274 811.5,339.5Q840,405 840,480Q840,555 811.5,620.5Q783,686 734.5,734.5Q686,783 620.5,811.5Q555,840 480,840ZM592,648L440,496L440,280L520,280L520,464L648,592L592,648Z"/></svg>"""
        private const val DELETE_ICON = """<svg viewBox="0 0 960 960" aria-hidden="true"><path d="M280,840Q247,840 223.5,816.5Q200,793 200,760L200,240L160,240L160,160L360,160L360,120L600,120L600,160L800,160L800,240L760,240L760,760Q760,793 736.5,816.5Q713,840 680,840L280,840ZM680,240L280,240L280,760L680,760L680,240ZM360,680L440,680L440,320L360,320L360,680ZM520,680L600,680L600,320L520,320L520,680Z"/></svg>"""
        private const val EXPORT_ICON ="""<svg viewBox="0 -960 960 960" aria-hidden="true"><path d="M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v80h-80v-80H200v560h560v-80h80v80q0 33-23.5 56.5T760-120H200Zm480-160-56-56 103-104H360v-80h367L624-624l56-56 200 200-200 200Z"/></svg>"""
        // Some browsers keep each site's icon inline in a bookmarks file, so a large one can run to a few megabytes.
        private const val MAX_BODY = 16 * 1024 * 1024
    }
}
