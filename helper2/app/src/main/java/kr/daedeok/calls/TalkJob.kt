package kr.daedeok.calls

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 도우미 2.0: 통화가 끝난 뒤 뒤에서 녹음 찾기 → 글자 → 보내기. 막히면 Auto.escalate (정리 창 / 알림) */
class TalkJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        scope.launch {
            try {
                if (params.jobId == Auto.JOB_NET) Auto.log(ctx, "인터넷 연결됨 → 남은 통화 내용 보내기")
                Auto.runBackground(ctx)
            } catch (t: Throwable) {
                Auto.log(ctx, "뒤 작업 오류: $t")
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Auto.log(applicationContext, "시스템이 뒤 작업을 멈춤 (id ${params.jobId}) → 다시 예약")
        return true
    }
}
