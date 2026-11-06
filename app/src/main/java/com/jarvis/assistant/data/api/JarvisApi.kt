package com.jarvis.assistant.data.api

import com.jarvis.assistant.data.model.CommandRequest
import com.jarvis.assistant.data.model.CommandResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/** Full URLs are passed via @Url so the PC address can change at runtime. */
interface JarvisApi {
    @POST
    suspend fun sendCommand(
        @Url url: String,
        @Header("Authorization") auth: String?,
        @Body request: CommandRequest
    ): CommandResponse

    @GET
    suspend fun status(
        @Url url: String,
        @Header("Authorization") auth: String?
    ): ResponseBody
}
