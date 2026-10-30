package kr.daedeok.calls;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 음성 녹음 → ⋮ → 공유 → 텍스트 파일 → "대덕 전화분류" 를 고르면 그 글자와 파일 이름을 정산유틸로 넘김.
 * 글자(EXTRA_TEXT), 파일 1개(SEND + EXTRA_STREAM), 여러 개 보내기(SEND_MULTIPLE) 모두 받음.
 * 인터넷 권한이 없는 앱: 받은 글자는 폰 안의 정산유틸로만 넘어감.
 */
public class ShareActivity extends Activity {
    private static final int MAX_BYTES = 200 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String text = null, name = null;
        String type = in != null && in.getType() != null ? in.getType() : "";
        if (in != null) {
            // 1) 받은 파일 목록 모으기 (SEND: 1개, SEND_MULTIPLE: 여러 개, ClipData)
            List<Uri> uris = new ArrayList<>();
            try { Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); } catch (Exception ignored) { }
            try { ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) for (Uri u : l) if (u != null && !uris.contains(u)) uris.add(u); } catch (Exception ignored) { }
            ClipData cd = in.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) {
                ClipData.Item it = cd.getItemAt(i);
                if (it.getUri() != null && !uris.contains(it.getUri())) uris.add(it.getUri());
                else if (it.getUri() == null && it.getText() != null && isEmpty(text)) text = it.getText().toString();
            }
            // 2) 그중 글자 파일(.txt / text/*)을 먼저 읽음
            for (Uri u : uris) {
                String n = displayName(u), mt = mime(u);
                boolean isText = (n != null && n.toLowerCase().endsWith(".txt")) || mt.startsWith("text/");
                if (!isText) continue;
                String t = readText(u);
                if (!isEmpty(t)) { text = t; name = n; break; }
            }
            // 3) 글자로 직접 보낸 경우
            if (isEmpty(text)) { CharSequence cs = in.getCharSequenceExtra(Intent.EXTRA_TEXT); if (cs != null) text = cs.toString(); }
            if (isEmpty(name)) { CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TITLE); if (t == null) t = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT); if (t != null) name = t.toString(); }
            if (isEmpty(name) && !uris.isEmpty()) name = displayName(uris.get(0));
            // 4) 소리·사진만 왔으면 안내
            if (isEmpty(text) && !uris.isEmpty()) {
                String mt = mime(uris.get(0));
                if (mt.startsWith("audio/") || mt.startsWith("video/") || mt.startsWith("image/") || type.startsWith("audio/")) {
                    toast("녹음 파일(소리)은 읽을 수 없어요. ⋮ → 공유 → \"텍스트 파일\"로 보내 주세요.");
                    finish();
                    return;
                }
            }
        }
        if (isEmpty(text)) {
            // 원인 확인용: 어떤 방식·형식으로 왔는지 함께 보여 줌
            String how = in == null ? "" : (" [" + (in.getAction() == null ? "" : in.getAction().replace("android.intent.action.", "")) + " " + type + "]");
            toast("공유된 글자가 없습니다. 음성 녹음 → ⋮ → 공유 → 텍스트 파일로 보내 주세요." + how);
            finish();
            return;
        }
        if (name != null && name.length() > 120) name = name.substring(0, 120);
        toast(name != null && name.length() > 0 ? "정산유틸로 보냅니다: " + name : "통화 내용을 정산유틸로 보냅니다");
        try { Calls.openApp(this, text, name); }
        catch (Exception e) { toast("정산유틸 앱을 열지 못했습니다."); }
        finish();
    }

    private void toast(String s) { Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show(); }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private String mime(Uri u) {
        try { String t = getContentResolver().getType(u); return t == null ? "" : t; } catch (Exception e) { return ""; }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignored) { }
        return u.getLastPathSegment();
    }

    private String readText(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            if (is == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0 && bo.size() < MAX_BYTES) bo.write(buf, 0, n);
            byte[] b = bo.toByteArray();
            if (bo.size() >= MAX_BYTES) { int cut = b.length; while (cut > 0 && b[cut - 1] != '\n') cut--; if (cut > 0) b = java.util.Arrays.copyOf(b, cut); } // 잘린 글자 방지
            return decode(b);
        } catch (Exception e) { return null; }
    }

    /**
     * 1.8: 글자 방식 자동 판단. 삼성 음성 녹음 텍스트 파일은 UTF-8이 아닌 옛 한글 방식(CP949/EUC-KR)이나 UTF-16일 수 있음
     * (UTF-8로만 읽으면 한글이 "��" 로 깨짐)
     */
    static String decode(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        if (n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        if (n >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        int z0 = 0, z1 = 0;
        for (int i = 0; i < Math.min(n, 400); i++) if (b[i] == 0) { if ((i & 1) == 1) z1++; else z0++; }
        if (z1 > 20 && z1 > z0 * 4) return new String(b, StandardCharsets.UTF_16LE);
        if (z0 > 20 && z0 > z1 * 4) return new String(b, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException ignored) { }
        for (String cs : new String[]{"windows-949", "MS949", "x-windows-949", "EUC-KR"}) {
            try { if (Charset.isSupported(cs)) return new String(b, Charset.forName(cs)); } catch (Exception ignored) { }
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
