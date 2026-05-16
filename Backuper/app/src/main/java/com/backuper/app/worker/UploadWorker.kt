package com.backuper.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
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
import java.io.File
import java.io.FileOutputStream

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getDatabase(context)
    private val driveService = RetrofitClient.instance
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val NOTIFICATION_ID = 101
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_UPLOADED_COUNT = "uploaded_count"
        const val KEY_TOTAL_COUNT = "total_count"
        
        private const val CHUNK_SIZE = 5 * 1024 * 1024 // 5MB chunks
    }

    override suspend fun doWork(): Result {
        val token = EncryptionManager.getToken(applicationContext) ?: return Result.failure()
        val authHeader = "Bearer $token"

        setForeground(createForegroundInfo(0, 0, "Initializing..."))

        // 1. Ensure Backuper folder exists
        val folderId = getOrCreateFolder(authHeader) ?: return Result.retry()

        // 2. Scan media
        val allMedia = MediaScanner.scanMedia(applicationContext)
        val filesToUpload = allMedia.filter { !db.fileDao().isUploaded(it.hash) }
        
        var uploadedCount = 0
        val totalCount = filesToUpload.size

        for (media in filesToUpload) {
            if (isStopped) return Result.success()

            updateNotification(uploadedCount, totalCount, media.name)
            setProgress(workDataOf(
                KEY_PROGRESS to (uploadedCount * 100 / totalCount),
                KEY_FILE_NAME to media.name,
                KEY_UPLOADED_COUNT to uploadedCount,
                KEY_TOTAL_COUNT to totalCount
            ))

            val success = if (media.size < CHUNK_SIZE) {
                uploadSmallFile(authHeader, folderId, media)
            } else {
                uploadLargeFile(authHeader, folderId, media)
            }

            if (success) {
                db.fileDao().insert(UploadedFile(media.hash, media.name))
                uploadedCount++
            } else {
                // If one fails, we might want to retry later or just move to next
                // For now, let's just continue and count it as a failure
            }
        }

        return Result.success()
    }

    private suspend fun getOrCreateFolder(auth: String): String? {
        val response = driveService.findFolder(auth, "name = 'Backuper' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
        if (response.isSuccessful) {
            val folders = response.body()?.files
            if (!folders.isNullOrEmpty()) return folders[0].id
        }

        val createResponse = driveService.createFolder(auth, FolderMetadata("Backuper"))
        return if (createResponse.isSuccessful) createResponse.body()?.id else null
    }

    private suspend fun uploadSmallFile(auth: String, folderId: String, media: MediaFile): Boolean {
        val file = getFileFromUri(media.uri) ?: return false
        val metadata = "{\"name\": \"${media.name}\", \"parents\": [\"$folderId\"]}"
            .toRequestBody("application/json".toMediaType())
        
        val requestFile = file.asRequestBody(media.mimeType.toMediaType())
        val body = MultipartBody.Part.createFormData("file", media.name, requestFile)

        val response = driveService.uploadMultipart(auth, metadata, body)
        file.delete()
        return response.isSuccessful
    }

    private suspend fun uploadLargeFile(auth: String, folderId: String, media: MediaFile): Boolean {
        // Resumable upload flow
        val metadata = FileMetadata(media.name, listOf(folderId))
        val initResponse = driveService.initiateResumableUpload(auth, metadata)
        
        if (!initResponse.isSuccessful) return false
        val uploadUrl = initResponse.headers()["Location"] ?: return false

        val inputStream = applicationContext.contentResolver.openInputStream(media.uri) ?: return false
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        var totalUploaded: Long = 0

        inputStream.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) return false
                
                val chunk = buffer.copyOf(bytesRead)
                val range = "bytes $totalUploaded-${totalUploaded + bytesRead - 1}/${media.size}"
                val response = driveService.uploadChunk(uploadUrl, range, chunk.toRequestBody(media.mimeType.toMediaType()))
                
                if (response.code() == 308 || response.isSuccessful) {
                    totalUploaded += bytesRead
                } else {
                    return false
                }
            }
        }
        return true
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val file = File(applicationContext.cacheDir, "temp_upload")
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun createForegroundInfo(current: Int, total: Int, fileName: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Backup Progress", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backing up media...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, current, false)
            .setContentText("$fileName ($current/$total)")
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(current: Int, total: Int, fileName: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backing up media...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, current, false)
            .setContentText("$fileName ($current/$total)")
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
