package com.iv234.schedule;

import android.Manifest;
import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
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
 *  - WebView блокирует любые адреса, кроме api.github.com (и только для админ-панели; отключается ADMIN_IN_APP);
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
    // true — админ-панель работает и в приложении (WebView получает доступ только к api.github.com).
    // false — приложение полностью отрезано от сети (админ правит расписание на сайте).
    private static final boolean ADMIN_IN_APP = true;
    // true — раз в ~30 минут в фоне проверять, не отправил ли админ уведомление об изменении расписания
    private static final boolean BACKGROUND_NOTICES = true;
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
        s.setBlockNetworkLoads(!ADMIN_IN_APP); // без админ-панели WebView физически не может ходить в сеть
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
                // единственное исключение: вложения (фото/PDF) из нашей папки files/ открываются во внешнем браузере
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && "raw.githubusercontent.com".equals(u.getHost())
                        && u.getPath() != null
                        && u.getPath().startsWith("/s8lfov/Schedule_android/main/files/")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                }
                return true; // остальные переходы по ссылкам заблокированы
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/") || u.startsWith("data:")) return null;
                if (ADMIN_IN_APP && "https".equals(r.getUrl().getScheme())
                        && "api.github.com".equals(r.getUrl().getHost())) return null; // только для админ-панели
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                String cached = getSharedPreferences("s", MODE_PRIVATE).getString("state", null);
                if (cached != null) pushState(cached); // последняя сохранённая копия (работает без интернета)
                if (ADMIN_IN_APP) web.evaluateJavascript("window.__enableAdmin&&window.__enableAdmin()", null);
            }
        });

        setContentView(web);
        shownDay = today();
        web.loadUrl(ASSET_URL);
        if (CHECK_UPDATES) checkForUpdate();
        setupNotices();
    }

    /** Передаёт проверенный JSON странице как обычную строку-данные. */
    private void pushState(String json) {
        if (isFinishing() || isDestroyed()) return;
        String lit = JSONObject.quote(json).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        web.evaluateJavascript("window.__setState&&window.__setState(" + lit + ")", null);
    }

    private void fetchState() {
        new Thread(() -> {
            Context app = getApplicationContext();
            String clean = StateSync.fetchAndStore(app);
            if (clean == null) return; // нет интернета или данные не новее — остаётся сохранённая копия
            StateSync.notifyIfNew(app, clean, false); // приложение открыто: уведомление покажет сама страница
            runOnUiThread(() -> pushState(clean));
        }).start();
    }

    /**
     * Разрешение на уведомления (Android 13+) и редкая фоновая проверка.
     * Любой сбой здесь не должен ронять приложение: тогда оно просто работает без уведомлений.
     */
    private void setupNotices() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Throwable ignored) {
        }
        try {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!BACKGROUND_NOTICES) {
                js.cancel(1);
                return;
            }
            if (js.getPendingJob(1) != null) return;
            ComponentName cn = new ComponentName(this, NoticeJobService.class);
            try {
                js.schedule(new JobInfo.Builder(1, cn)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(30L * 60 * 1000)
                        .setPersisted(true)
                        .build());
            } catch (Throwable e) { // нет права на запуск после перезагрузки — планируем без него
                js.schedule(new JobInfo.Builder(1, cn)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(30L * 60 * 1000)
                        .build());
            }
        } catch (Throwable ignored) {
            // фоновая проверка недоступна (например, служба не объявлена в манифесте)
        }
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
