package dev.souchastnik.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.souchastnik.R
import dev.souchastnik.ai.GeminiNanoClient

/** Единственная функция приложения: одна строка над клавиатурой. */
class VerdictStrip(context: Context) : LinearLayout(context) {
    var onToggle: (() -> Unit)? = null

    private val label = TextView(context)
    private val toggle = TextView(context)

    private val colorClean = Color.parseColor("#7A7A80")
    private val colorAdmin = Color.parseColor("#D8A200")
    private val colorCrime = Color.parseColor("#D96A4A")
    private val colorHeavy = Color.parseColor("#C0392B")

    private var shown: CharSequence = ""
    private var run: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#17171A"))
        val pad = dp(10)
        setPadding(pad, dp(7), pad, dp(7))

        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        label.setSingleLine(true)
        label.ellipsize = null
        label.isHorizontalFadingEdgeEnabled = true
        label.setFadingEdgeLength(dp(14))
        label.typeface = Typeface.DEFAULT
        label.setOnClickListener { scrollOnce() }
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        toggle.setGravity(Gravity.CENTER)
        toggle.setPadding(0, 0, 0, 0)
        toggle.minWidth = dp(48)
        toggle.minimumHeight = dp(48)
        toggle.isClickable = true
        toggle.isFocusable = true
        toggle.contentDescription = "Переключить ИИ"
        toggle.setOnClickListener { onToggle?.invoke() }
        addView(toggle, LayoutParams(dp(48), dp(48)))
    }

    fun renderOff() {
        label.setTextColor(colorClean)
        setLabel(context.getString(R.string.strip_off))
        toggle.text = "○"
        toggle.setTextColor(colorClean)
    }

    fun render(state: GeminiNanoClient.State) {
        toggle.text = "◉"
        toggle.setTextColor(colorAdmin)
        when (state) {
            is GeminiNanoClient.State.NoModel -> {
                label.setTextColor(colorClean)
                setLabel(state.detail)
            }
            GeminiNanoClient.State.Loading -> {
                label.setTextColor(colorClean)
                setLabel("…")
            }
            GeminiNanoClient.State.Clean -> {
                label.setTextColor(colorClean)
                setLabel(context.getString(R.string.strip_clean))
            }
            GeminiNanoClient.State.Thinking -> {
                label.setTextColor(colorClean)
                setLabel(context.getString(R.string.strip_thinking))
            }
            is GeminiNanoClient.State.Verdict -> {
                label.setTextColor(
                    when (state.article.severity) {
                        1 -> colorAdmin
                        2 -> colorCrime
                        else -> colorHeavy
                    },
                )
                setLabel(state.article.strip())
            }
        }
    }

    private fun setLabel(text: CharSequence) {
        if (android.text.TextUtils.equals(text, shown)) return
        shown = text
        run?.cancel()
        run = null
        label.scrollTo(0, 0)
        label.text = text
        label.post { scrollOnce() }
    }

    private fun scrollOnce() {
        run?.cancel()
        run = null
        val layout = label.layout ?: return
        val viewport = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        val over = (layout.getLineWidth(0) - viewport).toInt()
        label.scrollTo(0, 0)
        if (over <= 0 || !ValueAnimator.areAnimatorsEnabled()) return
        val travel = (over / (40f * resources.displayMetrics.density) * 1000f).toLong()
            .coerceIn(500L, 8000L)
        run = ValueAnimator.ofInt(0, over).apply {
            startDelay = START_DELAY_MS
            duration = travel
            interpolator = LinearInterpolator()
            addUpdateListener { label.scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || run !== animation) return
                    label.postDelayed({
                        if (run === animation) {
                            run = null
                            label.scrollTo(0, 0)
                        }
                    }, END_HOLD_MS)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        run?.cancel()
        run = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val START_DELAY_MS = 1700L
        const val END_HOLD_MS = 1200L
    }
}
