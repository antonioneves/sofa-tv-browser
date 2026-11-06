package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.data.Site

/**
 * The menu for a home screen tile or a History or Bookmarks card, opened by holding OK on it: the tile's name and address above a column of
 * labelled actions on a rounded sheet, in the same focus style as the browse menu.
 */
class TileMenu(context: Context, private val site: Site, private val items: List<Item>) :
    Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    class Item(val icon: Int, val label: Int, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.p.bgMid, 28, Ui.p.fillFocused, 2)
            setPadding(24.px, 28.px, 24.px, 24.px)
        }
        sheet.addView(Ui.text(context, 34, Ui.p.text, bold = true).apply {
            text = site.title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(16.px, 0, 16.px, 0)
        })
        sheet.addView(Ui.text(context, 22, Ui.p.textWeak).apply {
            text = site.url.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(16.px, 6.px, 16.px, 20.px)
        })

        val rows = items.map { row(it.icon, it.label, it.action) }
        rows.forEachIndexed { i, row ->
            sheet.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72.px).apply {
                if (i > 0) topMargin = 6.px
            })
        }

        val root = FrameLayout(context)
        root.addView(sheet, FrameLayout.LayoutParams(560.px, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        rows.first().requestFocus()
    }

    private fun row(icon: Int, label: Int, onClick: () -> Unit): LinearLayout {
        val image = ImageView(context).apply { setImageResource(icon); setColorFilter(Ui.p.text) }
        val text = Ui.text(context, 28, Ui.p.text).apply { setText(label); maxLines = 1 }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(20.px, 0, 20.px, 0)
            background = Ui.fillSelector(Color.TRANSPARENT, Ui.p.focus, 16)
            addView(image, LinearLayout.LayoutParams(36.px, 36.px))
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 20.px })
            setOnFocusChangeListener { _, has ->
                val color = if (has) Ui.p.onFocus else Ui.p.text
                image.setColorFilter(color)
                text.setTextColor(color)
            }
            setOnClickListener { dismiss(); onClick() }
        }
    }
}
