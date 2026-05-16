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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getDatabase(context)
    private val goFileService = RetrofitClient.instance
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var foundCount = 0
    private var uploadedCount = 0

    companion object {
        const val CHANNEL_ID = "backup_channel_v3"
        const val NOTIFICATION_ID = 103
        const val KEY_FOUND_COUNT = "found_count"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_CURRENT_FILE = "current_file"
    }

    override suspend fun doWork(): Result {
        return try {
            val token = EncryptionManager.getToken(applicationContext) ?: return Result.failure()
            
            createNotificationChannel()
            setForeground(createForegroundInfo("Starting streaming scan..."))

            // 1. Get the best server
            val serverResponse = try {
                goFileService.getServer()
            } catch (e: Exception) {
                return Result.retry()
            }

            if (!serverResponse.isSuccessful || serverResponse.body()?.status != "ok") {
                return Result.retry()
            }
            val server = serverResponse.body()?.data?.server ?: return Result.retry()
            val uploadUrl = "https://$server.gofile.io/uploadFile"

            // 2. Start streaming scan and upload
            MediaScanner.scanStreaming { media ->
                if (isStopped) return@scanStreaming
                
                foundCount++
                
                // Use a runBlocking-like approach or just handle it sequentially since scanStreaming is blocking
                // But in a CoroutineWorker, we should be careful. 
                // Since scanStreaming is synchronous, this is fine.
                
                kotlinx.coroutines.runBlocking {
                    if (!db.fileDao().isUploaded(media.hash)) {
                        updateNotification(foundCount, uploadedCount, media.name)
                        setProgress(workDataOf(
                            KEY_FOUND_COUNT to foundCount,
                            KEY_UPLOADED_COUNT to uploadedCount,
                            KEY_CURRENT_FILE to media.name
                        ))

                        if (uploadToGoFile(uploadUrl, token, media)) {
                            db.fileDao().insert(UploadedFile(media.hash, media.name))
                            uploadedCount++
                        }
                    }
                }
            }

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
