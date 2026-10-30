package kr.daedeok.calls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰을 다시 켜거나 앱을 업데이트한 뒤에도 통화 감시를 다시 등록 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Calls.canRead(context)) Calls.watch(context);
        try {
            Auto.ensureSince(context); // 1.9 → 2.0 업데이트: 이제부터 오는 전화만 정리
            if (Auto.waitingCount(context) > 0 || Auto.textCount(context) > 0) Auto.schedule(context, Auto.JOB_RETRY, 30000);
        } catch (Throwable ignored) { }
    }
}
