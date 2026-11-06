# Changelog

## Unreleased

- **Hide parts of a page**: in the menu, "Hide parts of the page", then point at something and press OK to hide it;
  Back when you are done; hold OK on the menu button to bring back the last one hidden. Hidden parts stay hidden on that site's pages until you reset the site in
  Settings › Site settings (which also lists how many parts are hidden). Included in the backup; in the private space
  they are forgotten when it closes.
- **Backup**: on the phone page (the one for typing and for bookmarks), download everything you set up as one file
  (settings, bookmarks, history, home tiles, search shortcuts, per-site choices, hidden parts of pages, which filter lists are on, the lists you added and the ad blocking allowlist) and
  restore it later or on another TV. Restoring adds to what is there and never deletes; the file is checked, and
  anything unknown is ignored. Saved sign-ins and the PIN are not in it.
- **Saved sign-ins** (Settings › Privacy, off by default): after you sign in on a secure (https) site, Sofa offers to
  save the user name and password, and next time asks whether to fill them in. Passwords are encrypted with a key
  Android keeps for Sofa, tied to the exact site and user name. Saving one, and filling one in after you pick it in
  Sofa's own prompt, both ask for Sofa's PIN: the same one as the private space's, chosen when the first sign-in is
  saved, and not asked inside the space, which is behind it already. Forgetting the PIN erases them along with the
  space. They're never saved in private browsing, never shown in Settings and never backed up. Settings › Saved
  sign-ins lists them, with a site and user name only, and removes one or all. A password the site turns down (the sign-in form is back on the next page, or the page says the password is wrong) isn't offered for saving.
- **Start page** (Settings › Browsing, off by default): "Open a page at start" makes Sofa open a chosen page instead
  of the home screen when it starts. Pick one of your bookmarks or type an address. Off, Sofa starts on the home
  screen as before.
- **Import and export bookmarks** in the HTML file Chrome uses, which Firefox, Edge and Safari also read and write.
  In Bookmarks, "Import or export" opens a page on the home network for your phone or computer: download Sofa's
  bookmarks from it, or pick a file exported from another browser to add its bookmarks (folders are flattened, and
  bookmarks Sofa already has are skipped). When the TV has a file manager, you can also export to a file or import
  from one on the TV.
- **The "Type on your phone" page lists your bookmarks and recent pages:** tap one to open it on the TV. It also has
  the bookmarks file download and import.
- **Private space** (Settings, off by default): adds a button on the home screen that opens a second
  Sofa behind a 4-digit PIN, with its own bookmarks, history, settings, logins and downloads, kept apart from the rest
  of Sofa. Inside, Sofa is teal instead of blue, the lock shows open, and the search field says "Private space", so
  it's always clear which side you are on. The space locks when you switch to another app, the screen goes off or the
  screensaver starts (even on TVs where the screensaver only covers Sofa), and Sofa asks for the PIN again when it
  comes back into view. A forgotten PIN can't be recovered, but "Forgot PIN" erases the space to start again. The data is kept in Sofa's app storage and isn't encrypted with the
  PIN. Needs Android 9 or newer.
- **Add to TV home:** a new button in the page menu gives the page its own tile among the TV's apps. Sofa makes and
  signs a tiny launcher app on the TV itself (nothing is downloaded or sent), and the tile opens the page in Sofa with
  nothing on screen but the page: no menu, and Back goes back through the page and then leaves to the TV home screen,
  never into the browser. Android asks you to allow Sofa to install apps the first time. The tile uses the
  page's own theme colour and its large icon when it has them, and a wide preview picture of the page (its `og:image`)
  fills the tile when the page has one. The icon and picture are the only things fetched, from the page's own site and
  only when you press the button.
- **Most visited tiles have a menu:** hold OK (or press Menu) on one to open it, add it to Quick access, or remove it.
  A removed site keeps its history and comes back only if you visit it often again.
- **History and Bookmarks:** the delete button beside each card is gone. Hold OK (or press Menu) on a card to open
  or delete it.
- **Browse as, per site:** Sofa now browses as Chrome on Android by default, which is what the TV is. Some sites,
  Qwant among them, took the old desktop disguise for a bot and refused to search. "Browse as" in the menu now
  chooses for the page's site only, and Sofa remembers it there (in private browsing, only until it closes).
  Settings → Browse as sets the default for every other site. If you had chosen a mode in Settings yourself, it stays.
- The menu is one slim row: Home, the address, Back, Forward, Reload, zoom and More. More slides the other actions up
  below it, grouped by what they do, and folds them away again; the menu opens the way it was left. "Add to TV
  home" has its own icon, a TV with a plus, so it no longer looks like "Browse as".
- **Zoom, per site:** the zoom chosen on a site is kept for that site's pages (in private browsing, only until it
  closes).
- **Translate** (page menu, under More): translates the page in place into the TV's language with Google Translate,
  and keeps translating the site's pages as you go, until "Show original" or you leave the site. Hold OK on it to
  choose another language. The page's text goes to Google only when you press it; sites that refuse Google's
  translator can't be translated.
- **Type on your phone:** the phone button beside the mic on the search screen shows a QR code. Scan it with the
  phone's camera, type an address or a search on the page that opens, and the TV opens it. The page is served by Sofa
  itself on your home network, only while the code is on screen and at an address with a random key, so nothing
  goes through the internet.
- **Reader view** (page menu, under More): shows just the page's article, in large type on a dark page, without
  menus, ads or sidebars. Up and Down (or Left and Right) turn the pages, and Back returns to the page as it was.
  It uses Mozilla's Readability, built into Sofa, so nothing is sent anywhere.
- **Sleep timer:** the moon button in the video controls stops playback after 15 minutes to 2 hours, in Sofa
  Player and on the page alike. A message comes up a minute before, and the button shows lit while the timer
  runs. Press it again to change the time or turn it off.
- **Search shortcuts:** type a keyword and a space before a search to send it straight to one site: `yt` YouTube,
  `w` Wikipedia (in the TV's language), `imdb` IMDb, `tw` Twitch, `r` Reddit, `map` OpenStreetMap, `g` Google and
  `ddg` DuckDuckGo. The search screen shows where it will go as you type. Settings → Search shortcuts lists them, and there
  you can add your own (any site's search address, with `%s` for the words), change one's address, or remove one;
  removed built-in ones can be restored.
- **Site settings** (Settings → Site settings): lists every site with its own choices (zoom, Browse as, ad blocking
  turned off, keys going to the site), and resets one site or all of them. Choices made in private browsing,
  including answers to the protected-content prompt, are never saved and are gone when it closes.
- Clearing browsing data, now or when Sofa closes, also forgets the choices made for each site.
- The menu is more solid, so the page behind it no longer shows through its buttons.
- The home screen no longer has an Exit button: Home or Back on the remote leaves Sofa.
- Fixed: Sofa could crash when a small download finished the moment it started.
- Fixed: a dialog button or tile could stay enlarged after focus moved on quickly, and the last button in a dialog
  was cut off when enlarged.
- Fixed: zoom out did nothing below 100%, though the menu showed a smaller number. It now makes the page smaller,
  down to 64%, and the zoom buttons grey out at either end.
- Fixed: the first and last buttons of the menu, and their names, could be cut off at the sides of the screen.
- Fixed: Back from Bookmarks, History, Downloads or Settings could land on the home screen instead of the page
  they were opened from, on TVs short of memory. The page now comes back where it was, with its Back history.
- Fixed: the border of a focused tile at the start or end of a row on the home screen, and of a focused card in
  History, Bookmarks and Downloads, was cut off.

## 1.2.2 (2026-10-30)

**Sofa Player**

- **4K and 60 fps on more TVs:** Auto now plays the stream's best picture, such as 4K or 1440p at 60 fps, when the
  TV's hardware decoder says it plays it. Before, many TVs got a smaller picture, often at 30 fps, because of the
  frame rates their maker listed. If such a picture drops frames, Auto moves to a smaller one and says so.
- **Steps down instead of giving up:** when the TV can't decode one of the stream's pictures, Sofa Player goes on
  from the same point with another one (smaller, or another codec), and says which over the video and in Video info.
- **One decoder per video:** the page's own video now rests while Sofa Player plays, which frees its hardware
  decoder and some memory, and helps a second 4K video in a row find one. When Sofa Player only gets a software
  decoder, it keeps to 1080p at 30 fps, or hands the video back to the site's player.
- **Open videos in Sofa Player straight from the page:** a video you start on the page yourself, with sound and a
  minute or longer, goes fullscreen in Sofa Player at once, without Back and Watch. Muted previews and short ads
  stay on the page. On by default; Settings → "Open videos in Sofa Player" turns it off.
- Fixed: on Back from Sofa Player, a muted preview elsewhere on the page could get the position instead of the video
  you watched.

**Watch**

- **Watch by itself** (Settings, off by default): runs Watch on its own once a page has loaded, on every site or only
  on the sites you choose in the menu. It runs once per page and stops as soon as you press a key.
- Watch now reaches a video under an invisible ad layer, and when a layer you can see covers the video, it says so
  instead of "No video found".

**Play in another app**

- Players that can't open Sofa's copy of a stream, such as C Media Player, get the site's address instead.

**Ad blocking**

- Sofa offers Stronger ad blocking (EasyList and EasyPrivacy) once at start, with what it costs. Settings changes it
  any time.
- The bundled lists are refreshed: about 77,000 StevenBlack and 51,000 HaGeZi pop-up domains.

**Other**

- A link sent from another app or a phone remote while Sofa's menu is open now closes the menu and loads.
- The old-WebView warning now starts below version 143.
- `SECURITY.md` says how to report a security problem privately.

## 1.2.1 (2026-10-27)

**Sofa Player**

- **Your choices stay:** the quality, sound and subtitles you pick on the bar now stay for the next videos, on every
  site: the same quality or the nearest lower one, and the same language for sound and subtitles. Private browsing
  uses them but saves nothing new. Settings → Sofa Player → "Forget my player choices" goes back to Auto and the
  TV's own settings.
- **Starts sharp:** with HLS streams, Sofa Player starts at the quality the site's player was showing, instead of a
  lower one for the first seconds.
- **Says why:** when Watch leaves a video with the site's player (protected, no stream Sofa Player can open, or one
  it can't read or play), a short note in fullscreen says why, and Video info keeps the reason.
- Fixed: after a video Sofa Player couldn't take over, later videos in the same session could stay with the site's
  player, because Sofa looked for their stream only once.

## 1.2.0 (2026-10-26)

**Sofa Player**

- **Sofa's own video player:** in fullscreen, Sofa now plays the site's stream in its own player when it can: 4K at
  full resolution on 4K TVs, HDR when the stream and the TV have it, and a choice of quality, sound and subtitles on
  the bar. It takes over at the same moment, without a jump, and "Site's player" on the bar goes back. Protected
  videos and videos under a minute stay with the site's player. Settings → Sofa Player turns it off.
- At the end of a video, the site's player plays the last moment, so the site moves on as usual (its next episode
  or end screen).
- **HDR stays HDR:** when a stream offers HDR and SDR versions, Auto keeps to the HDR ones on HDR TVs (and to SDR on
  others), so a film doesn't change look halfway.
- **Quality:** pick one by hand and it stays for that site until Sofa closes.
- **Subtitles** follow the TV's caption setting. When the stream has none of its own, the page's subtitle files are
  used, and the ones the site was showing stay on.
- If Sofa Player only gets a software decoder while the TV has a hardware one, it pauses the site's video and tries
  once more.
- Match frame rate uses the stream's own frame rate, and Video info shows the stream's format, the decoder and the
  frames shown and dropped.

**Play in another app**

- **Works on many more sites:** Sofa now hands over the whole stream the site's player uses, not just one part of
  it, with the page's subtitle files and the position, and with what the site expects from a player. Sites that
  only let the browser read their stream get a private copy that VLC and Just Player can open.
- **Settings → Play in…** chooses the app, or "Ask every time" to pick on each press.
- **Always play in another app (experimental, off by default)** sends fullscreen videos straight to that app.
- When a video can't be handed over (protected, only inside the page, or no player installed), fullscreen says why.

**Game pads**

- Xbox, PlayStation, 8BitDo and other pads work like in a console browser: the left stick moves the pointer, the
  right stick scrolls, A selects, B goes back, X watches the page's video, Y searches, and the bumpers and triggers
  go back, forward and page up and down. In fullscreen video they play, seek and switch subtitles. Settings → Game
  controller shows every button.

**Links and images**

- **Hold OK on a link or an image** for its menu: open it in another app, copy or share its address, or download
  it (links to files and pictures). Anywhere else, holding OK still switches to scroll mode.

**Better ad blocking**

- **Filter lists** in the formats of uBlock Origin, Adblock Plus and hosts files: Sofa now understands rules for
  parts of a site (such as `/ads/banner`), rule options, exceptions, and rules that hide the empty boxes ads leave
  behind, including ads a page adds after it has loaded.
- **Stronger ad blocking:** one switch in Settings adds EasyList and EasyPrivacy.
- **Settings → Filter lists:** add lists by address, switch each one on or off, see how many rules each brings,
  update them all at once, or go back to the copy that came with Sofa.
- **Automatic list updates,** every few days, if you want them: Sofa asks once.
- The two lists Sofa came with stay on. A blocklist address you set yourself is kept as a list of its own.
- Blocking still happens entirely on the TV, and lists are downloaded without cookies.
- **Faster start:** lists are stored ready to use, so they load in a moment instead of being read again each time.

**Far fewer pop-ups**

- A press anywhere on a page no longer opens an ad in place of the page: windows that a script opens to another
  site are blocked unless you pressed a link to it, and so are the invisible links that pop-under networks lay over
  a page. Your press then usually reaches what you were pressing.
- A press on a link can't also open an ad, and a page can't send itself to an ad site as it opens a window.
- The pop-up rules of filter lists now count too.
- Ads with a broken security certificate no longer interrupt the page with a warning; only the page itself can.
- Buttons that open another site's window on purpose (some "Sign in with…" or payment buttons) are blocked like
  pop-ups too: "Allow once" in the menu opens them.

**Smaller things**

- **0.75x** playback speed.
- "Continue from" no longer turns up on another video or page.
- **Fixed:** after changing a setting, the focus went back to the top of Settings; it now stays on that setting.

## 1.1.0 (2026-10-20)

**Watching video**

- **Watch:** press Play on the remote, or Watch in the menu, and Sofa finds the video on the page and plays it
  fullscreen, even when it comes from another site.
- **New buttons in fullscreen:**
  - subtitles on and off, when the video has them;
  - picture-in-picture, on TVs that support it;
  - Video info: the video's quality and frame rate, and whether the TV decodes it in hardware;
  - Play in another app: open the video in VLC, MX Player or another player. Android asks the first time and Sofa
    remembers; hold OK on the button to pick another one.
- **Continue where you stopped** in long films.
- **Sharper video on 4K TVs:** in fullscreen, sites can now send their 4K stream.
- **Smoother films (experimental):** turn on "Match frame rate" in Settings, and the TV switches to the film's
  frame rate, such as 24 per second, while it plays fullscreen. Only on TVs that support it; the screen goes dark
  for a moment when it switches.
- **The screen stays on** while a video plays, and can go to sleep again when you pause.

**Fewer ads and pop-ups**

- Pop-ups and pages that jump to ad sites on their own are blocked, now with a second list made for pop-up ads.
  Links you press still work, and "Allow once" in the menu opens something that was blocked.
- Fewer "please turn off your ad blocker" messages.
- The Ad block button in the menu shows how many things were blocked on the page.

**Browsing and settings**

- **Find in page,** typed or spoken.
- **Keep history for** 7, 30 or 90 days, always or never. Your choice from 1.0.0 carries over.
- **Protected video** (the kind streaming services lock): ask for each site, always allow, or always block.
- Sofa tells you when your TV's web engine is very old and needs an update, and About shows its version.
- **Fixed:** ad blocking and its count now work from the moment a page starts loading.

## 1.0.0 (2026-10-19)

First public release.

- **Remote-control browsing:** a D-pad mouse pointer that accelerates while held, with hover, drag, edge
  scrolling, a scroll mode, and a D-pad mode for sites built for keyboards.
- **Back-key menu:** address, navigation, bookmarks, history, per-site ad blocking, "Browse as" user agents and
  zoom.
- **Home screen:** your own quick-access tiles, most visited sites and recent bookmarks.
- **Video:** a fullscreen control bar with seeking, play/pause, scrubbing and playback speed.
- **Privacy:**
  - ads and trackers blocked on the device, and third-party cookies blocked;
  - no Do Not Track header, and a desktop user agent by default, so Sofa blends in;
  - private browsing in a separate WebView profile where the WebView supports it;
  - no analytics, accounts or servers of its own.
- **Search:** you choose the engine at first start, from twelve. Suggestions come from your bookmarks and history,
  and voice search runs in Sofa's own screen.
- **Downloads:** pause and resume, automatic retries, and downloads that keep going in the background.
- **Themes:** Dark and Light, or the same as the TV.
- **Updates:** opt-in update checks for copies installed from GitHub.
