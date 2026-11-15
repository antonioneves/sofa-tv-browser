# User scripts (not planned)

People ask for Firefox-style extensions in Sofa. Sofa is built on Android System WebView, which can't run browser
extensions. The nearest thing it could offer is Greasemonkey-style user scripts: single `.user.js` files that change
the sites they name. Sofa doesn't offer them, on purpose.

- **Little to gain on a TV.** What most people would want a script for, removing ads, pop-ups and the clutter they
  leave, Sofa already does. Finding a script and judging whether to trust it is awkward with a remote, so few people
  would use them.
- **A real risk.** A script can read and change everything on the sites it runs on, passwords included, and send it
  anywhere. WebView has no separate world for scripts, so pages could also tamper with them. Few people read a
  script's source before installing it, least of all on a TV.
- **More to maintain** for a feature hardly anyone would use.

A working version was built and then taken out (see the commit history, "Add user scripts"). If many people ask for
it, it can come back behind a switch in Settings that is off until the user turns it on.
