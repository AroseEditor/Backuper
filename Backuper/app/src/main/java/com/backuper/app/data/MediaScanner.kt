package com.backuper.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class MediaFile(
    val uri: Uri,
    val file: File,
    val name: String,
    val size: Long,
    val hash: String,
    val mimeType: String
)

object MediaScanner {

    private val TARGET_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "tiff", "raw"
    )

    fun scanAllFiles(context: Context): List<MediaFile> {
        val mediaList = mutableListOf<MediaFile>()
        val root = Environment.getExternalStorageDirectory()
        
        scanDirectory(root, mediaList)
        
        return mediaList
    }

    private fun scanDirectory(dir: File, list: MutableList<MediaFile>) {
        val files = dir.listFiles() ?: return
        
        for (file in files) {
            if (file.isDirectory) {
                // Skip some known junk folders
                if (file.name.startsWith(".") || 
                    file.name.equals("Android", ignoreCase = true)) continue
                
                scanDirectory(file, list)
            } else {
                val ext = file.extension.lowercase()
                if (TARGET_EXTENSIONS.contains(ext)) {
                    val hash = calculateHash(file) ?: continue
                    list.add(
                        MediaFile(
                            uri = Uri.fromFile(file),
                            file = file,
                            name = file.name,
                            size = file.length(),
                            hash = hash,
                            mimeType = getMimeType(ext)
                        )
                    )
                }
            }
        }
    }

    private fun calculateHash(file: File): String? {
        return try {
            file.inputStream().use { input ->
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

    private fun getMimeType(ext: String): String {
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "bmp" -> "image/x-ms-bmp"
            "tiff" -> "image/tiff"
            else -> "image/*"
        }
    }
}
