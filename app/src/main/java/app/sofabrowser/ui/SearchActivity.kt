package app.sofabrowser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.searchShortcuts
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.PhoneLink
import app.sofabrowser.browser.SearchShortcuts
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.Warmup
import app.sofabrowser.data.Db
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Address and search entry. Returns the URL to open in [EXTRA_URL]. Suggestions come from local bookmarks and
 * history; remote suggestions are opt-in and go straight to the chosen engine.
 *
 * Until something is typed, the open tabs ([OpenTabs]) show under the field, as pictures of their pages, like a
 * phone browser's tab switcher: OK on one goes to it ([EXTRA_TAB]), its X closes it ([EXTRA_CLOSED_TABS], in every
 * result), and the last card makes what is opened next open in a new tab ([EXTRA_NEW_TAB]): it is marked as the tab
 * things open in, and keeps the focus until the field is reached. The button beside the magnifier opens what is typed
 * in a new tab too, or an empty new tab with nothing typed.
 */
class SearchActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var field: EditText
    private lateinit var engineLabel: TextView
    private lateinit var list: LinearLayout
    private lateinit var tabsBox: LinearLayout
    private val tabs = OpenTabs.list.toMutableList()
    private val closedTabs = ArrayList<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val privateMode by lazy { intent.getBooleanExtra(EXTRA_PRIVATE, false) }
    private var typedSite: String? = null
    private val warmTyped = Runnable { Warmup.preconnect(typedSite) }
    private var remoteRequest = 0
    private lateinit var micButton: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var halo: View
    private val voiceInput by lazy { VoiceInput(this, voiceListener) }
    /** The new tab card was chosen: what is opened goes to a new tab. */
    private var inNewTab = false
    private lateinit var newTabButton: FrameLayout
    private lateinit var newTabIcon: ImageView

    // IME_FLAG_NO_PERSONALIZED_LEARNING is a compile-time constant; keyboards before API 26 just ignore it.
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(privateMode)
            // The bar sits where the home screen's top bar does: the sofa stays put in its corner and the field
            // takes over from the search pill while the two screens cross-fade.
            setPadding(64.px, 32.px, 64.px, 0)
            // The mic halo may grow beyond the button row and into the padding.
            clipToPadding = false
            clipChildren = false
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_sofa) },
            LinearLayout.LayoutParams(64.px, 64.px).apply { marginEnd = 32.px })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.p.fillFocused, 12)
        }
        engineLabel = Ui.text(this, 26, Ui.p.textMid).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            background = Ui.fillSelector(0, Ui.p.focus, 12)
            setPadding(24.px, 0, 24.px, 0)
            setOnClickListener { pickEngine() }
            setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
        }
        field = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 32.px.toFloat())
            setTextColor(Ui.p.text)
            setHintTextColor(Ui.p.textHint)
            hint = getString(R.string.search_hint)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setPadding(24.px, 0, 24.px, 0)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    updateEngineVisibility(s.toString())
                    suggest(s.toString())
                }
            })
        }
        box.addView(field, LinearLayout.LayoutParams(0, 96.px, 1f))
        // The engine sits at the end of the field, and only while what is typed is a search, not a link.
        box.addView(engineLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 96.px))
        bar.addView(box, LinearLayout.LayoutParams(0, 96.px, 1f))
        bar.addView(squareButton(R.drawable.ic_search, R.string.search) { submit(field.text.toString()) })
        newTabButton = squareButton(R.drawable.ic_tab_new, R.string.open_in_new_tab) {
            val t = field.text.toString()
            if (t.isBlank()) { setResult(RESULT_OK, result().putExtra(EXTRA_NEW_TAB, true)); finish() } else submit(t, newTab = true)
        }
        newTabIcon = newTabButton.getChildAt(0) as ImageView
        // The icon is a whole tab, outline and all: a little larger than the glyphs beside it, to read as large.
        newTabIcon.layoutParams = FrameLayout.LayoutParams(56.px, 56.px, Gravity.CENTER)
        // Orange while the new tab card is chosen, as a reminder once the cards make way for what is typed.
        newTabButton.setOnFocusChangeListener { _, _ -> tintNewTabButton() }
        bar.addView(newTabButton)
        // The mic sits on a halo that grows with the voice level. The holder and the bar let the halo draw
        // beyond their bounds, so it is never cut square.
        bar.clipChildren = false
        val micHolder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        halo = View(this).apply { background = Ui.rounded(0x59FF8A3D, 48); visibility = View.INVISIBLE }
        micHolder.addView(halo, FrameLayout.LayoutParams(96.px, 96.px))
        micButton = squareButton(R.drawable.ic_mic, R.string.voice_search) { voice() }
        micHolder.addView(micButton, FrameLayout.LayoutParams(96.px, 96.px))
        micIcon = micButton.getChildAt(0) as ImageView
        micHolder.visibility = if (prefs.voiceSearch) View.VISIBLE else View.GONE
        bar.addView(micHolder, LinearLayout.LayoutParams(96.px, 96.px).apply { marginStart = 36.px })
        // Typing on the phone sits with the mic as another way to type, set apart from the search button.
        bar.addView(squareButton(R.drawable.ic_smartphone, R.string.phone_type) { typeOnPhone() }.apply {
            if (!prefs.voiceSearch) (layoutParams as LinearLayout.LayoutParams).marginStart = 36.px
        })
        root.addView(bar)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24.px, 0, 48.px) }
        tabsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val below = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(list)
            addView(tabsBox)
        }
        root.addView(FocusScrollView(this, 48.px).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(below) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // From the menu bar's new tab button: the new tab card comes chosen, and the field takes what is typed.
        inNewTab = intent.getBooleanExtra(EXTRA_IN_NEW_TAB, false)
        tintNewTabButton()
        showTabs()
        setContentView(root)

        updateEngineLabel()
        if (!inNewTab) field.setText(prefill(intent.getStringExtra(EXTRA_CURRENT_URL)))
        field.setSelection(field.text.length)
        if (intent.getBooleanExtra(EXTRA_VOICE, false) && prefs.voiceSearch) voice() else showKeyboard()
        // From the home screen's phone button: go straight to the QR code.
        if (intent.getBooleanExtra(EXTRA_PHONE, false)) field.post { typeOnPhone() }
    }

    private fun squareButton(icon: Int, label: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        contentDescription = getString(label)
        background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 12)
        val img = ImageView(context).apply { setImageResource(icon); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        addView(img, FrameLayout.LayoutParams(48.px, 48.px, Gravity.CENTER))
        setOnFocusChangeListener { _, has -> img.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(96.px, 96.px).apply { marginStart = 12.px }
    }

    private fun showKeyboard() {
        field.requestFocus()
        field.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /** On a results page of the current engine, edit the query rather than the long URL. */
    private fun prefill(current: String?): String {
        if (current.isNullOrEmpty()) return ""
        val template = Uri.parse(prefs.searchEngine.template.replace("%s", "QUERY"))
        val param = template.queryParameterNames.firstOrNull { template.getQueryParameter(it) == "QUERY" }
        val uri = Uri.parse(current)
        if (param != null && uri.host == template.host) uri.getQueryParameter(param)?.let { return it }
        return current
    }

    private fun updateEngineLabel() {
        engineLabel.text = prefs.searchEngine.name
        val icon = EngineIcon.drawable(this, prefs.searchEngine).mutate().apply { setBounds(0, 0, 40.px, 40.px) }
        engineLabel.setCompoundDrawablesRelative(icon, null, null, null)
        engineLabel.compoundDrawablePadding = 16.px
    }

    private fun updateEngineVisibility(text: String) {
        val t = text.trim()
        val isLink = t.isNotEmpty() && UrlInput.resolve(t, prefs.searchEngine, searchShortcuts(this)) != prefs.searchEngine.searchUrl(t)
        engineLabel.visibility = if (isLink) View.GONE else View.VISIBLE
    }

    private fun pickEngine() {
        val engines = Prefs.ENGINES
        val current = engines.indexOfFirst { it.id == prefs.searchEngineId }
        Ui.dialog(this)
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), current) { d, which ->
                prefs.searchEngineId = engines[which].id
                updateEngineLabel()
                updateEngineVisibility(field.text.toString())
                d.dismiss()
                field.requestFocus()
            }.show()
    }

    private fun submit(text: String, newTab: Boolean = inNewTab) {
        val url = UrlInput.resolve(text, prefs.searchEngine, searchShortcuts(this))
        if (url.isEmpty()) return
        open(url, newTab)
    }

    private fun tintNewTabButton() {
        newTabIcon.setColorFilter(when {
            newTabButton.hasFocus() -> Ui.p.onFocus
            inNewTab -> Ui.BRAND
            else -> Ui.p.textMid
        }, PorterDuff.Mode.SRC_IN)
    }

    /** OK on the new tab card: it becomes (or stops being) the tab things open in, and keeps the focus. */
    private fun toggleNewTab() {
        inNewTab = !inNewTab
        val current = prefill(intent.getStringExtra(EXTRA_CURRENT_URL))
        // A new tab starts out empty: the address of the page in view makes way, and comes back when it is undone.
        if (inNewTab && field.text.toString() == current) field.setText("")
        if (!inNewTab && field.text.isEmpty()) { field.setText(current); field.setSelection(field.text.length) }
        tintNewTabButton()
        showTabs()
        cards().lastOrNull()?.requestFocus()
    }

    /** A result, with the tabs closed here, whatever else it says. */
    private fun result() = Intent().putExtra(EXTRA_CLOSED_TABS, closedTabs.toLongArray())

    // ---- The open tabs, under the field until something is typed ----

    private fun showTabs() {
        tabsBox.removeAllViews()
        if (tabs.isEmpty()) return
        tabsBox.addView(Ui.text(this, 26, Ui.p.textMid).apply {
            text = getString(R.string.open_tabs)
            setPadding(8.px, 32.px, 0, 20.px)
        })
        val cards: List<View> = tabs.map(::tabCard) + newTabCard()
        cards.chunked(TAB_COLUMNS).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            row.forEachIndexed { i, card ->
                line.addView(card, LinearLayout.LayoutParams(0, TAB_CARD_HEIGHT.px, 1f).apply { if (i > 0) marginStart = 32.px })
            }
            // A short last row keeps its cards as wide as the others.
            repeat(TAB_COLUMNS - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = 32.px }) }
            tabsBox.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32.px })
        }
    }

    /** A tab: its site and name over a picture of its page. The tab in view has an orange edge. */
    private fun tabCard(t: OpenTabs.Tab): View {
        val name = t.title.ifBlank { Browser.hostOf(t.url)?.removePrefix("www.").orEmpty() }.ifBlank { getString(R.string.new_tab) }
        val card = cardFrame(t.current && !inNewTab) { setResult(RESULT_OK, result().putExtra(EXTRA_TAB, t.id)); finish() }
        card.contentDescription = name
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.px, 0, 72.px, 0)
        }
        head.addView(ImageView(this).apply { setImageDrawable(app.sofabrowser.data.Favicons.get(context).drawable(t.url, name, 64)) },
            LinearLayout.LayoutParams(36.px, 36.px).apply { marginEnd = 16.px })
        val title = Ui.text(this, 24, Ui.p.text).apply { text = name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        Ui.focusScale(card) { has -> title.setTextColor(if (has) Ui.p.onFocus else Ui.p.text) }
        column.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, TAB_HEAD.px))
        val picture = t.thumbnail
        column.addView(if (picture != null) ImageView(this).apply {
            setImageBitmap(picture)
            scaleType = ImageView.ScaleType.MATRIX
            // The top of the page, as wide as the card.
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val k = (r - l).toFloat() / picture.width
                (v as ImageView).imageMatrix = android.graphics.Matrix().apply { setScale(k, k) }
            }
        } else FrameLayout(this).apply {
            setBackgroundColor(Ui.p.card)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_sofa) }, FrameLayout.LayoutParams(96.px, 96.px, Gravity.CENTER))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The X: up from the card, and only from it (moving about the cards never stops on one); down goes back to it.
        val close = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = false
            isClickable = true
            contentDescription = getString(R.string.close_tab)
            background = Ui.fillSelector(0, Ui.p.focus, 12)
            val x = ImageView(context).apply { setImageResource(R.drawable.ic_close); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
            addView(x, FrameLayout.LayoutParams(32.px, 32.px, Gravity.CENTER))
            setOnFocusChangeListener { v, has ->
                x.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
                if (!has) v.isFocusable = false
            }
            setOnClickListener { closeTab(t) }
        }
        card.addView(close, FrameLayout.LayoutParams(52.px, 52.px, Gravity.TOP or Gravity.END).apply { setMargins(0, 6.px, 8.px, 0) })
        card.id = View.generateViewId()
        card.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            if (e.action == KeyEvent.ACTION_DOWN) { close.isFocusable = true; close.requestFocus() }
            true
        }
        close.nextFocusDownId = card.id
        return card
    }

    /** The last card: a new tab, which opens on the home screen. */
    private fun newTabCard(): View {
        val card = cardFrame(inNewTab) { toggleNewTab() }
        card.contentDescription = getString(R.string.open_in_new_tab)
        // Chosen, it is the tab things open in: orange, with the same words.
        val rest = if (inNewTab) Ui.BRAND else Ui.p.textMid
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_tab_new); setColorFilter(rest, PorterDuff.Mode.SRC_IN) }
        val label = Ui.text(this, 26, rest).apply {
            text = getString(R.string.open_in_new_tab)
            gravity = Gravity.CENTER
            setPadding(16.px, 0, 16.px, 0)
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon, LinearLayout.LayoutParams(72.px, 72.px).apply { bottomMargin = 16.px })
            addView(label)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.setOnFocusChangeListener { v, has ->
            Ui.animateScale(v, if (has) 1.04f else 1f, has)
            val c = if (has) Ui.p.onFocus else rest
            icon.setColorFilter(c, PorterDuff.Mode.SRC_IN)
            label.setTextColor(c)
        }
        return card
    }

    private fun cardFrame(current: Boolean, onClick: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.p.focus, 16))
            addState(intArrayOf(), Ui.rounded(Ui.p.fill, 16))
        }
        // The picture keeps to the card's rounded corners, the ones its edge is drawn with.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 16.px.toFloat())
        }
        clipToOutline = true
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Color.TRANSPARENT, 16, Ui.p.focus, 6))
            addState(intArrayOf(), if (current) Ui.rounded(Color.TRANSPARENT, 16, Ui.BRAND, 4) else ColorDrawable(Color.TRANSPARENT))
        }
        Ui.focusScale(this)
    }

    /** Closes [t] (when the search screen closes) and takes its card away; the focus goes to the card that takes its place. */
    private fun closeTab(t: OpenTabs.Tab) {
        val at = tabs.indexOf(t)
        tabs.remove(t)
        closedTabs += t.id
        setResult(RESULT_CANCELED, result())
        showTabs()
        val cards = cards()
        (cards.getOrNull(at) ?: cards.lastOrNull())?.requestFocus() ?: field.requestFocus()
    }

    /** The cards under the field, the new tab card last. */
    private fun cards(): List<View> = (0 until tabsBox.childCount).map { tabsBox.getChildAt(it) }.filterIsInstance<LinearLayout>()
        .flatMap { line -> (0 until line.childCount).map { line.getChildAt(it) }.filter { it.isFocusable } }

    private fun suggest(text: String) {
        list.removeAllViews()
        val q = text.trim()
        val typing = q.isNotEmpty() && q != prefill(intent.getStringExtra(EXTRA_CURRENT_URL))
        // The tabs make way for what is typed.
        tabsBox.visibility = if (typing) View.GONE else View.VISIBLE
        if (q.isEmpty() || q == intent.getStringExtra(EXTRA_CURRENT_URL)) return
        // The site OK would open gets its connection ready once the typing pauses, not for every half-typed address.
        if (!privateMode) {
            typedSite = UrlInput.resolve(q, prefs.searchEngine, searchShortcuts(this))
            field.removeCallbacks(warmTyped)
            field.postDelayed(warmTyped, 400)
        }
        // A shortcut says where to search, so neither history nor the engine's suggestions apply.
        SearchShortcuts.match(q, searchShortcuts(this))?.let { (shortcut, query) ->
            addRow(R.drawable.ic_search, getString(R.string.search_shortcut_row, shortcut.name, query), null, shortcut.url(query)) { open(shortcut.url(query)) }
            return
        }
        val local = Db.get(this).suggest(q, 4)
        local.forEach { addRow(R.drawable.ic_history, it.title.ifBlank { it.url }, UrlInput.display(it.url), it.url) { open(it.url) } }
        val engine = prefs.searchEngine
        val suggestUrl = engine.suggestTemplate
        if (!prefs.searchSuggestions || suggestUrl == null) return
        val request = ++remoteRequest
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            thread(name = "suggest") {
                val words = fetchSuggestions(suggestUrl.replace("%s", Uri.encode(q)))
                runOnUiThread {
                    if (request != remoteRequest || isFinishing) return@runOnUiThread
                    words.take(6).forEach { w -> addRow(R.drawable.ic_search, w, null) { submit(w) } }
                }
            }
        }, 250)
    }

    /** Both supported endpoints answer in the OpenSearch suggestion format: ["query", ["s1", "s2", ...]]. */
    private fun fetchSuggestions(url: String): List<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.useCaches = false
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText()).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun open(url: String, newTab: Boolean = inNewTab) {
        setResult(RESULT_OK, result().putExtra(EXTRA_URL, url).putExtra(EXTRA_NEW_TAB, newTab))
        finish()
    }

    /** A suggestion; [url], when known, gets its connection ready while the row has the focus. */
    private fun addRow(icon: Int, title: String, subtitle: String?, url: String? = null, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(0, Ui.p.focus, 16)
            setPadding(44.px, 0, 44.px, 0)
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply { setImageResource(icon); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        row.addView(img, LinearLayout.LayoutParams(40.px, 40.px).apply { marginEnd = 32.px })
        val t = Ui.text(this, 30, Ui.p.textMid).apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t) }
        val s = subtitle?.let { Ui.text(this, 22, Ui.p.textWeak).apply { text = it; maxLines = 1; ellipsize = TextUtils.TruncateAt.END } }
        s?.let(texts::addView)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnFocusChangeListener { _, has ->
            val c = if (has) Ui.p.onFocus else Ui.p.textMid
            t.setTextColor(c)
            s?.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textWeak)
            img.setColorFilter(c, PorterDuff.Mode.SRC_IN)
            if (has && !privateMode) Warmup.preconnect(url)
        }
        list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 94.px))
    }

    /** The mic: listens right here in the search field; pressing it again while listening stops. */
    private fun voice() {
        when {
            voiceInput.listening -> voiceInput.cancel()
            !voiceInput.available -> systemVoice()
            !voiceInput.hasPermission -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            else -> {
                hideKeyboard()
                field.setText("")
                voiceInput.start()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        // Without the microphone, the system's own voice screen still works (it records, not Sofa).
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice() else systemVoice()
    }

    private val voiceListener = object : VoiceInput.Listener {
        override fun onListening(active: Boolean) {
            field.hint = getString(if (active) R.string.voice_listening else R.string.search_hint)
            // Focus first: the button's focus listener recolours its icon.
            if (active) micButton.requestFocus()
            micButton.background = if (active) Ui.rounded(Ui.BRAND, 48) else Ui.fillSelector(Ui.p.fill, Ui.p.focus, 12)
            micIcon.setColorFilter(if (active) Color.WHITE else if (micButton.hasFocus()) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
            halo.animate().cancel()
            if (active) {
                // A resting ring shows that Sofa is listening even before anything is said.
                halo.scaleX = HALO_REST
                halo.scaleY = HALO_REST
                halo.alpha = 0.6f
                halo.visibility = View.VISIBLE
            } else {
                halo.visibility = View.INVISIBLE
            }
        }

        override fun onLevel(level: Float) {
            val s = HALO_REST + level * (HALO_MAX - HALO_REST)
            halo.animate().scaleX(s).scaleY(s).alpha(0.6f + 0.4f * level).setDuration(90).start()
        }

        override fun onPartial(text: String) = field.setText(text)

        override fun onResult(text: String) {
            field.setText(text)
            field.setSelection(text.length)
            // A moment to see what was heard before it opens.
            handler.postDelayed({ if (!isFinishing) submit(text) }, 400)
        }

        override fun onFailed(message: Int) {
            field.hint = getString(message)
            micButton.requestFocus()
        }
    }

    /** Fallback: the system voice screen (used when Sofa can't run recognition itself). */
    private fun systemVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            SofaToast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            showKeyboard()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
    }

    @Deprecated("Framework activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !spoken.isNullOrBlank()) submit(spoken) else showKeyboard()
    }

    /** Leaves with the same cross-fade it arrived with. */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_fade_out)
    }

    override fun onStop() {
        voiceInput.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        voiceInput.destroy()
        phoneLink?.stop()
        super.onDestroy()
    }

    // ---- Typing on the phone: a QR code opens a page served by Sofa on the home network ----

    private var phoneLink: PhoneLink? = null

    private fun typeOnPhone() {
        var dialog: SofaDialog? = null
        // Over a web page the phone can also type what to find on it: the page's own bar then opens on that text.
        val page = intent.getStringExtra(EXTRA_CURRENT_URL)?.takeIf(Browser::isWebUrl)
        val onFind: ((String) -> Unit)? = page?.let {
            { text: String ->
                dialog?.dismiss()
                setResult(RESULT_OK, result().putExtra(EXTRA_FIND, text))
                finish()
            }
        }
        val link = PhoneLink(this, onText = { text ->
            dialog?.dismiss()
            submit(text)
        }, onImported = { _, message -> SofaToast.makeText(this, message, Toast.LENGTH_LONG).show() }, onFind = onFind)
        val address = link.start(this) ?: return SofaToast.makeText(this, R.string.phone_no_network, Toast.LENGTH_LONG).show()
        phoneLink?.stop()
        phoneLink = link
        dialog = Ui.dialog(this)
            .setTitle(R.string.phone_type)
            .setView(PhoneCard.view(this, address,
                listOf(R.string.phone_step_scan, R.string.phone_step_type, R.string.phone_step_open).map(::getString),
                getString(R.string.phone_or_open)))
            .setCloseIcon()
            .setOnDismissListener { link.stop(); if (phoneLink === link) phoneLink = null }
            .show()
    }

    // BACK while listening only stops listening (TV remotes send BACK as a key; see the manifest).
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && voiceInput.listening) {
            voiceInput.cancel()
            return true
        }
        // Down from the field goes to the suggestions even when the IME is closed.
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus() && list.childCount > 0) {
            list.getChildAt(0).requestFocus()
            return true
        }
        // Typing on a keyboard with the focus away from the field (on the new tab card, say) types into the field.
        if (!field.hasFocus() && event.isPrintingKey && event.unicodeChar != 0 && !event.isCtrlPressed) {
            field.requestFocus()
            field.setSelection(field.text.length)
            return field.dispatchKeyEvent(event)
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_CURRENT_URL = "current"
        /** Returned instead of an address when the phone typed what to find on the page. */
        const val EXTRA_FIND = "find"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_PRIVATE = "private"
        /** Opens with the new tab card chosen: what is opened goes to a new tab. */
        const val EXTRA_IN_NEW_TAB = "in_new_tab"
        /** Returned: the tab chosen, the ones closed, or that a new tab is wanted (with [EXTRA_URL]: to open it in). */
        const val EXTRA_TAB = "tab"
        const val EXTRA_CLOSED_TABS = "closed_tabs"
        const val EXTRA_NEW_TAB = "new_tab"
        private const val TAB_COLUMNS = 4
        /** The first row of cards ends above where a TV's on-screen keyboard starts. */
        private const val TAB_CARD_HEIGHT = 292
        private const val TAB_HEAD = 64
        private const val REQ_VOICE = 1
        private const val REQ_MIC = 2
        private const val HALO_REST = 1.15f
        private const val HALO_MAX = 1.5f
    }
}
