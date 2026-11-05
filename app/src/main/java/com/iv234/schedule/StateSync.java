package com.iv234.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Загрузка расписания (общая для приложения и фоновой проверки) и системные уведомления.
 * Из сети берётся только файл schedule.json, он проходит строгую проверку (StateValidator).
 */
final class StateSync {
    static final String DATA_HOST = "raw.githubusercontent.com";
    // Расписание лежит в отдельной ветке data, чтобы выпуск новых версий кода (ветка main)
    // не мог его стереть. Ветка main — запасной вариант для старых данных.
    static final String DATA_URL =
            "https://raw.githubusercontent.com/s8lfov/Schedule_android/data/schedule.json";
    static final String LEGACY_URL =
            "https://raw.githubusercontent.com/s8lfov/Schedule_android/main/schedule.json";
    private static final int MAX_BYTES = 200 * 1024;
    private static final String CHANNEL = "notices";

    private StateSync() {}

    /** Скачивает и проверяет расписание. Возвращает проверенный JSON, если он новее сохранённого, иначе null. */
    static String fetchAndStore(Context ctx) {
        String raw = download(DATA_URL);
        if (raw == null) raw = download(LEGACY_URL); // в ветке data файла ещё нет
        if (raw == null) return null;                // нет сети / файла нигде нет — остаётся сохранённая копия
        String clean = StateValidator.sanitize(raw);
        if (clean == null) return null;              // не прошло проверку — остаётся старая копия
        SharedPreferences sp = ctx.getSharedPreferences("s", Context.MODE_PRIVATE);
        String old = sp.getString("state", null);
        if (old != null && !isNewerState(clean, old)) return null; // устаревшая копия из кэша
        sp.edit().putString("state", clean).apply();
        return clean;
    }

    /** Загрузка текста по адресу (только raw.githubusercontent.com по HTTPS). null — не удалось. */
    private static String download(String url) {
        HttpURLConnection c = null;
        try {
            // Динамический параметр времени для обхода кэша CDN GitHub
            URL u = new URL(url + "?t=" + System.currentTimeMillis());
            if (!"https".equals(u.getProtocol()) || !DATA_HOST.equals(u.getHost())) return null;
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("Expires", "0");
            c.setRequestProperty("User-Agent", "IV234");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType();
            if (ct == null || !(ct.startsWith("application/json") || ct.startsWith("text/plain"))) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return null;   // слишком большой ответ — отбрасываем
                bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Сохранённая копия расписания; если она записана старой версией приложения — приводится к новой схеме. */
    static String cached(Context ctx) {
        String c = ctx.getSharedPreferences("s", Context.MODE_PRIVATE).getString("state", null);
        if (c == null) return null;
        String clean = StateValidator.sanitize(c);
        return clean != null ? clean : c;
    }

    /**
     * Снимок schedule.json, зашитый в APK при сборке (assets/schedule.json, кладётся сборкой на GitHub).
     * Нужен, чтобы только что установленное приложение сразу показывало актуальное расписание,
     * в том числе без интернета. Применяется, только если новее сохранённой копии; всё равно
     * проходит ту же проверку, что и данные из сети.
     */
    static void seedFromAssets(Context ctx) {
        try (InputStream in = ctx.getAssets().open("schedule.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return;
                bo.write(buf, 0, n);
            }
            String clean = StateValidator.sanitize(bo.toString("UTF-8"));
            if (clean == null) return;
            SharedPreferences sp = ctx.getSharedPreferences("s", Context.MODE_PRIVATE);
            String old = sp.getString("state", null);
            if (old != null && !isNewerState(clean, old)) return;
            sp.edit().putString("state", clean).apply();
            // уведомление из снимка считается уже виденным (не показываем его как новое)
            notifyIfNew(ctx, clean, false);
        } catch (Exception ignored) {
            // снимка в APK нет или он повреждён — расписание придёт из сети
        }
    }

    /**
     * Новее по номеру версии (rev): устаревшие копии из кэша GitHub не должны откатывать расписание.
     * Исключение: если файл schedule.json на GitHub был удалён и создан заново, rev начинается с
     * малых значений. Тогда состояние считается новее, если оно записано позже (поле "at", UTC).
     * Без этого приложение навсегда «застревало» бы на старой копии с большим rev.
     */
    static boolean isNewerState(String a, String b) {
        try {
            JSONObject x = new JSONObject(a), y = new JSONObject(b);
            if (x.optInt("rev", 0) > y.optInt("rev", 0)) return true;
            String ax = x.optString("at", ""), ay = y.optString("at", "");
            return !ay.isEmpty() && ax.compareTo(ay) > 0;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Запоминает увиденное уведомление админа и, если show == true (приложение закрыто),
     * показывает системное уведомление. При первом запуске старое уведомление не показывается.
     */
    static void notifyIfNew(Context ctx, String stateJson, boolean show) {
        try {
            JSONObject n = new JSONObject(stateJson).optJSONObject("notice");
            if (n == null) return;
            String id = n.optString("id", "");
            if (id.isEmpty()) return;
            SharedPreferences sp = ctx.getSharedPreferences("s", Context.MODE_PRIVATE);
            String seen = sp.getString("seen_notice", null);
            if (id.equals(seen)) return;
            sp.edit().putString("seen_notice", id).apply();
            if (seen == null || !show) return;

            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Изменения расписания", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Расписание ИВ 234")
                    .setContentText(n.optString("t", "Изменено расписание"))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(1001, b.build());
        } catch (Exception ignored) {
            // уведомление не критично
        }
    }
}