package com.backuper.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream
import java.security.MessageDigest

data class MediaFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val hash: String,
    val mimeType: String
)

object MediaScanner {

    fun scanMedia(context: Context): List<MediaFile> {
        val mediaList = mutableListOf<MediaFile>()
        
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATA
        )

        // Target extensions from Jack's request
        val selection = (
            "(" + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'image/%' OR " +
            MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'video/%' OR " +
            MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.raw' OR " +
            MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.tiff' OR " +
            MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.bmp' OR " +
            MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.heic')"
        )

        val queryUri = MediaStore.Files.getContentUri("external")

        context.contentResolver.query(queryUri, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "unknown"
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                val path = cursor.getString(dataColumn)

                // Skip temp/cache files
                if (path?.contains("cache", ignoreCase = true) == true || 
                    path?.contains("tmp", ignoreCase = true) == true) continue

                val contentUri = ContentUris.withAppendedId(queryUri, id)
                
                // Calculate hash
                val hash = calculateHash(context, contentUri) ?: continue

                mediaList.add(MediaFile(contentUri, name, size, hash, mimeType))
            }
        }
        return mediaList
    }

    private fun calculateHash(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
