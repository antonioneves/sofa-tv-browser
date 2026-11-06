package app.sofabrowser.browser

import android.content.Context

/**
 * Reader view: Mozilla's Readability (bundled in assets/reader, Apache-2.0) finds the page's article, and the page is
 * replaced in place by just its text, in large type on a dark page. Nothing leaves the TV; reloading brings the page
 * back as it was.
 */
object Reader {

    /** What [script] answers when the page has no article to read. */
    const val NONE = "none"

    private var readability: String? = null

    /** Finds the article and puts it in place of the page; evaluates to "ok" or [NONE]. */
    fun script(context: Context): String {
        val source = readability ?: context.assets.open("reader/Readability.js").bufferedReader().use { it.readText() }
            .also { readability = it }
        return "(function () {\n$source\n$SHOW\n})();"
    }

    private const val CSS = """
:root { color-scheme: dark; }
html, body { background: #121212; }
body {
  margin: 0 auto; max-width: 70vw; padding: 6vh 0 60vh;
  font: clamp(20px, 2.4vw, 56px)/1.6 Georgia, 'Noto Serif', serif; color: #e6e6e6;
  overflow-wrap: break-word;
}
#sofa-reader-site { color: #9aa0a6; font: 0.7em/1.4 sans-serif; text-transform: uppercase; letter-spacing: 0.05em; }
h1#sofa-reader-title { font-size: 1.6em; line-height: 1.25; margin: 0.2em 0; }
#sofa-reader-byline { color: #9aa0a6; font: 0.75em/1.4 sans-serif; margin-bottom: 1.5em; }
h2, h3, h4 { line-height: 1.3; }
a { color: #8ab4f8; text-decoration: none; }
img, video, picture, svg, figure, table { max-width: 100%; height: auto; }
figure { margin: 1em 0; }
figcaption { color: #9aa0a6; font-size: 0.7em; }
pre, code { white-space: pre-wrap; font-size: 0.8em; }
blockquote { border-left: 4px solid #555; margin-left: 0; padding-left: 1em; color: #cfcfcf; }
"""

    private val SHOW = """
  var a = new Readability(document.cloneNode(true), { charThreshold: 300 }).parse();
  if (!a || !a.content || !(a.textContent || '').trim()) return '$NONE';
  var html = document.createElement('html');
  html.lang = a.lang || document.documentElement.lang || '';
  if (a.dir) html.dir = a.dir;
  var head = document.createElement('head');
  var meta = document.createElement('meta');
  meta.name = 'viewport';
  meta.content = 'width=device-width, initial-scale=1';
  head.appendChild(meta);
  var title = document.createElement('title');
  title.textContent = a.title || document.title;
  head.appendChild(title);
  var style = document.createElement('style');
  style.textContent = ${org.json.JSONObject.quote(CSS)};
  head.appendChild(style);
  var body = document.createElement('body');
  var add = function (tag, id, text) {
    if (!text) return;
    var e = document.createElement(tag);
    e.id = id;
    e.textContent = text;
    body.appendChild(e);
  };
  add('div', 'sofa-reader-site', a.siteName || location.hostname.replace(/^www\./, ''));
  add('h1', 'sofa-reader-title', a.title);
  add('div', 'sofa-reader-byline', a.byline);
  var article = document.createElement('article');
  article.innerHTML = a.content;
  body.appendChild(article);
  html.appendChild(head);
  html.appendChild(body);
  document.adoptedStyleSheets = [];
  document.replaceChild(html, document.documentElement);
  window.scrollTo(0, 0);
  return 'ok';
"""
}
