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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Calendar;

public class MainActivity extends Activity {
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final boolean ADMIN_IN_APP = true;
    private static final boolean BACKGROUND_NOTICES = true;
    private static final long POLL_MS = 30 * 1000;

    private static final boolean CHECK_UPDATES = true;
    private static final long CHECK_EVERY_MS = 12L * 60 * 60 * 1000;
    private static final String REPO = "s8lfov/Schedule_android";
    private static final String APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/IV234.apk";

    private WebView web;
    private SwipeRefreshLayout swipeRefresh;
    private int shownDay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            fetchState(null);
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
        
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF6D28D9);

        web = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(0xFF6D28D9); // Фиолетовый цвет индикатора загрузки

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBlockNetworkLoads(!ADMIN_IN_APP);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && "raw.githubusercontent.com".equals(u.getHost())
                        && u.getPath() != null
                        && (u.getPath().startsWith("/s8lfov/Schedule_android/data/files/")
                            || u.getPath().startsWith("/s8lfov/Schedule_android/main/files/"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/") || u.startsWith("data:")) return null;
                
                // Разрешаем запросы к GitHub API (для админки) и к raw-файлу расписания
                if ("https".equals(r.getUrl().getScheme())) {
                    String host = r.getUrl().getHost();
                    if ("api.github.com".equals(host) || "raw.githubusercontent.com".equals(host)) {
                        return null; 
                    }
                }
                
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                String cached = StateSync.cached(getApplicationContext());
                if (cached != null) pushState(cached);
                if (ADMIN_IN_APP) web.evaluateJavascript("window.__enableAdmin&&window.__enableAdmin()", null);
            }
        });

        // БЛОКИРОВКА СВАЙПА: Разрешаем свайп только при прокрутке в самый верх (scrollY == 0)
        web.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefresh.setEnabled(scrollY == 0);
        });

        // СОБЫТИЕ СВАЙПА: Принудительное скачивание schedule.json с сервера
        swipeRefresh.setOnRefreshListener(() -> fetchState(() -> {
            runOnUiThread(() -> swipeRefresh.setRefreshing(false));
        }));

        shownDay = today();
        StateSync.seedFromAssets(getApplicationContext()); // расписание на момент сборки APK (если новее сохранённого)
        web.loadUrl(ASSET_URL);
        if (CHECK_UPDATES) checkForUpdate();
        setupNotices();
    }

    private void pushState(String json) {
        if (isFinishing() || isDestroyed()) return;
        String lit = JSONObject.quote(json).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        web.evaluateJavascript("window.__setState&&window.__setState(" + lit + ")", null);
    }

    private void fetchState(Runnable onComplete) {
        new Thread(() -> {
            Context app = getApplicationContext();
            String fresh = StateSync.fetchAndStore(app);
            if (fresh != null) StateSync.notifyIfNew(app, fresh, false);
            final String show = fresh != null ? fresh : StateSync.cached(app);
            if (show != null) runOnUiThread(() -> pushState(show));
            if (onComplete != null) onComplete.run();
        }).start();
    }

    private void setupNotices() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Throwable ignored) {}
        try {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!BACKGROUND_NOTICES) {
                js.cancel(1);
                return;
            }
            if (js.getPendingJob(1) != null) return;
            ComponentName cn = new ComponentName(this, NoticeJobService.class);
            js.schedule(new JobInfo.Builder(1, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60 * 1000)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {}
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
        handler.post(poll);
    }

    private void checkForUpdate() {
        SharedPreferences p = getSharedPreferences("u", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("t", 0) < CHECK_EVERY_MS) return;

        new Thread(() -> {
            // Логика проверки обновлений
        }).start();
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