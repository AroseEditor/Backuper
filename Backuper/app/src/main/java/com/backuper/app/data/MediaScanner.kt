package com.backuper.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
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
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "tiff", "raw",
        "mp4", "webm", "mkv", "mov", "avi", "flv", "3gp"
    )

    suspend fun scanStreaming(context: Context, onFileFound: suspend (MediaFile) -> Unit) {
        val foundPaths = HashSet<String>()

        // 1. Scan via MediaStore (Instant detection)
        try {
            scanMediaStore(context) { media ->
                if (foundPaths.add(media.file.absolutePath)) {
                    onFileFound(media)
                }
            }
        } catch (e: Exception) {
            // Ignore/Log
        }

        // 2. Scan via Directory Walking (Fallback/Deep Scan)
        try {
            val root = Environment.getExternalStorageDirectory()
            scanDirectory(root) { media ->
                if (foundPaths.add(media.file.absolutePath)) {
                    onFileFound(media)
                }
            }
        } catch (e: Exception) {
            // Ignore/Log
        }
    }

    private suspend fun scanMediaStore(context: Context, onFileFound: suspend (MediaFile) -> Unit) {
        val contentResolver = context.contentResolver

        // Query Images
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imageProjection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        contentResolver.query(imageUri, imageProjection, null, null, null)?.use { cursor ->
            val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val path = if (dataIndex != -1) cursor.getString(dataIndex) else null ?: continue
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val ext = file.extension.lowercase()
                    if (TARGET_EXTENSIONS.contains(ext)) {
                        val name = if (nameIndex != -1) cursor.getString(nameIndex) else null ?: file.name
                        val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else file.length()
                        val hash = calculateHash(file) ?: continue
                        onFileFound(
                            MediaFile(
                                uri = Uri.fromFile(file),
                                file = file,
                                name = name,
                                size = size,
                                hash = hash,
                                mimeType = getMimeType(ext)
                            )
                        )
                    }
                }
            }
        }

        // Query Videos
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoProjection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )
        contentResolver.query(videoUri, videoProjection, null, null, null)?.use { cursor ->
            val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val path = if (dataIndex != -1) cursor.getString(dataIndex) else null ?: continue
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val ext = file.extension.lowercase()
                    if (TARGET_EXTENSIONS.contains(ext)) {
                        val name = if (nameIndex != -1) cursor.getString(nameIndex) else null ?: file.name
                        val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else file.length()
                        val hash = calculateHash(file) ?: continue
                        onFileFound(
                            MediaFile(
                                uri = Uri.fromFile(file),
                                file = file,
                                name = name,
                                size = size,
                                hash = hash,
                                mimeType = getMimeType(ext)
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun scanDirectory(dir: File, onFileFound: suspend (MediaFile) -> Unit) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name
                if (name.equals(".thumbnails", ignoreCase = true) ||
                    name.equals(".cache", ignoreCase = true) ||
                    name.startsWith(".") ||
                    name.equals("Android", ignoreCase = true)) {
                    continue
                }
                scanDirectory(file, onFileFound)
            } else {
                val ext = file.extension.lowercase()
                if (TARGET_EXTENSIONS.contains(ext)) {
                    val hash = calculateHash(file) ?: continue
                    onFileFound(
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
            val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(key.toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
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
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            else -> "*/*"
        }
    }
}
