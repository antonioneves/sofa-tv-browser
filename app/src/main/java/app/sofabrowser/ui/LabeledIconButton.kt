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
 * always shown, with the focus fill behind the whole line. With [iconOnly] the icon sits in the middle of the line
 * and there is no label.
 */
@SuppressLint("ViewConstructor")
class LabeledIconButton(context: Context, icon: Int, label: String, private val labelled: Boolean = true,
    private val row: Boolean = false, iconOnly: Boolean = false, private val square: Int = 80, onClick: () -> Unit) : FrameLayout(context) {
    private val circle = FrameLayout(context)
    private val image = ImageView(context)
    private val text: TextView = Ui.text(context, 24, Ui.p.text)

    /** The icon's colour while not focused, instead of the text colour. */
    var accent: Int? = null
        set(value) { field = value; tint(hasFocus()) }

    private var plain = false

    /** Called when the button gains focus. */
    var onFocused: (() -> Unit)? = null

    /** Called when the button loses focus. */
    var onBlurred: (() -> Unit)? = null

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
            minimumHeight = 64.px
            setPadding(0, 8.px, 0, 8.px)
            if (iconOnly) {
                addView(image, LayoutParams(36.px, 36.px, Gravity.CENTER))
            } else {
                addView(image, LayoutParams(36.px, 36.px, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = 18.px })
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24.px.toFloat())
                text.maxLines = 2
                text.ellipsize = TextUtils.TruncateAt.END
                addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
                    marginStart = 70.px
                    marginEnd = 14.px
                })
            }
        } else {
            // A rounded square, not a circle, with the corners a site's icon has in it ([setPicture]): every focus
            // square in a row of buttons alike, icon or picture.
            circle.background = Ui.fillSelector(Color.TRANSPARENT, Ui.p.focus, pictureRadius())
            circle.isDuplicateParentStateEnabled = true
            // A site's icon may cast a shadow past its square (the tabs' icons).
            circle.clipChildren = false
            circle.addView(image, LayoutParams(48.px, 48.px, Gravity.CENTER))
            addView(circle, LayoutParams(square.px, square.px, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

            text.gravity = Gravity.CENTER_HORIZONTAL
            text.maxLines = 1
            text.visibility = if (labelled) INVISIBLE else GONE
            addView(text, LayoutParams(LABEL_WIDTH.px, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = 92.px
            })
            layoutParams = LayoutParams(WIDTH.px, if (labelled) 128.px else square.px)
        }
        tint(false)
        setOnFocusChangeListener { _, has -> focused(has) }
    }

    fun set(icon: Int, label: String) {
        if (plain && !row) image.layoutParams = LayoutParams(48.px, 48.px, Gravity.CENTER)
        plain = false
        image.setImageResource(icon)
        text.text = label
        contentDescription = label
        tint(hasFocus())
    }

    /** A tinted icon as big as a site's icon ([setPicture]), with its corners: the new tab button beside the tabs. */
    fun setFullIcon(icon: Int) {
        plain = false
        if (!row) image.layoutParams = LayoutParams((square - 8).px, (square - 8).px, Gravity.CENTER)
        image.setImageResource(icon)
        tint(hasFocus())
    }

    /** A picture as it is, a site's icon, in place of a tinted icon. It fills the focus square but for a thin margin. */
    fun setPicture(picture: android.graphics.drawable.Drawable) {
        plain = true
        // Fills nearly all of the focus square, where a drawn icon keeps room around it.
        if (!row) image.layoutParams = LayoutParams((square - 8).px, (square - 8).px, Gravity.CENTER)
        image.setImageDrawable(picture)
        image.clearColorFilter()
    }

    /** The picture [setPicture] gave, or null when the button shows an icon. */
    fun picture(): android.graphics.drawable.Drawable? = if (plain) image.drawable else null

    /**
     * The focus square's corner radius around a site's icon: the icon's own (Favicons.CORNER of its size) plus the margin
     * around it, so that the two curves run side by side and the focus ring is as thick at the corners as on the sides.
     */
    private fun pictureRadius() = Math.round((square - 8) * app.sofabrowser.data.Favicons.CORNER + 4)

    /** A button that cannot be used is not shown: gone, or invisible where its neighbours must stay in place. */
    fun setEnabledLook(enabled: Boolean, gone: Boolean = true) {
        isEnabled = enabled
        isFocusable = enabled
        visibility = if (enabled) VISIBLE else if (gone) GONE else INVISIBLE
    }

    private fun focused(has: Boolean) {
        if (has) onFocused?.invoke() else onBlurred?.invoke()
        tint(has)
        if (row) {
            text.setTextColor(if (has) Ui.p.onFocus else Ui.p.text)
            return
        }
        if (labelled) text.visibility = if (has) VISIBLE else INVISIBLE
        Ui.animateScale(circle, if (has) 1.12f else 1f, has)
    }

    private fun tint(focused: Boolean) { if (!plain) image.setColorFilter(if (focused) Ui.p.onFocus else accent ?: Ui.p.text) }

    /** The icon, for the sofa to move from and into ([SofaGlide]). */
    val icon: ImageView get() = image

    /** The icon's colour as it shows now: null for a picture, which has its own. */
    fun iconColor(): Int? = if (plain) null else if (hasFocus()) Ui.p.onFocus else accent ?: Ui.p.text

    companion object {
        const val WIDTH = 104
        const val LABEL_WIDTH = 320
    }
}
