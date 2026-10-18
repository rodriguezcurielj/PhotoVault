package com.photovault.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.LocalItem
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.Repo
import java.util.concurrent.TimeUnit

/** Uploads new photos/videos from the phone to the server, skipping anything it already has. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Repo.prefs
        if (prefs.token == null) return Result.success()
        val shareUris = inputData.getStringArray("uris")
        if (shareUris != null) return uploadShared(shareUris.map(Uri::parse))
        if (!prefs.backupEnabled) return Result.success()

        Repo.syncRemote()
        if (Repo.online.value != true) return Result.retry()
        Repo.refreshLocal()
        val backed = Repo.backupDb.all()
        val failed = Repo.backupDb.failedIds()
        val pending = Repo.local.value.filter { item ->
            val b = backed[item.id]
            (b == null || b.size != item.size) && item.id !in failed &&
                item.bucketId !in prefs.excludedBuckets && (prefs.backupVideos || !item.isVideo)
        }.sortedByDescending { it.dateTaken }
        if (pending.isEmpty()) { Repo.backup.value = Repo.backup.value.copy(running = false, pending = 0); return Result.success() }

        runCatching { setForeground(foregroundInfo(0, pending.size, null)) }
        var done = 0
        var lastError: String? = null
        Repo.backup.value = com.photovault.app.data.BackupStatus(running = true, pending = pending.size)
        for (chunk in pending.chunked(40)) {
            if (isStopped) break
            // Ask the server which ones it already has (e.g. after reinstalling the app).
            val sums = chunk.associateWith { LocalMedia.sha1(applicationContext.contentResolver, it.uri) }
            val existing = runCatching { Repo.api.check(sums.values.filterNotNull()) }.getOrElse { return Result.retry() }
            for (item in chunk) {
                if (isStopped) break
                val sum = sums[item]
                val remoteId = sum?.let { existing[it] }
                try {
                    if (remoteId != null) {
                        val known = Repo.remote.value[remoteId]
                        if (known != null) Repo.markBacked(item, known, sum)
                        else { Repo.backupDb.mark(item, remoteId, sum) }
                    } else {
                        Repo.backup.value = Repo.backup.value.copy(current = item.name, done = done)
                        runCatching { setForeground(foregroundInfo(done, pending.size, item.name)) }
                        val a = upload(item)
                        Repo.markBacked(item, a, sum)
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    if (e is java.io.IOException && e !is com.photovault.app.data.ApiException) {
                        // Network trouble: stop and let WorkManager try again later.
                        Repo.backup.value = Repo.backup.value.copy(running = false, error = "Connection lost, will retry")
                        return Result.retry()
                    }
                    Repo.backupDb.failed(item.id, e.message ?: "error")
                }
                done++
                Repo.backup.value = Repo.backup.value.copy(done = done)
            }
        }
        Repo.backed.value = Repo.backupDb.all()
        Repo.backup.value = com.photovault.app.data.BackupStatus(running = false, pending = 0, done = done, error = lastError)
        Repo.syncRemote()
        return Result.success()
    }

    private suspend fun upload(item: LocalItem) = Repo.api.upload(
        applicationContext.contentResolver, originalUri(item.uri), item.name, item.mime, item.size,
        item.dateTaken, item.id.toString(),
    )

    /** The original file with GPS intact (requires the media-location permission). */
    private fun originalUri(uri: Uri): Uri = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)

    /** Files copied into cache/upload by ShareActivity (the share permission doesn't outlive that screen). */
    private suspend fun uploadShared(uris: List<Uri>): Result {
        val resolver = applicationContext.contentResolver
        var ok = 0
        for ((i, uri) in uris.withIndex()) {
            val file = java.io.File(uri.path ?: continue)
            if (!file.exists()) continue
            runCatching { setForeground(foregroundInfo(i, uris.size, file.name)) }
            val name = file.name.substringAfter('_')
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            runCatching { Repo.api.upload(resolver, uri, name, mime, file.length(), file.lastModified(), null) }
                .onSuccess { ok++; file.delete() }
                .onFailure { if (it is java.io.IOException && it !is com.photovault.app.data.ApiException) return Result.retry() }
        }
        Repo.syncRemote()
        notifyDone("Uploaded $ok of ${uris.size} to PhotoVault")
        return Result.success()
    }

    private fun channel(): String {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("backup", "Backup", NotificationManager.IMPORTANCE_LOW))
        return "backup"
    }

    private fun foregroundInfo(done: Int, total: Int, name: String?): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up ${done + 1} of $total")
            .setContentText(name ?: "PhotoVault")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(43, NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(text).setAutoCancel(true).build())
    }

    companion object {
        private fun constraints(watchMedia: Boolean): Constraints {
            val p = Repo.prefs
            return Constraints.Builder()
                .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(p.chargingOnly)
                .apply {
                    if (watchMedia) {
                        addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                    }
                }
                .build()
        }

        /** Periodic backup plus an instant one whenever a new photo/video appears. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!Repo.prefs.backupEnabled || Repo.prefs.token == null) {
                wm.cancelUniqueWork("backup-periodic"); wm.cancelUniqueWork("backup-watch"); return
            }
            wm.enqueueUniquePeriodicWork("backup-periodic", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(false)).build())
            watchForNewMedia(context, ExistingWorkPolicy.KEEP)
        }

        /** From inside WatchWorker use APPEND_OR_REPLACE so the next watch starts after this one ends. */
        fun watchForNewMedia(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork("backup-watch", policy,
                OneTimeWorkRequestBuilder<WatchWorker>().setConstraints(constraints(true)).build())
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("backup-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(false))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }

        fun uploadShared(context: Context, uris: List<Uri>) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setInputData(workDataOf("uris" to uris.map { it.toString() }.toTypedArray()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }
}

/** Fires when MediaStore changes: starts a backup, then waits for the next change. */
class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        BackupWorker.runNow(applicationContext)
        BackupWorker.watchForNewMedia(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
