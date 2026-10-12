package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SupabaseSignUpRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "data") val data: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class SupabaseLoginRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class SupabaseRecoverRequest(
    @Json(name = "email") val email: String
)

@JsonClass(generateAdapter = true)
data class SupabaseUserDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null
) {
    val displayName: String
        get() = (userMetadata?.get("full_name") as? String)
            ?: (userMetadata?.get("name") as? String)
            ?: email?.substringBefore("@").orEmpty()
}

@JsonClass(generateAdapter = true)
data class SupabaseAuthResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "user") val user: SupabaseUserDto? = null,
    @Json(name = "id") val directId: String? = null,
    @Json(name = "email") val directEmail: String? = null,
    @Json(name = "user_metadata") val directMetadata: Map<String, Any?>? = null
) {
    fun resolveUser(): SupabaseUserDto? {
        if (user != null && !user.id.isNullOrBlank()) return user
        if (!directId.isNullOrBlank()) {
            return SupabaseUserDto(
                id = directId,
                email = directEmail,
                userMetadata = directMetadata
            )
        }
        return null
    }
}

@JsonClass(generateAdapter = true)
data class SupabaseBuildRecordDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "user_id") val userId: String,
    @Json(name = "app_name") val appName: String,
    @Json(name = "zip_file_name") val zipFileName: String,
    @Json(name = "package_name") val packageName: String,
    @Json(name = "repo_full_name") val repoFullName: String,
    @Json(name = "branch") val branch: String,
    @Json(name = "status") val status: String,
    @Json(name = "progress") val progress: Float,
    @Json(name = "status_step_message") val statusStepMessage: String,
    @Json(name = "created_at_millis") val createdAtMillis: Long,
    @Json(name = "updated_at_millis") val updatedAtMillis: Long,
    @Json(name = "workflow_run_id") val workflowRunId: Long? = null,
    @Json(name = "workflow_html_url") val workflowHtmlUrl: String? = null,
    @Json(name = "apk_download_url") val apkDownloadUrl: String? = null,
    @Json(name = "apk_file_name") val apkFileName: String? = null,
    @Json(name = "apk_size_bytes") val apkSizeBytes: Long = 0L,
    @Json(name = "error_summary") val errorSummary: String? = null,
    @Json(name = "logs_text") val logsText: String = ""
)

@JsonClass(generateAdapter = true)
data class EdgeCompileFileDto(
    @Json(name = "path") val path: String,
    @Json(name = "content_base64") val contentBase64: String,
    @Json(name = "mode") val mode: String = "100644"
)

@JsonClass(generateAdapter = true)
data class EdgeCompilerRequest(
    @Json(name = "action") val action: String, // "start_release_build", "recompile_release", "check_status", "download_apk"
    @Json(name = "build_id") val buildId: Long? = null,
    @Json(name = "app_name") val appName: String? = null,
    @Json(name = "repo_slug") val repoSlug: String? = null,
    @Json(name = "repo_full_name") val repoFullName: String? = null,
    @Json(name = "branch") val branch: String? = "main",
    @Json(name = "run_id") val runId: Long? = null,
    @Json(name = "download_url") val downloadUrl: String? = null,
    @Json(name = "files") val files: List<EdgeCompileFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class EdgeCompilerResponse(
    @Json(name = "ok") val ok: Boolean = true,
    @Json(name = "repo_full_name") val repoFullName: String? = null,
    @Json(name = "branch") val branch: String? = null,
    @Json(name = "run_id") val runId: Long? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "status") val status: String? = null, // "in_progress", "completed"
    @Json(name = "conclusion") val conclusion: String? = null, // "success", "failure"
    @Json(name = "step_title") val stepTitle: String? = null,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "apk_download_url") val apkDownloadUrl: String? = null,
    @Json(name = "apk_base64") val apkBase64: String? = null,
    @Json(name = "error_message") val errorMessage: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAppConfigDto(
    @Json(name = "key") val key: String? = null,
    @Json(name = "value") val value: String? = null
)

/**
 * Interface REST oficial do Supabase:
 * - GoTrue Auth (`auth/v1/...`)
 * - PostgREST com RLS (`rest/v1/builds`)
 * - Supabase Edge Function (`functions/v1/gera-apk-compiler`) que utiliza o `GITHUB_TOKEN`
 *   armazenado exclusivamente no cofre/secrets do Supabase, sem expor o token ao usuário.
 */
interface SupabaseApiService {

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseSignUpRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token?grant_type=password")
    suspend fun signInWithPassword(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseLoginRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/recover")
    suspend fun recoverPassword(
        @Header("apikey") apiKey: String,
        @Body request: SupabaseRecoverRequest
    ): Response<ResponseBody>

    @POST("auth/v1/logout")
    suspend fun signOut(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String
    ): Response<ResponseBody>

    @GET("rest/v1/builds")
    suspend fun getUserBuilds(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("order") order: String = "created_at_millis.desc"
    ): Response<List<SupabaseBuildRecordDto>>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("rest/v1/builds")
    suspend fun upsertBuildRecord(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body record: SupabaseBuildRecordDto
    ): Response<List<SupabaseBuildRecordDto>>

    @DELETE("rest/v1/builds")
    suspend fun deleteBuildRecord(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String
    ): Response<ResponseBody>

    /**
     * Invoca a Edge Function segura no Supabase (`functions/v1/gera-apk-compiler`).
     * A Edge Function lê o token do GitHub diretamente do Supabase Secrets / Vault
     * no servidor, sem jamais enviar o token para o dispositivo ou logs.
     */
    @POST("functions/v1/gera-apk-compiler")
    suspend fun invokeCompilerEdgeFunction(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body request: EdgeCompilerRequest
    ): Response<EdgeCompilerResponse>

    @Streaming
    @POST("functions/v1/gera-apk-compiler")
    suspend fun downloadApkFromEdgeFunction(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body request: EdgeCompilerRequest
    ): Response<ResponseBody>

    /**
     * Permite que o backend recupere dinamicamente a configuração do compilador no Supabase
     * caso a tabela `app_config` / RPC esteja configurada pelo administrador.
     */
    @GET("rest/v1/app_config")
    suspend fun getDynamicConfig(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("key") keyFilter: String = "eq.github_token",
        @Query("limit") limit: Int = 1
    ): Response<List<SupabaseAppConfigDto>>

    companion object {
        fun create(supabaseUrl: String): SupabaseApiService {
            val normalizedUrl = supabaseUrl.trim().trimEnd('/') + "/"
            val moshi = Moshi.Builder().build()
            val okHttp = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttp)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SupabaseApiService::class.java)
        }
    }
}
