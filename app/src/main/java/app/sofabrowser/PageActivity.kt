package app.sofabrowser

/** A web page saved to the TV home screen: [MainActivity] showing the page alone, in a task of its own. */
class PageActivity : MainActivity() {
    override val standalone = true
}
