package kr.daedeok.calls;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 대덕 전화분류 도우미
 * 1) 이 폰의 통화 기록에서 "마지막으로 가져간 뒤" 걸려 온 전화(받은 전화·부재중·거절)를 읽어
 * 2) 정산유틸 주소 #calls=... 에 실어 앱을 연다 (# 뒤는 서버로 보내지지 않음)
 * 3) 정산유틸이 로그인 후 "걸려 온 전화" 분류 창을 띄운다
 * 통화가 끝나면 "분류할 전화 N통" 알림(아이콘 숫자)을 띄운다. 전화 걸기·녹음·문자는 하지 않는다.
 * 2.0: 받은 전화가 끝나면 갤럭시 자동 녹음을 폰 안에서 글자로 바꿔 '글자만' 정산유틸 서버로 보낸다 (Auto.kt)
 *
 * 정산유틸이 넘기는 값 (intent://read#Intent;scheme=ddkcalls;…)
 *  S.block = 차단 번호 목록 (숫자만, 점으로 구분) · S.sync=1 이면 목록만 저장하고 바로 닫힘(앱을 다시 열지 않음)
 *  S.url = 정산유틸 주소 · S.srv / S.htok = (2.0) 서버 주소·도우미 열쇠
 */
public class MainActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String block = in != null ? in.getStringExtra("block") : null;
        if (block != null) Calls.prefs(this).edit().putString("block", block).apply();
        rememberHost(in);
        try { Auto.ensureSince(this); } catch (Throwable ignored) { }
        if (in != null && in.getData() != null && "status".equals(in.getData().getHost())) {
            // ddkcalls://status → 통화 내용 자동 정리 상태 화면
            startActivity(new Intent(this, TalkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (in != null && "1".equals(in.getStringExtra("sync"))) {
            // 정산유틸에서 차단·해제했을 때: 목록만 맞추고 알림 숫자 다시 계산 후 닫기
            try { Calls.prefs(this).edit().putInt("notified", -1).apply(); Calls.notifyNew(this, true); } catch (Exception ignored) { }
            Toast.makeText(this, "도우미 차단 목록을 맞췄습니다", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        List<String> need = new ArrayList<>();
        if (!Calls.canRead(this)) need.add(Manifest.permission.READ_CALL_LOG);
        // 2.0: 통화 내용 자동 정리용 권한을 처음 한 번 물음 (음악·오디오 = 통화 녹음 찾기, 마이크 = 음성인식 규칙상 필요·녹음은 안 함)
        if (!Calls.prefs(this).getBoolean("askedAuto", false)) {
            Calls.prefs(this).edit().putBoolean("askedAuto", true).apply();
            if (!Auto.hasAudioPerm(this)) need.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (!Auto.hasMic(this)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !Calls.prefs(this).getBoolean("askedNoti", false)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            Calls.prefs(this).edit().putBoolean("askedNoti", true).apply();
        }
        if (need.isEmpty()) go();
        else requestPermissions(need.toArray(new String[0]), REQ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ) return;
        if (!Calls.canRead(this)) {
            Toast.makeText(this, "통화 기록 권한을 허용해야 걸려 온 전화를 가져올 수 있습니다. (설정 > 애플리케이션 > 대덕 전화분류 > 권한)", Toast.LENGTH_LONG).show();
        }
        go();
    }

    private void go() {
        try {
            if (Calls.canRead(this)) Calls.watch(this); // 통화 끝나면 알림 (한 번 등록하면 계속)
            if (!Calls.canRead(this)) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Calls.appUrl(this) + "#calls-denied"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String pkg = Calls.targetPackage(this, i.getData());
                if (pkg != null) i.setPackage(pkg);
                startActivity(i);
            } else if (Auto.enabled(this) && Auto.waitingCount(this) > 0 && Auto.hasAudioPerm(this)) {
                // 2.0: 아직 글자로 못 바꾼 통화가 있으면 (화면에 떠 있는 지금) 먼저 정리하고 정산유틸을 엶
                startActivity(new Intent(this, AutoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from", "open"));
            } else {
                Calls.openApp(this, null);
                askBatteryOnce();
            }
        } catch (Exception e) {
            Toast.makeText(this, "정산유틸 앱을 열지 못했습니다. 크롬에서 정산유틸을 홈 화면에 설치해 주세요.", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** 정산유틸의 "가져오기"(ddkcalls://)로 불렸으면, 부른 브라우저를 기억 → 다음부터 그 브라우저로 정산유틸을 엶 */
    private void rememberHost(Intent in) {
        try {
            if (in == null || in.getData() == null || !"ddkcalls".equals(in.getData().getScheme())) return;
            String url = in.getStringExtra("url"); // 정산유틸이 자기 주소를 알려 줌 (Netlify 주소가 바뀌어도 따라감)
            // 2.0: 정산유틸(v7.13+)이 서버 주소·도우미 열쇠를 넘겨 줌 → 통화 내용을 서버로 바로 보냄 (구글 주소만 받음)
            try { Auto.rememberServer(this, in.getStringExtra("srv"), in.getStringExtra("htok")); } catch (Throwable ignored) { }
            if (url != null && url.startsWith("https://") && url.length() < 300) Calls.prefs(this).edit().putString("url", url).apply();
            Uri ref = getReferrer();
            if (ref == null || !"android-app".equals(ref.getScheme())) return;
            String pkg = ref.getHost();
            if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return;
            Calls.prefs(this).edit().putString("host", pkg).apply();
        } catch (Exception ignored) { }
    }

    /** 갤럭시 배터리 절약 때문에 통화 후 알림이 늦거나 안 오는 것을 막기 위해, 처음 한 번만 "제한 없음"을 물음 */
    private void askBatteryOnce() {
        if (Calls.prefs(this).getBoolean("askedBattery", false)) return;
        Calls.prefs(this).edit().putBoolean("askedBattery", true).apply();
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent b = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            b.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(b);
        } catch (Exception ignored) { }
    }
}
