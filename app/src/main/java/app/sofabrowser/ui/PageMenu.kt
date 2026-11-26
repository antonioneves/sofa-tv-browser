package app.sofabrowser.ui

import android.app.Activity
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * The page's menu, wherever Settings puts it: the slim bar at the left edge ([BrowserMenu]) or the bar along the bottom
 * of the screen ([BottomMenu]). Either can be docked beside the page, or open over it.
 * Both take the same [BrowserMenu.State] and answer with the same [BrowserMenu.Actions].
 */
interface PageMenu {
    /** The bar is docked in the screen, the page beside it, and not shown in a dialog. */
    val docked: Boolean
    /** The bar is on screen. */
    val shown: Boolean
    /** The bar is being used: open, or docked with the focus in it. */
    val inUse: Boolean
    val dockHasFocus: Boolean
    /** Called when the docked bar is left: the focus goes back to the page. */
    var onLeave: (() -> Unit)?
    /** The More actions and their order, from Settings > Menu buttons; a menu built for another layout is built again. */
    val layout: MenuLayout

    fun show(state: BrowserMenu.State)
    fun update(s: BrowserMenu.State)
    fun dismiss()

    /** The sofa on the Home button as it shows, for it to move on to the next screen ([SofaGlide]); null with the bar out of sight. */
    fun sofa(): SofaGlide.Spot?
    /** The sofa of the screen being left moves into the Home button: the bar docked beside the page. */
    fun sofaTo(activity: Activity, from: SofaGlide.Spot?) = Unit

    fun dock(host: FrameLayout, index: Int) = Unit
    fun undock() = Unit
    fun setDockVisible(visible: Boolean) = Unit
    /** A key while the docked bar has the focus: what the bar adds to the usual way the focus moves. */
    fun dockedKey(event: KeyEvent): Boolean = false
    /** A tap at ([x], [y]) in the window by the pointer, on the docked bar or what is open beside it: true when a button or the bar took it. */
    fun tapAt(x: Float, y: Float): Boolean = false
}
