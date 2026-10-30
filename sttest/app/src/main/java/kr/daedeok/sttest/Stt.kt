package kr.daedeok.sttest

import android.content.Context
import android.net.Uri
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * 구글 ML Kit 음성인식(알파) 감싸기.
 * 라이브러리가 알파라 버전마다 이름이 조금씩 다름(문서끼리도 다름) →
 * 파일 넣는 방법·모델 내려받기는 이름을 찾아서 부르고, 찾은 이름을 진단에 남김.
 */
object Stt {
    private val clients = HashMap<Boolean, SpeechRecognizer>()

    fun client(advanced: Boolean): SpeechRecognizer = clients.getOrPut(advanced) {
        SpeechRecognition.getClient(speechRecognizerOptions {
            locale = Locale.KOREA
            preferredMode = if (advanced) SpeechRecognizerOptions.Mode.MODE_ADVANCED
            else SpeechRecognizerOptions.Mode.MODE_BASIC
        })
    }

    /** 오류 뒤에는 엔진이 닫힐 수 있어서, 다시 시도할 때는 새로 만듦 */
    fun fresh(advanced: Boolean): SpeechRecognizer { clients.remove(advanced)?.let { try { it.close() } catch (_: Throwable) {} }; return client(advanced) }

    fun closeAll() {
        for (c in clients.values) try { c.close() } catch (_: Throwable) {}
        clients.clear()
    }

    fun statusName(st: Int): String = when (st) {
        FeatureStatus.AVAILABLE -> "사용 가능 ✅"
        FeatureStatus.DOWNLOADABLE -> "모델 내려받기 필요 ⬇️"
        else -> {
            val n = constName(FeatureStatus::class.java, st)
            when (n) {
                "DOWNLOADING" -> "모델 내려받는 중 ⏳"
                "UNAVAILABLE" -> "이 폰에서 안 됨 ❌"
                null -> "기타 상태 (코드 $st)"
                else -> "$n (코드 $st)"
            }
        }
    }

    /** 상수 이름 찾기 (FeatureStatus.UNAVAILABLE 같은 이름이 버전마다 다를 수 있어 직접 참조하지 않음) */
    private fun constName(cls: Class<*>, v: Int): String? {
        val pools = ArrayList<Pair<Any?, Class<*>>>()
        pools.add(null to cls)
        try { val comp = cls.getField("Companion").get(null); if (comp != null) pools.add(comp to comp.javaClass) } catch (_: Throwable) {}
        for (c in cls.declaredClasses) pools.add(null to c)
        for ((recv, c) in pools) {
            for (f in c.declaredFields) {
                try {
                    if (f.type != Int::class.javaPrimitiveType) continue
                    f.isAccessible = true
                    val x = if (Modifier.isStatic(f.modifiers)) f.getInt(null) else if (recv != null) f.getInt(recv) else continue
                    if (x == v) return f.name
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    /** 모델 내려받기: 문서에 따라 download() 함수 또는 download 속성 */
    fun downloadFlow(r: SpeechRecognizer): Flow<*> {
        val m = SpeechRecognizer::class.java.methods.firstOrNull {
            (it.name == "download" || it.name == "getDownload") && it.parameterTypes.isEmpty()
        } ?: throw IllegalStateException("라이브러리에 download가 없음: " + SpeechRecognizer::class.java.methods.joinToString { it.name })
        return m.invoke(r) as Flow<*>
    }

    private fun factories(): List<Pair<Any?, Method>> {
        val out = ArrayList<Pair<Any?, Method>>()
        val cls = AudioSource::class.java
        for (m in cls.methods) if (Modifier.isStatic(m.modifiers) && cls.isAssignableFrom(m.returnType)) out.add(null to m)
        try {
            val comp = cls.getField("Companion").get(null)
            if (comp != null) for (m in comp.javaClass.methods) if (!Modifier.isStatic(m.modifiers) && cls.isAssignableFrom(m.returnType)) out.add(comp to m)
        } catch (_: Throwable) {}
        return out
    }

    fun describeFactories(): String = factories().joinToString(", ") { (r, m) ->
        (if (r == null) "" else "Companion.") + m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")"
    }.ifEmpty { "(없음)" }

    var lastFactoryError: String? = null

    /**
     * 녹음 파일을 통째로 넣는 방법을 찾음: fromFile(File) → fromFile(String) → fromUri(Context,Uri) → fromUri(Uri).
     * 없으면 null (→ PCM 실시간 넣기로 대신함).
     */
    fun fileSource(ctx: Context, f: File, uri: Uri, kind: String = "any"): Pair<AudioSource, String>? {
        lastFactoryError = null
        val cands = factories()
        val order = listOf<(Method) -> Array<Any>?>(
            { m -> if (m.parameterTypes.size == 1 && m.parameterTypes[0] == File::class.java) arrayOf(f) else null },
            { m -> if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java && m.name.contains("file", true)) arrayOf(f.absolutePath) else null },
            { m -> if (m.parameterTypes.size == 2 && Context::class.java.isAssignableFrom(m.parameterTypes[0]) && m.parameterTypes[1] == Uri::class.java) arrayOf(ctx, uri) else null },
            { m -> if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Uri::class.java) arrayOf(uri) else null },
        )
        val picks = when (kind) { "file" -> order.take(2); "uri" -> order.drop(2); else -> order }
        for (pick in picks) {
            for ((recv, m) in cands) {
                val args = pick(m) ?: continue
                try {
                    val src = m.invoke(recv, *args) as AudioSource
                    return src to (m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")")
                } catch (t: Throwable) {
                    val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    lastFactoryError = m.name + ": " + c
                }
            }
        }
        return null
    }

    /** 응답의 글자 꺼내기 (text 속성이 있으면 그것, 없으면 toString) */
    fun textOf(o: Any?): String {
        if (o == null) return ""
        return try { (o.javaClass.getMethod("getText").invoke(o) as? String) ?: o.toString() } catch (_: Throwable) { o.toString() }
    }
}
