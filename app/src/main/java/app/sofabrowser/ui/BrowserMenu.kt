package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.Space

/**
 * The browse menu, opened with BACK: a slim vertical bar on the left edge of the screen, with Home, the address,
 * back, forward, reload, zoom and More, one under the other. More slides a second column of action buttons out beside
 * it, and folds it away again; the menu keeps that choice. The focused button's name shows in a small label beside
 * it. Like BrowsHere's, the menu remembers the last button used and doesn't hide on its own.
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
    private lateinit var actionsPane: android.view.View
    private val topRow = setOf(Action.HOME, Action.BACK, Action.FORWARD, Action.RELOAD, Action.ZOOM_OUT, Action.ZOOM_IN)
    private var lastFocused = Action.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // The whole screen, the bar at its left edge: the bar then widens smoothly as More opens it, where a window
            // fitted to it would jump to its new size.
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(if (Space.active) Ui.p.sheetSpace else Ui.p.sheet)
                // Rounded where it faces the page: the top right and bottom right corners.
                cornerRadii = FloatArray(8) { if (it in 2..5) 16.px.toFloat() else 0f }
            }
            clipChildren = false
        }

        fun column() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 48.px, 0, 48.px)
            // The focused button's scaled square may reach into the padding.
            clipChildren = false
            clipToPadding = false
        }
        val bar = column()
        val list = column()
        fun place(b: LabeledIconButton, into: LinearLayout, gap: Int) {
            b.onFocused = b.onFocused.let { was -> { was?.invoke(); showTip(b) } }
            into.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap.px
            })
        }
        fun add(action: Action, icon: Int, label: Int, into: LinearLayout = list, gap: Int = 16) {
            // The icons speak for themselves; the name of the focused one shows beside it.
            val b = LabeledIconButton(context, icon, context.getString(label), labelled = false) { actions.onAction(action) }
            b.onFocused = { lastFocused = action }
            buttons[action] = b
            place(b, into, gap)
        }
        // Home stays in reach with the actions folded away.
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
        add(Action.RELOAD, R.drawable.ic_refresh, R.string.reload, bar)
        add(Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out, bar, 0)
        zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        bar.addView(zoomLabel, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, 56.px))
        add(Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in, bar, 28)
        more = LabeledIconButton(context, R.drawable.ic_chevron_right, context.getString(R.string.more_actions), labelled = false) {
            prefs.menuExpanded = !expanded
            setExpanded(prefs.menuExpanded)
        }
        place(more, bar, 0)

        // Grouped by what they act on.
        // Watching, the action a TV is most often used for, first.
        add(Action.WATCH, R.drawable.ic_play_circle, R.string.watch)
        // Kept per site; not in private browsing, which saves none.
        add(Action.AUTO_WATCH, R.drawable.ic_autoplay, R.string.auto_watch)
        // This page: look through it, keep it.
        add(Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page)
        add(Action.TRANSLATE, R.drawable.ic_translate, R.string.translate)
        buttons[Action.TRANSLATE]?.setOnLongClickListener { actions.onActionHeld(Action.TRANSLATE); true }
        add(Action.READER, R.drawable.ic_article, R.string.reader_view)
        add(Action.HIDE_PARTS, R.drawable.ic_visibility_off, R.string.hide_parts)
        buttons[Action.HIDE_PARTS]?.setOnLongClickListener { actions.onActionHeld(Action.HIDE_PARTS); true }
        add(Action.BOOKMARK, R.drawable.ic_star, R.string.bookmark)
        add(Action.PIN, R.drawable.ic_add, R.string.pin_to_home)
        // A tile on the TV's home screen would show anyone what's in the private space.
        if (!Space.active) add(Action.TV_SHORTCUT, R.drawable.ic_tv, R.string.tv_shortcut)
        // Somewhere else to go.
        add(Action.BOOKMARKS, R.drawable.ic_bookmarks, R.string.bookmarks)
        add(Action.HISTORY, R.drawable.ic_history, R.string.history)
        add(Action.DOWNLOADS, R.drawable.ic_download, R.string.downloads)
        // How this site is shown.
        add(Action.ADBLOCK, R.drawable.ic_shield, R.string.adblock_site)
        // Shown only after a block, next to the shield it belongs with; the menu then opens with it focused.
        add(Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once)
        add(Action.USER_AGENT, R.drawable.ic_desktop_windows, R.string.user_agent)
        // How the remote moves about pages.
        add(Action.SCROLL, R.drawable.ic_swap_vert, R.string.scroll_mode)
        add(Action.POINTER, R.drawable.ic_mouse, R.string.pointer)
        add(Action.SETTINGS, R.drawable.ic_settings, R.string.settings, gap = 0)

        // The second column scrolls from the top of the screen to its foot; a focused button keeps some room from
        // both, so it is never cut.
        actionsPane = FocusScrollView(context, 96.px).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list, android.widget.FrameLayout.LayoutParams(COLUMN.px, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = android.view.View.GONE
            // The label follows its button as the column scrolls.
            setOnScrollChangeListener { _, _, _, _, _ -> placeTip() }
        }
        root.addView(bar, LinearLayout.LayoutParams(COLUMN.px, LinearLayout.LayoutParams.MATCH_PARENT))
        // Beside the bar: opening it widens the sheet.
        root.addView(actionsPane, LinearLayout.LayoutParams(COLUMN.px, LinearLayout.LayoutParams.MATCH_PARENT))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTip() }

        tip = Ui.text(context, 26, Ui.p.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = 900.px
            background = Ui.rounded(if (Space.active) Ui.p.sheetSpace else Ui.p.sheet, 12)
            setPadding(24.px, 14.px, 24.px, 14.px)
            visibility = android.view.View.INVISIBLE
        }
        setContentView(android.widget.FrameLayout(context).apply {
            addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
            addView(tip, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    private fun placeTip() {
        val button = tipFor ?: return
        val frame = tip.parent as? android.view.View ?: return
        if (!button.isAttachedToWindow || frame.height == 0) return
        tip.text = button.contentDescription
        tip.measure(android.view.View.MeasureSpec.makeMeasureSpec(900.px, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val at = IntArray(2).also { button.getLocationInWindow(it) }
        val origin = IntArray(2).also { frame.getLocationInWindow(it) }
        tip.x = (at[0] - origin[0] + button.width + 28.px).toFloat()
        tip.y = (at[1] - origin[1] + (button.height - tip.measuredHeight) / 2).coerceIn(8.px, maxOf(8.px, frame.height - tip.measuredHeight - 8.px)).toFloat()
    }

    private val prefs = app.sofabrowser.Prefs(context)
    private var expanded = false

    fun show(state: State) {
        if (!isShowing) show()
        update(state)
        // A pending "Allow once" is why the menu was opened, most likely: it opens with the actions out, this time only.
        if (state.allowPending) {
            setExpanded(true, animate = false)
            buttons[Action.ALLOW_ONCE]?.requestFocus()
            return
        }
        setExpanded(prefs.menuExpanded, animate = false)
        // An action last used from the folded row is one press away, on More.
        val last = buttons[lastFocused]?.takeIf { (expanded || lastFocused in topRow) && it.isEnabled && it.visibility == android.view.View.VISIBLE }
        (last ?: if (expanded || lastFocused in topRow) buttons[Action.HOME] else more)?.requestFocus()
    }

    private var paneAnimator: android.animation.ValueAnimator? = null

    /** Shows or hides the second column; from More ([animate]) the sheet widens or narrows to fit, with focus following. */
    private fun setExpanded(open: Boolean, animate: Boolean = true) {
        if (!::actionsPane.isInitialized) return
        val changed = open != expanded || paneAnimator?.isRunning == true
        expanded = open
        // One chevron, pointing at where the column comes from or goes back to.
        more.set(R.drawable.ic_chevron_right, context.getString(if (open) R.string.fewer_actions else R.string.more_actions))
        more.scaleX = if (open) -1f else 1f
        placeTip()
        paneAnimator?.cancel()
        val pane = actionsPane
        val full = COLUMN.px
        if (!open && animate && pane.hasFocus()) more.requestFocus()
        if (!animate || !changed) {
            pane.layoutParams.width = full
            pane.alpha = 1f
            pane.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            pane.requestLayout()
            return
        }
        // From the width it has now (part way, if it was moving) to its full width or none.
        val from = if (pane.visibility == android.view.View.VISIBLE) pane.width else 0
        val to = if (open) full else 0
        pane.visibility = android.view.View.VISIBLE
        pane.layoutParams.width = from
        pane.requestLayout()
        paneAnimator = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                pane.layoutParams.width = it.animatedValue as Int
                pane.alpha = if (to > 0) it.animatedFraction else 1f - it.animatedFraction
                pane.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    pane.layoutParams.width = full
                    pane.alpha = 1f
                    if (!open) pane.visibility = android.view.View.GONE
                    pane.requestLayout()
                }
            })
            start()
        }
        if (open) (buttons[lastFocused]?.takeIf { lastFocused !in topRow && it.isEnabled && it.visibility == android.view.View.VISIBLE }
            ?: buttons[Action.WATCH])?.requestFocus()
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
    }

    private fun zoomLimit(action: Action, enabled: Boolean, other: Action) {
        val b = buttons[action] ?: return
        val focused = b.hasFocus()
        b.setEnabledLook(enabled)
        if (focused && !enabled) buttons[other]?.requestFocus()
    }

    private companion object {
        /** Design pixels: the width of the bar, and of the column More opens beside it. */
        const val COLUMN = 136
    }
}
