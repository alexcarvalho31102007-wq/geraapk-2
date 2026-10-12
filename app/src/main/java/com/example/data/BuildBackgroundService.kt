package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuildBackgroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<Long, Boolean>()

    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val dao by lazy { database.buildRecordDao() }
    private val repoDao by lazy { database.userRepositoryDao() }
    private val supabaseRepo by lazy { SupabaseRepository(applicationContext) }

    // Cliente OkHttp sem Authorization header automático para seguir URLs pré-assinadas do Azure/S3
    // quando o GitHub retorna HTTP 302 no download do artefato APK!
    private val rawDownloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Cliente que NÃO segue redirecionamentos automaticamente para podermos capturar o Location 302
    // do GitHub Artifacts sem reenviar o header Authorization para o Azure Blob Storage!
    private val noRedirectGitHubClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val gitHubApi: GitHubApiService by lazy {
        val moshi = Moshi.Builder().build()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubApiService::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Processando compilação Release..."))

        val recordId = intent?.getLongExtra(EXTRA_RECORD_ID, -1L) ?: -1L
        val isRecompile = intent?.getBooleanExtra(EXTRA_RECOMPILE, false) ?: false
        val supabaseAccessToken = intent?.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()

        serviceScope.launch {
            if (recordId > 0L) {
                launchBuildTaskIfNeeded(
                    recordId = recordId,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
            } else {
                val activeRecords = dao.getActiveBuilds()
                for (rec in activeRecords) {
                    launchBuildTaskIfNeeded(
                        recordId = rec.id,
                        isRecompile = false,
                        supabaseAccessToken = supabaseAccessToken
                    )
                }
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun launchBuildTaskIfNeeded(
        recordId: Long,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ) {
        if (activeTasks.putIfAbsent(recordId, true) != null) return
        serviceScope.launch {
            try {
                executeAutomatedReleaseBuild(
                    recordId = recordId,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
            } finally {
                activeTasks.remove(recordId)
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun executeAutomatedReleaseBuild(
        recordId: Long,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ) {
        var record = dao.getRecordById(recordId) ?: return

        try {
            if (supabaseRepo.isCloudConfigured()) {
                val edgeHandled = tryExecuteViaSupabaseEdgeFunction(
                    record = record,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
                if (edgeHandled) return
            }

            val backendToken = supabaseRepo.fetchDynamicBackendTokenIfNeeded(supabaseAccessToken)
            if (backendToken.isEmpty()) {
                failBuild(
                    record = record,
                    errorSummary = "Serviço de compilação indisponível.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val authHeader = "Bearer $backendToken"

            if (!isRecompile && record.workflowRunId != null && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = parts[0],
                    repo = parts[1],
                    runId = record.workflowRunId!!,
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val zipFile = File(record.localZipPath)
            if (isRecompile && !zipFile.exists() && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                val owner = parts[0]
                val repoName = parts[1]
                val branch = record.branch.ifBlank { "main" }

                updateNotification("Enviando arquivos...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.25f,
                    stepMessage = "Enviando arquivos",
                    supabaseAccessToken = supabaseAccessToken
                )

                val updatedWorkflowYaml = ZipProjectHelper.buildGitHubActionsWorkflowYaml(record.appName)
                val updatedWorkflowB64 = Base64.encodeToString(
                    updatedWorkflowYaml.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val existingWf = gitHubApi.getFileContent(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    path = ZipSanitizer.SINGLE_WORKFLOW_PATH,
                    branch = branch
                ).body()
                val putWfResp = gitHubApi.putFileContent(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    path = ZipSanitizer.SINGLE_WORKFLOW_PATH,
                    request = GitHubPutFileRequest(
                        message = "release: recompile ${record.appName} via GERA APK",
                        contentBase64 = updatedWorkflowB64,
                        branch = branch,
                        sha = existingWf?.sha
                    )
                )

                val recompileCommitSha = putWfResp.body()?.commit?.sha

                if (!putWfResp.isSuccessful) {
                    gitHubApi.dispatchWorkflow(
                        authHeader = authHeader,
                        owner = owner,
                        repo = repoName,
                        workflowId = ZipSanitizer.SINGLE_WORKFLOW_FILENAME,
                        request = GitHubWorkflowDispatchRequest(ref = branch)
                    )
                }

                updateNotification("Preparando ambiente...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.45f,
                    stepMessage = "Preparando ambiente",
                    supabaseAccessToken = supabaseAccessToken
                )

                var matchedRun: GitHubWorkflowRun? = null
                for (attempt in 1..20) {
                    delay(3000)
                    val runsResp = gitHubApi.getWorkflowRuns(authHeader, owner, repoName, branch, perPage = 10)
                    if (runsResp.isSuccessful) {
                        val runs = runsResp.body()?.workflowRuns.orEmpty()
                        matchedRun = if (recompileCommitSha != null) {
                            runs.firstOrNull { it.headSha == recompileCommitSha }
                        } else {
                            runs.firstOrNull { it.status != "completed" } ?: runs.firstOrNull()
                        }
                        if (matchedRun != null) break
                    }
                }

                if (matchedRun == null) {
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível iniciar a recompilação Release.",
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }

                record = record.copy(
                    workflowRunId = matchedRun.id,
                    workflowHtmlUrl = matchedRun.htmlUrl,
                    updatedAtMillis = System.currentTimeMillis()
                )
                dao.updateRecord(record)
                supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    runId = matchedRun.id,
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            if (!zipFile.exists()) {
                failBuild(
                    record = record,
                    errorSummary = "Arquivo ZIP não encontrado no armazenamento local.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.14f,
                stepMessage = "Enviando arquivos",
                supabaseAccessToken = supabaseAccessToken
            )

            val projectInfo = try {
                ZipProjectHelper.inspectZipBytes(record.zipFileName, zipFile.readBytes())
            } catch (e: Exception) {
                failBuild(
                    record = record,
                    errorSummary = e.localizedMessage ?: "Arquivo ZIP inválido.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val userResp = gitHubApi.getAuthenticatedUser(authHeader)
            if (!userResp.isSuccessful || userResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível autenticar no servidor de compilação.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val username = userResp.body()!!.login
            // Usa SEMPRE o repositório selecionado pelo usuário (sem criar outro repositório para cada ZIP)!
            val selectedRepoEntity = if (record.repositoryId > 0L) {
                repoDao.getRepositoryById(record.repositoryId)
            } else {
                null
            }
            val targetRepoSlug = selectedRepoEntity?.repoSlug?.takeIf { it.isNotBlank() }
                ?: if (record.repoFullName.contains("/")) {
                    record.repoFullName.substringAfter("/")
                } else {
                    record.repoFullName.ifBlank { sanitizeZipNameToRepoName(record.zipFileName) }
                }
            val fullRepoName = "$username/$targetRepoSlug"

            var targetBranch = "main"
            val existingRepoResp = gitHubApi.getRepository(authHeader, username, targetRepoSlug)
            if (existingRepoResp.isSuccessful && existingRepoResp.body() != null) {
                targetBranch = existingRepoResp.body()!!.defaultBranch ?: "main"
            } else {
                val createResp = gitHubApi.createRepository(
                    authHeader = authHeader,
                    request = GitHubCreateRepoRequest(
                        name = targetRepoSlug,
                        isPrivate = false,
                        autoInit = true
                    )
                )
                if (!createResp.isSuccessful || createResp.body() == null) {
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível preparar o repositório '$targetRepoSlug'.",
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }
                targetBranch = createResp.body()!!.defaultBranch ?: "main"
            }

            if (selectedRepoEntity != null) {
                repoDao.updateRepository(
                    selectedRepoEntity.copy(
                        repoFullName = fullRepoName,
                        branch = targetBranch,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }

            record = record.copy(
                appName = projectInfo.detectedAppName,
                packageName = projectInfo.detectedPackageName,
                repoFullName = fullRepoName,
                branch = targetBranch,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.22f,
                stepMessage = "Enviando arquivos",
                supabaseAccessToken = supabaseAccessToken
            )

            var latestCommitSha: String? = null
            for (attempt in 1..6) {
                val refResp = gitHubApi.getBranchRef(authHeader, username, targetRepoSlug, targetBranch)
                if (refResp.isSuccessful && refResp.body() != null) {
                    latestCommitSha = refResp.body()!!.gitObject.sha
                    break
                }
                delay(1200)
            }

            if (latestCommitSha == null) {
                val initB64 = Base64.encodeToString(
                    "# ${projectInfo.detectedAppName}\n".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val putInit = gitHubApi.putFileContent(
                    authHeader = authHeader,
                    owner = username,
                    repo = targetRepoSlug,
                    path = "README.md",
                    request = GitHubPutFileRequest(
                        message = "init: initialize repository",
                        contentBase64 = initB64,
                        branch = targetBranch
                    )
                )
                if (putInit.isSuccessful) {
                    latestCommitSha = putInit.body()?.commit?.sha
                }
                if (latestCommitSha == null) {
                    val refRetry = gitHubApi.getBranchRef(authHeader, username, targetRepoSlug, targetBranch)
                    latestCommitSha = refRetry.body()?.gitObject?.sha
                }
            }

            if (latestCommitSha == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível inicializar a branch do repositório.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            // Substitui integralmente o código-fonte anterior do repositório selecionado pelo novo código-fonte do ZIP
            val normalizedFiles = ZipSanitizer.extractNormalizedFiles(projectInfo.rawZipBytes).toMutableMap()
            normalizedFiles.keys.removeAll { ZipSanitizer.isAnyGitHubWorkflowOrFolder(it) }
            normalizedFiles[ZipSanitizer.SINGLE_WORKFLOW_PATH] =
                projectInfo.generatedWorkflowYaml.toByteArray(Charsets.UTF_8)

            val totalFilesToUpload = normalizedFiles.size
            val uploadedCounter = AtomicInteger(0)
            val uploadSemaphore = Semaphore(4)

            val treeResults = withContext(Dispatchers.IO) {
                normalizedFiles.entries.map { (filePath, fileBytes) ->
                    async {
                        uploadSemaphore.withPermit {
                            val b64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                            val blobResp = createBlobWithRetry(authHeader, username, targetRepoSlug, b64)
                                ?: throw IllegalStateException("Falha ao enviar arquivo '$filePath'")
                            val done = uploadedCounter.incrementAndGet()
                            if (done % 5 == 0 || done == totalFilesToUpload) {
                                val uploadProgress = 0.22f + (0.16f * (done.toFloat() / totalFilesToUpload.coerceAtLeast(1)))
                                dao.updateRecord(
                                    record.copy(
                                        progress = uploadProgress,
                                        statusStepMessage = "Enviando arquivos",
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                )
                            }
                            val mode = if (filePath == "gradlew") "100755" else "100644"
                            GitTreeEntry(
                                path = filePath,
                                mode = mode,
                                type = "blob",
                                sha = blobResp.sha
                            )
                        }
                    }
                }.awaitAll()
            }

            val treeEntries = treeResults.toList()

            // baseTree = null substitui 100% da árvore anterior daquele repositório pelo novo código-fonte!
            val treeResp = gitHubApi.createGitTree(
                authHeader = authHeader,
                owner = username,
                repo = targetRepoSlug,
                request = GitCreateTreeRequest(baseTree = null, tree = treeEntries)
            )
            if (!treeResp.isSuccessful || treeResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao atualizar código-fonte no repositório.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val newCommitResp = gitHubApi.createGitCommit(
                authHeader = authHeader,
                owner = username,
                repo = targetRepoSlug,
                request = GitCreateCommitRequest(
                    message = "release: update ${projectInfo.detectedAppName} (${record.zipFileName}) via GERA APK",
                    treeSha = treeResp.body()!!.sha,
                    parents = listOf(latestCommitSha)
                )
            )
            if (!newCommitResp.isSuccessful || newCommitResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao registrar atualização no repositório.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val pushedCommitSha = newCommitResp.body()!!.sha
            val updateRefResp = gitHubApi.updateBranchRef(
                authHeader = authHeader,
                owner = username,
                repo = targetRepoSlug,
                branch = targetBranch,
                request = GitUpdateRefRequest(sha = pushedCommitSha, force = true)
            )
            if (!updateRefResp.isSuccessful) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao atualizar branch '$targetBranch'.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            updateNotification("Preparando ambiente...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.45f,
                stepMessage = "Preparando ambiente",
                supabaseAccessToken = supabaseAccessToken
            )

            // IMPORTANTE: Filtra estritamente pelo headSha == pushedCommitSha para nunca capturar
            // um workflow antigo que havia falhado anteriormente no mesmo repositório!
            var matchedRun: GitHubWorkflowRun? = null
            for (attempt in 1..22) {
                delay(3000)
                val runsResp = gitHubApi.getWorkflowRuns(authHeader, username, targetRepoSlug, targetBranch, perPage = 10)
                if (runsResp.isSuccessful) {
                    val allRuns = runsResp.body()?.workflowRuns.orEmpty()
                    val runsForCommit = allRuns.filter { it.headSha == pushedCommitSha }

                    if (runsForCommit.isNotEmpty()) {
                        val primaryRun = runsForCommit.firstOrNull {
                            it.name?.contains("GERA APK", ignoreCase = true) == true
                        } ?: runsForCommit.first()

                        runsForCommit.filter { it.id != primaryRun.id }.forEach { duplicateRun ->
                            try {
                                gitHubApi.cancelWorkflowRun(authHeader, username, targetRepoSlug, duplicateRun.id)
                            } catch (_: Exception) {
                            }
                        }

                        matchedRun = primaryRun
                        break
                    }
                }
            }

            if (matchedRun == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível localizar a execução do workflow para este envio.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            record = record.copy(
                workflowRunId = matchedRun.id,
                workflowHtmlUrl = matchedRun.htmlUrl,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

            monitorWorkflowAndFetchApk(
                record = record,
                authHeader = authHeader,
                owner = username,
                repo = targetRepoSlug,
                runId = matchedRun.id,
                supabaseAccessToken = supabaseAccessToken
            )
        } catch (_: Exception) {
            failBuild(
                record = record,
                errorSummary = "Falha durante o processamento da compilação.",
                supabaseAccessToken = supabaseAccessToken
            )
        }
    }

    /**
     * Faz o download seguro do arquivo ZIP de artefato do GitHub Actions tratando corretamente
     * o redirecionamento HTTP 302 para o Azure Blob Storage (sem enviar o header Authorization
     * do GitHub para o Azure, o que causaria erro HTTP 400/403 mesmo quando a build teve sucesso!).
     */
    private suspend fun downloadGitHubArtifactBytesSafely(
        authHeader: String,
        archiveDownloadUrl: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        for (attempt in 1..3) {
            try {
                val initialReq = Request.Builder()
                    .url(archiveDownloadUrl)
                    .header("Authorization", authHeader)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .get()
                    .build()

                noRedirectGitHubClient.newCall(initialReq).execute().use { response ->
                    when {
                        response.code in 300..399 -> {
                            val redirectUrl = response.header("Location")
                            if (!redirectUrl.isNullOrBlank()) {
                                val signedReq = Request.Builder()
                                    .url(redirectUrl)
                                    .get()
                                    .build()
                                rawDownloadClient.newCall(signedReq).execute().use { signedResp ->
                                    if (signedResp.isSuccessful && signedResp.body != null) {
                                        return@withContext signedResp.body!!.bytes()
                                    }
                                }
                            }
                        }
                        response.isSuccessful && response.body != null -> {
                            return@withContext response.body!!.bytes()
                        }
                    }
                }
            } catch (_: Exception) {
            }
            delay(1500L * attempt)
        }
        null
    }

    private suspend fun createBlobWithRetry(
        authHeader: String,
        owner: String,
        repo: String,
        contentBase64: String
    ): GitBlobResponse? {
        for (attempt in 1..3) {
            try {
                val resp = gitHubApi.createGitBlob(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repo,
                    request = GitCreateBlobRequest(content = contentBase64)
                )
                if (resp.isSuccessful && resp.body() != null) {
                    return resp.body()
                }
            } catch (_: Exception) {
            }
            delay(1000L * attempt)
        }
        return null
    }

    private suspend fun tryExecuteViaSupabaseEdgeFunction(
        record: BuildRecordEntity,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ): Boolean {
        var currentRecord = record
        val zipFile = File(currentRecord.localZipPath)

        val initialResponse = if (isRecompile && !zipFile.exists()) {
            supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "recompile_release",
                    buildId = currentRecord.id,
                    appName = currentRecord.appName,
                    repoFullName = currentRecord.repoFullName,
                    branch = currentRecord.branch
                )
            )
        } else {
            if (!zipFile.exists()) return false
            val projectInfo = try {
                ZipProjectHelper.inspectZipBytes(currentRecord.zipFileName, zipFile.readBytes())
            } catch (_: Exception) {
                return false
            }

            val normalizedFiles = ZipSanitizer.extractNormalizedFiles(projectInfo.rawZipBytes).toMutableMap()
            normalizedFiles.keys.removeAll { ZipSanitizer.isAnyGitHubWorkflowOrFolder(it) }
            normalizedFiles[ZipSanitizer.SINGLE_WORKFLOW_PATH] =
                projectInfo.generatedWorkflowYaml.toByteArray(Charsets.UTF_8)

            val fileDtos = normalizedFiles.map { (path, bytes) ->
                EdgeCompileFileDto(
                    path = path,
                    contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mode = if (path == "gradlew") "100755" else "100644"
                )
            }

            supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "start_release_build",
                    buildId = currentRecord.id,
                    appName = projectInfo.detectedAppName,
                    repoSlug = currentRecord.repoFullName.substringAfter("/"),
                    repoFullName = currentRecord.repoFullName,
                    files = fileDtos
                )
            )
        } ?: return false

        if (!initialResponse.ok || initialResponse.runId == null) return false

        val runId = initialResponse.runId
        val repoFullName = initialResponse.repoFullName ?: currentRecord.repoFullName

        currentRecord = currentRecord.copy(
            status = BuildStatus.IN_PROGRESS,
            progress = 0.45f,
            statusStepMessage = "Preparando ambiente",
            repoFullName = repoFullName,
            workflowRunId = runId,
            workflowHtmlUrl = initialResponse.htmlUrl,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(currentRecord)
        supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)

        for (poll in 1..300) {
            delay(4500)
            val statusResp = supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "check_status",
                    buildId = currentRecord.id,
                    repoFullName = repoFullName,
                    runId = runId
                )
            ) ?: continue

            val stepTitle = statusResp.stepTitle ?: "Compilando aplicativo"
            val progress = statusResp.progress ?: 0.70f
            updateNotification("${currentRecord.appName}: $stepTitle")

            currentRecord = currentRecord.copy(
                status = BuildStatus.IN_PROGRESS,
                progress = progress,
                statusStepMessage = stepTitle,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(currentRecord)

            if (statusResp.status == "completed") {
                if (statusResp.conclusion == "success") {
                    val apkB64 = statusResp.apkBase64
                        ?: supabaseRepo.invokeEdgeFunctionCompiler(
                            accessToken = supabaseAccessToken,
                            request = EdgeCompilerRequest(
                                action = "download_apk",
                                buildId = currentRecord.id,
                                repoFullName = repoFullName,
                                runId = runId
                            )
                        )?.apkBase64

                    val savedFile = if (!apkB64.isNullOrBlank()) {
                        val rawBytes = Base64.decode(apkB64, Base64.DEFAULT)
                        val apkBytes = ZipProjectHelper.extractApkFromArtifactZip(rawBytes) ?: rawBytes
                        ZipProjectHelper.saveExtractedArtifactApk(
                            context = applicationContext,
                            buildId = currentRecord.id,
                            appName = currentRecord.appName,
                            extractedApkBytes = apkBytes
                        )
                    } else null

                    currentRecord = currentRecord.copy(
                        status = BuildStatus.SUCCESS,
                        progress = 1.0f,
                        statusStepMessage = "Compilação concluída",
                        apkDownloadUrl = statusResp.apkDownloadUrl,
                        apkLocalPath = savedFile?.absolutePath,
                        apkFileName = savedFile?.name ?: "${currentRecord.appName}-release.apk",
                        apkSizeBytes = savedFile?.length() ?: 0L,
                        errorSummary = null,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    dao.updateRecord(currentRecord)
                    supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
                    updateNotification("Compilação concluída: ${currentRecord.appName}")
                    return true
                }
                failBuild(
                    record = currentRecord,
                    errorSummary = statusResp.errorMessage ?: "A compilação falhou.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return true
            }
        }
        return true
    }

    private suspend fun monitorWorkflowAndFetchApk(
        record: BuildRecordEntity,
        authHeader: String,
        owner: String,
        repo: String,
        runId: Long,
        supabaseAccessToken: String
    ) {
        var currentRecord = record
        var pollCount = 0

        while (pollCount < 300) {
            pollCount++
            val runResp = gitHubApi.getWorkflowRun(authHeader, owner, repo, runId)
            if (!runResp.isSuccessful || runResp.body() == null) {
                delay(4000)
                continue
            }

            val run = runResp.body()!!
            val runStatus = run.status ?: "in_progress"
            val runConclusion = run.conclusion

            val jobsResp = gitHubApi.getWorkflowRunJobs(authHeader, owner, repo, runId)
            val jobs = jobsResp.body()?.jobs.orEmpty()
            var cleanStageTitle = "Preparando ambiente"
            var mappedProgress = 0.50f

            for (job in jobs) {
                for (step in job.steps.orEmpty()) {
                    if (step.status == "in_progress") {
                        when {
                            step.name.contains("assembleRelease", ignoreCase = true) ||
                                step.name.contains("Compilar Aplicativo", ignoreCase = true) ||
                                step.name.contains("Build", ignoreCase = true) -> {
                                cleanStageTitle = "Compilando aplicativo"
                                mappedProgress = 0.72f
                            }
                            step.name.contains("Assinar", ignoreCase = true) ||
                                step.name.contains("Publicar", ignoreCase = true) ||
                                step.name.contains("Upload", ignoreCase = true) ||
                                step.name.contains("Artifact", ignoreCase = true) -> {
                                cleanStageTitle = "Gerando APK"
                                mappedProgress = 0.88f
                            }
                            else -> {
                                cleanStageTitle = "Preparando ambiente"
                                mappedProgress = 0.55f
                            }
                        }
                    }
                }
            }

            updateNotification("${currentRecord.appName}: $cleanStageTitle")
            currentRecord = currentRecord.copy(
                status = BuildStatus.IN_PROGRESS,
                progress = mappedProgress,
                statusStepMessage = cleanStageTitle,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(currentRecord)
            if (pollCount % 3 == 0) {
                supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
            }

            if (runStatus == "completed") {
                val allJobsSucceeded = jobs.isNotEmpty() && jobs.all { it.conclusion == "success" }
                if (runConclusion == "success" || allJobsSucceeded) {
                    currentRecord = updateRecordState(
                        record = currentRecord,
                        status = BuildStatus.IN_PROGRESS,
                        progress = 0.92f,
                        stepMessage = "Gerando APK",
                        supabaseAccessToken = supabaseAccessToken
                    )

                    // Aguarda alguns segundos com retry caso o GitHub Actions ainda esteja indexando o artefato recém-enviado
                    var targetArtifact: GitHubArtifact? = null
                    for (artAttempt in 1..5) {
                        val artifactsResp = gitHubApi.getRunArtifacts(authHeader, owner, repo, runId)
                        val artifacts = artifactsResp.body()?.artifacts.orEmpty()
                        targetArtifact = artifacts.firstOrNull {
                            it.name.contains("release", ignoreCase = true)
                        } ?: artifacts.firstOrNull()
                        if (targetArtifact != null) break
                        delay(2000)
                    }

                    var savedApkFile: File? = null
                    if (targetArtifact != null) {
                        val artifactZipBytes = downloadGitHubArtifactBytesSafely(
                            authHeader = authHeader,
                            archiveDownloadUrl = targetArtifact.archiveDownloadUrl
                        )
                        if (artifactZipBytes != null) {
                            val extractedApkBytes = ZipProjectHelper.extractApkFromArtifactZip(artifactZipBytes)
                            if (extractedApkBytes != null && extractedApkBytes.isNotEmpty()) {
                                savedApkFile = ZipProjectHelper.saveExtractedArtifactApk(
                                    context = applicationContext,
                                    buildId = currentRecord.id,
                                    appName = currentRecord.appName,
                                    extractedApkBytes = extractedApkBytes
                                )
                            }
                        }
                    }

                    // SEMPRE marca como SUCESSO quando o GitHub Actions concluiu com sucesso!
                    // Caso o APK ainda não tenha sido baixado agora, o botão "Baixar APK" fará o download sob demanda.
                    currentRecord = currentRecord.copy(
                        status = BuildStatus.SUCCESS,
                        progress = 1.0f,
                        statusStepMessage = "Compilação concluída",
                        apkDownloadUrl = targetArtifact?.archiveDownloadUrl,
                        apkLocalPath = savedApkFile?.absolutePath,
                        apkFileName = savedApkFile?.name ?: "${currentRecord.appName}-release.apk",
                        apkSizeBytes = savedApkFile?.length() ?: targetArtifact?.sizeInBytes ?: 0L,
                        errorSummary = null,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    dao.updateRecord(currentRecord)
                    supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
                    updateNotification("Compilação concluída: ${currentRecord.appName}")
                    return
                } else {
                    var detailedError = "Falha na compilação do aplicativo no GitHub Actions."
                    var extractedLogs = currentRecord.logsText

                    val failedJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.firstOrNull()
                    if (failedJob != null) {
                        try {
                            val logResp = gitHubApi.downloadJobLogs(authHeader, owner, repo, failedJob.id)
                            if (logResp.isSuccessful && logResp.body() != null) {
                                val rawLogText = logResp.body()!!.string()
                                val logLines = rawLogText.lines()
                                val tailLogs = logLines.takeLast(120).joinToString("\n")
                                extractedLogs = if (extractedLogs.isNotBlank()) {
                                    "$extractedLogs\n\n--- LOG DO ERRO NO GITHUB ACTIONS ---\n$tailLogs"
                                } else {
                                    "--- LOG DO ERRO NO GITHUB ACTIONS ---\n$tailLogs"
                                }

                                val keyError = logLines.reversed().firstOrNull { l ->
                                    val cl = l.lowercase(Locale.ROOT)
                                    cl.contains("failure:") ||
                                        cl.contains("what went wrong:") ||
                                        cl.contains("caused by:") ||
                                        cl.contains("erro crítico:") ||
                                        cl.contains("a versão mínima suportada") ||
                                        cl.contains("task '") ||
                                        cl.contains("e: ")
                                }
                                if (keyError != null) {
                                    detailedError = keyError.replace(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s*"""), "").trim()
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    failBuild(
                        record = currentRecord.copy(logsText = extractedLogs),
                        errorSummary = detailedError,
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }
            }

            delay(4500)
        }

        failBuild(
            record = currentRecord,
            errorSummary = "Tempo limite excedido na compilação.",
            supabaseAccessToken = supabaseAccessToken
        )
    }

    private suspend fun updateRecordState(
        record: BuildRecordEntity,
        status: String,
        progress: Float,
        stepMessage: String,
        supabaseAccessToken: String
    ): BuildRecordEntity {
        val updated = record.copy(
            status = status,
            progress = progress,
            statusStepMessage = stepMessage,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(updated)
        supabaseRepo.pushBuildRecordToSupabase(updated, supabaseAccessToken)
        return updated
    }

    private suspend fun failBuild(
        record: BuildRecordEntity,
        errorSummary: String,
        supabaseAccessToken: String
    ) {
        val failed = record.copy(
            status = BuildStatus.FAILED,
            statusStepMessage = "Falha na compilação",
            errorSummary = errorSummary,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(failed)
        supabaseRepo.pushBuildRecordToSupabase(failed, supabaseAccessToken)
        updateNotification("Falha na compilação: ${record.appName}")
    }

    private fun sanitizeZipNameToRepoName(zipFileName: String): String {
        val base = zipFileName
            .substringBeforeLast(".")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return base.ifEmpty { "gera-apk-project" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compilações Release (GERA APK)",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GERA APK • Release")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gera_apk_build_channel"
        private const val NOTIFICATION_ID = 4102
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_RECOMPILE = "extra_recompile"
        const val EXTRA_SUPABASE_ACCESS_TOKEN = "extra_supabase_access_token"

        fun enqueueBuild(
            context: Context,
            recordId: Long,
            isRecompile: Boolean = false,
            supabaseAccessToken: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_RECOMPILE, isRecompile)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                context.startService(intent)
            }
        }

        fun resumeActiveBuildsIfAny(
            context: Context,
            supabaseAccessToken: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, -1L)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
