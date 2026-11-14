package app.sofabrowser.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A speech balloon: a rounded rectangle with a small tail on its left edge pointing at [tailY] (from the top of the
 * balloon). The tail takes the first [tailWidth] pixels of the bounds, so the balloon's content wants that much more
 * padding on the left.
 */
class BalloonDrawable(
    fill: Int, line: Int,
    private val radius: Float,
    private val tailWidth: Float,
    private val tailHalfHeight: Float,
    private val lineWidth: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; style = Paint.Style.STROKE; strokeWidth = lineWidth }
    private val path = Path()
    private val tail = Path()
    private val body = RectF()

    /** Where the tail's tip is, measured from the top of the balloon. */
    var tailY = 0f
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        body.set(b.left + tailWidth, b.top + lineWidth / 2, b.right - lineWidth / 2, b.bottom - lineWidth / 2)
        val y = (b.top + tailY).coerceIn(body.top + radius + tailHalfHeight, maxOf(body.top + radius + tailHalfHeight, body.bottom - radius - tailHalfHeight))
        path.rewind()
        path.addRoundRect(body, radius, radius, Path.Direction.CW)
        tail.rewind()
        tail.moveTo(body.left + 2f, y - tailHalfHeight)
        tail.lineTo(b.left + lineWidth / 2, y)
        tail.lineTo(body.left + 2f, y + tailHalfHeight)
        tail.close()
        path.op(tail, Path.Op.UNION)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, linePaint)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; linePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; linePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
