package dev.souchastnik.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.souchastnik.ai.GeminiNanoClient
import dev.souchastnik.data.Agents
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Prefs
import dev.souchastnik.data.Triggers

/** Системная клавиатура «Соучастник». */
class SouchastnikIME : InputMethodService(), KeyboardView.Listener {
    companion object {
        private const val WORD_ENDS = ",.!?;:)"
        private const val WORD_LOOKBEHIND = 64
        private const val ANALYZE_DELAY_MS = 450L
    }

    private lateinit var strip: VerdictStrip
    private lateinit var keyboard: KeyboardView
    private lateinit var gemini: GeminiNanoClient
    private val main = Handler(Looper.getMainLooper())
    private var suppressed = false
    private var enabled = false

    private val analyzeRunnable = Runnable { analyzeCurrentNow() }

    override fun onCreate() {
        super.onCreate()
        Articles.load(this)
        Triggers.load(this)
        Agents.load(this)
        gemini = GeminiNanoClient().also { client ->
            client.onState = { state ->
                if (::strip.isInitialized) strip.render(state)
            }
        }
    }

    override fun onCreateInputView(): View {
        strip = VerdictStrip(this)
        keyboard = KeyboardView(this).also { it.listener = this }
        strip.onToggle = {
            val now = !Prefs.isEnabled(this)
            Prefs.setEnabled(this, now)
            applyEnabled(now)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(keyboard, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        suppressed = isSensitive(info)
        applyEnabled(Prefs.isEnabled(this) && !suppressed)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelAnalyze()
        gemini.cancel()
    }

    override fun onDestroy() {
        cancelAnalyze()
        gemini.close()
        super.onDestroy()
    }

    private fun applyEnabled(value: Boolean) {
        enabled = value
        cancelAnalyze()
        if (!value) {
            gemini.cancel()
            if (::strip.isInitialized) strip.renderOff()
            return
        }
        gemini.prepareInBackground()
    }

    private fun isSensitive(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) return true
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        return false
    }

    override fun onChar(c: Char) {
        if (c in WORD_ENDS) markAgent()
        currentInputConnection?.commitText(c.toString(), 1)
        scheduleAnalyze()
    }

    override fun onSpace() {
        markAgent()
        currentInputConnection?.commitText(" ", 1)
        scheduleAnalyze()
    }

    private fun markAgent() {
        if (!enabled || suppressed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(96, 0) ?: return
        if (before.isEmpty() || before.last() == ')') return
        val marker = Agents.lastWord(before)?.let { Agents.markerFor(it) }
            ?: Agents.markerForTail(before)
            ?: return
        // The legal/status marker belongs in the keyboard status strip,
        // not in the user's text field.
        strip.renderAgentMarker(marker)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (!deleteSelection(ic)) ic.deleteSurroundingTextInCodePoints(1, 0)
        scheduleAnalyze()
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        if (deleteSelection(ic)) {
            scheduleAnalyze()
            return
        }
        val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0) ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
        scheduleAnalyze()
    }

    private fun deleteSelection(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) return false
        ic.commitText("", 1)
        return true
    }

    override fun onEnter() {
        markAgent()
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE) ic.performEditorAction(action)
        else ic.commitText("\n", 1)
        gemini.cancel()
    }

    private fun scheduleAnalyze() {
        if (!enabled || suppressed) return
        main.removeCallbacks(analyzeRunnable)
        main.postDelayed(analyzeRunnable, ANALYZE_DELAY_MS)
    }

    private fun cancelAnalyze() {
        main.removeCallbacks(analyzeRunnable)
    }

    private fun analyzeCurrentNow() {
        if (!enabled || suppressed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        gemini.launchAnalyze(before)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        scheduleAnalyze()
    }
}
