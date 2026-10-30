package kr.daedeok.calls

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 통화가 끝난 뒤 잠깐 뜨는 작은 "통화 내용 정리" 창.
 * 폰 안 AI(글자 변환)는 화면에 떠 있는 앱만 쓸 수 있어서, 뒤에서 막히면 이 창이 대신 처리하고 저절로 닫힘.
 * from: pop(스스로 뜸) · noti(알림을 눌러서) · open(정산유틸 열기 전에, 다 하면 정산유틸을 엶)
 */
class AutoActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var ui: Ui
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var prog: ProgressBar
    private var from = "noti"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        from = intent?.getStringExtra("from") ?: "noti"
        setFinishOnTouchOutside(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14)); setBackgroundColor(ui.card) }
        title = ui.text("통화 내용 정리", 18f, ui.ink, bold = true)
        status = ui.text("잠깐만요… 통화 녹음을 글자로 바꿔 정산유틸로 보냅니다.\n(녹음 파일은 폰 밖으로 나가지 않고, 글자만 보냅니다)", 14f, ui.sub)
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(title); root.addView(ui.gap(6)); root.addView(status); root.addView(ui.gap(10)); root.addView(prog)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ui.button("나중에") { Auto.log(this, "정리 창: 나중에 누름"); finish() }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) } })
        row.addView(ui.button("자세히", primary = false) { startActivity(Intent(this, TalkActivity::class.java)); finish() }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10); marginStart = ui.dp(8) } })
        root.addView(row)
        setContentView(root)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("from")?.let { if (it == "open") from = it }
    }

    override fun onResume() {
        super.onResume()
        if (job?.isActive == true) return
        // 권한이 없으면 먼저 물어봄 (음악·오디오: 녹음 찾기 / 마이크: 음성인식 규칙, 녹음은 하지 않음)
        val need = ArrayList<String>()
        if (!Auto.hasAudioPerm(this)) need.add(Manifest.permission.READ_MEDIA_AUDIO)
        if (!Auto.hasMic(this)) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty() && !asked) { asked = true; requestPermissions(need.toTypedArray(), 5); return }
        job = scope.launch { work() }
    }

    private var asked = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) Auto.log(this, "정리 창: 권한 일부 거부됨 " + permissions.zip(grantResults.toTypedArray()).joinToString { it.first.substringAfterLast('.') + "=" + (if (it.second == 0) "허용" else "거부") })
        // onResume 이 다시 불려서 이어서 진행
    }

    private suspend fun work() {
        Auto.log(this, "정리 창 시작 ($from) · 대기 ${Auto.waitingCount(this)}건 · 보낼 것 ${Auto.textCount(this)}건")
        var r: Auto.Result? = null
        for (i in 0 until 30) { // 뒤 작업이 돌고 있으면 끝날 때까지 기다림 (최대 1분)
            r = withContext(Dispatchers.IO) { Auto.process(this@AutoActivity, fg = true) { s -> runOnUiThread { status.text = s } } }
            if (!r.busy) break
            status.text = "앞의 작업이 끝나기를 기다리는 중…"
            delay(2000)
        }
        val res = r ?: return
        prog.isIndeterminate = false; prog.progress = 100
        if (Auto.waitingCount(this) == 0) Auto.clearTap(this)
        if (res.needNet) Auto.schedule(this, Auto.JOB_NET, 3000)
        if (res.retry) Auto.schedule(this, Auto.JOB_RETRY, 20000)
        when {
            Auto.textCount(this) > 0 && !Auto.hasServer(this) -> {
                status.text = "정산유틸을 열어 통화 내용을 붙입니다…"
                delay(600)
                try { Auto.handOne(this) } catch (t: Throwable) { Auto.log(this, "정산유틸 열기 실패: $t") }
                val left = Auto.textCount(this)
                if (left > 0) Auto.notifyTap(this, left, local = true)
                finish(); return
            }
            res.done > 0 -> {
                title.text = "보냈습니다 ✅"
                status.setTextColor(ui.ink)
                status.text = (if (res.done > 1) "통화 ${res.done}건" else res.lastWho) + (if (res.lastSum.isNotEmpty()) "\n" + res.lastSum else "\n정산유틸 걸려 온 전화 카드에 붙었습니다")
            }
            res.needNet -> { title.text = "인터넷을 기다리는 중"; status.text = "글자로는 바꿨습니다. 인터넷이 되면 저절로 보냅니다." }
            res.retry -> { title.text = "녹음 파일을 아직 못 찾음"; status.text = "갤럭시가 녹음 파일을 저장하는 중일 수 있어요. 잠시 뒤 다시 찾습니다." }
            res.lastErr.isNotEmpty() -> {
                title.text = "정리하지 못했습니다"
                status.setTextColor(ui.badC)
                status.text = res.lastErr + "\n'자세히'에서 권한·진단을 확인해 주세요."
                if (from != "open") return // 오류는 사용자가 볼 수 있게 창을 남김
                delay(3000) // 정산유틸 '가져오기'로 왔으면 오류를 잠깐 보여 주고 정산유틸은 그대로 엶
            }
            else -> { title.text = "정리할 통화가 없습니다"; status.text = "" }
        }
        delay(if (res.done > 0) 3500L else 2000L)
        if (from == "open") try { Calls.openApp(this, null) } catch (_: Throwable) {}
        finish()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
