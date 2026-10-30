package kr.daedeok.calls

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 두 화면(정리 창 · 상태 화면)이 같이 쓰는 글자·버튼 모양 (밝은/어두운 화면 둘 다) */
class Ui(private val a: Activity) {
    val dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val ink = if (dark) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
    val sub = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
    val bg = if (dark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
    val card = if (dark) Color.parseColor("#1E293B") else Color.WHITE
    val acc = if (dark) Color.parseColor("#A5B4FC") else Color.parseColor("#4338CA")
    val accBg = if (dark) Color.parseColor("#312E81") else Color.parseColor("#EEF2FF")
    val okC = if (dark) Color.parseColor("#6EE7B7") else Color.parseColor("#047857")
    val badC = if (dark) Color.parseColor("#FDA4AF") else Color.parseColor("#BE123C")

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun text(s: CharSequence, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.25f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(a).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) (if (dark) Color.parseColor("#1E1B4B") else Color.WHITE) else acc)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (primary) acc else accBg) }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    fun box(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(card) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    fun gap(h: Int) = View(a).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
