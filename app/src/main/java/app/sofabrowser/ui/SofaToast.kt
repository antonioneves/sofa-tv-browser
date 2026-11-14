package app.sofabrowser.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import app.sofabrowser.R

/**
 * Sofa's own toast: the message in a dark pill with the orange sofa in front of it. Android draws a text toast with
 * the app's launcher icon (the sofa on an orange square), and that cannot be changed, so this one brings its own
 * view. It is only for screens of Sofa itself: Android doesn't show custom toasts for an app that is in the
 * background, which is why the messages sent from a receiver or a service keep the plain Toast.
 *
 * Used like Toast.makeText: `SofaToast.makeText(this, R.string.x, Toast.LENGTH_SHORT).show()`.
 */
object SofaToast {

    fun makeText(context: Context, res: Int, duration: Int): Toast = makeText(context, context.getText(res), duration)

    @Suppress("DEPRECATION") // custom toast views: the only way to choose the icon
    fun makeText(context: Context, text: CharSequence, duration: Int): Toast {
        val p = Ui.p
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (p.dark) 0xFF252B48.toInt() else 0xFFFFFFFF.toInt(), 32, p.fillFocused, 2)
            setPadding(28.px, 16.px, 36.px, 16.px)
        }
        view.addView(ImageView(context).apply { setImageResource(R.drawable.ic_sofa) },
            LinearLayout.LayoutParams(44.px, 44.px).apply { marginEnd = 20.px })
        view.addView(Ui.text(context, 28, p.text).apply {
            this.text = text
            maxWidth = 1100.px
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Toast(context).apply {
            this.view = view
            this.duration = duration
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 64.px)
        }
    }
}
