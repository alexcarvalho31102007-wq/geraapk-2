package com.example.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipSanitizer {

    const val SINGLE_WORKFLOW_FILENAME = "gera-apk-build.yml"
    const val SINGLE_WORKFLOW_PATH = ".github/workflows/$SINGLE_WORKFLOW_FILENAME"

    // Regex que detecta tokens literais em texto plano para evitar bloqueio HTTP 422
    // do GitHub Secret Scanning Push Protection ("Repository rule violations found: Secret detected in content")
    private val SECRET_PATTERNS = listOf(
        Regex("""gh[pousr]_[A-Za-z0-9_]{20,255}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,255}"""),
        Regex("""sk-[A-Za-z0-9]{20,255}""")
    )

    fun sanitizeZipForGradle9(rawBytes: ByteArray): ByteArray {
        return try {
            val entries = extractNormalizedFiles(rawBytes)
            if (entries.isEmpty()) return rawBytes

            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        } catch (_: Exception) {
            rawBytes
        }
    }

    /**
     * Detecta a versão do Android Gradle Plugin (AGP) presente nos arquivos do projeto.
     * Retorna a versão (ex: "9.1.1", "8.7.0") ou null se não encontrada.
     */
    fun detectAgpVersion(entries: Map<String, ByteArray>): String? {
        val tomlBytes = entries.entries.firstOrNull { it.key.endsWith("libs.versions.toml") }?.value
        if (tomlBytes != null) {
            val tomlText = tomlBytes.toString(Charsets.UTF_8)
            val match = Regex("""agp\s*=\s*["']([0-9.]+)["']""").find(tomlText)
            if (match != null) return match.groupValues[1]
        }

        for ((path, bytes) in entries) {
            val lower = path.lowercase(Locale.ROOT)
            if (lower.endsWith(".gradle.kts") || lower.endsWith(".gradle")) {
                val text = bytes.toString(Charsets.UTF_8)
                val match = Regex("""(?:com\.android\.tools\.build:gradle|com\.android\.application)[^0-9]*([0-9]+\.[0-9]+(?:\.[0-9]+)?)""").find(text)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Extrai e normaliza todos os arquivos do projeto Android:
     * - Remove 100% de arquivos dentro de `.github/` ou `workflows/` para impedir execuções duplicadas.
     * - Remove segredos literais (`ghp_...`, `github_pat_...`) de arquivos `.env`, `.env.example`,
     *   `.properties`, `.kt`, `.java`, `.xml` e `.gradle(.kts)` para impedir que o GitHub Push Protection
     *   rejeite o envio com erro HTTP 422 (`Secret detected in content`).
     * - Define a versão correta do Gradle Wrapper de acordo com o AGP do projeto (Gradle 9.3.1 para AGP 9.x,
     *   ou Gradle 8.11.1 para AGP 8.x/projetos tradicionais).
     */
    fun extractNormalizedFiles(rawBytes: ByteArray, depth: Int = 0): Map<String, ByteArray> {
        val rawEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory &&
                    cleanName.isNotEmpty() &&
                    !shouldIgnoreEntry(cleanName)
                ) {
                    rawEntries[cleanName] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (rawEntries.isEmpty()) return emptyMap()

        if (depth < 2 && rawEntries.size == 1) {
            val singleEntry = rawEntries.entries.first()
            if (singleEntry.key.lowercase(Locale.ROOT).endsWith(".zip")) {
                val innerExtracted = extractNormalizedFiles(singleEntry.value, depth + 1)
                if (innerExtracted.isNotEmpty()) {
                    return innerExtracted
                }
            }
        }

        val rootMarkerKey = rawEntries.keys.firstOrNull {
            val lower = it.lowercase(Locale.ROOT)
            lower == "settings.gradle.kts" || lower == "settings.gradle" ||
                lower.endsWith("/settings.gradle.kts") || lower.endsWith("/settings.gradle")
        } ?: rawEntries.keys.firstOrNull {
            val lower = it.lowercase(Locale.ROOT)
            lower == "gradlew" || lower.endsWith("/gradlew")
        }

        val commonPrefix = when {
            rootMarkerKey == null -> ""
            rootMarkerKey.contains("/") -> rootMarkerKey.substringBeforeLast("/") + "/"
            else -> ""
        }

        val detectedAgp = detectAgpVersion(rawEntries)
        val isAgp9 = detectedAgp?.startsWith("9.") == true

        val normalized = mutableMapOf<String, ByteArray>()
        for ((originalPath, bytes) in rawEntries) {
            val relPath = if (commonPrefix.isNotEmpty() && originalPath.startsWith(commonPrefix)) {
                originalPath.removePrefix(commonPrefix)
            } else {
                originalPath
            }
            val cleanRelPath = relPath.trimStart('/')
            if (cleanRelPath.isBlank() || shouldIgnoreEntry(cleanRelPath)) continue

            if (isAnyGitHubWorkflowOrFolder(cleanRelPath)) {
                continue
            }

            if (cleanRelPath.endsWith("gradle-wrapper.properties")) {
                val text = bytes.toString(Charsets.UTF_8)
                val targetGradleUrl = if (isAgp9) {
                    "https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip"
                } else if (text.contains("gradle-9.")) {
                    // Se não é AGP 9 mas o wrapper tinha gradle 9, rebaixa para 8.11.1 para não quebrar AGP 8
                    "https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
                } else if (text.contains("distributionUrl")) {
                    // Mantém a distribuição 8.x original do projeto do usuário
                    null
                } else {
                    "https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
                }

                val fixedText = if (targetGradleUrl != null) {
                    if (text.contains("distributionUrl")) {
                        text.replace(Regex("""distributionUrl=.*"""), "distributionUrl=$targetGradleUrl")
                    } else {
                        defaultGradleWrapperProperties(targetGradleUrl)
                    }
                } else {
                    text
                }
                normalized["gradle/wrapper/gradle-wrapper.properties"] = fixedText.toByteArray(Charsets.UTF_8)
            } else if (cleanRelPath == ".env" || cleanRelPath.endsWith("/.env")) {
                // Nunca envia arquivos .env reais para o GitHub para evitar vazamento e erro HTTP 422
                continue
            } else if (cleanRelPath == ".env.example" || cleanRelPath.endsWith("/.env.example")) {
                val scrubbedEnv = scrubSecretsFromEnvFile(bytes.toString(Charsets.UTF_8))
                normalized[cleanRelPath] = scrubbedEnv.toByteArray(Charsets.UTF_8)
            } else if (isScannableTextFile(cleanRelPath)) {
                val scrubbedText = scrubDetectedSecrets(bytes.toString(Charsets.UTF_8))
                normalized[cleanRelPath] = scrubbedText.toByteArray(Charsets.UTF_8)
            } else {
                normalized[cleanRelPath] = bytes
            }
        }

        if (!normalized.containsKey("gradle/wrapper/gradle-wrapper.properties")) {
            val defaultUrl = if (isAgp9) {
                "https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip"
            } else {
                "https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
            }
            normalized["gradle/wrapper/gradle-wrapper.properties"] =
                defaultGradleWrapperProperties(defaultUrl).toByteArray(Charsets.UTF_8)
        }

        if (!normalized.containsKey(".env.example")) {
            normalized[".env.example"] = defaultSafeEnvExample().toByteArray(Charsets.UTF_8)
        }

        return normalized
    }

    /**
     * Substitui qualquer valor real de chave dentro do `.env.example` por `PLACEHOLDER`
     * mantendo todas as chaves declaradas para que o `Secrets Gradle Plugin` gere os campos
     * no `BuildConfig` sem acionar o bloqueio HTTP 422 do GitHub Push Protection.
     */
    fun scrubSecretsFromEnvFile(content: String): String {
        val scrubbedLines = content.lineSequence().map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                line
            } else {
                val key = trimmed.substringBefore("=").trim()
                val rawVal = trimmed.substringAfter("=").trim()
                val safeVal = if (SECRET_PATTERNS.any { it.containsMatchIn(rawVal) }) {
                    "PLACEHOLDER"
                } else {
                    scrubDetectedSecrets(rawVal)
                }
                "$key=$safeVal"
            }
        }
        return scrubbedLines.joinToString("\n") + "\n"
    }

    fun scrubDetectedSecrets(content: String): String {
        var result = content
        for (regex in SECRET_PATTERNS) {
            result = regex.replace(result, "REDACTED_BY_GERA_APK")
        }
        return result
    }

    private fun isScannableTextFile(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".kt") ||
            lower.endsWith(".java") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".gradle") ||
            lower.endsWith(".gradle.kts") ||
            lower.endsWith(".properties") ||
            lower.endsWith(".toml") ||
            lower.endsWith(".json") ||
            lower.endsWith(".md") ||
            lower.endsWith(".txt")
    }

    fun isAnyGitHubWorkflowOrFolder(path: String): Boolean {
        val lower = "/${path.lowercase(Locale.ROOT).trimStart('/')}"
        return lower.startsWith("/.github/") ||
            lower.contains("/.github/") ||
            lower.contains("/workflows/")
    }

    private fun shouldIgnoreEntry(path: String): Boolean {
        val lower = "/${path.lowercase(Locale.ROOT).trimStart('/')}"
        return lower.contains("/__macosx/") ||
            lower.contains("/.git/") ||
            lower.contains("/.gradle/") ||
            lower.contains("/.idea/") ||
            lower.contains("/build/") ||
            lower.startsWith("/.github/") ||
            lower.contains("/.github/") ||
            lower.contains("/workflows/") ||
            lower.endsWith(".ds_store") ||
            lower.endsWith("/local.properties") ||
            lower.endsWith(".iml")
    }

    fun defaultSafeEnvExample(): String = """
        GEMINI_API_KEY=PLACEHOLDER
        GITHUB_TOKEN=PLACEHOLDER
        SUPABASE_URL=PLACEHOLDER
        SUPABASE_ANON_KEY=PLACEHOLDER
    """.trimIndent() + "\n"

    fun defaultGradleWrapperProperties(
        distributionUrl: String = "https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
    ): String = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=$distributionUrl
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """.trimIndent()
}
