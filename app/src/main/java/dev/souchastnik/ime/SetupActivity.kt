package dev.souchastnik.ime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.souchastnik.R
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Prefs

/** Экран установки: включить клавиатуру в системе и глобальный тумблер. */
class SetupActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Articles.load(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad + pad / 2, pad, pad)
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_lockup)
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = getString(R.string.app_name)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt(),
            )
        })
        root.addView(spacer(pad + pad / 2))
        root.addView(text(getString(R.string.disclaimer), 15f, color = R.color.brand_red))
        root.addView(spacer(pad))

        root.addView(
            text(
                "Клавиатура показывает статью и наказание за то, что вы печатаете. " +
                    "Работает в любом приложении.\n\n" +
                    "Разбор выполняется на телефоне через Gemini Nano. У приложения " +
                    "нет разрешения на интернет — проверьте это на APK. Работает без интернета.",
                14f,
            )
        )
        root.addView(spacer(pad))

        root.addView(Button(this).apply {
            text = "1. Включить клавиатуру в системе"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(spacer(pad / 2))
        root.addView(text("Перезагрузите устройство", 14f, center = true))
        root.addView(spacer(pad / 2))
        root.addView(Button(this).apply {
            text = "2. Выбрать «Соучастник»"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
        })
        root.addView(spacer(pad))

        val hint = text("", 12.5f, color = R.color.brand_muted)
        val switch = Switch(this).apply {
            textSize = 16f
            isChecked = Prefs.isEnabled(this@SetupActivity)
        }
        fun describe(checked: Boolean) {
            switch.text = if (checked) "  Статьи показываются" else "  Статьи скрыты"
            hint.text = if (checked) {
                "Gemini Nano используется при открытии клавиатуры. Тот же тумблер есть справа в строке."
            } else {
                "Клавиатура работает как обычная, анализ не запускается. Тот же тумблер есть справа в строке."
            }
        }
        describe(switch.isChecked)
        switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this@SetupActivity, checked)
            describe(checked)
        }
        root.addView(switch)
        root.addView(hint)
        root.addView(spacer(pad))

        status = text("", 13f, color = R.color.brand_muted)
        root.addView(status)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = buildString {
            append("Статей в справочнике: ${Articles.size()}\n")
            append("Движок: Gemini Nano / AICore\n")
            append("Сторонняя модель в APK не поставляется.\n")
            append("Доступность Gemini Nano определяется системой устройства.")
        }
    }

    private fun text(
        s: String,
        size: Float,
        bold: Boolean = false,
        @ColorRes color: Int? = null,
        center: Boolean = false,
    ) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            color?.let { setTextColor(ContextCompat.getColor(this@SetupActivity, it)) }
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            val p = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
        }

    private fun spacer(h: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }
}
