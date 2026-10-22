package com.iv234.schedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

public class MainActivity extends Activity {
    // true  — раз в 12 часов спрашивать у GitHub, нет ли новой версии (единственный сетевой запрос)
    // false — приложение вообще не обращается к сети
    private static final boolean CHECK_UPDATES = true;
    private static final long CHECK_EVERY_MS = 12L * 60 * 60 * 1000;

    private static final String REPO = "s8lfov/Schedule_android";
    private static final String APK_URL =
            "https://github.com/" + REPO + "/releases/latest/download/IV234.apk";

    private WebView web;
    private int shownDay;

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
        s.setJavaScriptEnabled(true);          // нужен для расчёта недели
        s.setDomStorageEnabled(true);          // хранение правок из админ-панели на устройстве
        s.setBlockNetworkLoads(true);          // WebView физически не может ходить в сеть
        s.setAllowFileAccess(false);           // доступ к файлам телефона закрыт (assets работают)
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

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
        });

        setContentView(web);
        shownDay = today();
        web.loadUrl("file:///android_asset/index.html");
        if (CHECK_UPDATES) checkForUpdate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        web.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
        if (shownDay != today()) { // наступил новый день — пересчитать неделю
            shownDay = today();
            web.reload();
        }
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
                // свой короткий User-Agent, чтобы не светить модель телефона и версию Android
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
                // показываем окно один раз для каждой новой версии
                if (isNewer(tag, current) && !tag.equals(p.getString("notified", ""))) {
                    p.edit().putString("notified", tag).apply();
                    runOnUiThread(() -> showDialog(tag));
                }
            } catch (Exception ignored) {
                // нет интернета или лимит GitHub — молча пропускаем
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
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
        // сначала закрываем открытое окно (звонки/админ-панель), иначе выходим
        web.evaluateJavascript(
                "(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()",
                v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
