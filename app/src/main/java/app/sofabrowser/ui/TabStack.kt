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
) : FrameLayout(context) {

    class Tab(val id: Long, val url: String, val title: String)

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

    init {
        clipChildren = false
        clipToPadding = false
        isChildrenDrawingOrderEnabled = true
        addView(marker, LayoutParams(6.px, MARKER.px, Gravity.TOP or Gravity.START).apply { leftMargin = 2.px })
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
        b.setOnKeyListener { _, code, e ->
            val i = buttons.indexOf(b)
            val down = e.action == KeyEvent.ACTION_DOWN
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> if (i > 0) { if (down) buttons[i - 1].requestFocus(); true } else false
                // The last tab is the foot of the bar.
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && i < buttons.size - 1) buttons[i + 1].requestFocus(); true }
                else -> false
            }
        }
        addView(b, LayoutParams((BrowserMenu.BAR - 4).px, square, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
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

    /** The name and address the label shows for [view], when it is one of the tabs. */
    fun tipFor(view: View): Pair<String, String>? {
        val t = tabs.getOrNull(buttons.indexOf(view)) ?: return null
        return name(t) to UrlInput.display(t.url)
    }

    private fun name(t: Tab) = t.title.ifBlank { Browser.hostOf(t.url)?.removePrefix("www.").orEmpty() }
        .ifBlank { context.getString(R.string.new_tab) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = minOf(TabStackLayout.natural(buttons.size, slot, square), maxOf(limit(), square))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A new height places the icons at once; only the focus moving slides them.
        arrange(animate = arranged && !changed)
    }

    /** Puts each icon where [TabStackLayout] says, sliding there when [animate]. */
    private fun arrange(animate: Boolean) {
        val n = buttons.size
        if (n == 0 || height == 0) return
        val now = tabs.indexOfFirst { it.id == current }
        val f = if (focus >= 0) focus else now.coerceAtLeast(0)
        val tops = TabStackLayout.tops(n, f, height, slot, square, MIN_STEP.px)
        order = IntArray(n + 1).also { o ->
            o[0] = indexOfChild(marker)
            (0 until n).sortedByDescending { Math.abs(it - f) }.forEachIndexed { k, i -> o[k + 1] = indexOfChild(buttons[i]) }
        }
        invalidate()
        buttons.forEachIndexed { i, b -> move(b, tops[i].toFloat(), animate) }
        marker.visibility = if (n > 1 && now >= 0) VISIBLE else GONE
        if (now >= 0) move(marker, (tops[now] + (square - MARKER.px) / 2).toFloat(), animate)
        arranged = true
    }

    private fun move(v: View, y: Float, animate: Boolean) {
        v.animate().cancel()
        if (animate && v.translationY != y) v.animate().translationY(y).setDuration(200).setInterpolator(DecelerateInterpolator(1.5f)).start()
        else v.translationY = y
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
