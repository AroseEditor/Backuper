package com.backuper.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploaded_files")
data class UploadedFile(
    @PrimaryKey val hash: String, // SHA-256 hash of the file
    val fileName: String,
    val uploadDate: Long = System.currentTimeMillis()
)
