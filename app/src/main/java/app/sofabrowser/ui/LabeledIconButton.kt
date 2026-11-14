package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * A round icon button with its label shown underneath only while focused (BrowsHere's top bar and menu style).
 * The button has a fixed width; the label may spill sideways over its neighbours, so hidden labels never make
 * the spacing uneven. On/off state is shown by the icon itself (filled when on, outlined when off). Without
 * [labelled], the button is just its circle, and the label is only read out by TalkBack.
 *
 * As a [row] (the actions in the menu's balloon) it is a full-width line instead: the icon, then the label beside it,
 * always shown, with the focus fill behind the whole line.
 */
@SuppressLint("ViewConstructor")
class LabeledIconButton(context: Context, icon: Int, label: String, private val labelled: Boolean = true,
    private val row: Boolean = false, onClick: () -> Unit) : FrameLayout(context) {
    private val circle = FrameLayout(context)
    private val image = ImageView(context)
    private val text: TextView = Ui.text(context, 24, Ui.p.text)

    /** The icon's colour while not focused, instead of the text colour. */
    var accent: Int? = null
        set(value) { field = value; tint(hasFocus()) }

    /** Called when the button gains focus. */
    var onFocused: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        contentDescription = label
        setOnClickListener { if (isEnabled) onClick() }

        image.setImageResource(icon)
        text.text = label
        if (row) {
            background = Ui.fillSelector(Color.TRANSPARENT, Ui.p.focus, 20)
            minimumHeight = 72.px
            setPadding(0, 10.px, 0, 10.px)
            addView(image, LayoutParams(40.px, 40.px, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = 24.px })
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26.px.toFloat())
            text.maxLines = 2
            text.ellipsize = TextUtils.TruncateAt.END
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
                marginStart = 88.px
                marginEnd = 20.px
            })
        } else {
            // A rounded square, not a circle, like Sofa's other focused buttons.
            circle.background = Ui.fillSelector(Color.TRANSPARENT, Ui.p.focus, 24)
            circle.isDuplicateParentStateEnabled = true
            circle.addView(image, LayoutParams(48.px, 48.px, Gravity.CENTER))
            addView(circle, LayoutParams(80.px, 80.px, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

            text.gravity = Gravity.CENTER_HORIZONTAL
            text.maxLines = 1
            text.visibility = if (labelled) INVISIBLE else GONE
            addView(text, LayoutParams(LABEL_WIDTH.px, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 92.px
            })
            layoutParams = LayoutParams(WIDTH.px, if (labelled) 128.px else 80.px)
        }
        tint(false)
        setOnFocusChangeListener { _, has -> focused(has) }
    }

    fun set(icon: Int, label: String) {
        image.setImageResource(icon)
        text.text = label
        contentDescription = label
        tint(hasFocus())
    }

    fun setEnabledLook(enabled: Boolean) {
        isEnabled = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun focused(has: Boolean) {
        if (has) onFocused?.invoke()
        tint(has)
        if (row) {
            text.setTextColor(if (has) Ui.p.onFocus else Ui.p.text)
            return
        }
        if (labelled) text.visibility = if (has) VISIBLE else INVISIBLE
        Ui.animateScale(circle, if (has) 1.12f else 1f, has)
    }

    private fun tint(focused: Boolean) = image.setColorFilter(if (focused) Ui.p.onFocus else accent ?: Ui.p.text)

    companion object {
        const val WIDTH = 104
        const val LABEL_WIDTH = 320
    }
}
