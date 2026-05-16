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

    companion object {
        const val CHANNEL_ID = "backup_channel_v2"
        const val NOTIFICATION_ID = 102
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_NAME = "file_name"
    }

    override suspend fun doWork(): Result {
        return try {
            val token = EncryptionManager.getToken(applicationContext) ?: return Result.failure()
            
            // Ensure notification channel exists before setForeground
            createNotificationChannel()
            setForeground(createForegroundInfo(0, 0, "Scanning files..."))

            // 1. Get the best server
            val serverResponse = try {
                goFileService.getServer()
            } catch (e: Exception) {
                Log.e("Backuper", "Failed to get server", e)
                return Result.retry()
            }

            if (!serverResponse.isSuccessful || serverResponse.body()?.status != "ok") {
                return Result.retry()
            }
            val server = serverResponse.body()?.data?.server ?: return Result.retry()
            val uploadUrl = "https://$server.gofile.io/uploadFile"

            // 2. Scan all files
            val filesToScan = MediaScanner.scanAllFiles(applicationContext)
            val filesToUpload = filesToScan.filter { !db.fileDao().isUploaded(it.hash) }
            
            var uploadedCount = 0
            val totalCount = filesToUpload.size

            for (media in filesToUpload) {
                if (isStopped) break

                updateNotification(uploadedCount, totalCount, media.name)
                setProgress(workDataOf(
                    KEY_PROGRESS to (if (totalCount > 0) uploadedCount * 100 / totalCount else 0),
                    KEY_FILE_NAME to media.name
                ))

                if (uploadToGoFile(uploadUrl, token, media)) {
                    db.fileDao().insert(UploadedFile(media.hash, media.name))
                    uploadedCount++
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

    private fun createForegroundInfo(current: Int, total: Int, fileName: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backuper is running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, current, false)
            .setContentText(fileName)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(current: Int, total: Int, fileName: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backing up to GoFile...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, current, false)
            .setContentText("$fileName ($current/$total)")
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
