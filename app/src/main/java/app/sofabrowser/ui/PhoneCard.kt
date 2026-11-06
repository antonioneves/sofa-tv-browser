package app.sofabrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.nayuki.qrcodegen.QrCode

/** A page on the home network (such as typing on the phone) shown as its QR code beside numbered steps. */
object PhoneCard {

    /** The code on a white tile beside [steps], with [orLabel] and the address to type underneath them. */
    fun view(context: Context, address: String, steps: List<String>, orLabel: String): View {
        val p = Ui.p
        val tile = FrameLayout(context).apply {
            background = Ui.rounded(Color.WHITE, 28, p.border, if (p.dark) 0 else 2)
            // The tile's white margin is the code's quiet zone.
            setPadding(32.px, 32.px, 32.px, 32.px)
            addView(ImageView(context).apply { setImageDrawable(qrCode(context, address)) }, FrameLayout.LayoutParams(264.px, 264.px))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            steps.forEachIndexed { i, step ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.text(context, 24, Color.WHITE, bold = true).apply {
                        text = (i + 1).toString()
                        gravity = Gravity.CENTER
                        background = Ui.rounded(Ui.SWITCH_ON, 22)
                    }, LinearLayout.LayoutParams(44.px, 44.px).apply { marginEnd = 20.px })
                    addView(Ui.text(context, 28, p.text).apply { text = step },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = 20.px
                })
            }
            addView(Ui.text(context, 22, p.textHint).apply { text = orLabel },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 40.px
                })
            // Browsers add the scheme themselves, so the address to type is shorter without it.
            addView(Ui.text(context, 26, p.text, bold = true).apply {
                text = address.removePrefix("http://")
                background = Ui.rounded(p.fill, 16)
                setPadding(20.px, 14.px, 20.px, 14.px)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.px
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16.px, 0, 0)
            addView(tile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 44.px })
        }
    }

    /** Black modules on white, drawn one pixel per module and scaled up without smoothing. */
    private fun qrCode(context: Context, text: String): BitmapDrawable {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val n = qr.size
        val pixels = IntArray(n * n) { i -> if (qr.getModule(i % n, i / n)) Color.BLACK else Color.WHITE }
        return BitmapDrawable(context.resources, Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)).apply {
            isFilterBitmap = false
        }
    }
}
