package com.backuper.app.db

import androidx.room.*

@Dao
interface FileDao {
    @Query("SELECT EXISTS(SELECT 1 FROM uploaded_files WHERE hash = :hash)")
    suspend fun isUploaded(hash: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: UploadedFile)

    @Query("SELECT COUNT(*) FROM uploaded_files")
    suspend fun getCount(): Int
}
