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
 * The browse menu, opened with BACK: one slim row with Home, the address pill, back, forward, reload, zoom and More.
 * More slides a row of labelled action buttons up below it, and folds it away again; the menu keeps that choice.
 * Like BrowsHere's, it remembers the last button used and doesn't hide on its own.
 */
class BrowserMenu(context: Context, private val actions: Actions) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    enum class Action { HOME, WATCH, AUTO_WATCH, BACK, FORWARD, RELOAD, FIND, BOOKMARK, PIN, TV_SHORTCUT, HISTORY, BOOKMARKS, DOWNLOADS, ADBLOCK, ALLOW_ONCE, USER_AGENT, SCROLL, POINTER, ZOOM_OUT, ZOOM_IN, SETTINGS }

    interface Actions {
        fun onAddress()
        fun onAction(action: Action)
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
    )

    private val buttons = LinkedHashMap<Action, LabeledIconButton>()
    private lateinit var address: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var more: LabeledIconButton
    private lateinit var actionsPane: android.view.View
    private val topRow = setOf(Action.HOME, Action.BACK, Action.FORWARD, Action.RELOAD, Action.ZOOM_OUT, Action.ZOOM_IN)
    private var lastFocused = Action.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // The whole screen, the sheet at its foot: the sheet then grows smoothly as More opens it, where a window
            // fitted to it would jump to its new size.
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (Space.active) Ui.p.sheetSpace else Ui.p.sheet)
                cornerRadii = FloatArray(8) { if (it < 4) 16.px.toFloat() else 0f }
            }
            setPadding(72.px, 24.px, 72.px, 24.px)
            // The focused button's scaled circle may reach into the padding.
            clipToPadding = false
        }

        val addressRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        address = Ui.text(context, 30, Ui.p.textMid).apply {
            isFocusable = true
            isClickable = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32.px, 0, 32.px, 0)
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 44)
            setCompoundDrawablesRelative(tinted(R.drawable.ic_search), null, null, null)
            compoundDrawablePadding = 20.px
            setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid)
                v.compoundDrawablesRelative[0]?.setTint(if (has) Ui.p.onFocus else Ui.p.textMid)
            }
            setOnClickListener { dismiss(); actions.onAddress() }
        }
        root.addView(addressRow)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        fun add(action: Action, icon: Int, label: Int, into: LinearLayout = row, gap: Int = 28) {
            // Beside the address, the icons speak for themselves: a label there would crowd the row below.
            val b = LabeledIconButton(context, icon, context.getString(label), labelled = into == row) { actions.onAction(action) }
            b.onFocused = { lastFocused = action }
            buttons[action] = b
            into.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap.px
            })
        }
        // Home stays in reach with the actions folded away.
        add(Action.HOME, R.drawable.ic_home, R.string.home, addressRow)
        addressRow.addView(address, LinearLayout.LayoutParams(0, 80.px, 1f).apply { marginEnd = 28.px })
        // Moving about the page, beside its address.
        add(Action.BACK, R.drawable.ic_arrow_back, R.string.back, addressRow, 16)
        add(Action.FORWARD, R.drawable.ic_arrow_forward, R.string.forward, addressRow, 16)
        add(Action.RELOAD, R.drawable.ic_refresh, R.string.reload, addressRow, 16)
        add(Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out, addressRow, 0)
        zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        addressRow.addView(zoomLabel, LinearLayout.LayoutParams(100.px, 88.px))
        add(Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in, addressRow, 28)
        more = LabeledIconButton(context, R.drawable.ic_keyboard_arrow_up, context.getString(R.string.more_actions), labelled = false) {
            prefs.menuExpanded = !expanded
            setExpanded(prefs.menuExpanded)
        }
        addressRow.addView(more, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Grouped by what they act on.
        // Watching, the action a TV is most often used for, first.
        add(Action.WATCH, R.drawable.ic_play_circle, R.string.watch)
        // Kept per site; not in private browsing, which saves none.
        add(Action.AUTO_WATCH, R.drawable.ic_autoplay, R.string.auto_watch)
        // This page: look through it, keep it.
        add(Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page)
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
        add(Action.SETTINGS, R.drawable.ic_settings, R.string.settings)

        // The row scrolls through the sheet's side padding to the screen edge, and a focused button keeps room for
        // its label (wider than the button) plus the padding, so neither icon nor label is ever cut.
        row.setPadding(72.px, 32.px, 72.px - 28.px, 0)
        actionsPane = FocusRowScrollView(context, ((LabeledIconButton.LABEL_WIDTH - LabeledIconButton.WIDTH) / 2 + 72).px).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
            visibility = android.view.View.GONE
        }
        // Below the slim row, as before: opening it lifts the sheet, the slim row with it.
        root.addView(actionsPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -72.px
            marginEnd = -72.px
        })
        setContentView(android.widget.FrameLayout(context).apply {
            addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
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

    /** Shows or hides the actions; from More ([animate]) the sheet grows or shrinks to fit, with focus following. */
    private fun setExpanded(open: Boolean, animate: Boolean = true) {
        if (!::actionsPane.isInitialized) return
        val changed = open != expanded || paneAnimator?.isRunning == true
        expanded = open
        more.set(if (open) R.drawable.ic_keyboard_arrow_down else R.drawable.ic_keyboard_arrow_up,
            context.getString(if (open) R.string.fewer_actions else R.string.more_actions))
        paneAnimator?.cancel()
        val pane = actionsPane
        if (!open && animate && pane.hasFocus()) more.requestFocus()
        if (!animate || !changed) {
            pane.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            pane.alpha = 1f
            pane.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            pane.requestLayout()
            return
        }
        // From the height it has now (part way, if it was moving) to its full height or none.
        val from = if (pane.visibility == android.view.View.VISIBLE) pane.height else 0
        pane.measure(android.view.View.MeasureSpec.makeMeasureSpec(maxOf(pane.width, 1), android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val to = if (open) pane.measuredHeight else 0
        pane.visibility = android.view.View.VISIBLE
        pane.layoutParams.height = from
        pane.requestLayout()
        paneAnimator = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                pane.layoutParams.height = it.animatedValue as Int
                pane.alpha = if (to > 0) it.animatedFraction else 1f - it.animatedFraction
                pane.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    pane.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
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
        address.text = s.address.ifEmpty { context.getString(R.string.search_hint) }
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
        buttons[Action.USER_AGENT]?.set(if (s.desktop) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone,
            context.getString(R.string.user_agent))
        buttons[Action.POINTER]?.set(if (s.pointer) R.drawable.ic_mouse else R.drawable.ic_keyboard,
            context.getString(if (s.pointer) R.string.pointer_on else R.string.pointer_off))
        zoomLabel.text = context.getString(R.string.percent, s.zoomPercent)
        // At either end of the zoom, focus moves to the other zoom button instead of falling out of the row.
        zoomLimit(Action.ZOOM_OUT, s.canZoomOut, Action.ZOOM_IN)
        zoomLimit(Action.ZOOM_IN, s.canZoomIn, Action.ZOOM_OUT)
    }

    private fun zoomLimit(action: Action, enabled: Boolean, other: Action) {
        val b = buttons[action] ?: return
        val focused = b.hasFocus()
        b.setEnabledLook(enabled)
        if (focused && !enabled) buttons[other]?.requestFocus()
    }

    private fun tinted(res: Int) = context.getDrawable(res)?.mutate()?.apply {
        setBounds(0, 0, 40.px, 40.px)
        setTint(Ui.p.textMid)
    }
}
