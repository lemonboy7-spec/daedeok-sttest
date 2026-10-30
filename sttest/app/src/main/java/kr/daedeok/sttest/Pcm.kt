package kr.daedeok.sttest

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * 예비 방법: 파일을 통째로 넣는 기능이 라이브러리에 없을 때,
 * 녹음(m4a)을 풀어서 16kHz·모노·16비트 소리로 바꿔 "실시간 속도"로 흘려 넣음.
 * (구글 문서: 이 방식은 1초에 1초 분량씩 넣어야 함 → 3분 통화면 약 3분 걸림)
 */
object Pcm {
    const val OUT_RATE = 16000

    class Info(var inRate: Int = 0, var channels: Int = 0, var mime: String = "", var seconds: Double = 0.0)

    /**
     * @param speed 1.0 = 실시간. alive()가 false가 되면 멈춤.
     * @param onProgress (넣은 초, 전체 초)
     */
    fun feed(
        ctx: Context, uri: Uri, out: ParcelFileDescriptor, speed: Double,
        alive: () -> Boolean, onProgress: (Double, Double) -> Unit, info: Info
    ) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        val os: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(out)
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("오디오 트랙이 없음")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val totalSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else 0.0
            info.mime = mime; info.inRate = inRate; info.channels = ch; info.seconds = totalSec
            val d = MediaCodec.createDecoderByType(mime)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()
            var isFloat = false
            val rs = Resampler(inRate)
            val bi = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val t0 = SystemClock.elapsedRealtime()
            var outSamples = 0L
            val samplesPerSec = OUT_RATE * speed
            while (!outDone) {
                if (!alive()) return
                if (!inDone) {
                    val ii = d.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = d.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = d.dequeueOutputBuffer(bi, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = d.outputFormat
                    inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    rs.inRate = inRate
                    info.inRate = inRate; info.channels = ch
                } else if (oi >= 0) {
                    val ob = d.getOutputBuffer(oi)!!
                    ob.position(bi.offset); ob.limit(bi.offset + bi.size)
                    val mono = toMono(ob.slice().order(ByteOrder.nativeOrder()), ch, isFloat)
                    d.releaseOutputBuffer(oi, false)
                    val bytes = rs.process(mono)
                    if (bytes.isNotEmpty()) {
                        os.write(bytes)
                        outSamples += bytes.size / 2
                        // 너무 빨리 넣지 않게 (0.3초 정도만 앞서게)
                        val should = ((outSamples / samplesPerSec) - 0.3) * 1000
                        val el = (SystemClock.elapsedRealtime() - t0).toDouble()
                        if (should > el) Thread.sleep((should - el).toLong())
                        onProgress(outSamples.toDouble() / OUT_RATE, totalSec)
                    }
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            os.flush()
        } finally {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            try { os.close() } catch (_: Throwable) {}
        }
    }

    private fun toMono(b: java.nio.ByteBuffer, ch: Int, isFloat: Boolean): FloatArray {
        val c = if (ch <= 0) 1 else ch
        return if (isFloat) {
            val fb = b.asFloatBuffer()
            val frames = fb.remaining() / c
            FloatArray(frames) { i -> var s = 0f; for (k in 0 until c) s += fb.get(i * c + k); s / c }
        } else {
            val sb = b.asShortBuffer()
            val frames = sb.remaining() / c
            FloatArray(frames) { i -> var s = 0f; for (k in 0 until c) s += sb.get(i * c + k) / 32768f; s / c }
        }
    }

    /** 단순 선형 보간으로 16kHz로 바꿈 (말소리 확인용으로 충분) */
    class Resampler(var inRate: Int) {
        private var pos = 0.0
        private var last = 0f
        fun process(x: FloatArray): ByteArray {
            if (x.isEmpty()) return ByteArray(0)
            val step = inRate.toDouble() / OUT_RATE
            val cap = (x.size / step).toInt() + 4
            val out = ByteArray(cap * 2)
            var k = 0
            while (pos < x.size - 1 && k < cap) {
                val i0 = floor(pos).toInt()
                val fr = (pos - i0).toFloat()
                val a = if (i0 < 0) last else x[i0]
                val b = x[i0 + 1]
                var v = a + (b - a) * fr
                if (v > 1f) v = 1f
                if (v < -1f) v = -1f
                val s = (v * 32767).toInt()
                out[k * 2] = (s and 0xff).toByte()
                out[k * 2 + 1] = ((s shr 8) and 0xff).toByte()
                k++
                pos += step
            }
            pos -= x.size
            last = x[x.size - 1]
            return out.copyOf(k * 2)
        }
    }
}
