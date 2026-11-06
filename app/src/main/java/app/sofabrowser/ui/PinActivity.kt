package app.sofabrowser.ui

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.Space
import app.sofabrowser.data.SpacePin
import kotlin.concurrent.thread

/** Asks for the private space's PIN, by the screen's number pad or the remote's number keys. */
class PinActivity : Activity() {
    private enum class Step { CHECK, CHOOSE, REPEAT }

    private lateinit var title: TextView
    private lateinit var note: TextView
    private lateinit var dots: TextView
    private lateinit var forgot: View
    private var mode = MODE_ENTER
    private var step = Step.CHECK
    private var typed = ""
    private var chosen = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_ENTER)
        setContentView(LinearLayout(this).apply {
            val p = Ui.p
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.background(false, space = false)
            title = Ui.text(context, 44, p.text, bold = true).apply { gravity = Gravity.CENTER }
            addView(title)
            note = Ui.text(context, 26, p.textMid).apply { gravity = Gravity.CENTER; setPadding(0, 14.px, 0, 0) }
            addView(note, LinearLayout.LayoutParams(900.px, LinearLayout.LayoutParams.WRAP_CONTENT))
            dots = Ui.text(context, 44, p.text).apply { gravity = Gravity.CENTER; setPadding(0, 24.px, 0, 24.px) }
            addView(dots)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "")
            var first: View? = null
            keys.chunked(3).forEach { row ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    row.forEach { key ->
                        val button = Ui.button(context, key, 32) { if (key == "⌫") erase() else digit(key) }
                        // Keeps 0 under 8.
                        if (key.isEmpty()) button.visibility = View.INVISIBLE
                        if (key == "1") first = button
                        addView(button, LinearLayout.LayoutParams(112.px, 72.px).apply { setMargins(8.px, 8.px, 8.px, 8.px) })
                    }
                })
            }
            forgot = Ui.button(context, getString(R.string.pin_forgot), 24) { askErase() }
            addView(forgot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16.px
            })
            first?.requestFocus()
        })
        begin()
    }

    private fun begin() {
        step = if (mode == MODE_ENTER && !SpacePin.isSet(this)) Step.CHOOSE else Step.CHECK
        typed = ""
        chosen = ""
        forgot.visibility = if (mode == MODE_ENTER && step == Step.CHECK) View.VISIBLE else View.GONE
        show(null)
    }

    private fun show(message: String?) {
        title.setText(when (step) {
            Step.CHECK -> if (mode == MODE_CHANGE) R.string.pin_current else R.string.pin_enter
            Step.CHOOSE -> if (mode == MODE_CHANGE) R.string.pin_new else R.string.pin_choose
            Step.REPEAT -> R.string.pin_repeat
        })
        note.text = message ?: getString(if (step == Step.CHOOSE && mode == MODE_ENTER) R.string.pin_choose_sub else R.string.private_space)
        dots.text = (0 until SpacePin.LENGTH).joinToString(" ") { if (it < typed.length) "●" else "○" }
    }

    private fun digit(key: String) {
        if (busy) return
        val wait = if (step == Step.CHECK) SpacePin.waiting(this) else 0
        if (wait > 0) return show((wait / 1000 + 1).toInt().let { resources.getQuantityString(R.plurals.pin_wait, it, it) })
        typed += key
        show(null)
        if (typed.length == SpacePin.LENGTH) submit(typed)
    }

    private fun erase() {
        if (busy || typed.isEmpty()) return
        typed = typed.dropLast(1)
        show(null)
    }

    private fun submit(pin: String) {
        typed = ""
        when (step) {
            Step.CHECK -> work({ SpacePin.check(this, pin) }) { right ->
                when {
                    !right -> show(getString(R.string.pin_wrong))
                    mode == MODE_CHANGE -> { step = Step.CHOOSE; show(null) }
                    else -> done()
                }
            }
            Step.CHOOSE -> { chosen = pin; step = Step.REPEAT; show(null) }
            Step.REPEAT ->
                if (pin != chosen) { step = Step.CHOOSE; show(getString(R.string.pin_mismatch)) }
                else work({ SpacePin.set(this, pin); true }) {
                    if (mode == MODE_CHANGE) {
                        android.widget.Toast.makeText(this, R.string.pin_changed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    done()
                }
        }
    }

    /** The PIN's hash takes a moment on a TV, so it's worked out off the main thread. */
    private fun <T> work(job: () -> T, then: (T) -> Unit) {
        busy = true
        thread(name = "pin") {
            val result = job()
            runOnUiThread {
                busy = false
                if (!isFinishing) then(result)
            }
        }
    }

    private fun done() {
        when (mode) {
            MODE_ENTER -> Space.enter(this)
            MODE_UNLOCK -> { Space.unlock(); finish() }
            else -> finish()
        }
    }

    private fun askErase() {
        Ui.dialog(this)
            .setTitle(R.string.pin_forgot_title)
            .setMessage(R.string.pin_forgot_message)
            .setPositiveButton(R.string.pin_forgot_erase) { _, _ -> Space.erase(this); begin() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            // Erasing can't be undone, so a second press of OK mustn't do it.
            .getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit((keyCode - KeyEvent.KEYCODE_0).toString())
            KeyEvent.KEYCODE_DEL -> erase()
            // Away from a locked space means back to the normal side, not into what's behind this screen.
            KeyEvent.KEYCODE_BACK -> if (mode == MODE_UNLOCK) Space.leave(this) else finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ENTER = 0
        const val MODE_UNLOCK = 1
        const val MODE_CHANGE = 2
    }
}
