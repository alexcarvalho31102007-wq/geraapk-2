package com.example.model

data class ZipProjectInfo(
    val fileName: String,
    val fileSizeBytes: Long,
    val totalFiles: Int,
    val kotlinCount: Int,
    val javaCount: Int,
    val xmlCount: Int,
    val gradleCount: Int,
    val detectedPackageName: String,
    val detectedAppName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val hasGradleWrapper: Boolean,
    val entryPaths: List<String>,
    val generatedWorkflowYaml: String,
    val rawZipBytes: ByteArray
) {
    val formattedSize: String
        get() {
            val kb = fileSizeBytes / 1024.0
            return if (kb >= 1024.0) {
                String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.1f KB", kb)
            }
        }
}

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class PipelineStep(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: StepStatus = StepStatus.PENDING,
    val durationLabel: String = ""
)

data class CompiledApkArtifact(
    val apkFileName: String,
    val apkFilePath: String,
    val fileSizeBytes: Long,
    val sha256Checksum: String,
    val workflowRunId: Long,
    val workflowHtmlUrl: String,
    val isLiveGitHubBuild: Boolean,
    val compiledAtFormatted: String,
    val packageName: String
) {
    val formattedSize: String
        get() {
            val kb = fileSizeBytes / 1024.0
            return if (kb >= 1024.0) {
                String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.1f KB", kb)
            }
        }
}

sealed class CompilerStage {
    data object Idle : CompilerStage()
    data class ProjectReady(val project: ZipProjectInfo) : CompilerStage()
    data class Compiling(
        val project: ZipProjectInfo,
        val steps: List<PipelineStep>,
        val progressFraction: Float,
        val currentActionText: String,
        val isLiveGitHubBuild: Boolean,
        val workflowRunUrl: String? = null
    ) : CompilerStage()
    data class Completed(
        val project: ZipProjectInfo,
        val steps: List<PipelineStep>,
        val artifact: CompiledApkArtifact,
        val isDownloadingApk: Boolean = false,
        val apkReadyOnDisk: Boolean = false,
        val savedUriMessage: String? = null
    ) : CompilerStage()
    data class Failed(
        val project: ZipProjectInfo?,
        val steps: List<PipelineStep>,
        val errorMessage: String
    ) : CompilerStage()
}
