package app.sofabrowser.browser

/**
 * The page side of "Load images last": while a page's images are held back they would show as broken-image boxes,
 * so every image stays invisible, keeping its space, until it has loaded. One style rule and one listener, nothing
 * that looks through the page, so the page loads as fast as without it. Some time after the page has loaded the rule
 * goes, so an image the listener could not see load (one a page loaded off to the side and then put in) still shows.
 */
object ImagesLast {
    /** How long after the page's load event images that never said they loaded are shown anyway. */
    private const val GIVE_UP_MS = 10_000

    /** The script for every frame, marking loaded images with the attribute [mark]. */
    fun script(mark: String) = """
(() => {
  const M = "data-$mark";
  const style = document.createElement('style');
  style.textContent = 'img:not([' + M + ']){visibility:hidden!important}';
  (document.head || document.documentElement).appendChild(style);
  document.addEventListener('load', (e) => { const t = e.target; if (t && t.tagName === 'IMG') t.setAttribute(M, ''); }, true);
  const done = () => setTimeout(() => style.remove(), $GIVE_UP_MS);
  if (document.readyState === 'complete') done(); else addEventListener('load', done, { once: true });
})();
""".trimIndent()
}
