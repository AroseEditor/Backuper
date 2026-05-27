package com.backuper.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface GoFileService {

    @GET("servers")
    suspend fun getServers(): Response<ServersResponse>

    @POST
    @Multipart
    suspend fun uploadFile(
        @Url url: String,
        @Part file: MultipartBody.Part,
        @Part("token") token: RequestBody,
        @Part("folderId") folderId: RequestBody? = null
    ): Response<UploadResponse>
}

data class ServersResponse(val status: String, val data: ServersData)
data class ServersData(val servers: List<ServerInfo>)
data class ServerInfo(val name: String, val zone: String)

data class UploadResponse(val status: String, val data: UploadData?)
data class UploadData(val downloadPage: String, val code: String, val parentFolder: String, val fileId: String)
