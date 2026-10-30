package kr.daedeok.calls;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** 통화 기록이 바뀔 때(통화가 끝날 때) 깨어나 알림을 갱신하고(2.0: 통화 내용 정리도 예약), 다음 변화를 위해 다시 등록 */
public class CallWatch extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { Calls.notifyNew(getApplicationContext()); } catch (Exception ignored) { }
                try { Auto.onCallLog(getApplicationContext()); } catch (Throwable ignored) { } // 2.0: 받은 전화 통화 내용 자동 정리
                try { Calls.watch(getApplicationContext()); } catch (Exception ignored) { } // 내용 감시 작업은 한 번 돌면 끝나므로 다시 등록
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
