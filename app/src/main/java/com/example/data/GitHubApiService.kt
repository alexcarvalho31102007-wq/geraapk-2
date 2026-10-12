package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

@JsonClass(generateAdapter = true)
data class GitHubUserResponse(
    @Json(name = "login") val login: String,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubCreateRepoRequest(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String = "Repositório de compilação Release via GERA APK",
    @Json(name = "private") val isPrivate: Boolean = false,
    @Json(name = "auto_init") val autoInit: Boolean = true
)

@JsonClass(generateAdapter = true)
data class GitHubRepoResponse(
    @Json(name = "full_name") val fullName: String,
    @Json(name = "default_branch") val defaultBranch: String? = "main",
    @Json(name = "html_url") val htmlUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubFileResponse(
    @Json(name = "sha") val sha: String?,
    @Json(name = "html_url") val htmlUrl: String?
)

@JsonClass(generateAdapter = true)
data class GitHubPutFileRequest(
    @Json(name = "message") val message: String,
    @Json(name = "content") val contentBase64: String,
    @Json(name = "branch") val branch: String,
    @Json(name = "sha") val sha: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubPutFileResult(
    @Json(name = "commit") val commit: GitHubCommitInfo?
)

@JsonClass(generateAdapter = true)
data class GitHubCommitInfo(
    @Json(name = "sha") val sha: String?,
    @Json(name = "html_url") val htmlUrl: String?
)

@JsonClass(generateAdapter = true)
data class GitRefResponse(
    @Json(name = "object") val gitObject: GitRefObject
)

@JsonClass(generateAdapter = true)
data class GitRefObject(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class GitCommitResponse(
    @Json(name = "sha") val sha: String,
    @Json(name = "tree") val tree: GitRefObject
)

@JsonClass(generateAdapter = true)
data class GitCreateBlobRequest(
    @Json(name = "content") val content: String,
    @Json(name = "encoding") val encoding: String = "base64"
)

@JsonClass(generateAdapter = true)
data class GitBlobResponse(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class GitTreeEntry(
    @Json(name = "path") val path: String,
    @Json(name = "mode") val mode: String = "100644",
    @Json(name = "type") val type: String = "blob",
    @Json(name = "sha") val sha: String? = null,
    @Json(name = "content") val content: String? = null
)

@JsonClass(generateAdapter = true)
data class GitCreateTreeRequest(
    @Json(name = "base_tree") val baseTree: String? = null,
    @Json(name = "tree") val tree: List<GitTreeEntry>
)

@JsonClass(generateAdapter = true)
data class GitTreeResponse(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class GitCreateCommitRequest(
    @Json(name = "message") val message: String,
    @Json(name = "tree") val treeSha: String,
    @Json(name = "parents") val parents: List<String>
)

@JsonClass(generateAdapter = true)
data class GitUpdateRefRequest(
    @Json(name = "sha") val sha: String,
    @Json(name = "force") val force: Boolean = true
)

@JsonClass(generateAdapter = true)
data class GitHubWorkflowDispatchRequest(
    @Json(name = "ref") val ref: String
)

@JsonClass(generateAdapter = true)
data class GitHubWorkflowRunsResponse(
    @Json(name = "total_count") val totalCount: Int,
    @Json(name = "workflow_runs") val workflowRuns: List<GitHubWorkflowRun>
)

@JsonClass(generateAdapter = true)
data class GitHubWorkflowRun(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String?,
    @Json(name = "head_sha") val headSha: String? = null,
    @Json(name = "status") val status: String?, // queued, in_progress, completed
    @Json(name = "conclusion") val conclusion: String?, // success, failure, cancelled
    @Json(name = "html_url") val htmlUrl: String?
)

@JsonClass(generateAdapter = true)
data class GitHubJobsResponse(
    @Json(name = "total_count") val totalCount: Int = 0,
    @Json(name = "jobs") val jobs: List<GitHubJob> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GitHubJob(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "status") val status: String?,
    @Json(name = "conclusion") val conclusion: String?,
    @Json(name = "steps") val steps: List<GitHubJobStep>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class GitHubJobStep(
    @Json(name = "name") val name: String,
    @Json(name = "status") val status: String?,
    @Json(name = "conclusion") val conclusion: String?,
    @Json(name = "number") val number: Int
)

@JsonClass(generateAdapter = true)
data class GitHubArtifactsResponse(
    @Json(name = "total_count") val totalCount: Int,
    @Json(name = "artifacts") val artifacts: List<GitHubArtifact>
)

@JsonClass(generateAdapter = true)
data class GitHubArtifact(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "size_in_bytes") val sizeInBytes: Long,
    @Json(name = "archive_download_url") val archiveDownloadUrl: String
)

interface GitHubApiService {

    @GET("user")
    suspend fun getAuthenticatedUser(
        @Header("Authorization") authHeader: String
    ): Response<GitHubUserResponse>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepository(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubRepoResponse>

    @POST("user/repos")
    suspend fun createRepository(
        @Header("Authorization") authHeader: String,
        @Body request: GitHubCreateRepoRequest
    ): Response<GitHubRepoResponse>

    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchRef(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): Response<GitRefResponse>

    @GET("repos/{owner}/{repo}/git/commits/{commit_sha}")
    suspend fun getGitCommit(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commit_sha") commitSha: String
    ): Response<GitCommitResponse>

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createGitBlob(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: GitCreateBlobRequest
    ): Response<GitBlobResponse>

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createGitTree(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: GitCreateTreeRequest
    ): Response<GitTreeResponse>

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createGitCommit(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: GitCreateCommitRequest
    ): Response<GitCommitResponse>

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateBranchRef(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body request: GitUpdateRefRequest
    ): Response<GitRefResponse>

    @POST("repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches")
    suspend fun dispatchWorkflow(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("workflow_id") workflowId: String,
        @Body request: GitHubWorkflowDispatchRequest
    ): Response<ResponseBody>

    @POST("repos/{owner}/{repo}/actions/runs/{run_id}/cancel")
    suspend fun cancelWorkflowRun(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFileContent(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") branch: String
    ): Response<GitHubFileResponse>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putFileContent(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body request: GitHubPutFileRequest
    ): Response<GitHubPutFileResult>

    @GET("repos/{owner}/{repo}/actions/runs")
    suspend fun getWorkflowRuns(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("branch") branch: String,
        @Query("per_page") perPage: Int = 5
    ): Response<GitHubWorkflowRunsResponse>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}")
    suspend fun getWorkflowRun(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<GitHubWorkflowRun>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/jobs")
    suspend fun getWorkflowRunJobs(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<GitHubJobsResponse>

    @Streaming
    @GET("repos/{owner}/{repo}/actions/jobs/{job_id}/logs")
    suspend fun downloadJobLogs(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("job_id") jobId: Long
    ): Response<ResponseBody>

    @GET("repos/{owner}/{repo}/actions/runs/{run_id}/artifacts")
    suspend fun getRunArtifacts(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("run_id") runId: Long
    ): Response<GitHubArtifactsResponse>

    @Streaming
    @GET
    suspend fun downloadArtifactZip(
        @Header("Authorization") authHeader: String,
        @Url downloadUrl: String
    ): Response<ResponseBody>
}
