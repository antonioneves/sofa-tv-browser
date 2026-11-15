# User scripts

People ask for Firefox-style extensions in Sofa. Sofa is built on Android System WebView, which can't run browser
extensions: there is no extension API, no background pages and no isolated worlds. What WebView can do is run a script
in every page Sofa loads. That is what Greasemonkey-style user scripts are: a single `.user.js` file with a short
header that says which sites it runs on. Thousands exist, for example on Greasy Fork, to remove clutter, add a
download button, skip an intro or fix a site that works badly on a TV.

User scripts need a WebView with document-start scripts and web message listeners (WebView 90 or so). On older ones
Settings → User scripts says a newer Android System WebView is needed, and links to scripts open as files.

## What scripts can do

Sofa runs ordinary user scripts, those written with `@grant none`, which is most of them.

- **The header** (`// ==UserScript==` … `// ==/UserScript==`) is read for `@name`, `@namespace`, `@version`,
  `@description`, `@match`, `@include`, `@exclude`, `@exclude-match`, `@run-at` (`document-start`,
  `document-end`, `document-body`, `document-idle`, the default), `@noframes`, `@updateURL` and `@downloadURL`.
  A script without `@match` or `@include` is refused.
- **Where it runs:** only on `http` and `https` pages whose address (without its `#` part) matches. `@match` follows
  Chrome's match patterns; `@include` and `@exclude` take globs, with `.tld` for any top-level domain, or
  `/regular expressions/`. Each script is added to every document from its start, in a small wrapper that checks the
  address before the script itself runs. An exclusion Sofa can't read refuses the script, so it never runs where it
  was meant not to.
- **When it runs:** at document start, once the page is parsed, or just after that, as `@run-at` asks.
- **A little help:** `GM_addStyle`, `GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues` and `GM_info`, and
  the same as promises under `GM.`. Values are stored per script, on the TV, as JSON.

Left out, on purpose:

- **`GM_xmlhttpRequest` and `@connect`.** They let a script fetch from any site with the user's cookies, past the
  rules that keep sites apart. That is the most dangerous thing a user script can do.
- **`@require` and `@resource`.** They load more code from elsewhere at run time, so what runs could differ from what
  the user installed. Every line a script runs has to be in the file the user saw.
- **Automatic updates.** Updates are checked only when the user asks, like filter lists.
- **Menu commands, notifications, downloads, opening tabs and the clipboard** (`GM_registerMenuCommand`,
  `GM_notification`, `GM_download`, `GM_openInTab`, `GM_setClipboard`). Each would need its own TV design.
- **`unsafeWindow` and sandboxing.** WebView has no isolated world, so scripts run in the page's own context, as with
  `@grant none` elsewhere. Pages can see and interfere with them. This is a limit of WebView, and the install screen
  says so in plain words.

A script that asks for something left out is refused at install, with the reason shown. It isn't installed half
working.

## Finding, installing, managing and removing scripts with a remote

Nothing on the TV needs typing.

- **Finding:** browse a script site such as Greasy Fork in Sofa, with the pointer. Sofa doesn't ship or promote a
  catalogue of its own.
- **Installing:** pressing a link to a `.user.js` file opens Sofa's install screen instead of the file. It shows the
  name and version, the description, the sites it runs on ("Runs on all sites" stands out), the help it asks for, a
  plain warning, and the source in a box that scrolls with the D-pad. Cancel has the focus; the user has to move to
  Install. Pages can't start an install by themselves: it needs a press on a link, like a download. The script is
  fetched without cookies, referrer or identifiers.
- **By address:** Settings → User scripts → "Add from address", for a script found on a phone.
- **Managing:** Settings → User scripts lists each script with its sites and an on/off switch. Pressing OK on a script
  opens: Turn off / on, Also run in private browsing, Check for an update, View source, Remove.
- **On a page:** More shows "Scripts here (2)" when scripts are made for the page in view, switched on or not. It
  lists them with a switch each, so a script that breaks a site can be stopped where the problem is; the page then
  loads again without it.
- **Updates:** "Check for an update" fetches the script's `@downloadURL`, `@updateURL` (unless it is a `.meta.js`) or
  the address it was installed from. When it changed, the new version is shown like a new install, with the version it
  replaces and a note when it asks for more sites or more help. It keeps the old one's values and switches.
- **Removing** deletes the script and its stored values.

## Privacy and security

A user script can read everything on the pages it runs on, passwords included, and send it anywhere a page can. The
design keeps that power in the user's hands.

- **Only what the user installed runs.** Nothing comes with Sofa, nothing installs or updates on its own, and all of
  a script's code is in the file shown at install.
- **No extra reach.** Scripts get no more than a page has: no cross-site requests with cookies, no files, no access
  to Sofa's settings, history or bookmarks. A page can only store values for a script that runs on that page.
- **Nothing leaves the TV** apart from what the user asks for: downloading a script or checking it for an update,
  without cookies or identifiers, as for filter lists. Only `https://` addresses are fetched.
- **Private browsing:** scripts don't run in private browsing unless the user allows it for that script ("Also run in
  private browsing", off by default, as in Firefox). When allowed, values a script stores in private browsing are kept
  apart, in memory, and deleted when private browsing ends.
- **Sofa's own screens are off limits.** The home screen, search screen and settings are not web pages, so scripts
  never reach them.
- **The private space** has its own scripts and values, apart from the normal ones.
- **Limits:** scripts of at most 1 MB, and stored values of at most 1 MB per script, so a script can't fill the TV.
- **Honest wording.** The install screen says, in plain words, that the script can see and change the sites it runs
  on, passwords included.

## Licence note

Fulguris has a user-script implementation, but it is under CPAL-1.0, which is incompatible with Sofa's GPL-3.0-only
licence. Sofa's version must be written independently of that code: no copying, no translating, and no reading it
while writing Sofa's version. The sources to work from are the public descriptions of the format: the Greasemonkey
and Tampermonkey documentation of the header and the GM functions, and Chrome's documentation of match patterns.
Sofa's ad blocking took Apache-2.0 files from Fulguris (see NOTICE.md). That doesn't extend to the user scripts.

## Open questions

- Is `@grant none` plus `GM_addStyle` and storage enough for the scripts people actually want on a TV? A survey of the
  most installed scripts on Greasy Fork would tell.
- Should `@require` be allowed later when it names a fixed version with a hash (subresource integrity), so the code
  still can't change?
- Should Sofa offer a short, reviewed list of TV-friendly scripts, or stay out of choosing?
- Should a script that fails often (errors in the console) be switched off for that site automatically?
