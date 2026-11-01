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
    static final String DATA_URL =
            "https://raw.githubusercontent.com/s8lfov/Schedule_android/main/schedule.json";
    private static final int MAX_BYTES = 200 * 1024;
    private static final String CHANNEL = "notices";

    private StateSync() {}

    /** Скачивает и проверяет расписание. Возвращает проверенный JSON, если он новее сохранённого, иначе null. */
    static String fetchAndStore(Context ctx) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(DATA_URL + "?t=" + System.currentTimeMillis());
            if (!"https".equals(u.getProtocol()) || !DATA_HOST.equals(u.getHost())) return null;
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);   // не идём по перенаправлениям
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
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
            String clean = StateValidator.sanitize(bo.toString("UTF-8"));
            if (clean == null) return null;           // не прошло проверку — остаётся старая копия
            SharedPreferences sp = ctx.getSharedPreferences("s", Context.MODE_PRIVATE);
            String old = sp.getString("state", null);
            if (old != null && !isNewerState(clean, old)) return null; // устаревшая копия из кэша
            sp.edit().putString("state", clean).apply();
            return clean;
        } catch (Exception e) {
            return null;                               // нет интернета и т.п. — работаем с сохранённой копией
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Новее только по номеру версии: время на разных устройствах может расходиться. */
    static boolean isNewerState(String a, String b) {
        try {
            return new JSONObject(a).optInt("rev", 0) > new JSONObject(b).optInt("rev", 0);
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
