package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.Space

/**
 * The browse menu, opened with BACK: a slim vertical bar on the left edge of the screen, with Home, the address,
 * back, forward, reload, zoom and More, one under the other. More opens a speech balloon beside it with all the other
 * actions, each with its name: the toggles first, which keep the menu open, then the single actions, which close it.
 * BACK or More closes the balloon again. The focused bar button's name shows in a small label
 * beside it. Like BrowsHere's, the menu remembers the last button used and doesn't hide on its own.
 */
class BrowserMenu(context: Context, private val actions: Actions) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    enum class Action { HOME, WATCH, AUTO_WATCH, BACK, FORWARD, RELOAD, FIND, TRANSLATE, READER, HIDE_PARTS, BOOKMARK, PIN, TV_SHORTCUT, HISTORY, BOOKMARKS, DOWNLOADS, ADBLOCK, ALLOW_ONCE, USER_AGENT, SCROLL, POINTER, ZOOM_OUT, ZOOM_IN, SETTINGS }

    interface Actions {
        fun onAddress()
        fun onAction(action: Action)
        /** OK held on [action]'s button; Translate answers it with its languages, Hide parts with an undo. */
        fun onActionHeld(action: Action)
    }

    data class State(
        val address: String,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val loading: Boolean,
        val bookmarked: Boolean,
        val adBlockOn: Boolean,
        /** Requests, pop-ups and redirects blocked on this page. */
        val blocked: Int,
        val desktop: Boolean,
        val pointer: Boolean,
        val zoomPercent: Int,
        val canZoomOut: Boolean,
        val canZoomIn: Boolean,
        val privateMode: Boolean,
        /** A pop-up or redirect was just blocked and can still be opened once. */
        val allowPending: Boolean,
        /** "Watch by itself" runs on this site (on every site, or this one is chosen). */
        val autoWatchHere: Boolean,
        /** Pages of this site are being translated. */
        val translated: Boolean,
        /** The name of the language Translate translates into. */
        val translateInto: String,
        /** The page shows in reader view. */
        val reading: Boolean,
    )

    private val buttons = LinkedHashMap<Action, LabeledIconButton>()
    private lateinit var address: LabeledIconButton
    /** The name of the focused button, floating beside it. */
    private lateinit var tip: TextView
    private var tipFor: android.view.View? = null
    private lateinit var zoomLabel: TextView
    private lateinit var more: LabeledIconButton
    private lateinit var balloon: LinearLayout
    private lateinit var balloonList: LinearLayout
    private lateinit var balloonScroll: FocusScrollView
    private lateinit var balloonDrawable: BalloonDrawable
    private val topRow = setOf(Action.HOME, Action.BACK, Action.FORWARD, Action.RELOAD)
    private var lastFocused = Action.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // The whole screen, the bar at its left edge and the balloon beside it: one window for both, so the
            // balloon needs no window of its own to open in.
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val sheetColor = if (Space.active) Ui.p.sheetSpace else Ui.p.sheet
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Square corners: the bar runs from the top of the screen to its foot.
            setBackgroundColor(sheetColor)
            setPadding(0, 48.px, 0, 48.px)
            // The focused button's scaled square may reach into the padding.
            clipChildren = false
            clipToPadding = false
        }
        balloonList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val list = balloonList
        fun place(b: LabeledIconButton, into: LinearLayout, gap: Int) {
            if (into === list) {
                into.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.px })
                return
            }
            // In the bar the name shows beside the focused button; a balloon that is open makes way for another button.
            b.onFocused = b.onFocused.let { was -> { was?.invoke(); showTip(b); if (b !== more && balloonOpen) setBalloon(false) } }
            into.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap.px
            })
        }
        fun add(action: Action, icon: Int, label: Int, into: LinearLayout = list, gap: Int = 16) {
            // In the bar the icons speak for themselves; in the balloon each action is a line with its name.
            val inList = into === list
            val b = LabeledIconButton(context, icon, context.getString(label), labelled = false, row = inList) { actions.onAction(action) }
            b.onFocused = { lastFocused = action; if (inList) hideTip() }
            buttons[action] = b
            place(b, into, gap)
        }
        // Home stays in reach with the balloon closed.
        // The sofa is Sofa's home: orange at rest, like on the start screen.
        add(Action.HOME, R.drawable.ic_sofa, R.string.home, bar, 28)
        buttons[Action.HOME]?.accent = Ui.BRAND
        // The address: the bar is too slim to spell it out, so it shows in the label while this button is focused.
        address = LabeledIconButton(context, R.drawable.ic_search, context.getString(R.string.search_hint), labelled = false) {
            dismiss(); actions.onAddress()
        }
        place(address, bar, 28)
        // Moving about the page.
        add(Action.BACK, R.drawable.ic_arrow_back, R.string.back, bar)
        add(Action.FORWARD, R.drawable.ic_arrow_forward, R.string.forward, bar)
        add(Action.RELOAD, R.drawable.ic_refresh, R.string.reload, bar, 28)
        more = LabeledIconButton(context, R.drawable.ic_chevron_right, context.getString(R.string.more_actions), labelled = false) {
            setBalloon(!balloonOpen, focusInside = true)
        }
        // Right on More opens the balloon too, as it sits in that direction.
        more.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && !balloonOpen) {
                setBalloon(true, focusInside = true); true
            } else false
        }
        place(more, bar, 0)

        // Two groups. First the toggles: they change how the page or the site is shown, the balloon stays open and
        // the line shows the new state. Then the actions: each does one thing and closes the menu.
        // Zoom first: out, the level, in, as wide as the balloon and as tall as the lines below, a third each.
        fun zoomButton(action: Action, icon: Int, label: Int) =
            LabeledIconButton(context, icon, context.getString(label), labelled = false, row = true, iconOnly = true) { actions.onAction(action) }.also {
                it.onFocused = { lastFocused = action; hideTip() }
                buttons[action] = it
            }
        zoomLabel = Ui.text(context, 24, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(zoomButton(Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out), LinearLayout.LayoutParams(0, 64.px, 1f))
            addView(zoomLabel, LinearLayout.LayoutParams(0, 64.px, 1f))
            addView(zoomButton(Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in), LinearLayout.LayoutParams(0, 64.px, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.px })
        // Kept per site; not in private browsing, which saves none.
        add(Action.AUTO_WATCH, R.drawable.ic_autoplay, R.string.auto_watch)
        add(Action.TRANSLATE, R.drawable.ic_translate, R.string.translate)
        buttons[Action.TRANSLATE]?.setOnLongClickListener { actions.onActionHeld(Action.TRANSLATE); true }
        add(Action.READER, R.drawable.ic_article, R.string.reader_view)
        add(Action.BOOKMARK, R.drawable.ic_star, R.string.bookmark)
        // How this site is shown, and how the remote moves about it.
        add(Action.ADBLOCK, R.drawable.ic_shield, R.string.adblock_site)
        add(Action.POINTER, R.drawable.ic_mouse, R.string.pointer)
        list.addView(android.view.View(context).apply { setBackgroundColor(Ui.p.fillFocused) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2.px).apply { setMargins(8.px, 10.px, 8.px, 8.px) })
        // Shown only after a block; the menu then opens with it focused.
        add(Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once)
        // Watching, the action a TV is most often used for, first of the actions.
        add(Action.WATCH, R.drawable.ic_play_circle, R.string.watch)
        // This page: look through it, hide parts, keep it.
        add(Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page)
        add(Action.HIDE_PARTS, R.drawable.ic_visibility_off, R.string.hide_parts)
        buttons[Action.HIDE_PARTS]?.setOnLongClickListener { actions.onActionHeld(Action.HIDE_PARTS); true }
        add(Action.PIN, R.drawable.ic_add, R.string.pin_to_home)
        // A tile on the TV's home screen would show anyone what's in the private space.
        if (!Space.active) add(Action.TV_SHORTCUT, R.drawable.ic_tv, R.string.tv_shortcut)
        // Somewhere else to go.
        add(Action.BOOKMARKS, R.drawable.ic_bookmarks, R.string.bookmarks)
        add(Action.HISTORY, R.drawable.ic_history, R.string.history)
        add(Action.DOWNLOADS, R.drawable.ic_download, R.string.downloads)
        // Asks which one, then closes the menu.
        add(Action.USER_AGENT, R.drawable.ic_desktop_windows, R.string.user_agent)
        add(Action.SCROLL, R.drawable.ic_swap_vert, R.string.scroll_mode)
        add(Action.SETTINGS, R.drawable.ic_settings, R.string.settings)

        // The balloon: a scrolling list in a bubble whose tail points at More. A long list scrolls inside it, and a
        // focused line keeps some room from its top and bottom edges.
        balloonScroll = FocusScrollView(context, 96.px).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(balloonList)
        }
        balloonDrawable = BalloonDrawable(sheetColor, Ui.p.fillFocused, 24.px.toFloat(), TAIL.px.toFloat(), 18.px.toFloat(), 2.px.toFloat())
        balloon = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = balloonDrawable
            setPadding(TAIL.px + 12.px, 16.px, 12.px, 16.px)
            visibility = android.view.View.GONE
            addView(balloonScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        tip = Ui.text(context, 26, Ui.p.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = 900.px
            background = Ui.rounded(sheetColor, 12)
            setPadding(24.px, 14.px, 24.px, 14.px)
            visibility = android.view.View.INVISIBLE
        }
        setContentView(android.widget.FrameLayout(context).apply {
            addView(bar, android.widget.FrameLayout.LayoutParams(BAR.px, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
            addView(balloon, android.widget.FrameLayout.LayoutParams(BALLOON.px, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tip, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTip() }
        })
    }

    /** Shows [button]'s name beside it; the address button's is the page's address. */
    private fun showTip(button: android.view.View) {
        tipFor = button
        tip.text = button.contentDescription
        tip.visibility = android.view.View.VISIBLE
        // Placed once the button is where a scroll or a layout leaves it.
        tip.post { placeTip() }
    }

    private fun hideTip() {
        tipFor = null
        tip.visibility = android.view.View.INVISIBLE
    }

    private fun placeTip() {
        val button = tipFor ?: return
        val frame = tip.parent as? android.view.View ?: return
        if (!button.isAttachedToWindow || frame.height == 0) return
        tip.text = button.contentDescription
        tip.measure(android.view.View.MeasureSpec.makeMeasureSpec(900.px, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val at = IntArray(2).also { button.getLocationInWindow(it) }
        val origin = IntArray(2).also { frame.getLocationInWindow(it) }
        tip.x = (at[0] - origin[0] + button.width + 20.px).toFloat()
        tip.y = (at[1] - origin[1] + (button.height - tip.measuredHeight) / 2).coerceIn(8.px, maxOf(8.px, frame.height - tip.measuredHeight - 8.px)).toFloat()
    }

    private var balloonOpen = false

    fun show(state: State) {
        if (!isShowing) show()
        update(state)
        // The balloon starts closed; a pending "Allow once" is why the menu was opened, most likely, so then it opens
        // with that action focused.
        if (state.allowPending) {
            setBalloon(true, focusInside = true, animate = false)
            buttons[Action.ALLOW_ONCE]?.requestFocus()
            return
        }
        setBalloon(false, animate = false)
        // An action last used from the balloon is one press away, on More.
        val last = buttons[lastFocused]?.takeIf { lastFocused in topRow && it.isEnabled && it.visibility == android.view.View.VISIBLE }
        (last ?: if (lastFocused in topRow) buttons[Action.HOME] else more)?.requestFocus()
    }

    /** BACK closes the balloon first, and the menu when it is already closed. */
    @Deprecated("Framework dialog back handling")
    override fun onBackPressed() {
        if (balloonOpen) {
            setBalloon(false)
            more.requestFocus()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Opens or closes the balloon beside More. [focusInside] moves the focus to its last used action (or Watch). */
    private fun setBalloon(open: Boolean, focusInside: Boolean = false, animate: Boolean = true) {
        if (!::balloon.isInitialized) return
        val changed = open != balloonOpen
        balloonOpen = open
        // One chevron, pointing at where the balloon is or goes back to.
        more.set(R.drawable.ic_chevron_right, context.getString(if (open) R.string.fewer_actions else R.string.more_actions))
        more.scaleX = if (open) -1f else 1f
        if (!open) {
            if (balloon.hasFocus()) more.requestFocus()
            if (changed && animate) balloon.animate().alpha(0f).setDuration(120).withEndAction {
                if (!balloonOpen) balloon.visibility = android.view.View.GONE
            }.start() else balloon.visibility = android.view.View.GONE
            return
        }
        balloon.animate().cancel()
        balloon.visibility = android.view.View.VISIBLE
        balloon.alpha = if (changed && animate) 0f else 1f
        if (changed && animate) balloon.animate().alpha(1f).setDuration(120).start()
        placeBalloon()
        if (focusInside) {
            hideTip()
            (buttons[lastFocused]?.takeIf { lastFocused !in topRow && it.isEnabled && it.visibility == android.view.View.VISIBLE }
                ?: buttons[Action.WATCH])?.requestFocus()
        }
    }

    /** Sizes the balloon to its list (or the screen's height) and puts its tail on More. */
    private fun placeBalloon() {
        val frame = balloon.parent as? android.view.View ?: return
        // Not laid out yet: tried again once it is.
        if (frame.height == 0 || !more.isAttachedToWindow) { frame.post { if (balloonOpen) placeBalloon() }; return }
        val margin = 24.px
        val inner = BALLOON.px - balloon.paddingLeft - balloon.paddingRight
        balloonList.measure(android.view.View.MeasureSpec.makeMeasureSpec(inner, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val h = minOf(balloonList.measuredHeight + balloon.paddingTop + balloon.paddingBottom, frame.height - 2 * margin)
        balloon.layoutParams.height = h
        balloon.requestLayout()
        val at = IntArray(2).also { more.getLocationInWindow(it) }
        val origin = IntArray(2).also { frame.getLocationInWindow(it) }
        val moreY = at[1] - origin[1] + more.height / 2
        val top = (moreY - h / 2).coerceIn(margin, maxOf(margin, frame.height - h - margin))
        balloon.x = (BAR.px + 4.px).toFloat()
        balloon.y = top.toFloat()
        balloonDrawable.tailY = (moreY - top).toFloat()
    }

    fun update(s: State) {
        if (!::address.isInitialized) return
        address.set(R.drawable.ic_search, s.address.ifEmpty { context.getString(R.string.search_hint) })
        buttons[Action.ALLOW_ONCE]?.visibility = if (s.allowPending) android.view.View.VISIBLE else android.view.View.GONE
        buttons[Action.BACK]?.setEnabledLook(s.canGoBack)
        buttons[Action.FORWARD]?.setEnabledLook(s.canGoForward)
        buttons[Action.RELOAD]?.set(if (s.loading) R.drawable.ic_close else R.drawable.ic_refresh,
            context.getString(if (s.loading) R.string.stop else R.string.reload))
        buttons[Action.BOOKMARK]?.set(if (s.bookmarked) R.drawable.ic_star_filled else R.drawable.ic_star,
            context.getString(if (s.bookmarked) R.string.bookmarked else R.string.bookmark))
        buttons[Action.ADBLOCK]?.set(if (s.adBlockOn) R.drawable.ic_shield_filled else R.drawable.ic_shield,
            when {
                !s.adBlockOn -> context.getString(R.string.adblock_site_off)
                s.blocked > 0 -> context.resources.getQuantityString(R.plurals.adblock_site_on_count, s.blocked, s.blocked)
                else -> context.getString(R.string.adblock_site_on)
            })
        buttons[Action.AUTO_WATCH]?.let { b ->
            b.visibility = if (s.privateMode) android.view.View.GONE else android.view.View.VISIBLE
            b.set(R.drawable.ic_autoplay, context.getString(if (s.autoWatchHere) R.string.auto_watch_on else R.string.auto_watch_off))
        }
        buttons[Action.TRANSLATE]?.set(R.drawable.ic_translate,
            if (s.translated) context.getString(R.string.translate_off) else context.getString(R.string.translate_into, s.translateInto))
        buttons[Action.READER]?.set(R.drawable.ic_article,
            context.getString(if (s.reading) R.string.reader_view_off else R.string.reader_view))
        buttons[Action.USER_AGENT]?.set(if (s.desktop) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone,
            context.getString(R.string.user_agent))
        buttons[Action.POINTER]?.set(if (s.pointer) R.drawable.ic_mouse else R.drawable.ic_keyboard,
            context.getString(if (s.pointer) R.string.pointer_on else R.string.pointer_off))
        zoomLabel.text = context.getString(R.string.percent, s.zoomPercent)
        // At either end of the zoom, focus moves to the other zoom button instead of falling out of the row.
        zoomLimit(Action.ZOOM_OUT, s.canZoomOut, Action.ZOOM_IN)
        zoomLimit(Action.ZOOM_IN, s.canZoomIn, Action.ZOOM_OUT)
        // The focused button may have a new name (Reload to Stop, the page's address).
        placeTip()
        if (balloonOpen) placeBalloon()
    }

    private fun zoomLimit(action: Action, enabled: Boolean, other: Action) {
        val b = buttons[action] ?: return
        val focused = b.hasFocus()
        b.setEnabledLook(enabled)
        if (focused && !enabled) buttons[other]?.requestFocus()
    }

    private companion object {
        // Design pixels: the bar's width, the balloon's width, and the room its tail takes at the balloon's left.
        const val BAR = 108
        const val BALLOON = 470
        const val TAIL = 22
    }
}
