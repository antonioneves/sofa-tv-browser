package app.sofabrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.FrameLayout

/**
 * The root of a dialog: draws a soft shadow behind its first child, a rounded sheet with corners of [corner] design
 * pixels. Drawn here rather than with view elevation, which some TVs and emulators don't render. The blurred shadow is
 * made once per sheet size.
 */
class ShadowFrame(context: Context, private val corner: Int) : FrameLayout(context) {

    private var shadow: Bitmap? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        val sheet = getChildAt(0)
        if (sheet != null && sheet.width > 0 && sheet.height > 0) {
            val blur = BLUR.px
            val w = sheet.width + blur * 2
            val h = sheet.height + blur * 2
            val bitmap = shadow?.takeIf { it.width == w && it.height == h } ?: render(w, h, sheet.width, sheet.height, blur).also { shadow = it }
            canvas.drawBitmap(bitmap, sheet.left.toFloat() - blur, sheet.top.toFloat() - blur + DROP.px, null)
        }
        super.dispatchDraw(canvas)
    }

    private fun render(w: Int, h: Int, sheetW: Int, sheetH: Int, blur: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 190
            maskFilter = BlurMaskFilter(blur / 2f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawRoundRect(blur.toFloat(), blur.toFloat(), (blur + sheetW).toFloat(), (blur + sheetH).toFloat(),
            corner.px.toFloat(), corner.px.toFloat(), paint)
        return bitmap
    }

    private companion object {
        const val BLUR = 56 // design px of room around the sheet for the shadow
        const val DROP = 16 // how far the shadow sits below the sheet
    }
}
