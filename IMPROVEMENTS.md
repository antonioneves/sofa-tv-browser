# Performance Improvements

This document tracks performance optimizations made to Sofa TV Browser for low-to-mid range Android TV hardware (e.g. MediaTek quad-core ARM Cortex-A53/A55 SoCs with 1.5–2 GB RAM and 32-bit `armeabi-v7a` userland).

---

## Implemented Optimizations

### 1. Short-Circuit Video Stream Sniffing
- **Location:** `app/src/main/java/app/sofabrowser/browser/Browser.kt`, `app/src/main/java/app/sofabrowser/browser/StreamSniffer.kt`
- **Change:** Added `StreamSniffer.isCandidate(url)` to check for media extensions (`.m3u8`, `.mpd`, `.mp4`, `.m4v`, `.webm`, `.mkv`, `.mov`) before evaluating `listed(url)`. Non-media requests (`.js`, `.css`, images, fonts, API calls) now bypass `listed(url)`, avoiding redundant host parsing and binary search hash lookups in the domain blocklist.

### 2. Deduplicated In-Page Mutation Observer
- **Location:** `app/src/main/java/app/sofabrowser/adblock/InPageBlocking.kt`
- **Change:**
  - **Deduplicated Roots:** Filtered queued DOM mutation nodes to process only top-level ancestor elements, preventing repeated `querySelectorAll` calls over overlapping subtrees.
  - **Adaptive Debounce:** Batches mutations with a 250 ms delay during initial page loading (`document.readyState !== 'complete'`) and 120 ms post-load to reduce main-thread script interruptions while the page parses.
  - **Load Event Flush:** Immediately flushes any pending mutation queue on `DOMContentLoaded` and `load`.

### 3. Chromium Pre-warming on Home Screen
- **Location:** `app/src/main/java/app/sofabrowser/MainActivity.kt`
- **Change:** Pre-creates and pauses the single `WebView` in the background during UI idle on the home screen using `Looper.myQueue().addIdleHandler`. This moves Chromium engine initialization (GPU channels, renderer process, shader caches) to idle time rather than blocking the first page navigation.

### 4. Fast-Path Domain Blocklist Checks
- **Location:** `app/src/main/java/app/sofabrowser/adblock/RuleSet.kt`
- **Change:** When only domain blocklists are active (no ABP deny, allow, or important rules), `RuleSet.blocks()` checks `domains.isBlocked(host)` directly without allocating a `ContentRequest` or generating tag strings.

### 5. In-Memory Site Allowlist Cache
- **Location:** `app/src/main/java/app/sofabrowser/adblock/AdBlocker.kt` (`Allowlist`)
- **Change:** Cached the set of user-allowlisted domains in memory instead of reading from `SharedPreferences` for every subresource interception.

### 6. Lazy Content-Type Evaluation in Ad Blocker
- **Location:** `app/src/main/java/app/sofabrowser/adblock/AdBlocker.kt`, `app/src/main/java/app/sofabrowser/adblock/RuleSet.kt`
- **Change:** Subresource request content type (`getContentType`) is passed to `RuleSet.blocks()` as a lazy lambda. When using standard domain blocklists, the MIME headers, extensions, and substring parsing are bypassed entirely.

### 7. Asynchronous History SQLite Writes
- **Location:** `app/src/main/java/app/sofabrowser/data/Db.kt`
- **Change:** `recordVisit()` and `updateHistoryTitle()` offload database writes to a background single-thread worker (`history-db`), preventing flash storage I/O from blocking the Android UI thread during page load.

### 8. Disabled Overscroll Composition
- **Location:** `app/src/main/java/app/sofabrowser/MainActivity.kt`
- **Change:** Set `view.overScrollMode = View.OVER_SCROLL_NEVER` on `WebView`, so no stretch or glow effect is drawn at the page edge. Not measured; a remote has no use for it either way.

### 9. Explicit HTTP Cache Mode
- **Location:** `app/src/main/java/app/sofabrowser/browser/WebViews.kt`
- **Change:** Sets `s.cacheMode = WebSettings.LOAD_DEFAULT`. That is already WebView's default, so this documents the intent and changes nothing.

---

## Measurements

One Xiaomi MiTV-MOSR4 (Android 11, 1.45 GB RAM, 4 cores, WebView 156) over Wi-Fi. Median of 3 loads per setup, empty
cache and cookies each time, so first visits only. Page load event, in seconds:

| Site | Desktop | Mobile |
|---|---|---|
| Wikipedia | 4.6 | 5.1 |
| BBC News | 8.6 | 8.2 |
| Reddit | 14.4 | 13.4 |
| Twitch | 9.5 | 4.0 |

- **Mobile is not a general speed-up.** It was about the same on Wikipedia, BBC and Reddit. Twitch loaded sooner but
  painted its first content later (0.6 s → 3.8 s). What it reliably saves is renderer memory: roughly 30–65 MB on four
  of the five sites.
- **Sofa's injected scripts cost little.** Over 30 matched pairs, turning them off changed the load event by a median of
  80 ms and main-thread blocking by 160 ms; scripts-on was slower in only 18 of 30.
- **Most of the wait is the sites' own JavaScript:** Reddit blocks the main thread for about 10 s and BBC for about 5 s,
  with or without the app's scripts.
- YouTube is left out: with cookies cleared on every load it never got past its consent page.
- Not measured: repeat visits, logged-in pages, other TVs, and the memory cost of pre-warming WebView (item 3).

## User Settings Notes

- **"Browse as" (Mobile / TV vs. Desktop):** Mobile saves renderer memory (see above) but doesn't reliably load faster, and some sites change layout or playback with it.
- **Filter Lists:** The default bundled StevenBlack hosts list and HaGeZi Pop-Up Ads list operate entirely as 64-bit hash domain lookups. Enabling "Stronger ad blocking" (EasyList + EasyPrivacy) introduces ~145,000 regex, cosmetic, and path-based rules that require additional evaluation on every subresource.
