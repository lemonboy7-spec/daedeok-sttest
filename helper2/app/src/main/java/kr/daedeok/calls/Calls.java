package kr.daedeok.calls;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.util.Base64;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;

/** 통화 기록 읽기 · 앱 열기 · 통화 후 알림을 한곳에 모음 */
final class Calls {
    static final long FIRST_WINDOW = 2L * 24 * 60 * 60 * 1000; // 처음엔 이틀 치
    static final long OVERLAP = 10L * 60 * 1000;                 // 10분 겹쳐 읽음 (앱이 중복은 걸러냄)
    static final int MAX = 300;
    static final int JOB_ID = 41;
    static final int NOTI_ID = 42;
    static final String CHANNEL = "calls";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ddk", Context.MODE_PRIVATE); }

    /** +82 10-1234-5678 → 01012345678 (정산유틸과 같은 방식으로 번호를 맞춤) */
    static String key(String num) {
        String d = num == null ? "" : num.replaceAll("\\D", "");
        if (d.startsWith("82") && d.length() >= 10 && d.length() <= 12) d = "0" + d.substring(2);
        return d;
    }

    static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    /** last 이후 걸려 온 전화(받은·부재중·거절). 각 줄: [번호, 시각ms, 통화초, i|m|r, 연락처이름] */
    static JSONArray read(Context ctx, long since) {
        JSONArray out = new JSONArray();
        // 정산유틸에서 "차단"한 번호(가족·지인)는 빼고 셈 — 앱의 "가져오기" 버튼이 목록을 넘겨 줌
        java.util.Set<String> blocked = new java.util.HashSet<>(java.util.Arrays.asList(prefs(ctx).getString("block", "").split("\\.")));
        String[] proj = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE};
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, proj,
                CallLog.Calls.DATE + " >= ?", new String[]{String.valueOf(since)}, CallLog.Calls.DATE + " DESC")) {
            while (c != null && c.moveToNext() && out.length() < MAX) {
                int type = c.getInt(4);
                String k;
                if (type == CallLog.Calls.INCOMING_TYPE) k = "i";
                else if (type == CallLog.Calls.MISSED_TYPE) k = "m";
                else if (type == CallLog.Calls.REJECTED_TYPE) k = "r";
                else continue; // 건 전화·차단·음성사서함은 제외
                String num = c.getString(0);
                if (num == null || key(num).length() < 8) continue; // 발신번호 표시 제한 등
                if (blocked.contains(key(num))) continue;
                JSONArray row = new JSONArray();
                row.put(key(num));
                row.put(c.getLong(2));
                row.put(c.getLong(3));
                row.put(k);
                String name = c.getString(1);
                row.put(name == null ? "" : name);
                out.put(row);
            }
        }
        return out;
    }

    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** 새 전화를 읽어 정산유틸을 연다. talk 가 있으면 통화 내용(갤럭시 AI 글자)도 함께 */
    static void openApp(Context ctx, String talk) { openApp(ctx, talk, null); }

    /** name: 공유된 파일 이름 → 정산유틸이 파일 이름의 이름·번호·시각으로 그 전화 카드에 바로 붙임 */
    static void openApp(Context ctx, String talk, String name) {
        long now = System.currentTimeMillis();
        String rows = "[]"; // 읽지 못해도 빈 목록을 보냄 (통화 내용이 함께 사라지지 않게)
        boolean read = false;
        if (canRead(ctx)) {
            long last = prefs(ctx).getLong("last", now - FIRST_WINDOW);
            try { rows = read(ctx, Math.max(0, last - OVERLAP)).toString(); read = true; } catch (Exception ignored) { }
        }
        StringBuilder hash = new StringBuilder("#calls=").append(b64(rows));
        if (talk != null && talk.trim().length() > 0) {
            String t = talk.length() > 15000 ? talk.substring(0, 15000) : talk; // 1.7: 긴 통화(10분 이상)도 잘리지 않게 15000자까지
            hash.append("&talk=").append(b64(t));
            if (name != null && name.trim().length() > 0) hash.append("&tname=").append(b64(name.trim()));
        }
        hash.append("&t=").append(now);
        Uri uri = Uri.parse(appUrl(ctx) + hash);
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = targetPackage(ctx, uri);
        if (pkg != null) i.setPackage(pkg);
        try { ctx.startActivity(i); }
        catch (android.content.ActivityNotFoundException e) { i.setPackage(null); ctx.startActivity(i); } // 실패하면 예외 → "가져감" 표시 안 함
        if (read) prefs(ctx).edit().putLong("last", now).putInt("notified", 0).apply();
        clearNotice(ctx);
    }

    /** 정산유틸 주소: 정산유틸의 "가져오기"가 알려 준 주소(S.url)를 기억해 사용, 없으면 빌드할 때 넣은 주소 */
    static String appUrl(Context ctx) {
        String u = prefs(ctx).getString("url", "");
        if (u.startsWith("https://")) return u;
        return ctx.getString(R.string.app_url);
    }

    /**
     * 정산유틸을 어느 앱으로 열지:
     * 1) 정산유틸 안의 "가져오기"를 눌러 도우미를 불렀던 브라우저(삼성 인터넷·크롬 등)를 기억해 두었다가 그대로 사용
     * 2) 없으면 홈 화면에 설치한 정산유틸(크롬 WebAPK)
     * 3) 그것도 없으면 폰의 기본 브라우저
     * (크롬을 강제로 고르면, 삼성 인터넷에 설치해 쓰는 경우 예전 버전·로그인 안 된 화면이 열림)
     */
    static String targetPackage(Context ctx, Uri uri) {
        PackageManager pm = ctx.getPackageManager();
        String host = prefs(ctx).getString("host", "");
        if (host.length() > 0) {
            try { pm.getPackageInfo(host, 0); return host; } catch (Exception ignored) { }
        }
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
                String p = ri.activityInfo != null ? ri.activityInfo.packageName : "";
                if (p.startsWith("org.chromium.webapk")) return p;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- 통화가 끝나면 알림 ----------
    /** 통화 기록이 바뀌면 깨어나도록 등록 (앱 업데이트·재부팅 뒤에도 다시 등록) */
    static void watch(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CallWatch.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(CallLog.Calls.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1500)
                .setTriggerContentMaxDelay(8000)
                .build();
        js.schedule(job);
    }

    /** 아직 정산유틸로 가져가지 않은 새 전화 수로 알림을 띄우거나 갱신 */
    static void notifyNew(Context ctx) { notifyNew(ctx, false); }

    /** quiet=true: 숫자만 조용히 고침 (차단 목록을 맞출 때) */
    static void notifyNew(Context ctx, boolean quiet) {
        if (!canRead(ctx)) return;
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        long now = System.currentTimeMillis();
        long last = prefs(ctx).getLong("last", now - FIRST_WINDOW);
        JSONArray rows;
        try { rows = read(ctx, last); } catch (Exception e) { return; }
        int n = rows.length();
        int before = prefs(ctx).getInt("notified", 0);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (n == 0) { nm.cancel(NOTI_ID); prefs(ctx).edit().putInt("notified", 0).apply(); return; }
        if (n == before) return; // 같은 수면 다시 울리지 않음
        boolean silent = quiet || (before > 0 && n < before);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "걸려 온 전화 분류", NotificationManager.IMPORTANCE_DEFAULT));
        String recent = "";
        try {
            JSONArray r0 = rows.getJSONArray(0);
            String nm0 = r0.optString(4, "");
            recent = nm0.length() > 0 ? nm0 : fmt(r0.optString(0, ""));
        } catch (Exception ignored) { }
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification no = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("분류할 전화 " + n + "통")
                .setContentText("최근: " + recent + " · 눌러서 정산유틸에서 분류")
                .setNumber(n)                       // 갤럭시: 앱 아이콘 위 숫자(배지)
                .setBadgeIconType(Notification.BADGE_ICON_SMALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setOnlyAlertOnce(silent)
                .build();
        nm.notify(NOTI_ID, no);
        prefs(ctx).edit().putInt("notified", n).apply();
    }

    static void clearNotice(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTI_ID);
    }

    static String fmt(String num) {
        String d = num.replaceAll("\\D", "");
        if (d.length() == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
        if (d.length() == 10) return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6);
        return num;
    }
}
