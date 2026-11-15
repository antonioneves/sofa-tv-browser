package app.sofabrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import app.sofabrowser.R

/**
 * The sofa moving from its place on one screen to its place on the next, instead of leaving one corner and showing up
 * in another: from the menu bar's Home button to the corner of the home screen, or of the search screen, which has it
 * in the same place ([corner]), and from the home screen to the bar docked beside a page.
 *
 * The moving sofa is drawn over the whole window ([View.getOverlay]), so nothing on the way hides it, and it changes
 * size and colour as it goes. The sofa it is going to waits unseen in its place, and shows again once it arrives.
 */
object SofaGlide {
    /** The sofa as it shows: where on the screen, and its colour (null: its own orange). */
    class Spot(val rect: RectF, val color: Int?)

    /** The corner of the home and search screens the sofa sits in (design pixels, from the top left of the screen). */
    const val CORNER_X = 64
    const val CORNER_Y = 48
    const val SIZE = 64

    private const val MS = 300L
    /** The most a kept sofa waits for the screen coming over it: a slow one shows soon after anyway. */
    private const val KEEP_MS = 3000L

    fun spot(icon: ImageView, color: Int? = null): Spot? = rect(icon)?.let { Spot(it, color) }

    /** The home and search screens' corner, on the screen. */
    fun corner(activity: Activity): RectF {
        val at = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
        return RectF(CORNER_X.px.toFloat(), CORNER_Y.px.toFloat(), (CORNER_X + SIZE).px.toFloat(), (CORNER_Y + SIZE).px.toFloat())
            .apply { offset(at[0].toFloat(), at[1].toFloat()) }
    }

    /** Where [icon]'s picture shows on the screen, grown or moved with the views it is in (a focused button grows). */
    fun rect(icon: ImageView): RectF? {
        val d = icon.drawable ?: return null
        if (!icon.isAttachedToWindow || !icon.isShown || icon.width == 0) return null
        val r = RectF(d.bounds)
        icon.imageMatrix.mapRect(r)
        r.offset(icon.paddingLeft.toFloat(), icon.paddingTop.toFloat())
        val m = Matrix()
        var v: View = icon
        while (true) {
            m.postConcat(v.matrix)
            m.postTranslate(v.left.toFloat(), v.top.toFloat())
            val parent = v.parent as? View ?: break
            m.postTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
            v = parent
        }
        m.mapRect(r)
        val at = IntArray(2).also { v.getLocationOnScreen(it) }
        r.offset(at[0] - v.left.toFloat(), at[1] - v.top.toFloat())
        return r
    }

    /**
     * The sofa that was at [from] goes into [icon]'s place, once the screen [icon] is on is laid out: just before it
     * is next drawn. [color] is the colour it ends in there (null: its own orange).
     */
    fun to(activity: Activity, from: Spot?, icon: ImageView, color: () -> Int? = { null }) {
        from ?: return
        val observer = icon.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (observer.isAlive) observer else icon.viewTreeObserver).removeOnPreDrawListener(this)
                val to = rect(icon) ?: return true
                icon.alpha = 0f
                glide(activity, from, to, color()) { icon.alpha = 1f }
                return true
            }
        })
    }

    /**
     * Draws the sofa moving from [from] to [to] (on the screen) over [activity]'s window, then calls [end]. With
     * [keep] it stays at [to] afterwards, for another screen with it in the same place to come over it: until the
     * returned function is called, or [KEEP_MS] at the most.
     */
    fun glide(activity: Activity, from: Spot, to: RectF, toColor: Int?, keep: Boolean = false, end: () -> Unit = {}): () -> Unit {
        val decor = activity.window.decorView
        val sofa = activity.getDrawable(R.drawable.ic_sofa)?.mutate() ?: run { end(); return {} }
        val remove = { decor.overlay.remove(sofa) }
        val at = IntArray(2).also { decor.getLocationOnScreen(it) }
        val a = RectF(from.rect).apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
        val b = RectF(to).apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
        val colors = ArgbEvaluator()
        val fromColor = from.color ?: Ui.BRAND
        val endColor = toColor ?: Ui.BRAND
        val bounds = Rect()
        fun frame(f: Float) {
            bounds.set(Math.round(a.left + (b.left - a.left) * f), Math.round(a.top + (b.top - a.top) * f),
                Math.round(a.right + (b.right - a.right) * f), Math.round(a.bottom + (b.bottom - a.bottom) * f))
            sofa.bounds = bounds
            sofa.colorFilter = PorterDuffColorFilter(colors.evaluate(f, fromColor, endColor) as Int, PorterDuff.Mode.SRC_IN)
            sofa.invalidateSelf()
        }
        frame(0f)
        decor.overlay.add(sofa)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MS
            // Quick to leave, slow to settle, like the screens' other movements.
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    end()
                    if (keep) decor.postDelayed(remove, KEEP_MS) else remove()
                }
            })
            start()
        }
        return remove
    }
}
