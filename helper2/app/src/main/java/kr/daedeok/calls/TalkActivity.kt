package kr.daedeok.calls

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "통화 내용 자동 정리" 상태 화면 (앱 아이콘 길게 누르기 → 통화 내용 자동 정리)
 * 켜기/끄기 · 권한 · 정리 창 자동 띄우기 · 최근 정리 목록 · 지금 정리하기 · 진단 기록 복사
 */
class TalkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private var running = false
    private var progress: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val sv = ScrollView(this).apply { setBackgroundColor(ui.bg); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(40)) }
        sv.addView(body)
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        body.removeAllViews()
        body.addView(ui.text("통화 내용 자동 정리", 22f, ui.ink, bold = true))
        body.addView(ui.text("받은 전화가 끝나면 갤럭시 자동 녹음 파일을 폰 안에서 글자로 바꿔, 글자만 정산유틸로 보냅니다. 녹음 파일은 폰 밖으로 나가지 않습니다.", 14f, ui.sub))

        // 상태
        val st = ui.box()
        st.addView(ui.text("지금 상태", 16f, ui.ink, bold = true))
        st.addView(ui.text(Auto.summary(this), 13f, ui.sub))
        body.addView(st)

        // 설정
        val set = ui.box()
        set.addView(ui.text("설정", 16f, ui.ink, bold = true))
        val on = Auto.enabled(this)
        set.addView(ui.button(if (on) "자동 정리: 켜짐 (눌러서 끄기)" else "자동 정리: 꺼짐 (눌러서 켜기)", primary = !on) { Auto.setEnabled(this, !on); if (!on) Auto.ensureSince(this); render() })
        val needPerm = !Auto.hasAudioPerm(this) || !Auto.hasMic(this) || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0
        if (needPerm) set.addView(ui.button("권한 허용하기 (음악·오디오 · 마이크 · 알림)", primary = true) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 9)
        })
        val canPop = Settings.canDrawOverlays(this)
        if (!canPop) {
            set.addView(ui.text("\n완전 자동으로 하려면: \"다른 앱 위에 표시\"를 허용하면 통화가 끝날 때 작은 정리 창이 스스로 떴다가 닫힙니다. 허용하지 않으면 \"눌러서 정리\" 알림을 한 번 눌러 주셔야 합니다.", 13f, ui.sub))
            set.addView(ui.button("다른 앱 위에 표시 허용하러 가기") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) { toast("설정 → 애플리케이션 → 대덕 전화분류 → 다른 앱 위에 표시") }
            })
        } else {
            val pop = Auto.popEnabled(this)
            set.addView(ui.button(if (pop) "정리 창 자동으로 띄우기: 켜짐" else "정리 창 자동으로 띄우기: 꺼짐 (알림으로)") { Auto.setPopEnabled(this, !pop); render() })
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            set.addView(ui.button("배터리 사용 '제한 없음'으로 (통화 뒤 작업이 늦지 않게)") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
            })
        }
        body.addView(set)

        // 지금 정리하기
        val act = ui.box()
        val w = Auto.waitingCount(this); val t = Auto.textCount(this)
        act.addView(ui.text("정리할 통화 ${w}건 · 보낼 통화 내용 ${t}건", 16f, ui.ink, bold = true))
        progress = ui.text("", 13f, ui.sub).also { act.addView(it) }
        act.addView(ui.button(if (running) "정리하는 중…" else "지금 정리하기", primary = true) { runNow() })
        val failed = Auto.items(this).count { it.st == "fail" || it.st == "none" }
        if (failed > 0) act.addView(ui.button("못 한 ${failed}건 다시 시도") { retryFailed() })
        if (t > 0 && !Auto.hasServer(this)) act.addView(ui.button("정산유틸로 직접 넘기기 (1건씩)") { Auto.handOne(this) })
        body.addView(act)

        // 최근 목록
        val list = ui.box()
        list.addView(ui.text("최근 정리", 16f, ui.ink, bold = true))
        val items = Auto.items(this).sortedByDescending { it.at }.take(15)
        if (items.isEmpty()) list.addView(ui.text("아직 없습니다. 2.0을 깐 뒤 걸려 온(받은) 전화부터 정리합니다.", 13f, ui.sub))
        for (it in items) {
            val (label, color) = when (it.st) {
                "sent" -> "보냄 ✅" to ui.okC
                "handed" -> "정산유틸로 넘김 ✅" to ui.okC
                "text" -> "글자 있음 · 보내기 전" to ui.acc
                "wait" -> "정리 기다림" to ui.acc
                "none" -> "녹음 없음" to ui.sub
                else -> "실패" to ui.badC
            }
            list.addView(ui.text("${DateFormat.format("M/d HH:mm", it.at)} · ${it.who} · ${it.dur / 60}분 ${it.dur % 60}초 · $label", 14f, color).apply { setPadding(0, ui.dp(8), 0, 0) })
            val extra = when { it.sum.isNotEmpty() -> it.sum; it.err.isNotEmpty() -> it.err; else -> "" }
            if (extra.isNotEmpty()) list.addView(ui.text(extra, 12f, ui.sub))
        }
        body.addView(list)

        // 진단
        val dg = ui.box()
        dg.addView(ui.text("진단 기록", 16f, ui.ink, bold = true))
        dg.addView(ui.text("잘 안 되면 '진단 기록 복사' → Claude 채팅에 붙여 넣어 주세요. (통화 글자 내용은 들어가지 않습니다)", 13f, ui.sub))
        dg.addView(ui.button("진단 기록 복사", primary = true) {
            val s = Auto.summary(this) + "\n" + Auto.logText(this).lines().takeLast(80).joinToString("\n")
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("대덕 도우미 진단", s))
            toast("복사했습니다")
        })
        dg.addView(ui.text(Auto.logText(this).lines().takeLast(25).reversed().joinToString("\n").ifEmpty { "(기록 없음)" }, 12f, ui.sub).apply { setPadding(0, ui.dp(8), 0, 0); setTextIsSelectable(true) })
        body.addView(dg)
    }

    private fun runNow() {
        if (running) return
        if (!Auto.hasAudioPerm(this) || !Auto.hasMic(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO), 9); return
        }
        running = true; render()
        scope.launch {
            Auto.log(this@TalkActivity, "상태 화면: 지금 정리하기")
            // 통화 기록을 다시 읽어 빠진 통화가 있으면 줄에 넣음
            withContext(Dispatchers.IO) { try { Auto.onCallLog(this@TalkActivity) } catch (_: Throwable) {} }
            val r = withContext(Dispatchers.IO) { Auto.process(this@TalkActivity, fg = true) { s -> runOnUiThread { progress?.text = s } } }
            running = false
            if (r.needNet) Auto.schedule(this@TalkActivity, Auto.JOB_NET, 3000)
            if (Auto.waitingCount(this@TalkActivity) == 0) Auto.clearTap(this@TalkActivity)
            render()
            progress?.text = when {
                r.busy -> "뒤에서 정리하는 중입니다. 잠시 뒤 다시 눌러 주세요."
                r.done > 0 -> "보냈습니다 ✅ ${r.done}건" + (if (r.lastSum.isNotEmpty()) "\n" + r.lastSum else "")
                r.local > 0 -> "서버 연결 전이라 '정산유틸로 직접 넘기기'를 눌러 주세요 (정산유틸 '가져오기'를 한 번 누르면 다음부터 자동)"
                r.lastErr.isNotEmpty() -> "못 했습니다: " + r.lastErr
                r.retry -> "녹음 파일을 아직 찾지 못했습니다. 잠시 뒤 다시 찾습니다."
                else -> "정리할 통화가 없습니다"
            }
        }
    }

    private fun retryFailed() {
        // 다시 시도: 실패·녹음없음을 "기다림"으로 되돌림
        val l = Auto.items(this)
        var n = 0
        for (it in l) if (it.st == "fail" || it.st == "none") { it.st = "wait"; it.tries = 0; it.ftries = 0; it.err = ""; n++ }
        Auto.replaceAll(this, l)
        Auto.log(this, "다시 시도로 ${n}건 되돌림")
        runNow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
