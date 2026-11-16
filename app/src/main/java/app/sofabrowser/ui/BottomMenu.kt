package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
 * ([BrowserMenu]): that bar turned on its side, the same buttons in the same order from left to right (Home, the
 * page's address, More, dock, back, forward, reload) and at its right end a new tab and the open tabs ([TabStack]).
 * More slides a row of labelled action buttons out below the bar, zoom first, and folds it away again; the menu keeps
 * that choice. A button that cannot be used (back, forward, zoom at either end) is not shown, and the address takes
 * its room. Like the bar at the left it can be docked: kept on screen under the page ([dock]), or opened over the
 * page with BACK or MENU. It remembers the last button used and doesn't hide on its own.
 */
class BottomMenu(context: Context, private val actions: BrowserMenu.Actions) :
    Dialog(context, android.R.style.Theme_Translucent_NoTitleBar), PageMenu {

    private val buttons = LinkedHashMap<BrowserMenu.Action, LabeledIconButton>()
    private lateinit var address: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var more: LabeledIconButton
    private lateinit var actionsPane: View
    private lateinit var tabStack: TabStack
    /** The bar, whose top edge the labels sit above. */
    private lateinit var sheet: View
    private lateinit var tip: TextView
    private lateinit var tipUrl: TextView
    private lateinit var tipPicture: android.widget.ImageView
    private lateinit var tipBox: LinearLayout
    /** The tab in view: OK on its icon opens the address and the search, as on the bar's. */
    private var shownTab = -1L
    /** The room the bar's buttons take, Back and Forward while hidden too, so the tabs never take the room they come back to. */
    private var reserved = 0
    private val topRow = setOf(BrowserMenu.Action.HOME, BrowserMenu.Action.DOCK, BrowserMenu.Action.BACK, BrowserMenu.Action.FORWARD, BrowserMenu.Action.RELOAD,
        BrowserMenu.Action.NEW_TAB, BrowserMenu.Action.PHONE_CREDENTIALS)
    private var lastFocused = BrowserMenu.Action.HOME
    private val prefs = Prefs(context)
    private var expanded = false

    /** The sheet, the label and the frame as big as the screen they lie on: in this dialog, or docked in the screen. */
    private lateinit var content: FrameLayout
    private var created = false

    /** The bar is docked in the screen (see [dock]) and not shown in this dialog. */
    override var docked = false
        private set
    private var dockHost: FrameLayout? = null

    /** The bar is on screen: its dialog is showing, or it is docked and not hidden behind another screen. */
    override val shown get() = isShowing || docked && content.visibility == View.VISIBLE
    /** The bar is being used: its dialog is showing, or the docked bar has the focus. */
    override val inUse get() = isShowing || dockHasFocus
    override val dockHasFocus get() = docked && content.findFocus() != null
    /** Called when the docked bar is left: the focus goes back to the page. */
    override var onLeave: (() -> Unit)? = null

    private val leavingFocus = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        // Focus went to the page: the actions and the label are done with.
        if (now == null || !within(now, content)) {
            hideTip()
            setExpanded(false, animate = false)
        }
    }

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
        created = true
        if (!docked) setContentView(build())
    }

    private fun build(): FrameLayout {
        if (::content.isInitialized) return content
        val sheetColor = if (Space.active) Ui.p.sheetSpace else Ui.p.sheet
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Square corners, like the bar at the left: it runs from one side of the screen to the other.
            setBackgroundColor(sheetColor)
            // The focused button's scaled square may reach past the bar, and the mark under the tab in view lies at its foot.
            clipToPadding = false
            clipChildren = false
            // A tap on the sheet between its buttons is not a tap outside the menu.
            isClickable = true
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SIDE.px, 0, SIDE.px, 0)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, BrowserMenu.BAR.px))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        fun place(b: LabeledIconButton, gap: Int) {
            b.onFocused = b.onFocused.let { was -> { was?.invoke(); showTip(b) } }
            bar.addView(b, LinearLayout.LayoutParams(BrowserMenu.BAR_SQUARE.px, BrowserMenu.BAR_SQUARE.px).apply { marginEnd = gap.px })
            reserved += BrowserMenu.BAR_SQUARE + gap
        }
        fun add(action: BrowserMenu.Action, icon: Int, label: Int, gap: Int = GAP, inBar: Boolean = false) {
            // In the bar the icons speak for themselves, their names in a label above; in the row below each has its name under it.
            val b = LabeledIconButton(context, icon, context.getString(label), labelled = !inBar,
                square = if (inBar) BrowserMenu.BAR_SQUARE else 80) { actions.onAction(action) }
            b.onFocused = { lastFocused = action; if (!inBar) hideTip() }
            buttons[action] = b
            if (inBar) place(b, gap)
            else row.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap.px })
        }
        // Home stays in reach with the actions folded away.
        // The sofa is Sofa's home: orange at rest, like on the start screen.
        add(BrowserMenu.Action.HOME, R.drawable.ic_sofa, R.string.home, BrowserMenu.GROUP_GAP, inBar = true)
        buttons[BrowserMenu.Action.HOME]?.accent = Ui.BRAND
        // The page's address, the way to the search; it takes the room of Back and Forward while they are hidden ([fitAddress]).
        address = Ui.text(context, 26, Ui.p.textMid).apply {
            isFocusable = true
            isClickable = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28.px, 0, 28.px, 0)
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, BrowserMenu.BAR_SQUARE / 2)
            setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
            // The menu stays until the search screen comes: its sofa moves there from the Home button.
            setOnClickListener { actions.onAddress() }
        }
        bar.addView(address, LinearLayout.LayoutParams(ADDRESS.px, BrowserMenu.BAR_SQUARE.px).apply { marginEnd = BrowserMenu.GROUP_GAP.px })
        reserved += ADDRESS + BrowserMenu.GROUP_GAP
        // More comes next; the actions come out below the bar.
        more = LabeledIconButton(context, R.drawable.ic_more_widgets, context.getString(R.string.more_actions), labelled = false,
            square = BrowserMenu.BAR_SQUARE) {
            prefs.menuExpanded = !expanded
            setExpanded(prefs.menuExpanded)
        }
        // Down on More opens the actions, or goes into them when they are out, as they lie in that direction.
        more.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && e.action == android.view.KeyEvent.ACTION_DOWN && !expanded) {
                prefs.menuExpanded = true
                setExpanded(true); true
            } else false
        }
        place(more, GAP)
        // How the bar lives: kept on screen under the page, or out of sight until BACK or MENU opens it.
        add(BrowserMenu.Action.DOCK, R.drawable.ic_dock_bottom_off, R.string.bar_dock_off, BrowserMenu.GROUP_GAP, inBar = true)
        // Moving about the page.
        // Reload comes first: it is always there, while Back and Forward come and go with the page's history.
        add(BrowserMenu.Action.RELOAD, R.drawable.ic_refresh, R.string.reload, inBar = true)
        add(BrowserMenu.Action.BACK, R.drawable.ic_arrow_back, R.string.back, inBar = true)
        add(BrowserMenu.Action.FORWARD, R.drawable.ic_arrow_forward, R.string.forward, BrowserMenu.GROUP_GAP, inBar = true)
        // Only while the page shows a sign-in form: the way to type it on the phone.
        add(BrowserMenu.Action.PHONE_CREDENTIALS, R.drawable.ic_smartphone, R.string.signin_phone_title, BrowserMenu.GROUP_GAP, inBar = true)
        buttons[BrowserMenu.Action.PHONE_CREDENTIALS]?.visibility = View.GONE
        // The tabs at the bar's right end, after the button for a new one, an empty tab where the next goes.
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        add(BrowserMenu.Action.NEW_TAB, R.drawable.ic_tab_new, R.string.new_tab, GAP, inBar = true)
        buttons[BrowserMenu.Action.NEW_TAB]?.setFullIcon(R.drawable.ic_tab_new)
        // The tabs' icons overlap, so the focus goes on to the first of them, not to the nearest drawn.
        buttons[BrowserMenu.Action.NEW_TAB]?.setOnKeyListener { _, code, e ->
            code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && (e.action != android.view.KeyEvent.ACTION_DOWN || tabStack.focusFirst())
        }
        // The open tabs, side by side while they fit, then overlapping: whatever the buttons and one more empty place leave.
        tabStack = TabStack(context, BrowserMenu.BAR_SQUARE, BrowserMenu.BAR_SQUARE + BrowserMenu.TAB_GAP, limit = {
            val w = bar.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            w - bar.paddingLeft - bar.paddingRight - (reserved + BrowserMenu.BAR_SQUARE + GAP).px
        }, onPick = { id -> if (id == shownTab) actions.onAddress() else actions.onTab(id) }, onFocused = { showTip(it) }, horizontal = true)
        bar.addView(tabStack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The page's zoom first, set apart from the actions after it.
        add(BrowserMenu.Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out, gap = 0)
        zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        row.addView(zoomLabel, LinearLayout.LayoutParams(100.px, 80.px))
        add(BrowserMenu.Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in, gap = 0)
        // A dot level with the icons, taking the room the line it replaced had.
        row.addView(View(context).apply { background = Ui.rounded(Ui.p.textWeak, DOT / 2) }, LinearLayout.LayoutParams(DOT.px, DOT.px).apply {
            topMargin = (40 - DOT / 2).px
            marginStart = (21 - DOT / 2).px
            marginEnd = (21 - DOT / 2).px + ROW_GAP.px
        })

        // Grouped by what they act on.
        // Watching, the action a TV is most often used for, first.
        add(BrowserMenu.Action.WATCH, R.drawable.ic_play_circle, R.string.watch, ROW_GAP)
        // Kept per site; not in private browsing, which saves none.
        add(BrowserMenu.Action.AUTO_WATCH, R.drawable.ic_autoplay, R.string.auto_watch, ROW_GAP)
        // This page: look through it, keep it.
        add(BrowserMenu.Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page, ROW_GAP)
        add(BrowserMenu.Action.TRANSLATE, R.drawable.ic_translate, R.string.translate, ROW_GAP)
        buttons[BrowserMenu.Action.TRANSLATE]?.setOnLongClickListener { actions.onActionHeld(BrowserMenu.Action.TRANSLATE); true }
        add(BrowserMenu.Action.READER, R.drawable.ic_article, R.string.reader_view, ROW_GAP)
        add(BrowserMenu.Action.HIDE_PARTS, R.drawable.ic_visibility_off, R.string.hide_parts, ROW_GAP)
        buttons[BrowserMenu.Action.HIDE_PARTS]?.setOnLongClickListener { actions.onActionHeld(BrowserMenu.Action.HIDE_PARTS); true }
        add(BrowserMenu.Action.BOOKMARK, R.drawable.ic_star, R.string.bookmark, ROW_GAP)
        add(BrowserMenu.Action.PIN, R.drawable.ic_add, R.string.add_to_quick_access, ROW_GAP)
        // A tile on the TV's home screen would show anyone what's in the private space.
        if (!Space.active) add(BrowserMenu.Action.TV_SHORTCUT, R.drawable.ic_tv, R.string.tv_shortcut, ROW_GAP)
        // Done with this tab; a new one is beside the tabs above.
        add(BrowserMenu.Action.CLOSE_TAB, R.drawable.ic_close, R.string.close_tab, ROW_GAP)
        // Somewhere else to go.
        add(BrowserMenu.Action.BOOKMARKS, R.drawable.ic_bookmarks, R.string.bookmarks, ROW_GAP)
        add(BrowserMenu.Action.HISTORY, R.drawable.ic_history, R.string.history, ROW_GAP)
        add(BrowserMenu.Action.DOWNLOADS, R.drawable.ic_download, R.string.downloads, ROW_GAP)
        // How this site is shown.
        add(BrowserMenu.Action.ADBLOCK, R.drawable.ic_shield, R.string.adblock_site, ROW_GAP)
        // Shown only after a block, next to the shield it belongs with; the menu then opens with it focused.
        add(BrowserMenu.Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once, ROW_GAP)
        add(BrowserMenu.Action.PHONE_SIGNIN, R.drawable.ic_smartphone, R.string.activation_title, ROW_GAP)
        add(BrowserMenu.Action.USER_AGENT, R.drawable.ic_desktop_windows, R.string.user_agent, ROW_GAP)
        add(BrowserMenu.Action.CLEAR_SITE, R.drawable.ic_delete, R.string.clear_site_data, ROW_GAP)
        // How the remote moves about pages.
        add(BrowserMenu.Action.SCROLL, R.drawable.ic_swap_vert, R.string.scroll_mode, ROW_GAP)
        add(BrowserMenu.Action.POINTER, R.drawable.ic_mouse, R.string.pointer, ROW_GAP)
        add(BrowserMenu.Action.SETTINGS, R.drawable.ic_settings, R.string.settings, ROW_GAP)

        // A focused button keeps room for its label (wider than the button) from the screen edge, so neither icon nor
        // label is ever cut.
        row.setPadding(SIDE.px, 18.px, SIDE.px - ROW_GAP.px, 24.px)
        val scroller = FocusRowScrollView(context, ((LabeledIconButton.LABEL_WIDTH - LabeledIconButton.WIDTH) / 2 + SIDE).px).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }
        // A line between the bar and the actions, coming and going with them.
        actionsPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(View(context).apply { background = Ui.rounded(Ui.p.textWeak, 1) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2.px).apply {
                    marginStart = SIDE.px
                    marginEnd = SIDE.px
                    topMargin = 10.px
                })
            addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        // Below the bar: opening it lifts the sheet, the bar with it.
        root.addView(actionsPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The focused button's name over the page above it; a tab's name and address, with a picture of its page when it
        // is out of view.
        tip = Ui.text(context, 26, Ui.p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = TIP_TEXT.px }
        tipUrl = Ui.text(context, 22, Ui.p.textWeak).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = TIP_TEXT.px }
        tipPicture = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, 8.px.toFloat())
            }
            clipToOutline = true
            visibility = View.GONE
        }
        tipBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(sheetColor, 12)
            setPadding(24.px, 14.px, 24.px, 14.px)
            addView(tipPicture, LinearLayout.LayoutParams(TIP_PICTURE.px, TIP_PICTURE.px * 9 / 16).apply { topMargin = 10.px; bottomMargin = 14.px })
            addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tipUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.px })
            visibility = View.INVISIBLE
        }
        content = FrameLayout(context).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(tipBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // The label goes with the focus off the bar's buttons: to the address, into the row of actions or out of the menu.
        address.onFocusChangeListener.let { was -> address.setOnFocusChangeListener { v, has -> was.onFocusChange(v, has); if (has) hideTip() } }
        content.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now == null || !within(now, content)) hideTip() }
        sheet = root
        // It follows the sheet as it grows or shrinks.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTip() }
        return content
    }

    /** Docks the bar in [host], at [index] among its views, instead of floating it over the page in a dialog. */
    override fun dock(host: FrameLayout, index: Int) {
        if (docked) return
        val c = build()
        if (isShowing) dismiss()
        (c.parent as? ViewGroup)?.removeView(c)
        host.addView(c, index, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dockHost = host
        docked = true
        c.viewTreeObserver.addOnGlobalFocusChangeListener(leavingFocus)
    }

    /** The bar goes back to its dialog, which opens with BACK or MENU. */
    override fun undock() {
        if (!docked) return
        docked = false
        content.viewTreeObserver.removeOnGlobalFocusChangeListener(leavingFocus)
        dockHost?.removeView(content)
        dockHost = null
        content.visibility = View.VISIBLE
        if (created) setContentView(content)
    }

    /** The docked bar is shown, or out of the way of the home screen and the fullscreen video. */
    override fun setDockVisible(visible: Boolean) {
        if (!docked) return
        content.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** A key while the docked bar has the focus: BACK and MENU give the focus back to the page, and so does UP from the bar, as the page is above it. */
    override fun dockedKey(event: android.view.KeyEvent): Boolean {
        val focus = content.findFocus() ?: return false
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> { if (down && event.repeatCount == 0) dismiss(); return true }
            android.view.KeyEvent.KEYCODE_MENU -> { if (down) dismiss(); return true }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> if (!within(focus, actionsPane)) { if (down) dismiss(); return true }
        }
        return false
    }

    /** Closing a docked bar leaves it where it is, and gives the focus back to the page. */
    override fun dismiss() {
        if (!docked) return super.dismiss()
        if (::actionsPane.isInitialized) setExpanded(false, animate = false)
        hideTip()
        onLeave?.invoke()
    }

    private fun within(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private var tipFor: LabeledIconButton? = null

    private fun showTip(b: LabeledIconButton) {
        tipFor = b
        tipBox.visibility = View.VISIBLE
        // Placed once the icon is where its slide along the row leaves it.
        tipBox.post { placeTip() }
    }

    private fun hideTip() {
        tipFor = null
        tipBox.visibility = View.INVISIBLE
    }

    /** Above the sheet, centred over the button (a tab's icon where it slides to), and inside the screen. */
    private fun placeTip() {
        val b = tipFor ?: return
        val frame = tipBox.parent as? View ?: return
        if (!b.isAttachedToWindow || frame.width == 0) return
        val t = tabStack.tipFor(b)
        val name = t?.name ?: (b.contentDescription ?: "").toString()
        val url = t?.url.orEmpty()
        val picture = t?.picture
        if (tipPicture.drawable.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } !== picture) tipPicture.setImageBitmap(picture)
        tipPicture.visibility = if (picture == null) View.GONE else View.VISIBLE
        // Only when they changed: this runs on every layout pass, and setting the same text asks for another layout.
        if (tip.text.toString() != name) tip.text = name
        if (tipUrl.text.toString() != url) tipUrl.text = url
        tipUrl.visibility = if (url.isEmpty()) View.GONE else View.VISIBLE
        tipBox.measure(View.MeasureSpec.makeMeasureSpec(900.px, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tipBox.layout(0, 0, tipBox.measuredWidth, tipBox.measuredHeight)
        val origin = IntArray(2).also { frame.getLocationInWindow(it) }
        val sheetTop = IntArray(2).also { sheet.getLocationInWindow(it) }[1]
        val left = if (t != null) IntArray(2).also { tabStack.getLocationInWindow(it) }[0] + (tabStack.placeOf(b) ?: 0)
            else IntArray(2).also { b.getLocationInWindow(it) }[0]
        val centre = left - origin[0] + b.width / 2
        tipBox.x = (centre - tipBox.measuredWidth / 2).coerceIn(16.px, maxOf(16.px, frame.width - tipBox.measuredWidth - 16.px)).toFloat()
        tipBox.y = (sheetTop - origin[1] - tipBox.measuredHeight - 16.px).toFloat()
    }

    override fun show(state: BrowserMenu.State) {
        if (!docked && !isShowing) show()
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

    /** The bar's sofa stays put: no sofa glides out of it, or into it, when the screen changes. */
    override fun sofa(): SofaGlide.Spot? = null

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
        more.set(R.drawable.ic_more_widgets,
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
        if (!::tabStack.isInitialized) return
        address.text = s.address.ifEmpty { context.getString(R.string.search_hint) }
        shownTab = s.currentTab
        buttons[BrowserMenu.Action.DOCK]?.set(if (s.docked) R.drawable.ic_dock_bottom_on else R.drawable.ic_dock_bottom_off,
            context.getString(if (s.docked) R.string.bar_dock_on else R.string.bar_dock_off))
        tabStack.set(s.tabs, s.currentTab)
        // The label of a tab that just closed goes with it; the focused button may have a new name (Reload to Stop).
        tipFor?.let { if (it.parent == null) hideTip() else tipBox.post { placeTip() } }
        buttons[BrowserMenu.Action.ALLOW_ONCE]?.visibility = if (s.allowPending) View.VISIBLE else View.GONE
        // Back and Forward are not shown while they cannot be used; the focus moves on from one that goes.
        hideUnusable(BrowserMenu.Action.BACK, s.canGoBack, BrowserMenu.Action.FORWARD)
        hideUnusable(BrowserMenu.Action.FORWARD, s.canGoForward, BrowserMenu.Action.BACK)
        fitAddress()
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
        buttons[BrowserMenu.Action.PHONE_SIGNIN]?.visibility = if (s.phoneSignIn) View.VISIBLE else View.GONE
        buttons[BrowserMenu.Action.PHONE_CREDENTIALS]?.takeIf { (it.visibility == View.VISIBLE) != s.phoneCredentials }?.let {
            if (!s.phoneCredentials && it.hasFocus()) buttons[BrowserMenu.Action.RELOAD]?.requestFocus()
            it.visibility = if (s.phoneCredentials) View.VISIBLE else View.GONE
        }
        buttons[BrowserMenu.Action.ADBLOCK]?.visibility = if (s.adBlockEnabled) View.VISIBLE else View.GONE
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
        hideUnusable(BrowserMenu.Action.ZOOM_OUT, s.canZoomOut, BrowserMenu.Action.ZOOM_IN)
        hideUnusable(BrowserMenu.Action.ZOOM_IN, s.canZoomIn, BrowserMenu.Action.ZOOM_OUT)
    }

    /** A button that cannot be used is not shown; its focus goes first to [other], or Reload. */
    private fun hideUnusable(action: BrowserMenu.Action, enabled: Boolean, other: BrowserMenu.Action) {
        val b = buttons[action] ?: return
        if (!enabled && b.hasFocus()) (buttons[other]?.takeIf { usable(it) } ?: buttons[BrowserMenu.Action.RELOAD])?.requestFocus()
        b.setEnabledLook(enabled)
    }

    /** The address is [ADDRESS] wide with Back and Forward shown, and takes the room of each that is hidden. */
    private fun fitAddress() {
        val hidden = listOf(BrowserMenu.Action.BACK, BrowserMenu.Action.FORWARD).count { buttons[it]?.visibility != View.VISIBLE }
        val width = (ADDRESS + hidden * (BrowserMenu.BAR_SQUARE + GAP)).px
        if (address.layoutParams.width != width) { address.layoutParams.width = width; address.requestLayout() }
    }

    companion object {
        /** The bar's room at its left and right ends; between its buttons, the left bar's gaps ([BrowserMenu.GAP]). */
        const val SIDE = 28
        const val GAP = BrowserMenu.GAP
        /** Between the labelled buttons in the row below the bar. */
        const val ROW_GAP = 14
        const val DOT = 8
        /** The address's width with Back and Forward shown. */
        const val ADDRESS = 600
        const val TIP_TEXT = 640
        const val TIP_PICTURE = 400
    }
}
