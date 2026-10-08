package com.uberbro.oberih.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uberbro.oberih.R
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.util.UpdateChecker
import java.util.concurrent.TimeUnit

/** Раз на добу: свіжі дані про злочинність + перевірка нової версії програми. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val grid = CrimeRepository.load(ctx)
        val err = CrimeRepository.refreshIfStale(ctx)
        val update = runCatching { UpdateChecker.check(ctx, force = true) }.getOrNull()
        if (update != null) notifyUpdate(ctx, update.version, update.pageUrl)
        return if (err != null && grid == null) Result.retry() else Result.success()
    }

    private fun notifyUpdate(ctx: Context, version: String, url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Оновлення програми", NotificationManager.IMPORTANCE_DEFAULT))
        // Відкриває Оберіг — там одразу з'явиться віконце «Оновити».
        val open = Intent(ctx, com.uberbro.oberih.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "updates")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Оберіг: є нова версія $version")
            .setContentText("Натисни — і далі «Оновити». Все встановиться саме.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(42, n) }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
