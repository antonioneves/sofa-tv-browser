package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * A vertical scroller that keeps the focused row [margin] away from its top and bottom edges. A plain ScrollView
 * stops as soon as the row fits, which leaves a focus-scaled row touching the screen edge and cut off.
 */
@SuppressLint("ViewConstructor")
open class FocusScrollView(context: Context, private val margin: Int = 120.px) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect).apply { inset(0, -margin) })
}

/** The same for a row scrolled sideways, so a focused tile at either end isn't cut by the screen edge. */
@SuppressLint("ViewConstructor")
class FocusRowScrollView(context: Context, private val margin: Int = 64.px) : HorizontalScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect).apply { inset(-margin, 0) })
}
