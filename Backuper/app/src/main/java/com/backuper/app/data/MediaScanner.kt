package com.backuper.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
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

        // 2. Scan via Directory Walking (Fallback/Deep Scan - handles all storage roots)
        try {
            val roots = getStorageRoots()
            for (root in roots) {
                scanDirectory(root) { media ->
                    if (foundPaths.add(media.file.absolutePath)) {
                        onFileFound(media)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore/Log
        }
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        // Primary external storage
        roots.add(Environment.getExternalStorageDirectory())
        
        // Scan /storage for secondary mounts (SD cards, OTG, etc.)
        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                val files = storageDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isDirectory && file.canRead()) {
                            val name = file.name
                            if (name != "self" && name != "emulated" && !roots.any { it.absolutePath == file.absolutePath }) {
                                roots.add(file)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return roots.distinctBy { it.absolutePath }
    }

    private suspend fun scanMediaStore(context: Context, onFileFound: suspend (MediaFile) -> Unit) {
        val contentResolver = context.contentResolver

        // Query Images (returns all images in MediaStore)
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
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
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
                            mimeType = mime
                        )
                    )
                }
            }
        }

        // Query Videos (returns all videos in MediaStore)
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
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
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
                            mimeType = mime
                        )
                    )
                }
            }
        }
    }

    private suspend fun scanDirectory(dir: File, onFileFound: suspend (MediaFile) -> Unit) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val name = file.name
                // Skip cache, thumbnails, Android system folder and git configs to avoid junk, but crawl all others including hidden dot-directories
                if (name.equals(".thumbnails", ignoreCase = true) ||
                    name.equals(".cache", ignoreCase = true) ||
                    name.equals("Android", ignoreCase = true) ||
                    name.equals(".git", ignoreCase = true)) {
                    continue
                }
                scanDirectory(file, onFileFound)
            } else {
                val ext = file.extension.lowercase()
                var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                var name = file.name

                // Fallback to signature/header detection if the file has a custom/obfuscated extension (e.g. .dat, .enc, or none)
                if (mime == null || mime == "application/octet-stream" || ext == "dat" || ext == "bin" || ext == "enc") {
                    val detectedMime = detectMimeTypeFromHeader(file)
                    if (detectedMime != null) {
                        mime = detectedMime
                        val suffix = when (detectedMime) {
                            "image/jpeg" -> ".jpg"
                            "image/png" -> ".png"
                            "image/gif" -> ".gif"
                            "image/webp" -> ".webp"
                            "video/x-matroska" -> ".mkv"
                            "video/mp4" -> ".mp4"
                            "video/x-msvideo" -> ".avi"
                            else -> ""
                        }
                        if (suffix.isNotEmpty() && !name.endsWith(suffix, ignoreCase = true)) {
                            name = if (name.contains(".")) {
                                name.substringBeforeLast(".") + suffix
                            } else {
                                name + suffix
                            }
                        }
                    }
                }

                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    val hash = calculateHash(file) ?: continue
                    onFileFound(
                        MediaFile(
                            uri = Uri.fromFile(file),
                            file = file,
                            name = name,
                            size = file.length(),
                            hash = hash,
                            mimeType = mime
                        )
                    )
                }
            }
        }
    }

    private fun detectMimeTypeFromHeader(file: File): String? {
        if (file.length() < 12) return null
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 4) return null

                // Check JPEG
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                    return "image/jpeg"
                }
                // Check PNG
                if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                    return "image/png"
                }
                // Check GIF
                if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x38.toByte()) {
                    return "image/gif"
                }
                // Check WEBP
                if (header[0] == 'R'.toByte() && header[1] == 'I'.toByte() && header[2] == 'F'.toByte() && header[3] == 'F'.toByte()) {
                    if (read >= 12 && header[8] == 'W'.toByte() && header[9] == 'E'.toByte() && header[10] == 'B'.toByte() && header[11] == 'P'.toByte()) {
                        return "image/webp"
                    }
                }
                // Check MKV/WEBM (EBML)
                if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) {
                    return "video/x-matroska"
                }
                // Check MP4 (ftyp)
                if (read >= 8 && header[4] == 'f'.toByte() && header[5] == 't'.toByte() && header[6] == 'y'.toByte() && header[7] == 'p'.toByte()) {
                    return "video/mp4"
                }
                // Check AVI
                if (header[0] == 'R'.toByte() && header[1] == 'I'.toByte() && header[2] == 'F'.toByte() && header[3] == 'F'.toByte()) {
                    if (read >= 12 && header[8] == 'A'.toByte() && header[9] == 'V'.toByte() && header[10] == 'I'.toByte()) {
                        return "video/x-msvideo"
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
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
}
