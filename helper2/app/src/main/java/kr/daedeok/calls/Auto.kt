package kr.daedeok.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 도우미 2.0 — 통화 내용 자동 정리
 *
 * 통화가 끝남(통화 기록이 바뀜) → 걸려 온 전화(받은 것) 줄 세우기
 *  → 갤럭시 자동 녹음 파일(Recordings/Call) 찾기 → 폰 안 음성인식(구글 ML Kit, 기본 방식)으로 글자
 *  → 내 정산유틸 서버(talkIn)에 '글자만' 보냄 → 서버가 AI 요약 → 정산유틸이 그 전화 카드에 "자동"으로 붙임
 *
 * 안드로이드 규칙: 폰 안 AI(글자 변환)는 "화면에 떠 있는 앱"만 쓸 수 있을 수 있음(BACKGROUND_USE_BLOCKED).
 * 그래서 ① 먼저 뒤에서 해 보고 ② 막히면 작은 정리 창(AutoActivity)을 띄우거나(다른 앱 위에 표시 허용 시)
 * ③ "눌러서 정리" 알림을 띄움. 무엇이 됐는지는 진단 기록에 남김.
 */
object Auto {
    const val JOB_WORK = 43
    const val JOB_RETRY = 46
    const val JOB_NET = 44
    const val NOTI_TAP = 45
    const val NOTI_DONE = 47
    private const val CH_TAP = "talk_tap"
    private const val CH_DONE = "talk_done"
    private const val MIN_SEC = 5            // 5초 미만 통화는 건너뜀
    private const val MAX_FIND_TRIES = 6     // 녹음 파일이 늦게 생길 때 다시 찾는 횟수 (약 5분)
    private const val TALK_MAX = 20000

    private val busy = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun p(ctx: Context) = Calls.prefs(ctx)

    @JvmStatic fun enabled(ctx: Context) = p(ctx).getBoolean("autoOn", true)
    @JvmStatic fun setEnabled(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("autoOn", on).apply(); log(ctx, if (on) "자동 정리 켬" else "자동 정리 끔") }
    @JvmStatic fun popEnabled(ctx: Context) = p(ctx).getBoolean("popOn", true)
    @JvmStatic fun setPopEnabled(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("popOn", on).apply() }
    @JvmStatic fun hasServer(ctx: Context) = p(ctx).getString("srv", "")!!.isNotEmpty() && p(ctx).getString("htok", "")!!.isNotEmpty()

    // ---------- 진단 기록 ----------
    @JvmStatic @Synchronized fun log(ctx: Context, s: String) {
        val t = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date())
        var all = p(ctx).getString("alog", "")!! + t + " " + s + "\n"
        if (all.length > 14000) all = all.substring(all.length - 12000).substringAfter('\n')
        p(ctx).edit().putString("alog", all).apply()
    }
    @JvmStatic fun logText(ctx: Context): String = p(ctx).getString("alog", "")!!

    // ---------- 줄(대기열) ----------
    /** st: wait(녹음 찾기·글자 변환 전) · text(글자 있음, 보내기 전) · sent(서버로 보냄) · handed(정산유틸에 직접 넘김) · none(녹음 없음) · fail */
    class Item(
        val at: Long, val num: String, val dur: Long, val name: String, val kind: String,
        var st: String = "wait", var file: String = "", var uri: String = "", var text: String = "",
        var tries: Int = 0, var ftries: Int = 0, var err: String = "", var how: String = "", var sum: String = "", var upd: Long = 0
    ) {
        fun json(): JSONObject = JSONObject().put("at", at).put("num", num).put("dur", dur).put("name", name).put("kind", kind)
            .put("st", st).put("file", file).put("uri", uri).put("text", text).put("tries", tries).put("ftries", ftries)
            .put("err", err).put("how", how).put("sum", sum).put("upd", upd)
        val who: String get() = name.ifEmpty { Calls.fmt(num) }
        companion object {
            fun of(o: JSONObject) = Item(
                o.optLong("at"), o.optString("num"), o.optLong("dur"), o.optString("name"), o.optString("kind", "i"),
                o.optString("st", "wait"), o.optString("file"), o.optString("uri"), o.optString("text"),
                o.optInt("tries"), o.optInt("ftries"), o.optString("err"), o.optString("how"), o.optString("sum"), o.optLong("upd")
            )
        }
    }

    @JvmStatic @Synchronized fun items(ctx: Context): MutableList<Item> {
        val out = ArrayList<Item>()
        try { val a = JSONArray(p(ctx).getString("tq", "[]")); for (i in 0 until a.length()) out.add(Item.of(a.getJSONObject(i))) } catch (_: Throwable) {}
        return out
    }

    @Synchronized private fun save(ctx: Context, list: List<Item>) {
        val now = System.currentTimeMillis()
        // 끝난 것은 3일 지나면 지움, 최대 40개
        val keep = list.filter { (it.st == "wait" || it.st == "text") || now - it.at < 3L * 24 * 3600 * 1000 }.sortedByDescending { it.at }.take(40)
        val a = JSONArray(); for (it in keep) a.put(it.json())
        p(ctx).edit().putString("tq", a.toString()).apply()
    }

    @Synchronized private fun update(ctx: Context, at: Long, num: String, f: (Item) -> Unit) {
        val l = items(ctx)
        l.firstOrNull { it.at == at && it.num == num }?.let { f(it); it.upd = System.currentTimeMillis() }
        save(ctx, l)
    }

    @JvmStatic fun replaceAll(ctx: Context, list: List<Item>) = save(ctx, list)

    @JvmStatic fun waitingCount(ctx: Context) = items(ctx).count { it.st == "wait" }
    @JvmStatic fun textCount(ctx: Context) = items(ctx).count { it.st == "text" }

    /** 처음 설치(또는 1.9에서 올림)했을 때: 지금부터 걸려 오는 전화만 정리 (예전 통화는 건드리지 않음) */
    @JvmStatic fun ensureSince(ctx: Context) {
        if (p(ctx).getLong("autoSince", 0L) == 0L) p(ctx).edit().putLong("autoSince", System.currentTimeMillis()).apply()
    }

    // ---------- 1) 통화가 끝나면 ----------
    /** CallWatch(통화 기록이 바뀔 때)에서 부름 */
    @JvmStatic fun onCallLog(ctx: Context) {
        if (!enabled(ctx) || !Calls.canRead(ctx)) return
        var since = p(ctx).getLong("autoSince", 0L)
        if (since == 0L) since = System.currentTimeMillis() - 15 * 60 * 1000L
        val blocked = p(ctx).getString("block", "")!!.split(".").toSet()
        val list = items(ctx)
        var maxAt = since
        var added = 0
        val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, CallLog.Calls.DATE + " > ?", arrayOf(since.toString()), CallLog.Calls.DATE + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val at = c.getLong(2)
                    if (at > maxAt) maxAt = at
                    if (c.getInt(4) != CallLog.Calls.INCOMING_TYPE) continue   // 받은 전화만 (부재중·거절은 녹음이 없음)
                    val dur = c.getLong(3)
                    if (dur < MIN_SEC) continue
                    val num = Calls.key(c.getString(0))
                    if (num.length < 8 || blocked.contains(num)) continue
                    if (list.any { it.at == at && it.num == num }) continue
                    list.add(Item(at, num, dur, c.getString(1) ?: "", "i"))
                    added++
                }
            }
        } catch (t: Throwable) { log(ctx, "통화 기록 읽기 오류: $t"); return }
        p(ctx).edit().putLong("autoSince", maxAt).apply()
        if (added > 0) {
            save(ctx, list)
            log(ctx, "통화 끝남 → 정리할 통화 ${added}건 줄 세움")
            schedule(ctx, JOB_WORK, 6000)
        }
    }

    @JvmStatic fun schedule(ctx: Context, id: Int, delayMs: Long) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val b = JobInfo.Builder(id, ComponentName(ctx, TalkJob::class.java)).setMinimumLatency(delayMs)
        if (id == JOB_NET) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) else b.setOverrideDeadline(delayMs + 30000)
        try { js.schedule(b.build()) } catch (t: Throwable) { log(ctx, "예약 오류: $t") }
    }

    // ---------- 2) 녹음 파일 찾기 ----------
    class Rec(val uri: Uri, val name: String, val durMs: Long, val addedMs: Long)

    private val nameTime = Regex("""(\d{6})_(\d{6})""")

    /** 파일 이름 끝의 yyMMdd_HHmmss (갤럭시: "통화 녹음 홍길동_251030_141523.m4a") → 시각 */
    private fun timeInName(n: String): Long {
        val m = nameTime.findAll(n).lastOrNull() ?: return 0
        return try {
            val d = m.groupValues[1]; val t = m.groupValues[2]
            Calendar.getInstance().apply {
                set(2000 + d.substring(0, 2).toInt(), d.substring(2, 4).toInt() - 1, d.substring(4, 6).toInt(), t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt())
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: Throwable) { 0 }
    }

    @JvmStatic fun hasAudioPerm(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    @JvmStatic fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun findRecording(ctx: Context, it: Item): Rec? {
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.RELATIVE_PATH)
        val used = items(ctx).filter { x -> x.uri.isNotEmpty() && !(x.at == it.at && x.num == it.num) }.map { x -> x.uri }.toSet()
        val start = it.at
        val end = it.at + it.dur * 1000
        val last4 = it.num.takeLast(4)
        var best: Rec? = null
        var bestScore = 0
        ctx.contentResolver.query(col, proj, MediaStore.Audio.Media.DATE_ADDED + " >= ?", arrayOf(((start - 120_000) / 1000).toString()), MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(4) ?: ""
                if (!(rel.contains("call", true) || rel.contains("통화"))) continue
                val uri = ContentUris.withAppendedId(col, c.getLong(0))
                if (used.contains(uri.toString())) continue
                val name = c.getString(1) ?: ""
                val dms = c.getLong(2)
                val added = c.getLong(3) * 1000
                var score = 0
                val nt = timeInName(name)
                if (nt > 0 && abs(nt - start) <= 90_000) score += 4
                if (dms > 0 && abs(dms - it.dur * 1000) <= maxOf(6000L, it.dur * 100)) score += 2
                if (added in (start - 30_000)..(end + 300_000)) score += 1
                if (last4.length == 4 && name.contains(last4)) score += 2
                if (it.name.isNotEmpty() && name.contains(it.name)) score += 2
                if (score > bestScore) { bestScore = score; best = Rec(uri, name, dms, added) }
            }
        }
        return if (bestScore >= 4) best else null
    }

    // ---------- 3) 글자로 바꾸기 ----------
    class Stt1(val text: String, val err: String?, val how: String)

    private fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
        val dir = File(ctx.cacheDir, "rec"); dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "call.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    /** 고급 방식이 되면 고급, 아니면 기본 (폴드7: 기본만 됨) */
    private suspend fun pickMode(): Boolean? {
        for (adv in listOf(true, false)) {
            val st = Stt.client(adv).checkStatus()
            if (st == FeatureStatus.AVAILABLE) return adv
        }
        return null
    }

    suspend fun transcribe(ctx: Context, rec: Rec, onPart: ((String) -> Unit)? = null): Stt1 {
        val mode: Boolean
        try {
            mode = pickMode() ?: return Stt1("", "음성인식 사용 불가 (기본: ${Stt.statusName(Stt.client(false).checkStatus())})", "")
        } catch (t: Throwable) {
            return Stt1("", "상태 확인 오류: " + causeOf(t), "")
        }
        val label = if (mode) "고급" else "기본"
        val f = copyToCache(ctx, rec.uri, rec.name)
        var lastErr: String? = null
        try {
            for (kind in listOf("file", "uri")) {
                val r = if (kind == "file") Stt.client(mode) else Stt.fresh(mode)
                val src = Stt.fileSource(ctx, f, rec.uri, kind)?.first ?: continue
                val finals = StringBuilder()
                var partial = ""
                var err: String? = null
                try {
                    // 30분 통화도 몇 분이면 끝남 (폴드7 기본 방식: 3분 19초 통화 → 17초)
                    withTimeout(9 * 60 * 1000L) {
                        r.startRecognition(speechRecognizerRequest { audioSource = src }).collect { resp ->
                            when (resp) {
                                is SpeechRecognizerResponse.FinalTextResponse -> { val s = resp.text.trim(); if (s.isNotEmpty()) finals.append(s).append('\n'); partial = ""; onPart?.invoke(finals.toString()) }
                                is SpeechRecognizerResponse.PartialTextResponse -> { partial = Stt.textOf(resp); onPart?.invoke(finals.toString() + partial) }
                                is SpeechRecognizerResponse.ErrorResponse -> err = resp.toString()
                                else -> {}
                            }
                        }
                    }
                } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                    err = "시간 초과(9분)"
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    err = causeOf(t)
                } finally {
                    try { (src as? java.io.Closeable)?.close() } catch (_: Throwable) {}
                }
                if (partial.isNotBlank() && !finals.contains(partial)) finals.append(partial)
                val txt = finals.toString().trim()
                if (txt.isNotEmpty()) return Stt1(txt, null, label)
                lastErr = err ?: "빈 결과"
                if (isFgOnly(lastErr) || lastErr.contains("PERMISSION")) break // 다른 넣는 방법도 똑같이 막힘
            }
        } finally {
            try { f.delete() } catch (_: Throwable) {}
        }
        return Stt1("", lastErr ?: "파일 넣는 방법을 찾지 못함", label)
    }

    private fun causeOf(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return if (c === t) t.toString() else "$t ← $c"
    }

    /** "화면에 떠 있어야 함" 계열 오류인지 */
    fun isFgOnly(e: String?): Boolean {
        if (e == null) return false
        val u = e.uppercase()
        return u.contains("BACKGROUND") || u.contains("FOREGROUND") || u.contains("NOT_TOP") || u.contains("TOP APP")
    }

    // ---------- 4) 한 번 돌기 ----------
    class Result {
        var done = 0; var retry = false; var needFg = false; var needNet = false; var busy = false; var local = 0
        var lastErr = ""; var lastWho = ""; var lastSum = ""
    }

    /**
     * 대기 중인 통화를 정리. fg=true: 화면에 떠 있는 상태(정리 창·상태 화면)에서 부름.
     * onStep: 화면에 진행 상황 표시용
     */
    suspend fun process(ctx: Context, fg: Boolean, onStep: ((String) -> Unit)? = null): Result {
        val r = Result()
        if (!busy.compareAndSet(false, true)) { r.busy = true; return r }
        try {
            val skipBg = !fg && System.currentTimeMillis() - p(ctx).getLong("bgBlockedAt", 0L) < 3L * 24 * 3600 * 1000
            for (it in items(ctx).filter { x -> x.st == "wait" }.sortedBy { x -> x.at }) {
                if (!hasAudioPerm(ctx)) { r.lastErr = "'음악 및 오디오' 권한 없음"; r.needFg = true; log(ctx, "녹음을 찾을 권한(음악 및 오디오)이 없음"); break }
                onStep?.invoke("${it.who} 통화 녹음 찾는 중…")
                val rec = try { findRecording(ctx, it) } catch (t: Throwable) { log(ctx, "녹음 찾기 오류: $t"); null }
                if (rec == null) {
                    update(ctx, it.at, it.num) { x ->
                        x.ftries++
                        if (x.ftries >= MAX_FIND_TRIES) { x.st = "none"; x.err = "통화 녹음 파일을 찾지 못함 (자동 녹음이 꺼져 있었나요?)" }
                    }
                    if (it.ftries + 1 < MAX_FIND_TRIES) r.retry = true
                    log(ctx, "${it.who}: 녹음 파일 아직 없음 (${it.ftries + 1}/$MAX_FIND_TRIES)")
                    continue
                }
                if (skipBg) { r.needFg = true; log(ctx, "${it.who}: 녹음 찾음(${rec.name}) → 뒤에서는 막혀 있어 정리 창으로"); update(ctx, it.at, it.num) { x -> x.file = rec.name; x.uri = rec.uri.toString() }; continue }
                if (!hasMic(ctx)) { r.needFg = true; r.lastErr = "마이크 권한 없음"; log(ctx, "음성인식에 필요한 마이크 권한이 없음 (녹음은 하지 않음)"); break }
                onStep?.invoke("${it.who} 통화(${it.dur / 60}분 ${it.dur % 60}초) 글자로 바꾸는 중…")
                val t0 = System.currentTimeMillis()
                val s = transcribe(ctx, rec) { part -> onStep?.invoke("글자로 바꾸는 중… ${part.length}글자") }
                val sec = (System.currentTimeMillis() - t0) / 1000
                if (s.text.isNotEmpty()) {
                    log(ctx, "${it.who}: 글자 변환 ✅ ${sec}초 · ${s.text.length}글자 · ${s.how} · " + (if (fg) "화면에서" else "뒤에서"))
                    if (!fg) p(ctx).edit().putLong("bgOkAt", System.currentTimeMillis()).apply()
                    update(ctx, it.at, it.num) { x -> x.st = "text"; x.text = s.text.take(TALK_MAX); x.file = rec.name; x.uri = rec.uri.toString(); x.how = "auto-" + (if (fg) "fg" else "bg") + "-" + s.how; x.err = "" }
                } else {
                    val e = s.err ?: "빈 결과"
                    log(ctx, "${it.who}: 글자 변환 실패 (" + (if (fg) "화면에서" else "뒤에서") + ") $e")
                    r.lastErr = e
                    if (!fg) {
                        if (isFgOnly(e)) p(ctx).edit().putLong("bgBlockedAt", System.currentTimeMillis()).apply()
                        update(ctx, it.at, it.num) { x -> x.file = rec.name; x.uri = rec.uri.toString(); x.err = e }
                        r.needFg = true
                    } else {
                        update(ctx, it.at, it.num) { x -> x.tries++; x.err = e; x.file = rec.name; x.uri = rec.uri.toString(); if (x.tries >= 3) x.st = "fail" }
                    }
                }
            }
            deliver(ctx, r, onStep)
        } finally {
            try { Stt.closeAll() } catch (_: Throwable) {}
            busy.set(false)
        }
        return r
    }

    // ---------- 5) 보내기 ----------
    private val srvOk = Regex("""^https://script\.google(usercontent)?\.com/[A-Za-z0-9_\-/.]+$""")

    /** 정산유틸의 "가져오기"가 넘겨 준 서버 주소·도우미 열쇠 저장 (구글 Apps Script 주소만 받음) */
    @JvmStatic fun rememberServer(ctx: Context, srv: String?, htok: String?) {
        if (srv == null || htok == null) return
        if (!srvOk.matches(srv) || srv.length > 400) { log(ctx, "받은 서버 주소가 구글 주소가 아니라 무시함"); return }
        if (htok.length < 16 || htok.length > 200 || !htok.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return
        val changed = p(ctx).getString("htok", "") != htok
        p(ctx).edit().putString("srv", srv).putString("htok", htok).apply()
        if (changed) log(ctx, "정산유틸 서버 연결 받음 → 통화 내용을 서버로 바로 보냄")
        if (textCount(ctx) > 0) schedule(ctx, JOB_NET, 1000)
    }

    private fun post(srv: String, body: JSONObject): JSONObject {
        var url = URL(srv)
        var data: ByteArray? = body.toString().toByteArray(Charsets.UTF_8)
        for (hop in 0 until 5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 90000
            if (data != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                c.outputStream.use { it.write(data) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without Location")
                url = URL(url, loc); data = null // Apps Script: POST 결과는 다른 주소에서 GET으로 받음
                c.disconnect(); continue
            }
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            if (code >= 400) throw IllegalStateException("HTTP $code")
            return JSONObject(txt)
        }
        throw IllegalStateException("redirect too many")
    }

    private fun deliver(ctx: Context, r: Result, onStep: ((String) -> Unit)?) {
        val srv = p(ctx).getString("srv", "")!!
        val htok = p(ctx).getString("htok", "")!!
        val ready = items(ctx).filter { it.st == "text" }.sortedBy { it.at }
        if (ready.isEmpty()) return
        if (srv.isEmpty() || htok.isEmpty()) {
            r.local = ready.size
            log(ctx, "서버 연결 전이라 ${ready.size}건은 폰 안에서 정산유틸로 넘김 (정산유틸 '가져오기'를 한 번 누르면 다음부터 자동)")
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        for (it in ready) {
            onStep?.invoke("${it.who} 통화 내용 보내는 중… (AI 요약)")
            try {
                val res = post(srv, JSONObject().put("action", "talkIn").put("htok", htok).put("number", it.num).put("at", it.at).put("dur", it.dur)
                    .put("kind", it.kind).put("name", it.name).put("file", it.file).put("text", it.text).put("today", today).put("how", it.how))
                if (res.optBoolean("ok")) {
                    val sum = res.optString("summary", "")
                    update(ctx, it.at, it.num) { x -> x.st = "sent"; x.sum = sum; x.text = "" }
                    r.done++; r.lastWho = it.who; r.lastSum = sum
                    log(ctx, "${it.who}: 서버로 보냄 ✅" + (if (res.optBoolean("ai")) " (AI 요약 붙음)" else ""))
                    notifyDone(ctx, it.who, sum)
                } else {
                    val e = res.optString("error", "?")
                    log(ctx, "${it.who}: 서버가 거절함 ($e)")
                    if (e == "auth") {
                        p(ctx).edit().remove("htok").apply()
                        log(ctx, "도우미 열쇠가 만료됨 → 정산유틸에서 '가져오기'를 한 번 눌러 주세요. 그 전까지는 폰 안에서 넘김")
                        r.local = items(ctx).count { x -> x.st == "text" }
                        return
                    }
                    if (e == "empty") update(ctx, it.at, it.num) { x -> x.st = "fail"; x.err = "빈 글자" }
                    else { r.needNet = true; update(ctx, it.at, it.num) { x -> x.err = "서버: $e" } }
                }
            } catch (t: Throwable) {
                log(ctx, "${it.who}: 보내기 실패 (인터넷?) $t → 인터넷이 되면 다시 보냄")
                r.needNet = true
                break
            }
        }
    }

    /** 서버 연결이 없을 때: 가장 오래된 1건을 정산유틸 주소(#talk=)로 넘김. 넘겼으면 true */
    @JvmStatic fun handOne(ctx: Context): Boolean {
        val it = items(ctx).filter { x -> x.st == "text" }.minByOrNull { x -> x.at } ?: return false
        val nm = it.file.substringBeforeLast('.').ifEmpty { "통화 녹음 ${it.who}" } + ".txt"
        Calls.openApp(ctx, it.text, nm)
        update(ctx, it.at, it.num) { x -> x.st = "handed"; x.text = "" }
        log(ctx, "${it.who}: 정산유틸로 직접 넘김")
        return true
    }

    // ---------- 6) 뒤에서 막혔을 때: 정리 창 또는 알림 ----------
    @JvmStatic fun escalate(ctx: Context, why: String) {
        val n = waitingCount(ctx)
        if (n == 0) return
        if (popEnabled(ctx) && Settings.canDrawOverlays(ctx)) {
            main.post {
                var v: View? = null
                try {
                    val wm = ctx.getSystemService(WindowManager::class.java)
                    val tv = TextView(ctx).apply {
                        text = "대덕: 통화 내용 정리 중…"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                        setPadding(36, 18, 36, 18)
                        background = GradientDrawable().apply { cornerRadius = 60f; setColor(0xE04338CA.toInt()) }
                    }
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
                    wm.addView(tv, lp); v = tv
                    // 안드로이드 15+: "다른 앱 위에 표시" 창이 보이는 동안에만 뒤에서 화면을 띄울 수 있음
                    main.postDelayed({
                        try {
                            ctx.startActivity(Intent(ctx, AutoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("from", "pop"))
                            log(ctx, "정리 창 띄움 ($why)")
                        } catch (t: Throwable) { log(ctx, "정리 창 띄우기 실패 → 알림으로: $t"); notifyTap(ctx, n) }
                    }, 300)
                    main.postDelayed({ try { wm.removeView(tv) } catch (_: Throwable) {} }, 4000)
                } catch (t: Throwable) {
                    log(ctx, "위에 표시 실패 → 알림으로: $t"); notifyTap(ctx, n)
                    v?.let { try { ctx.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Throwable) {} }
                }
            }
            // 정리 창이 실제로 처리하지 못했을 때를 대비해 알림도 남김 (처리되면 지워짐)
            notifyTap(ctx, n, quiet = true)
            return
        }
        log(ctx, "'눌러서 정리' 알림 띄움 ($why)")
        notifyTap(ctx, n)
    }

    private fun canNotify(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @JvmStatic fun notifyTap(ctx: Context, n: Int, quiet: Boolean = false, local: Boolean = false) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_TAP, "통화 내용 정리", NotificationManager.IMPORTANCE_DEFAULT))
        val i = Intent(ctx, AutoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from", "noti")
        val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (local) "통화 내용 ${n}건 준비됨" else "통화 내용 정리하기 (${n}건)"
        val body = if (local) "눌러서 정산유틸 전화 카드에 붙이기" else "눌러 주시면 잠깐(10~30초) 사이에 글자로 바꿔 정산유틸로 보냅니다"
        nm.notify(NOTI_TAP, Notification.Builder(ctx, CH_TAP).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(quiet).setCategory(Notification.CATEGORY_REMINDER).build())
    }

    @JvmStatic fun clearTap(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTI_TAP) }

    private fun notifyDone(ctx: Context, who: String, sum: String) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "통화 내용 보냄", NotificationManager.IMPORTANCE_LOW))
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = sum.ifEmpty { "정산유틸 걸려 온 전화 카드에 붙었습니다" }
        nm.notify(NOTI_DONE, Notification.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("통화 내용 보냄 · $who").setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    // ---------- 뒤에서 한 번 (TalkJob) ----------
    suspend fun runBackground(ctx: Context): Result {
        val r = process(ctx, fg = false)
        if (r.busy) { schedule(ctx, JOB_RETRY, 20000); return r }
        if (r.needNet) schedule(ctx, JOB_NET, 5000)
        if (r.needFg) escalate(ctx, r.lastErr.ifEmpty { "뒤에서 글자 변환이 막힘" })
        else if (waitingCount(ctx) == 0) clearTap(ctx)
        if (r.local > 0) notifyTap(ctx, r.local, local = true)
        if (r.retry) {
            val maxTry = items(ctx).filter { it.st == "wait" }.maxOfOrNull { it.ftries } ?: 1
            schedule(ctx, JOB_RETRY, (15000L shl (maxTry - 1).coerceIn(0, 4)).coerceAtMost(120000))
        }
        return r
    }

    /** 진단 요약 (상태 화면 · 진단 복사) */
    @JvmStatic fun summary(ctx: Context): String {
        val pk = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Throwable) { "?" }
        val f = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
        val bgOk = p(ctx).getLong("bgOkAt", 0L); val bgNo = p(ctx).getLong("bgBlockedAt", 0L)
        return buildString {
            append("도우미 ").append(pk).append(" · 안드로이드 ").append(android.os.Build.VERSION.SDK_INT).append(" · ").append(android.os.Build.MODEL).append('\n')
            append("자동 정리: ").append(if (enabled(ctx)) "켬" else "끔").append(" · 정리 창 자동: ").append(if (popEnabled(ctx) && Settings.canDrawOverlays(ctx)) "켬" else "꺼짐(알림으로)").append('\n')
            append("권한: 통화기록 ").append(ok(Calls.canRead(ctx))).append(" 음악·오디오 ").append(ok(hasAudioPerm(ctx))).append(" 마이크 ").append(ok(hasMic(ctx)))
                .append(" 알림 ").append(ok(canNotify(ctx))).append(" 위에표시 ").append(ok(Settings.canDrawOverlays(ctx))).append('\n')
            append("서버 연결: ").append(if (hasServer(ctx)) "됨" else "안 됨 (정산유틸 '가져오기' 한 번)").append('\n')
            append("뒤에서 변환: ").append(if (bgOk > bgNo && bgOk > 0) "됨 (" + f.format(Date(bgOk)) + ")" else if (bgNo > 0) "막힘 (" + f.format(Date(bgNo)) + ") → 정리 창 사용" else "아직 모름").append('\n')
        }
    }

    private fun ok(b: Boolean) = if (b) "✅" else "❌"
}
