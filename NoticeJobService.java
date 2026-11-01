package com.iv234.schedule;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Редкая фоновая проверка (раз в ~30 минут): есть ли новое уведомление админа. */
public class NoticeJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                String clean = StateSync.fetchAndStore(ctx);
                if (clean != null) StateSync.notifyIfNew(ctx, clean, true);
            } catch (Exception ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true; // работа продолжается в потоке
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
