package com.backuper.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.backuper.app.api.*
import com.backuper.app.data.MediaFile
import com.backuper.app.data.MediaScanner
import com.backuper.app.db.AppDatabase
import com.backuper.app.db.UploadedFile
import com.backuper.app.security.EncryptionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getDatabase(context)
    private val goFileService = RetrofitClient.instance
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val foundCount = AtomicInteger(0)
    private val uploadedCount = AtomicInteger(0)

    companion object {
        const val CHANNEL_ID = "backup_channel_v3"
        const val NOTIFICATION_ID = 103
        const val KEY_FOUND_COUNT = "found_count"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_CURRENT_FILE = "current_file"
        
        // Concurrent uploads
        private const val MAX_CONCURRENT_UPLOADS = 4
    }

    override suspend fun doWork(): Result = coroutineScope {
        try {
            val token = EncryptionManager.getToken(applicationContext) ?: return@coroutineScope Result.failure()
            
            createNotificationChannel()
            setForeground(createForegroundInfo("Starting streaming scan..."))

            // 1. Get the best server or use fallback
            var uploadUrl = "https://upload.gofile.io/uploadfile"
            try {
                val serversResponse = goFileService.getServers()
                if (serversResponse.isSuccessful && serversResponse.body()?.status == "ok") {
                    val servers = serversResponse.body()?.data?.servers
                    if (!servers.isNullOrEmpty()) {
                        val server = servers.first().name
                        uploadUrl = "https://$server.gofile.io/uploadFile"
                    }
                }
            } catch (e: Exception) {
                Log.e("Backuper", "Failed to retrieve servers, using global fallback", e)
            }

            // Producer-consumer channel to upload discovered files concurrently
            val uploadChannel = Channel<MediaFile>(capacity = 100)

            // Live progress updater
            var lastUpdateMillis = 0L
            var lastCurrentFile = ""
            fun postProgress(currentFile: String) {
                val now = System.currentTimeMillis()
                if (now - lastUpdateMillis > 300L || currentFile != lastCurrentFile) {
                    lastUpdateMillis = now
                    lastCurrentFile = currentFile
                    val f = foundCount.get()
                    val u = uploadedCount.get()
                    updateNotification(f, u, currentFile)
                    setProgressAsync(workDataOf(
                        KEY_FOUND_COUNT to f,
                        KEY_UPLOADED_COUNT to u,
                        KEY_CURRENT_FILE to currentFile
                    ))
                }
            }

            // Launch worker consumers
            val consumers = List(MAX_CONCURRENT_UPLOADS) {
                launch(Dispatchers.IO) {
                    for (media in uploadChannel) {
                        if (isStopped) break
                        
                        try {
                            if (!db.fileDao().isUploaded(media.hash)) {
                                postProgress(media.name)
                                if (uploadToGoFile(uploadUrl, token, media)) {
                                    db.fileDao().insert(UploadedFile(media.hash, media.name))
                                    uploadedCount.incrementAndGet()
                                    postProgress(media.name)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Backuper", "Upload error for ${media.name}", e)
                        }
                    }
                }
            }

            // Producer: Scan and feed files to the channel
            withContext(Dispatchers.IO) {
                MediaScanner.scanStreaming(applicationContext) { media ->
                    if (isStopped) return@scanStreaming
                    foundCount.incrementAndGet()
                    postProgress(media.name)
                    uploadChannel.send(media)
                }
            }

            uploadChannel.close()
            consumers.forEach { it.join() }

            // Final update
            val f = foundCount.get()
            val u = uploadedCount.get()
            updateNotification(f, u, "Scan and upload complete!")
            setProgressAsync(workDataOf(
                KEY_FOUND_COUNT to f,
                KEY_UPLOADED_COUNT to u,
                KEY_CURRENT_FILE to "Done"
            ))

            Result.success()
        } catch (e: Exception) {
            Log.e("Backuper", "Worker crashed", e)
            Result.failure()
        }
    }

    private suspend fun uploadToGoFile(url: String, token: String, media: MediaFile): Boolean {
        return try {
            val requestFile = media.file.asRequestBody(media.mimeType.toMediaType())
            val body = MultipartBody.Part.createFormData("file", media.name, requestFile)
            val tokenBody = token.toRequestBody("text/plain".toMediaType())

            val response = goFileService.uploadFile(url, body, tokenBody)
            response.isSuccessful && response.body()?.status == "ok"
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Backup Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(status: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backing up to GoFile")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentText(status)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(found: Int, uploaded: Int, fileName: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Uploading: $fileName")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentText("Found: $found | Uploaded: $uploaded")
            .setSubText("GoFile Backup")
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
