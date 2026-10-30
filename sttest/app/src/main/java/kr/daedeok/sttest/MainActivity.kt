package kr.daedeok.sttest

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 대덕 음성시험 0.2 (0.1 → 마이크 권한 추가 · 되는 방식 자동 선택 · 넣는 방법 차례로 시도)
 * ① 통화 녹음 폴더(Recordings/Call)의 녹음 파일을 읽을 수 있는지
 * ② 폰 안 음성인식(구글 ML Kit, 한국어)이 이 폰에서 되는지
 * ③ 최근 통화 1건을 글자로 바꿔 보여주고 → 복사 / 대덕 전화분류로 보내기
 * 인터넷 권한 없음. 녹음은 폰 밖으로 나가지 않음.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Rec(val uri: Uri, val name: String, val size: Long, val durMs: Long, val dateMs: Long, val path: String)

    private lateinit var root: LinearLayout
    private lateinit var recBox: LinearLayout
    private lateinit var selView: TextView
    private lateinit var statusView: TextView
    private lateinit var progView: TextView
    private lateinit var resultView: TextView
    private lateinit var logView: TextView
    private lateinit var modeBtn: Button
    private lateinit var runBtn: Button
    private lateinit var dlBox: LinearLayout

    private var selected: Rec? = null
    private var advanced = true
    private var job: Job? = null
    private val log = StringBuilder()
    private var resultText = ""

    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
    private val sub get() = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
    private val card get() = if (dark) Color.parseColor("#1E293B") else Color.WHITE
    private val bg get() = if (dark) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("기기: ${Build.MANUFACTURER} ${Build.MODEL} · 안드로이드 ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · 칩 ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}")
        try { log("파일 넣는 방법(라이브러리): " + Stt.describeFactories()) } catch (t: Throwable) { log("라이브러리 확인 실패: $t") }
        if (hasAudioPerm()) scan()
    }

    override fun onDestroy() {
        scope.cancel()
        Stt.closeAll()
        super.onDestroy()
    }

    // ───────────────────────── 화면
    private fun buildUi() {
        val sv = ScrollView(this)
        sv.setBackgroundColor(bg)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(40)) }
        sv.addView(root)
        setContentView(sv)
        window.statusBarColor = bg

        root.addView(text("대덕 음성시험 0.2", 22f, ink, bold = true))
        root.addView(text("폰 안에서 통화 녹음을 글자로 바꿔 보는 시험판입니다. 인터넷을 쓰지 않고, 녹음은 폰 밖으로 나가지 않습니다. 도우미(대덕 전화분류)와는 별개 앱이라 지워도 영향이 없습니다.", 14f, sub).also { it.setPadding(0, dp(4), 0, dp(8)) })

        // ① 녹음 파일
        val c1 = section("① 통화 녹음 파일 찾기")
        c1.addView(button("통화 녹음 폴더 확인") { if (hasAudioPerm()) scan() else askAudioPerm() })
        c1.addView(button("다른 녹음 파일 직접 고르기", secondary = true) { pickFile() })
        recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c1.addView(recBox)
        selView = text("선택한 녹음: 없음", 15f, ink).also { it.setPadding(0, dp(8), 0, 0) }
        c1.addView(selView)

        // ② 음성인식 상태
        val c2 = section("② 폰 안 음성인식 상태 (한국어)")
        c2.addView(button("이 폰에서 되는지 확인") { checkStatus() })
        statusView = text("아직 확인 안 함", 15f, ink).also { it.setPadding(0, dp(6), 0, 0) }
        c2.addView(statusView)
        dlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c2.addView(dlBox)

        // ③ 글자로 바꾸기
        val c3 = section("③ 글자로 바꾸기")
        modeBtn = button("방식: 고급(Advanced) — 눌러서 바꾸기", secondary = true) {
            advanced = !advanced
            modeLabel()
        }
        c3.addView(modeBtn)
        runBtn = button("선택한 녹음 글자로 바꾸기") { if (job?.isActive == true) stopRun() else run() }
        c3.addView(runBtn)
        progView = text("", 14f, sub).also { it.setPadding(0, dp(6), 0, 0) }
        c3.addView(progView)
        resultView = text("", 16f, ink).also {
            it.setTextIsSelectable(true); it.setPadding(dp(12), dp(10), dp(12), dp(10)); it.visibility = View.GONE
            it.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (dark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")) }
        }
        c3.addView(resultView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("글자 복사", secondary = true) { copy(resultText, "글자를 복사했습니다") }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("대덕 전화분류로 보내기") { shareResult() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        c3.addView(row)

        // 진단
        val c4 = section("진단 기록 (Claude에게 보낼 내용)")
        c4.addView(button("진단 기록 복사", secondary = true) { copy(log.toString(), "진단 기록을 복사했습니다. 채팅에 붙여 넣어 주세요") })
        logView = text("", 12f, sub).also { it.typeface = Typeface.MONOSPACE; it.setTextIsSelectable(true) }
        c4.addView(logView)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(card) }
        }
        box.addView(text(title, 17f, ink, bold = true).also { it.setPadding(0, 0, 0, dp(8)) })
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return box
    }

    private fun button(label: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (secondary) ink else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (secondary) (if (dark) Color.parseColor("#334155") else Color.parseColor("#E2E8F0")) else Color.parseColor("#7C3AED"))
        }
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun log(s: String) {
        val t = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        log.append(t).append("  ").append(s).append('\n')
        if (::logView.isInitialized) logView.text = log
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun copy(s: String, msg: String) {
        if (s.isBlank()) { toast("복사할 내용이 없습니다"); return }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("대덕 음성시험", s))
        toast(msg)
    }

    // ───────────────────────── ① 녹음 파일
    private val audioPerm get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPerm() = checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED
    private fun askAudioPerm() = requestPermissions(arrayOf(audioPerm), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            if (hasMic()) { log("마이크 권한: 허용됨"); run() }
            else { log("마이크 권한: 거부됨"); toast("안드로이드 음성인식은 마이크 권한이 있어야 돌아갑니다. 이 앱은 마이크로 녹음하지 않습니다. 설정 → 애플리케이션 → 대덕 음성시험 → 권한 → 마이크에서 허용해 주세요") }
            return
        }
        if (requestCode == 1) {
            if (hasAudioPerm()) { log("음악·오디오 권한: 허용됨"); scan() }
            else { log("음악·오디오 권한: 거부됨"); toast("'음악 및 오디오' 권한이 있어야 통화 녹음을 찾을 수 있습니다. 설정 → 애플리케이션 → 대덕 음성시험 → 권한에서 허용해 주세요") }
        }
    }

    private fun scan() {
        recBox.removeAllViews()
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.RELATIVE_PATH
        )
        var total = 0
        val folders = LinkedHashMap<String, Int>()
        val calls = ArrayList<Rec>()
        try {
            contentResolver.query(col, proj, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    total++
                    val rel = c.getString(5) ?: ""
                    folders[rel] = (folders[rel] ?: 0) + 1
                    if (rel.contains("call", true) || rel.contains("통화")) {
                        if (calls.size < 15) calls.add(Rec(
                            ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "?", c.getLong(2),
                            c.getLong(3), c.getLong(4) * 1000, rel
                        ))
                    }
                }
            }
        } catch (t: Throwable) { log("녹음 찾기 오류: $t") }
        log("오디오 파일 전체 ${total}개 · 통화 녹음으로 보이는 것 ${calls.size}개(최근 15개까지)")
        log("폴더별: " + folders.entries.take(8).joinToString { "${it.key.ifEmpty { "(루트)" }}=${it.value}" })
        if (calls.isEmpty()) {
            recBox.addView(text("통화 녹음 폴더에서 파일을 찾지 못했습니다. 자동 통화 녹음이 켜져 있는지 확인하거나, '다른 녹음 파일 직접 고르기'로 내 파일 → 오디오 → Call 폴더의 녹음을 골라 보세요.", 14f, sub).also { it.setPadding(0, dp(6), 0, 0) })
            return
        }
        recBox.addView(text("최근 통화 녹음 (눌러서 선택)", 14f, sub).also { it.setPadding(0, dp(8), 0, 0) })
        for (r in calls) {
            val b = button("${r.name}\n${DateFormat.format("M/d HH:mm", r.dateMs)} · ${fmtDur(r.durMs)} · ${r.size / 1024}KB", secondary = true) { select(r) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 13f
            recBox.addView(b)
        }
        select(calls[0])
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초" }

    private fun select(r: Rec) {
        selected = r
        selView.text = "선택한 녹음: ${r.name}\n(${fmtDur(r.durMs)}, ${r.path.ifEmpty { "폴더 정보 없음" }})"
        log("선택: ${r.name} · ${fmtDur(r.durMs)} · ${r.size}바이트 · ${r.path}")
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try { startActivityForResult(i, 2) } catch (t: Throwable) { toast("파일 고르기 화면을 열 수 없습니다") }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 2 || resultCode != RESULT_OK || uri == null) return
        var name = "선택한 파일"; var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Throwable) {}
        var dur = 0L
        try {
            val mr = android.media.MediaMetadataRetriever()
            mr.setDataSource(this, uri)
            dur = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mr.release()
        } catch (_: Throwable) {}
        select(Rec(uri, name, size, dur, System.currentTimeMillis(), "직접 고름"))
    }

    // ───────────────────────── ② 상태
    private fun checkStatus() {
        statusView.text = "확인 중…"
        dlBox.removeAllViews()
        scope.launch {
            val lines = ArrayList<String>()
            for (adv in listOf(true, false)) {
                val label = if (adv) "고급(Advanced)" else "기본(Basic)"
                try {
                    val st = Stt.client(adv).checkStatus()
                    val name = Stt.statusName(st)
                    lines.add("$label: $name")
                    log("상태 $label = $st ($name)")
                    if (st == FeatureStatus.DOWNLOADABLE) dlBox.addView(button("$label 모델 내려받기") { download(adv) })
                } catch (t: Throwable) {
                    lines.add("$label: 확인 실패")
                    log("상태 $label 오류: $t" + hint(t))
                }
            }
            statusView.text = lines.joinToString("\n")
            // 고급이 안 되고 기본이 되면 기본으로 자동 선택
            try { if (advanced && Stt.client(true).checkStatus() != FeatureStatus.AVAILABLE && Stt.client(false).checkStatus() == FeatureStatus.AVAILABLE) { advanced = false; modeLabel(); log("고급이 안 돼서 ③ 방식을 기본으로 자동 선택") } } catch (_: Throwable) {}
        }
    }

    private fun modeLabel() { modeBtn.text = if (advanced) "방식: 고급(Advanced) — 눌러서 바꾸기" else "방식: 기본(Basic) — 눌러서 바꾸기" }

    private fun hint(t: Throwable): String {
        val s = t.toString()
        return when {
            s.contains("606") -> "  → 폰 AI가 아직 준비 중일 수 있음: 폰을 다시 켠 뒤 다시 시도"
            s.contains("PERMISSION") -> "  → 마이크 권한이 필요합니다 (설정 → 애플리케이션 → 대덕 음성시험 → 권한 → 마이크 허용)"
            else -> ""
        }
    }

    private fun download(adv: Boolean) {
        val label = if (adv) "고급" else "기본"
        statusView.text = "$label 모델 내려받는 중… (와이파이 권장, 몇 분 걸릴 수 있음)"
        scope.launch {
            try {
                Stt.downloadFlow(Stt.client(adv)).collect { st ->
                    when (st) {
                        is DownloadStatus.DownloadCompleted -> { log("$label 모델 내려받기 완료"); statusView.text = "$label 모델 준비 완료 ✅ — ③에서 글자로 바꿔 보세요" }
                        is DownloadStatus.DownloadFailed -> { log("$label 모델 내려받기 실패: $st"); statusView.text = "$label 모델 내려받기 실패 ❌ (진단 기록 참고)" }
                        else -> { progView.text = "내려받기: $st" }
                    }
                }
            } catch (t: Throwable) {
                log("내려받기 오류: $t" + hint(t)); statusView.text = "내려받기 오류 (진단 기록 참고)"
            }
        }
    }

    // ───────────────────────── ③ 글자로 바꾸기
    private fun stopRun() {
        val j = job
        scope.launch {
            try { Stt.client(advanced).stopRecognition() } catch (_: Throwable) {}
            j?.cancel()
            runBtn.text = "선택한 녹음 글자로 바꾸기"
            progView.text = "멈췄습니다"
        }
    }

    // 안드로이드 음성인식은 마이크로 녹음하지 않아도 "마이크 권한"이 있어야 돌아감 (0.1 오류 ERROR_TYPE_INSUFFICIENT_PERMISSION)
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun run() {
        val r = selected ?: run { toast("먼저 ①에서 녹음을 골라 주세요"); return }
        if (!hasMic()) { log("마이크 권한 요청 (음성인식 규칙상 필요, 녹음은 하지 않음)"); requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3); return }
        resultText = ""
        resultView.text = ""; resultView.visibility = View.VISIBLE
        runBtn.text = "멈추기"
        val t0 = SystemClock.elapsedRealtime()
        job = scope.launch {
            // 1) 방식: 고른 방식이 안 되면 되는 방식으로 자동
            var mode = advanced
            try {
                if (Stt.client(mode).checkStatus() != FeatureStatus.AVAILABLE && Stt.client(!mode).checkStatus() == FeatureStatus.AVAILABLE) {
                    mode = !mode; advanced = mode; modeLabel()
                    log((if (mode) "기본" else "고급") + " 방식이 안 돼서 " + (if (mode) "고급" else "기본") + "으로 자동 전환")
                }
            } catch (_: Throwable) {}
            val label = if (mode) "고급" else "기본"
            val rec = Stt.client(mode)
            val ticker = launch { while (isActive) { progView.text = "글자로 바꾸는 중… ${(SystemClock.elapsedRealtime() - t0) / 1000}초 지남 ($label, 녹음 ${fmtDur(r.durMs)})"; delay(1000) } }
            val finals = StringBuilder()
            var lastErr: String? = null
            try {
                val st = rec.checkStatus()
                if (st != FeatureStatus.AVAILABLE) { lastErr = "$label 방식 상태: ${Stt.statusName(st)}"; return@launch }
                val f = withContext(Dispatchers.IO) { copyToCache(r.uri, r.name) }
                // 2) 넣는 방법을 차례로: 파일 통째로 → 주소(Uri)로 → 실시간 넣기
                for (kind in listOf("file", "uri", "pcm")) {
                    if (!isActive) break
                    val (txt, err) = attempt(if (kind == "file") rec else Stt.fresh(mode), kind, f, r, label)
                    if (txt.isNotBlank()) { finals.append(txt); lastErr = err; break }
                    lastErr = err
                    if (err != null && err.contains("PERMISSION")) break // 권한 문제는 다른 방법도 똑같음
                    if (err == "skip") continue
                    log("→ 다른 방법으로 다시 시도")
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                lastErr = t.toString(); log("오류: $t" + hint(t))
            } finally {
                ticker.cancel()
                resultText = finals.toString().trim()
                val el = (SystemClock.elapsedRealtime() - t0) / 1000
                runBtn.text = "선택한 녹음 글자로 바꾸기"
                if (resultText.isNotEmpty()) {
                    resultView.text = resultText
                    progView.text = "끝 ✅ ${el}초 걸림 · ${resultText.length}글자 ($label)"
                    log("결과: ${el}초 · ${resultText.length}글자 · 녹음 ${fmtDur(r.durMs)} · $label")
                } else {
                    resultView.text = "(글자가 나오지 않았습니다)"
                    val e = if (lastErr == "skip") null else lastErr
                    progView.text = "글자 없음 ❌ ${el}초" + (e?.let { "\n$it" + hint(RuntimeException(it)) } ?: "")
                    log("결과 없음: ${e ?: "오류 없이 빈 결과"}")
                }
            }
        }
    }

    /** 한 가지 넣는 방법으로 인식. 돌려줌: (글자, 오류) · 그 방법이 라이브러리에 없으면 오류 "skip" */
    private suspend fun attempt(rec: com.google.mlkit.genai.speechrecognition.SpeechRecognizer, kind: String, f: File, r: Rec, label: String): Pair<String, String?> = coroutineScope {
        var src: AudioSource? = null
        var feeder: Job? = null
        var pipeRead: ParcelFileDescriptor? = null
        val finals = StringBuilder()
        var partial = ""
        var err: String? = null
        try {
            val how: String
            if (kind == "pcm") {
                val pipe = ParcelFileDescriptor.createPipe()
                pipeRead = pipe[0]
                src = AudioSource.fromPfd(pipe[0])
                how = "실시간 넣기(녹음 길이만큼 걸림)"
                val info = Pcm.Info()
                feeder = launch(Dispatchers.IO) {
                    try { Pcm.feed(this@MainActivity, r.uri, pipe[1], 1.0, { isActive }, { _, _ -> }, info) } catch (t: Throwable) { withContext(Dispatchers.Main) { log("소리 풀기 오류: $t") } }
                    withContext(Dispatchers.Main) { log("소리 넣기 끝: ${info.mime} ${info.inRate}Hz ${info.channels}ch ${"%.0f".format(info.seconds)}초") }
                    delay(8000)
                    try { rec.stopRecognition() } catch (_: Throwable) {}
                }
            } else {
                val made = Stt.fileSource(this@MainActivity, f, r.uri, kind) ?: return@coroutineScope Pair("", "skip")
                src = made.first; how = (if (kind == "file") "파일 통째로: " else "주소로: ") + made.second
            }
            log("시작: $label · $how")
            rec.startRecognition(speechRecognizerRequest { audioSource = src!! }).collect { resp ->
                when (resp) {
                    is SpeechRecognizerResponse.FinalTextResponse -> { val s = resp.text.trim(); if (s.isNotEmpty()) finals.append(s).append('\n'); partial = ""; resultView.text = finals.toString().trim() }
                    is SpeechRecognizerResponse.PartialTextResponse -> { partial = Stt.textOf(resp); resultView.text = (finals.toString() + partial).trim() }
                    is SpeechRecognizerResponse.ErrorResponse -> { err = resp.toString(); log("인식 오류: $resp" + hint(RuntimeException(resp.toString()))) }
                    is SpeechRecognizerResponse.CompletedResponse -> log("인식 완료 신호 받음")
                    else -> {}
                }
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            err = t.toString(); log("오류: $t" + hint(t))
        } finally {
            feeder?.cancel()
            try { (src as? java.io.Closeable)?.close() } catch (_: Throwable) {}
            try { pipeRead?.close() } catch (_: Throwable) {}
        }
        if (partial.isNotBlank() && !finals.contains(partial)) finals.append(partial)
        Pair(finals.toString().trim(), err)
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
        val dir = File(cacheDir, "rec"); dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "call.$ext")
        contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    /** 결과를 도우미(대덕 전화분류)로 → 녹음 파일 이름(이름·번호·시간)을 같이 보내서 그 전화에 붙게 함 */
    private fun shareResult() {
        if (resultText.isBlank()) { toast("먼저 ③에서 글자로 바꿔 주세요"); return }
        val title = selected?.name?.substringBeforeLast('.') ?: "통화"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, resultText)
            .putExtra(Intent.EXTRA_TITLE, "$title.txt")
            .putExtra(Intent.EXTRA_SUBJECT, "$title.txt")
        val helper = Intent(i).setClassName("kr.daedeok.calls", "kr.daedeok.calls.ShareActivity")
        try { startActivity(helper) } catch (_: Throwable) {
            try { startActivity(Intent.createChooser(i, "보낼 곳")) } catch (_: Throwable) { toast("보낼 앱이 없습니다") }
        }
    }
}
