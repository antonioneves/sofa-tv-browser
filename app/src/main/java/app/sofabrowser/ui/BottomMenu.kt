package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.Space

/**
 * The page's menu along the bottom of the screen, as Settings can have it in place of the bar at the left edge
 * ([BrowserMenu]): one slim row with Home, the address pill, back, forward, reload, zoom and More. More slides a row
 * of labelled action buttons up below it, and folds it away again; the menu keeps that choice. The tabs are on the
 * search screen, which the address opens. It remembers the last button used and doesn't hide on its own.
 */
class BottomMenu(context: Context, private val actions: BrowserMenu.Actions) :
    Dialog(context, android.R.style.Theme_Translucent_NoTitleBar), PageMenu {

    private val buttons = LinkedHashMap<BrowserMenu.Action, LabeledIconButton>()
    private lateinit var address: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var more: LabeledIconButton
    private lateinit var actionsPane: View
    private val topRow = setOf(BrowserMenu.Action.HOME, BrowserMenu.Action.BACK, BrowserMenu.Action.FORWARD,
        BrowserMenu.Action.RELOAD, BrowserMenu.Action.ZOOM_OUT, BrowserMenu.Action.ZOOM_IN)
    private var lastFocused = BrowserMenu.Action.HOME
    private val prefs = Prefs(context)
    private var expanded = false

    override val docked get() = false
    override val shown get() = isShowing
    override val inUse get() = isShowing
    override val dockHasFocus get() = false
    override var onLeave: (() -> Unit)? = null

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
            // The focused button's scaled square may reach into the padding.
            clipToPadding = false
            // A tap on the sheet between its buttons is not a tap outside the menu.
            isClickable = true
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
            // The menu stays until the search screen comes: its sofa moves there from the Home button.
            setOnClickListener { actions.onAddress() }
        }
        root.addView(addressRow)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        fun add(action: BrowserMenu.Action, icon: Int, label: Int, into: LinearLayout = row, gap: Int = 28) {
            // Beside the address, the icons speak for themselves: a label there would crowd the row below.
            val b = LabeledIconButton(context, icon, context.getString(label), labelled = into == row) { actions.onAction(action) }
            b.onFocused = { lastFocused = action }
            buttons[action] = b
            into.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = gap.px
            })
        }
        // Home stays in reach with the actions folded away.
        // The sofa is Sofa's home: orange at rest, like on the start screen.
        add(BrowserMenu.Action.HOME, R.drawable.ic_sofa, R.string.home, addressRow)
        buttons[BrowserMenu.Action.HOME]?.accent = Ui.BRAND
        addressRow.addView(address, LinearLayout.LayoutParams(0, 80.px, 1f).apply { marginEnd = 28.px })
        // Moving about the page, beside its address.
        add(BrowserMenu.Action.BACK, R.drawable.ic_arrow_back, R.string.back, addressRow, 16)
        add(BrowserMenu.Action.FORWARD, R.drawable.ic_arrow_forward, R.string.forward, addressRow, 16)
        add(BrowserMenu.Action.RELOAD, R.drawable.ic_refresh, R.string.reload, addressRow, 16)
        add(BrowserMenu.Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out, addressRow, 0)
        zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        addressRow.addView(zoomLabel, LinearLayout.LayoutParams(100.px, 88.px))
        add(BrowserMenu.Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in, addressRow, 28)
        more = LabeledIconButton(context, R.drawable.ic_keyboard_arrow_up, context.getString(R.string.more_actions), labelled = false) {
            prefs.menuExpanded = !expanded
            setExpanded(prefs.menuExpanded)
        }
        addressRow.addView(more, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Grouped by what they act on.
        // Watching, the action a TV is most often used for, first.
        add(BrowserMenu.Action.WATCH, R.drawable.ic_play_circle, R.string.watch)
        // Kept per site; not in private browsing, which saves none.
        add(BrowserMenu.Action.AUTO_WATCH, R.drawable.ic_autoplay, R.string.auto_watch)
        // This page: look through it, keep it.
        add(BrowserMenu.Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page)
        add(BrowserMenu.Action.TRANSLATE, R.drawable.ic_translate, R.string.translate)
        buttons[BrowserMenu.Action.TRANSLATE]?.setOnLongClickListener { actions.onActionHeld(BrowserMenu.Action.TRANSLATE); true }
        add(BrowserMenu.Action.READER, R.drawable.ic_article, R.string.reader_view)
        add(BrowserMenu.Action.HIDE_PARTS, R.drawable.ic_visibility_off, R.string.hide_parts)
        buttons[BrowserMenu.Action.HIDE_PARTS]?.setOnLongClickListener { actions.onActionHeld(BrowserMenu.Action.HIDE_PARTS); true }
        add(BrowserMenu.Action.BOOKMARK, R.drawable.ic_star, R.string.bookmark)
        add(BrowserMenu.Action.PIN, R.drawable.ic_add, R.string.add_to_quick_access)
        // A tile on the TV's home screen would show anyone what's in the private space.
        if (!Space.active) add(BrowserMenu.Action.TV_SHORTCUT, R.drawable.ic_tv, R.string.tv_shortcut)
        // The tabs: a new one, or done with this one. The others are on the search screen.
        add(BrowserMenu.Action.NEW_TAB, R.drawable.ic_tab_new, R.string.new_tab)
        add(BrowserMenu.Action.CLOSE_TAB, R.drawable.ic_close, R.string.close_tab)
        // Somewhere else to go.
        add(BrowserMenu.Action.BOOKMARKS, R.drawable.ic_bookmarks, R.string.bookmarks)
        add(BrowserMenu.Action.HISTORY, R.drawable.ic_history, R.string.history)
        add(BrowserMenu.Action.DOWNLOADS, R.drawable.ic_download, R.string.downloads)
        // How this site is shown.
        add(BrowserMenu.Action.ADBLOCK, R.drawable.ic_shield, R.string.adblock_site)
        // Shown only after a block, next to the shield it belongs with; the menu then opens with it focused.
        add(BrowserMenu.Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once)
        add(BrowserMenu.Action.USER_AGENT, R.drawable.ic_desktop_windows, R.string.user_agent)
        // How the remote moves about pages.
        add(BrowserMenu.Action.SCROLL, R.drawable.ic_swap_vert, R.string.scroll_mode)
        add(BrowserMenu.Action.POINTER, R.drawable.ic_mouse, R.string.pointer)
        add(BrowserMenu.Action.SETTINGS, R.drawable.ic_settings, R.string.settings)

        // The row scrolls through the sheet's side padding to the screen edge, and a focused button keeps room for
        // its label (wider than the button) plus the padding, so neither icon nor label is ever cut.
        row.setPadding(72.px, 32.px, 72.px - 28.px, 0)
        actionsPane = FocusRowScrollView(context, ((LabeledIconButton.LABEL_WIDTH - LabeledIconButton.WIDTH) / 2 + 72).px).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
            visibility = View.GONE
        }
        // Below the slim row: opening it lifts the sheet, the slim row with it.
        root.addView(actionsPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -72.px
            marginEnd = -72.px
        })
        setContentView(FrameLayout(context).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    override fun show(state: BrowserMenu.State) {
        if (!isShowing) show()
        update(state)
        // A pending "Allow once" is why the menu was opened, most likely: it opens with the actions out, this time only.
        if (state.allowPending) {
            setExpanded(true, animate = false)
            buttons[BrowserMenu.Action.ALLOW_ONCE]?.requestFocus()
            return
        }
        setExpanded(prefs.menuExpanded, animate = false)
        // An action last used from the folded row is one press away, on More.
        val last = buttons[lastFocused]?.takeIf { (expanded || lastFocused in topRow) && usable(it) }
        (last ?: if (expanded || lastFocused in topRow) buttons[BrowserMenu.Action.HOME] else more)?.requestFocus()
    }

    override fun sofa(): SofaGlide.Spot? {
        if (!isShowing) return null
        val b = buttons[BrowserMenu.Action.HOME] ?: return null
        return SofaGlide.spot(b.icon, b.iconColor())
    }

    /** The window covers the screen, so a tap that the sheet doesn't take is a tap outside it: the menu closes. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) dismiss()
        return true
    }

    private fun usable(b: View) = b.isEnabled && b.visibility == View.VISIBLE

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
            pane.visibility = if (open) View.VISIBLE else View.GONE
            pane.requestLayout()
            return
        }
        // From the height it has now (part way, if it was moving) to its full height or none.
        val from = if (pane.visibility == View.VISIBLE) pane.height else 0
        pane.measure(View.MeasureSpec.makeMeasureSpec(maxOf(pane.width, 1), View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val to = if (open) pane.measuredHeight else 0
        pane.visibility = View.VISIBLE
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
                    if (!open) pane.visibility = View.GONE
                    pane.requestLayout()
                }
            })
            start()
        }
        if (open) (buttons[lastFocused]?.takeIf { lastFocused !in topRow && usable(it) }
            ?: buttons[BrowserMenu.Action.WATCH])?.requestFocus()
    }

    override fun update(s: BrowserMenu.State) {
        if (!::address.isInitialized) return
        address.text = s.address.ifEmpty { context.getString(R.string.search_hint) }
        buttons[BrowserMenu.Action.ALLOW_ONCE]?.visibility = if (s.allowPending) View.VISIBLE else View.GONE
        // Back and Forward stay in place, dimmed while they cannot be used; the focus moves on from one that goes.
        dim(BrowserMenu.Action.BACK, s.canGoBack, BrowserMenu.Action.FORWARD)
        dim(BrowserMenu.Action.FORWARD, s.canGoForward, BrowserMenu.Action.BACK)
        buttons[BrowserMenu.Action.RELOAD]?.set(if (s.loading) R.drawable.ic_close else R.drawable.ic_refresh,
            context.getString(if (s.loading) R.string.stop else R.string.reload))
        buttons[BrowserMenu.Action.BOOKMARK]?.set(if (s.bookmarked) R.drawable.ic_star_filled else R.drawable.ic_star,
            context.getString(when {
                s.bookmarked && s.pinned -> R.string.bookmarked_pinned
                s.bookmarked -> R.string.bookmarked
                else -> R.string.bookmark
            }))
        buttons[BrowserMenu.Action.PIN]?.set(if (s.pinned) R.drawable.ic_close else R.drawable.ic_add,
            context.getString(if (s.pinned) R.string.remove_from_quick_access else R.string.add_to_quick_access))
        buttons[BrowserMenu.Action.ADBLOCK]?.set(if (s.adBlockOn) R.drawable.ic_shield_filled else R.drawable.ic_shield,
            when {
                !s.adBlockOn -> context.getString(R.string.adblock_site_off)
                s.blocked > 0 -> context.resources.getQuantityString(R.plurals.adblock_site_on_count, s.blocked, s.blocked)
                else -> context.getString(R.string.adblock_site_on)
            })
        buttons[BrowserMenu.Action.AUTO_WATCH]?.let { b ->
            b.visibility = if (s.privateMode) View.GONE else View.VISIBLE
            b.set(R.drawable.ic_autoplay, context.getString(if (s.autoWatchHere) R.string.auto_watch_on else R.string.auto_watch_off))
        }
        buttons[BrowserMenu.Action.TRANSLATE]?.set(R.drawable.ic_translate,
            if (s.translated) context.getString(R.string.translate_off) else context.getString(R.string.translate_into, s.translateInto))
        buttons[BrowserMenu.Action.READER]?.set(R.drawable.ic_article,
            context.getString(if (s.reading) R.string.reader_view_off else R.string.reader_view))
        buttons[BrowserMenu.Action.USER_AGENT]?.set(if (s.desktop) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone,
            context.getString(R.string.user_agent))
        buttons[BrowserMenu.Action.POINTER]?.set(if (s.pointer) R.drawable.ic_mouse else R.drawable.ic_keyboard,
            context.getString(if (s.pointer) R.string.pointer_on else R.string.pointer_off))
        zoomLabel.text = context.getString(R.string.percent, s.zoomPercent)
        // At either end of the zoom, focus moves to the other zoom button instead of falling out of the row.
        dim(BrowserMenu.Action.ZOOM_OUT, s.canZoomOut, BrowserMenu.Action.ZOOM_IN)
        dim(BrowserMenu.Action.ZOOM_IN, s.canZoomIn, BrowserMenu.Action.ZOOM_OUT)
    }

    /** A button that cannot be used stays in its place, dimmed and passed over; its focus goes to [other], or Reload. */
    private fun dim(action: BrowserMenu.Action, enabled: Boolean, other: BrowserMenu.Action) {
        val b = buttons[action] ?: return
        val focused = b.hasFocus()
        b.isEnabled = enabled
        b.isFocusable = enabled
        b.alpha = if (enabled) 1f else 0.35f
        if (focused && !enabled) (buttons[other]?.takeIf { usable(it) } ?: buttons[BrowserMenu.Action.RELOAD])?.requestFocus()
    }

    private fun tinted(res: Int) = context.getDrawable(res)?.mutate()?.apply {
        setBounds(0, 0, 40.px, 40.px)
        setTint(Ui.p.textMid)
    }
}
