package app.sofabrowser.browser

/**
 * Turns cookie banners down. A small script in every frame (banners often live in an iframe) looks, as the page
 * loads, for a rejection button: the ones the common consent platforms use, or any button that says "Reject all",
 * "Decline", "Only necessary" and the like in a few languages, inside something that talks about cookies or consent.
 * It clicks that and nothing else: never "Accept", never "Manage", and it gives up after about 13 seconds or three
 * clicks. A button must be visible and sit near cookie or consent wording, so "Decline" on an invitation stays alone.
 *
 * It has no channel back to Sofa, so a page can't reach it, and it keeps nothing. The script is tested against mock
 * banners outside the app; a site that words its button some other way keeps its banner.
 */
object CookieBanners {
    val SCRIPT = """
(() => {
  if (window.__sofaConsent) return;
  try { Object.defineProperty(window, '__sofaConsent', { value: 1 }); } catch (e) { return; }
  // Rejection buttons of the common consent platforms; the rest is found by what the button says.
  const KNOWN = [
    '#onetrust-reject-all-handler', '.ot-pc-refuse-all-handler',
    '#CybotCookiebotDialogBodyButtonDecline', '#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll',
    '#didomi-notice-disagree-button', '[data-testid="uc-deny-all-button"]', '.cky-btn-reject',
    '#cn-refuse-cookie', '.cmplz-deny', '.osano-cm-denyAll', '.iubenda-cs-reject-btn', '.cn-decline',
    '#truste-consent-required', '.fc-cta-do-not-consent', '#sp-cc-rejectall-link', '#reject-all-gdpr'
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
  const inBanner = (el) => {
    for (let n = el.parentElement, i = 0; n && i < 7; n = n.parentElement, i++) {
      const t = n.textContent;
      if (t.length < 4000 && CONSENT.test(t)) return true;
    }
    return false;
  };
  const click = (el) => {
    seen.add(el);
    clicks++;
    el.click();
  };
  const attempt = () => {
    for (const sel of KNOWN) {
      let el;
      try { el = document.querySelector(sel); } catch (e) { continue; }
      if (el && !seen.has(el) && shown(el)) { click(el); return true; }
    }
    for (const el of document.querySelectorAll(CANDIDATES)) {
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
    if (clicks >= 3 || step >= DELAYS.length) return;
    try { attempt(); } catch (e) {}
    const wait = DELAYS[step++] - (DELAYS[step - 2] || 0);
    setTimeout(poll, wait);
  };
  setTimeout(poll, DELAYS[0]);
})();
""".trimIndent()
}
