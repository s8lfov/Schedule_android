package com.iv234.schedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * Модель безопасности:
 *  - внутри WebView работает только страница, зашитая в APK (file:///android_asset);
 *  - WebView не имеет доступа к сети и блокирует любые другие адреса;
 *  - из интернета приложение скачивает только файл schedule.json с расписанием, сама Java-часть
 *    проверяет его и пересобирает; ни HTML, ни скриптов, ни файлов с сайта не загружается;
 *  - последняя удачно проверенная копия хранится на устройстве и работает без интернета.
 */
public class MainActivity extends Activity {
    // Файл с расписанием в репозитории GitHub (его обновляют админы через админ-панель на сайте)
    private static final String DATA_HOST = "raw.githubusercontent.com";
    private static final String DATA_URL =
            "https://raw.githubusercontent.com/s8lfov/Schedule_android/main/schedule.json";
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final int MAX_BYTES = 200 * 1024;
    private static final long POLL_MS = 2 * 60 * 1000;

    // true — раз в 12 часов проверять новую версию APK на GitHub (только показывает окно, ничего не ставит)
    private static final boolean CHECK_UPDATES = true;
    private static final long CHECK_EVERY_MS = 12L * 60 * 60 * 1000;
    private static final String REPO = "s8lfov/Schedule_android";
    private static final String APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/IV234.apk";

    private WebView web;
    private int shownDay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            fetchState();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private static int today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF6D28D9);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBlockNetworkLoads(true);          // WebView физически не может ходить в сеть
        s.setAllowFileAccess(false);           // доступ к файлам телефона закрыт (assets работают)
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true; // никаких переходов по ссылкам
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/") || u.startsWith("data:")) return null;
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                String cached = getSharedPreferences("s", MODE_PRIVATE).getString("state", null);
                if (cached != null) pushState(cached); // последняя сохранённая копия (работает без интернета)
            }
        });

        setContentView(web);
        shownDay = today();
        web.loadUrl(ASSET_URL);
        if (CHECK_UPDATES) checkForUpdate();
    }

    /** Передаёт проверенный JSON странице как обычную строку-данные. */
    private void pushState(String json) {
        String lit = JSONObject.quote(json).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        web.evaluateJavascript("window.__setState&&window.__setState(" + lit + ")", null);
    }

    private void fetchState() {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(DATA_URL + "?t=" + System.currentTimeMillis());
                if (!"https".equals(u.getProtocol()) || !DATA_HOST.equals(u.getHost())) return;
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(false);   // не идём по перенаправлениям
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "IV234");
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) return;
                String ct = c.getContentType();
                if (ct == null || !(ct.startsWith("application/json") || ct.startsWith("text/plain"))) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return;     // слишком большой ответ — отбрасываем
                    bo.write(buf, 0, n);
                }
                String clean = StateValidator.sanitize(bo.toString("UTF-8"));
                if (clean == null) return;             // не прошло проверку — остаётся старая копия
                SharedPreferences sp = getSharedPreferences("s", MODE_PRIVATE);
                String old = sp.getString("state", null);
                if (old != null && !isNewerState(clean, old)) return; // устаревшая копия из кэша — игнорируем
                sp.edit().putString("state", clean).apply();
                runOnUiThread(() -> pushState(clean));
            } catch (Exception ignored) {
                // нет интернета — продолжаем работать с последней сохранённой копией
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
        web.onPause();
        web.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
        if (shownDay != today()) {
            shownDay = today();
            web.reload();
        }
        handler.post(poll); // сразу обновляем и дальше раз в 2 минуты, пока приложение открыто
    }

    private void checkForUpdate() {
        SharedPreferences p = getSharedPreferences("u", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("t", 0) < CHECK_EVERY_MS) return;

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL("https://api.github.com/repos/" + REPO + "/releases/latest");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "IV234");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                p.edit().putLong("t", now).apply();
                String tag = new JSONObject(sb.toString()).getString("tag_name");
                String current = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                if (isNewer(tag, current) && !tag.equals(p.getString("notified", ""))) {
                    p.edit().putString("notified", tag).apply();
                    runOnUiThread(() -> showDialog(tag));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private static boolean isNewerState(String a, String b) {
        try {
            JSONObject x = new JSONObject(a), y = new JSONObject(b);
            String ax = x.optString("at", ""), ay = y.optString("at", "");
            if (!ax.isEmpty() && !ay.isEmpty()) return ax.compareTo(ay) > 0;
            return x.optInt("rev", 0) > y.optInt("rev", 0);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isNewer(String latest, String current) {
        String[] a = latest.replaceAll("[^0-9.]", "").split("\\.");
        String[] b = current.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private void showDialog(String tag) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Доступно обновление")
                .setMessage("Вышла новая версия " + tag + ". Скачать?")
                .setPositiveButton("Скачать", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))))
                .setNegativeButton("Позже", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()",
                v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
