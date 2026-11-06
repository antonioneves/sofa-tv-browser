package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sofa's dialog, in the style of Material 3 Expressive: a large rounded tonal container with a headline, supporting
 * text, pill-shaped choice rows with a radio mark, and pill buttons (a filled primary, tonal secondaries). Focus is
 * the filled, scaled state used across Sofa.
 *
 * The [Builder] mirrors the parts of AlertDialog.Builder that Sofa uses, so call sites read the same.
 */
class SofaDialog private constructor(context: Context, private val b: Builder) :
    Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    private var positive: View? = null
    private var neutral: View? = null
    private var negative: View? = null
    private var firstFocus: View? = null
    private var messageView: TextView? = null

    class Builder(internal val context: Context) {
        internal var title: CharSequence? = null
        internal var message: CharSequence? = null
        internal var items: Array<out CharSequence>? = null
        internal var checked = -1
        internal var single = false
        internal var onItem: DialogInterface.OnClickListener? = null
        internal var view: View? = null
        internal var positiveText: CharSequence? = null
        internal var positiveClick: DialogInterface.OnClickListener? = null
        internal var neutralText: CharSequence? = null
        internal var neutralClick: DialogInterface.OnClickListener? = null
        internal var negativeText: CharSequence? = null
        internal var negativeClick: DialogInterface.OnClickListener? = null
        internal var onDismiss: DialogInterface.OnDismissListener? = null
        internal var onCancel: DialogInterface.OnCancelListener? = null

        fun setTitle(t: CharSequence?) = apply { title = t }
        fun setTitle(res: Int) = apply { title = context.getString(res) }
        fun setMessage(m: CharSequence?) = apply { message = m }
        fun setMessage(res: Int) = apply { message = context.getString(res) }
        fun setView(v: View) = apply { view = v }

        fun setItems(items: Array<out CharSequence>, l: DialogInterface.OnClickListener) = apply {
            this.items = items; single = false; onItem = l
        }

        fun setSingleChoiceItems(items: Array<out CharSequence>, checked: Int, l: DialogInterface.OnClickListener) = apply {
            this.items = items; this.checked = checked; single = true; onItem = l
        }

        fun setSingleChoiceItems(res: Int, checked: Int, l: DialogInterface.OnClickListener) =
            setSingleChoiceItems(context.resources.getTextArray(res), checked, l)

        fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { positiveText = t; positiveClick = l }
        fun setPositiveButton(res: Int, l: DialogInterface.OnClickListener?) = setPositiveButton(context.getString(res), l)
        fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { neutralText = t; neutralClick = l }
        fun setNeutralButton(res: Int, l: DialogInterface.OnClickListener?) = setNeutralButton(context.getString(res), l)
        fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { negativeText = t; negativeClick = l }
        fun setNegativeButton(res: Int, l: DialogInterface.OnClickListener?) = setNegativeButton(context.getString(res), l)
        fun setOnDismissListener(l: DialogInterface.OnDismissListener) = apply { onDismiss = l }
        fun setOnCancelListener(l: DialogInterface.OnCancelListener) = apply { onCancel = l }

        fun create() = SofaDialog(context, this)
        fun show() = create().also { it.show() }
    }

    /** Replaces the supporting text of a dialog that is showing, as a download's progress does. */
    fun setMessage(m: CharSequence) { messageView?.text = m }

    /** The button of [which] (a DialogInterface.BUTTON_* constant), once the dialog has been shown. */
    fun getButton(which: Int): View? = when (which) {
        DialogInterface.BUTTON_POSITIVE -> positive
        DialogInterface.BUTTON_NEUTRAL -> neutral
        else -> negative
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        b.onDismiss?.let { setOnDismissListener(it) }
        b.onCancel?.let { setOnCancelListener(it) }

        val p = Ui.p
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (p.dark) 0xFF252B48.toInt() else 0xFFFFFFFF.toInt(), 44, p.fillFocused, 2)
            setPadding(44.px, 40.px, 44.px, 32.px)
            clipChildren = false
            // A focused button at the end of the row grows into the padding.
            clipToPadding = false
        }
        val wide = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        b.title?.let {
            sheet.addView(Ui.text(context, 40, p.text, bold = true).apply { text = it }, wide)
        }
        b.message?.let { msg ->
            val text = Ui.text(context, 28, p.textMid).apply { text = msg; setLineSpacing(0f, 1.15f) }.also { messageView = it }
            val scroll = Capped(context, 80.px).apply {
                isFocusable = true
                isVerticalScrollBarEnabled = false
                addView(text)
            }
            sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (b.title != null) topMargin = 20.px
            })
        }
        b.items?.let { items -> sheet.addView(choices(items), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 20.px
        }) }
        b.view?.let { v ->
            styleFields(v)
            sheet.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.px
            })
        }
        if (b.positiveText != null || b.neutralText != null || b.negativeText != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                clipChildren = false
            }
            b.neutralText?.let { t ->
                neutral = pill(t, DialogInterface.BUTTON_NEUTRAL, primary = false) { b.neutralClick }.also { row.addView(it, gap()) }
            }
            b.negativeText?.let { t ->
                negative = pill(t, DialogInterface.BUTTON_NEGATIVE, primary = false) { b.negativeClick }.also { row.addView(it, gap()) }
            }
            b.positiveText?.let { t ->
                positive = pill(t, DialogInterface.BUTTON_POSITIVE, primary = true) { b.positiveClick }.also { row.addView(it, gap()) }
            }
            sheet.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 28.px
            })
        }

        val root = FrameLayout(context)
        root.addView(sheet, FrameLayout.LayoutParams(900.px, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // Keep the sheet within the screen: long text and long lists scroll inside it.
        root.setPadding(0, 48.px, 0, 48.px)
        root.clipChildren = false
        setContentView(root)
        (firstFocus ?: positive ?: negative ?: neutral)?.requestFocus()
    }

    private fun gap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 64.px).apply { marginStart = 12.px }

    private fun pill(label: CharSequence, which: Int, primary: Boolean, click: () -> DialogInterface.OnClickListener?): TextView {
        val p = Ui.p
        val idle = if (primary) Ui.SWITCH_ON else p.fillFocused
        val idleText = if (primary) Color.WHITE else p.text
        return Ui.text(context, 28, idleText, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            maxLines = 1
            setPadding(36.px, 0, 36.px, 0)
            minWidth = 140.px
            background = Ui.fillSelector(idle, p.focus, 32)
            Ui.focusScale(this, 1.06f) { has -> setTextColor(if (has) p.onFocus else idleText) }
            setOnClickListener {
                click()?.onClick(this@SofaDialog, which)
                dismiss()
            }
        }
    }

    private fun choices(items: Array<out CharSequence>): View {
        val p = Ui.p
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4.px, 0, 4.px) }
        items.forEachIndexed { i, label ->
            val selected = b.single && i == b.checked
            val radio = Radio(context, selected)
            val text = Ui.text(context, 30, p.text).apply { text = label; setLineSpacing(0f, 1.1f) }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                minimumHeight = 76.px
                setPadding(28.px, 12.px, 28.px, 12.px)
                background = Ui.fillSelector(if (selected) p.fillFocused else Color.TRANSPARENT, p.focus, 38)
                if (b.single) addView(radio, LinearLayout.LayoutParams(32.px, 32.px).apply { marginEnd = 24.px })
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnFocusChangeListener { _, has ->
                    text.setTextColor(if (has) p.onFocus else p.text)
                    text.text = tinted(label, has)
                    radio.tone(if (has) p.onFocus else p.text)
                }
                setOnClickListener {
                    b.onItem?.onClick(this@SofaDialog, i)
                    if (!b.single) dismiss()
                }
            }
            radio.tone(p.text)
            if (selected || (firstFocus == null && b.checked < 0)) firstFocus = row
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = 6.px
            })
        }
        return Capped(context, 90.px).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
    }

    /**
     * A label's own colour spans (the small grey line under an engine's name) are fixed colours, so on the white
     * focus fill they would vanish: while focused they take the on-focus tone instead.
     */
    private fun tinted(label: CharSequence, focused: Boolean): CharSequence {
        if (label !is Spanned) return label
        val spans = label.getSpans(0, label.length, ForegroundColorSpan::class.java)
        if (spans.isEmpty()) return label
        return SpannableString(label).apply {
            for (span in spans) {
                val from = getSpanStart(span)
                val to = getSpanEnd(span)
                removeSpan(span)
                setSpan(ForegroundColorSpan(if (focused) Ui.p.onFocusWeak else span.foregroundColor), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** Text fields inside a custom view get a rounded tonal field instead of the system underline. */
    private fun styleFields(v: View) {
        val p = Ui.p
        if (v is EditText) {
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30.px.toFloat())
            v.setTextColor(p.text)
            v.setHintTextColor(p.textHint)
            v.background = Ui.fillSelector(p.fill, p.fillFocused, 20)
            v.setPadding(28.px, 20.px, 28.px, 20.px)
            (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 8.px
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) styleFields(v.getChildAt(i))
        }
    }

    /** A scroller that never grows past [MAX_BODY] design pixels, so the sheet fits the screen. */
    private class Capped(context: Context, margin: Int) : FocusScrollView(context, margin) {
        init {
            // Rows scrolling out of view fade away instead of being cut off hard right under the title.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(36.px)
            overScrollMode = OVER_SCROLL_NEVER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(MAX_BODY.px, View.MeasureSpec.AT_MOST))
        }

        // The sheet doesn't clip its children (a focused button grows past it), so the scroller clips itself.
        override fun draw(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
            super.draw(canvas)
            canvas.restore()
        }
    }

    /** The radio mark of a single-choice row: a ring, with a dot when selected. */
    private class Radio(context: Context, private val selected: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun tone(color: Int) { paint.color = color; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val ring = 3.px.toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            canvas.drawCircle(c, c, c - ring / 2, paint)
            if (selected) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(c, c, c * 0.5f, paint)
            }
        }
    }

    private companion object { const val MAX_BODY = 560 }
}
