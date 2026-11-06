package app.sofabrowser.browser


/**
 * Turns cookie banners down. A small script in every frame (banners often live in an iframe) looks, as the page
 * loads, for a rejection button: the ones the common consent platforms use, or any button that says "Reject all",
 * "Decline", "Only necessary" and the like in a few languages, inside something that talks about cookies or consent,
 * also within the shadow roots some banners are drawn in.
 * It clicks that and nothing else: never "Accept", never "Manage", and it gives up after about 13 seconds or three
 * clicks. A button must be visible and sit near cookie or consent wording, so "Decline" on an invitation stays alone.
 *
 * A site is looked at once: after a click (the site keeps the answer in its cookies) or after a full look found
 * nothing in a page that finished loading, the script tells Sofa, which leaves that site alone for [RECHECK_DAYS] days, or until its cookies are
 * cleared. The channel has a random name per WebView ([PageKey]); a page that finds it can only stop the look on its
 * own site, which wording its button some other way does as well. A site that does so keeps its banner.
 */
object CookieBanners {
    /** Days after which a site that was looked at is looked at again, in case it asks once more. */
    const val RECHECK_DAYS = 30L
    private const val RECHECK_MS = RECHECK_DAYS * 24 * 60 * 60 * 1000

    /** When the check last learned to find more banners (2026-11-07, inside shadow roots): sites looked at before are looked at again. */
    private const val LEARNED_MS = 1_794_009_336_000L

    /** The sites of [looked] (site -> when, in ms) to leave alone at [now]. */
    fun current(looked: Map<String, String>, now: Long): List<String> =
        looked.filterValues { (it.toLongOrNull() ?: 0) in maxOf(now - RECHECK_MS + 1, LEARNED_MS)..now }.keys.sorted()

    /** The script for every frame, skipping pages of the [done] sites and reporting to [channel]. */
    fun script(channel: String, done: Collection<String>) = """
(() => {
  if (window.__sofaConsent) return;
  try { Object.defineProperty(window, '__sofaConsent', { value: 1 }); } catch (e) { return; }
  // The page's own address, also from inside its frames, where banners often are.
  const top = (() => {
    try {
      const a = location.ancestorOrigins;
      return new URL(a && a.length ? a[a.length - 1] : location.href).hostname.toLowerCase();
    } catch (e) { return location.hostname.toLowerCase(); }
  })();
  const DONE = ${done.filter { it.all { c -> c.isLetterOrDigit() || c in ".-" } }.joinToString(",", "[", "]") { "\"$it\"" }};
  if (!top || DONE.some((s) => top === s || top.endsWith('.' + s))) return;
  const L = "$channel";
  const tell = (found) => { try { const out = window[L]; if (out) out.postMessage(JSON.stringify({ h: top, found })); } catch (e) {} };
  // Rejection buttons of the common consent platforms; the rest is found by what the button says.
  const KNOWN = [
    '#onetrust-reject-all-handler', '.ot-pc-refuse-all-handler',
    '#CybotCookiebotDialogBodyButtonDecline', '#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll',
    '#didomi-notice-disagree-button', '[data-testid="uc-deny-all-button"]', '.cky-btn-reject',
    '#cn-refuse-cookie', '.cmplz-deny', '.osano-cm-denyAll', '.iubenda-cs-reject-btn', '.cn-decline',
    '#truste-consent-required', '.fc-cta-do-not-consent', '#sp-cc-rejectall-link', '#reject-all-gdpr', '.cmpboxbtnno'
  ];
  const WORDS = new Set([
    'reject', 'reject all', 'reject cookies', 'reject all cookies', 'reject optional cookies', 'reject non-essential',
    'reject non-essential cookies', 'decline', 'decline all', 'decline cookies', 'decline optional cookies', 'deny',
    'deny all', 'refuse', 'refuse all', 'only necessary', 'necessary only', 'only necessary cookies',
    'only essential cookies', 'essential cookies only', 'continue without accepting', 'no thanks', 'i do not accept',
    'ablehnen', 'alle ablehnen', 'nur notwendige', 'nur notwendige cookies', 'nur erforderliche cookies',
    'refuser', 'tout refuser', 'continuer sans accepter', 'rechazar', 'rechazar todo', 'rechazar todas',
    'rejeitar', 'rejeitar tudo', 'rejeitar todos', 'recusar', 'recusar tudo', 'rifiuta', 'rifiuta tutto',
    'rifiuta tutti', 'weigeren', 'alles weigeren', 'alle weigeren', 'odmítnout vše', 'odrzuć wszystkie'
  ]);
  const CONSENT = /cookie|consent|gdpr|datenschutz|privacy|privacidad|confidentialit|privacidade|einwilligung/i;
  const CANDIDATES = 'button,[role="button"],a,input[type="button"],input[type="submit"]';
  const seen = new WeakSet();
  let clicks = 0;
  const shown = (el) => {
    if (!el.isConnected || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none';
  };
  // Text too long to be one of WORDS, even with the markup's spaces, is skipped before any cleaning: news pages have
  // hundreds of links with whole headlines in them, and this runs on each of them every time it looks.
  const label = (el) => {
    const raw = el.value && el.tagName === 'INPUT' ? el.value : el.textContent || el.getAttribute('aria-label') || el.title || '';
    return raw.length > 200 ? '' : raw.replace(/\s+/g, ' ').trim().toLowerCase().replace(/[.!]+$/, '');
  };
  // A rejection button counts only inside something that talks about cookies or consent, so "Decline" on an
  // invitation or "Deny" in a game is left alone.
  // Up one level, from the top of a shadow root to the element that holds it.
  const up = (n) => n.parentElement || (n.parentNode && n.parentNode.host) || null;
  // Some banners keep their buttons in a shadow root (consentmanager), out of reach of the document's own search.
  const roots = () => {
    const out = [document];
    for (let i = 0; i < out.length; i++) for (const el of out[i].querySelectorAll('*')) if (el.shadowRoot) out.push(el.shadowRoot);
    return out;
  };
  const inBanner = (el) => {
    for (let n = up(el), i = 0; n && i < 7; n = up(n), i++) {
      const t = n.textContent;
      if (t.length < 4000 && CONSENT.test(t)) return true;
    }
    return false;
  };
  const click = (el) => {
    seen.add(el);
    clicks++;
    el.click();
    tell(true);
  };
  const attempt = () => {
    const all = roots();
    for (const sel of KNOWN) for (const root of all) {
      let el;
      try { el = root.querySelector(sel); } catch (e) { continue; }
      if (el && !seen.has(el) && shown(el)) { click(el); return true; }
    }
    for (const root of all) for (const el of root.querySelectorAll(CANDIDATES)) {
      if (seen.has(el)) continue;
      const text = label(el);
      if (!WORDS.has(text)) continue;
      if (!shown(el) || !inBanner(el)) continue;
      click(el);
      return true;
    }
    return false;
  };
  // Banners come late and often inside frames, so look a few times as the page loads, then stop.
  const DELAYS = [400, 900, 1500, 2500, 4000, 6000, 9000, 13000];
  let step = 0;
  const poll = () => {
    if (clicks >= 3) return;
    // The page itself looked all the way through: done with this site, banner or not.
    if (step >= DELAYS.length) { if (window === window.top) tell(false); return; }
    try { attempt(); } catch (e) {}
    const wait = DELAYS[step++] - (DELAYS[step - 2] || 0);
    setTimeout(poll, wait);
  };
  setTimeout(poll, DELAYS[0]);
})();
""".trimIndent()
}
