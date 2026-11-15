package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Favicons

/**
 * The open tabs at the foot of the menu bar: each tab's site icon, one under the other, the tab in view marked at the
 * bar's edge. OK on another tab goes to it; on the tab in view it opens the address and the search, with all tabs.
 *
 * The stack is never taller than [limit] leaves it. When the tabs don't fit, their icons overlap like a fanned deck,
 * and the focused one (or, without focus, the tab in view) is drawn on top with its neighbours slid off it; moving
 * the focus up and down slides the deck along.
 *
 * As [horizontal] (the menu along the bottom of the screen) the icons stand side by side instead, the tab in view
 * marked under its icon, and the focus moves left and right through them.
 */
@SuppressLint("ViewConstructor")
class TabStack(
    context: Context,
    /** The icons' focus square and the distance from one icon to the next when they don't overlap, in design pixels. */
    private val squareDp: Int,
    slotDp: Int,
    /** The most the stack may be tall, in pixels. */
    private val limit: () -> Int,
    private val onPick: (Long) -> Unit,
    private val onFocused: (LabeledIconButton) -> Unit,
    /** The icons side by side, the stack as wide as [limit] and the room it is given leave it, instead of one under the other. */
    private val horizontal: Boolean = false,
) : FrameLayout(context) {

    /** A tab: its site, its page's name, and the last picture of its page, if one was taken. */
    class Tab(val id: Long, val url: String, val title: String, val picture: android.graphics.Bitmap? = null)

    private val square = squareDp.px
    private val slot = slotDp.px
    private val buttons = ArrayList<LabeledIconButton>()
    private var tabs: List<Tab> = emptyList()
    private var current = -1L
    /** The index of the focused icon, -1 when none has the focus. */
    private var focus = -1
    /** Marks the tab in view, at the bar's left edge. */
    private val marker = View(context).apply { background = Ui.rounded(Ui.BRAND, 3) }
    /** The order the children are drawn in: the marker, then the icons, farthest from the focused one first. */
    private var order = IntArray(0)
    private var arranged = false
    /** Where each icon goes along the stack, as the last [arrange] put it. */
    private var places = IntArray(0)

    init {
        clipChildren = false
        clipToPadding = false
        isChildrenDrawingOrderEnabled = true
        // Under the icon, past the stack's foot, when the icons stand side by side.
        addView(marker, if (horizontal) LayoutParams(MARKER.px, 6.px, Gravity.TOP or Gravity.START).apply { topMargin = square + 4.px }
            else LayoutParams(6.px, MARKER.px, Gravity.TOP or Gravity.START).apply { leftMargin = 2.px })
    }

    /** Shows [list], with [currentId] the tab in view. */
    fun set(list: List<Tab>, currentId: Long) {
        val oldIds = tabs.map { it.id }
        val focusedId = buttons.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 }?.let { oldIds.getOrNull(it) }
        tabs = list
        current = currentId
        val rebuilt = list.map { it.id } != oldIds
        if (rebuilt) {
            buttons.forEach { removeView(it) }
            buttons.clear()
            list.forEach { t -> buttons += button(t.id) }
            focus = -1
            // A tab that had the focus keeps it; when it closed, its neighbour takes it.
            if (focusedId != null) {
                val at = list.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
                    ?: oldIds.indexOf(focusedId).coerceAtMost(list.size - 1)
                buttons.getOrNull(at)?.requestFocus()
            }
            requestLayout()
        }
        list.forEachIndexed { i, t ->
            buttons[i].setPicture(Shadowed(Favicons.get(context).drawable(t.url, name(t), 96)).apply { shown = !buttons[i].hasFocus() })
            buttons[i].contentDescription = name(t)
        }
        // New icons are put in place at once: only the focus moving slides them.
        arrange(animate = arranged && !rebuilt)
    }

    private fun button(id: Long): LabeledIconButton {
        val b = LabeledIconButton(context, R.drawable.ic_sofa, "", labelled = false, square = squareDp) { onPick(id) }
        b.onFocused = {
            shadow(b, false)
            focus = buttons.indexOf(b)
            arrange(animate = true)
            onFocused(b)
        }
        b.onBlurred = { shadow(b, true); post { if (buttons.none { it.hasFocus() } && focus >= 0) { focus = -1; arrange(animate = true) } } }
        // The icons overlap, so the focus moves through them in order, not by where they are drawn.
        val previous = if (horizontal) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
        val next = if (horizontal) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
        b.setOnKeyListener { _, code, e ->
            val i = buttons.indexOf(b)
            val down = e.action == KeyEvent.ACTION_DOWN
            when (code) {
                previous -> if (i > 0) { if (down) buttons[i - 1].requestFocus(); true } else false
                // The last tab is the foot of the bar (or its right end).
                next -> { if (down && i < buttons.size - 1) buttons[i + 1].requestFocus(); true }
                else -> false
            }
        }
        addView(b, if (horizontal) LayoutParams(square, square, Gravity.TOP or Gravity.START)
            else LayoutParams((BrowserMenu.BAR - 4).px, square, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        return b
    }

    /**
     * [picture] with a soft shadow around it (the shape of the picture itself, blurred), so that icons stacked over each
     * other stay apart. The shadow spills past the picture's bounds, into the room around the icon.
     */
    private class Shadowed(private val picture: Drawable) : Drawable() {
        private var shadow: Bitmap? = null
        private val offset = IntArray(2)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(SHADOW_ALPHA, 0, 0, 0) }

        /** Whether the shadow is drawn. */
        var shown = true
            set(value) { if (field != value) { field = value; invalidateSelf() } }

        override fun onBoundsChange(b: Rect) {
            picture.bounds = b
            shadow = null
            if (b.isEmpty) return
            // Drawn at its size on the screen, so the blur is the same for every icon, whatever the picture's size.
            val shape = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
            picture.setBounds(0, 0, b.width(), b.height())
            picture.draw(Canvas(shape))
            picture.bounds = b
            shadow = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(SHADOW_BLUR.px.toFloat(), BlurMaskFilter.Blur.NORMAL) }, offset)
            shape.recycle()
        }

        override fun draw(canvas: Canvas) {
            if (shown) shadow?.let { canvas.drawBitmap(it, (bounds.left + offset[0]).toFloat(), (bounds.top + offset[1] + SHADOW_DROP.px).toFloat(), paint) }
            picture.draw(canvas)
        }

        // No size of its own: the image view gives it all its room, without scaling the shadow.
        override fun getIntrinsicWidth() = -1
        override fun getIntrinsicHeight() = -1
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Shows or hides [b]'s shadow: the focused icon has none, as it sits on the focus square. */
    private fun shadow(b: LabeledIconButton, shown: Boolean) { b.picture()?.let { if (it is Shadowed) it.shown = shown } }

    /** Focuses the first tab; false when there is none. */
    fun focusFirst(): Boolean = buttons.firstOrNull()?.requestFocus() == true

    /** What the label shows for a tab: its name and address, and a picture of its page when it is not the one in view. */
    class Tip(val name: String, val url: String, val picture: android.graphics.Bitmap?)

    /** The label for [view], when it is one of the tabs. */
    fun tipFor(view: View): Tip? {
        val t = tabs.getOrNull(buttons.indexOf(view)) ?: return null
        return Tip(name(t), UrlInput.display(t.url), t.picture.takeIf { t.id != current })
    }

    /** Where [view], one of the tabs, goes along the stack once it has slid there: its left edge, or its top. */
    fun placeOf(view: View): Int? = places.getOrNull(buttons.indexOf(view))

    private fun name(t: Tab) = t.title.ifBlank { Browser.hostOf(t.url)?.removePrefix("www.").orEmpty() }
        .ifBlank { context.getString(R.string.new_tab) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Side by side, no wider than the room the row gives either.
        val room = if (horizontal && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED)
            minOf(limit(), MeasureSpec.getSize(widthMeasureSpec)) else limit()
        val length = minOf(TabStackLayout.natural(buttons.size, slot, square), maxOf(room, square))
        if (horizontal) super.onMeasure(MeasureSpec.makeMeasureSpec(length, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(square, MeasureSpec.EXACTLY))
        else super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(length, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A new height places the icons at once; only the focus moving slides them.
        arrange(animate = arranged && !changed)
    }

    /** Puts each icon where [TabStackLayout] says, sliding there when [animate]. */
    private fun arrange(animate: Boolean) {
        val n = buttons.size
        val length = if (horizontal) width else height
        if (n == 0 || length == 0) return
        val now = tabs.indexOfFirst { it.id == current }
        val f = if (focus >= 0) focus else now.coerceAtLeast(0)
        val tops = TabStackLayout.tops(n, f, length, slot, square, MIN_STEP.px)
        order = IntArray(n + 1).also { o ->
            o[0] = indexOfChild(marker)
            (0 until n).sortedByDescending { Math.abs(it - f) }.forEachIndexed { k, i -> o[k + 1] = indexOfChild(buttons[i]) }
        }
        places = tops
        invalidate()
        buttons.forEachIndexed { i, b -> move(b, tops[i].toFloat(), animate) }
        marker.visibility = if (n > 1 && now >= 0) VISIBLE else GONE
        if (now >= 0) move(marker, (tops[now] + (square - MARKER.px) / 2).toFloat(), animate)
        arranged = true
    }

    /** Slides [v] to [at] along the stack: down it, or across it when [horizontal]. */
    private fun move(v: View, at: Float, animate: Boolean) {
        v.animate().cancel()
        val now = if (horizontal) v.translationX else v.translationY
        if (animate && now != at) v.animate().apply { if (horizontal) translationX(at) else translationY(at) }
            .setDuration(200).setInterpolator(DecelerateInterpolator(1.5f)).start()
        else if (horizontal) v.translationX = at else v.translationY = at
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (order.size == childCount) order[drawingPosition] else drawingPosition

    companion object {
        /** The least the stacked icons are apart while the stack has room for it, in design pixels. */
        const val MIN_STEP = 14
        /** How tall the mark of the tab in view is. */
        const val MARKER = 36
        /** How soft each icon's shadow is, and how far it falls below the icon, in design pixels. */
        const val SHADOW_BLUR = 6
        const val SHADOW_DROP = 2
        const val SHADOW_ALPHA = 170
    }
}
