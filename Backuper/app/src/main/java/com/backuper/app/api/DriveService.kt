package com.backuper.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface DriveService {

    @GET("drive/v3/files")
    suspend fun findFolder(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("fields") fields: String = "files(id, name)"
    ): Response<FileListResponse>

    @POST("drive/v3/files")
    suspend fun createFolder(
        @Header("Authorization") auth: String,
        @Body metadata: FolderMetadata
    ): Response<FileResponse>

    @POST("upload/drive/v3/files?uploadType=multipart")
    @Multipart
    suspend fun uploadMultipart(
        @Header("Authorization") auth: String,
        @Part("metadata") metadata: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<FileResponse>

    // For Resumable Uploads
    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun initiateResumableUpload(
        @Header("Authorization") auth: String,
        @Body metadata: FileMetadata
    ): Response<ResponseBody>

    @PUT
    suspend fun uploadChunk(
        @Url url: String,
        @Header("Content-Range") range: String,
        @Body bytes: RequestBody
    ): Response<ResponseBody>
}

data class FileListResponse(val files: List<FileResponse>)
data class FileResponse(val id: String, val name: String)
data class FolderMetadata(val name: String, val mimeType: String = "application/vnd.google-apps.folder")
data class FileMetadata(val name: String, val parents: List<String>)
