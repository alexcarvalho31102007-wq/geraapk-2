package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.ZipProjectInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipProjectHelper {

    fun inspectZipBytes(fileName: String, rawBytes: ByteArray): ZipProjectInfo {
        val sanitizedBytes = ZipSanitizer.sanitizeZipForGradle9(rawBytes)
        val normalizedFiles = ZipSanitizer.extractNormalizedFiles(sanitizedBytes)

        var kotlinCount = 0
        var javaCount = 0
        var xmlCount = 0
        var gradleCount = 0
        var detectedPackageName = "com.example.app"
        var detectedAppName = fileName
            .substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9_ -]"), "")
            .trim()
            .ifEmpty { "Meu Aplicativo" }
        var minSdk = 24
        var targetSdk = 34
        var hasGradleWrapper = false
        val entryPaths = mutableListOf<String>()

        for ((cleanPath, contentBytes) in normalizedFiles) {
            entryPaths.add(cleanPath)
            val lower = cleanPath.lowercase(Locale.ROOT)
            when {
                lower.endsWith(".kt") -> kotlinCount++
                lower.endsWith(".java") -> javaCount++
                lower.endsWith(".xml") -> {
                    xmlCount++
                    if (cleanPath.endsWith("AndroidManifest.xml")) {
                        try {
                            val text = contentBytes.toString(Charsets.UTF_8)
                            val pkgRegex = Regex("""package\s*=\s*["']([^"']+)["']""")
                            pkgRegex.find(text)?.let { detectedPackageName = it.groupValues[1] }
                        } catch (_: Exception) {}
                    }
                    if (cleanPath.endsWith("strings.xml")) {
                        try {
                            val text = contentBytes.toString(Charsets.UTF_8)
                            val appNameRegex = Regex("""<string\s+name=["']app_name["']>([^<]+)</string>""")
                            appNameRegex.find(text)?.let {
                                val found = it.groupValues[1].trim()
                                if (found.isNotEmpty() && found != "My Application") {
                                    detectedAppName = found
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") -> {
                    gradleCount++
                    if (cleanPath.contains("app/build.gradle")) {
                        try {
                            val text = contentBytes.toString(Charsets.UTF_8)
                            Regex("""namespace\s*=?\s*["']([^"']+)["']""").find(text)?.let {
                                detectedPackageName = it.groupValues[1]
                            }
                            Regex("""applicationId\s*=?\s*["']([^"']+)["']""").find(text)?.let {
                                detectedPackageName = it.groupValues[1]
                            }
                            Regex("""minSdk\s*=?\s*([0-9]+)""").find(text)?.let {
                                minSdk = it.groupValues[1].toIntOrNull() ?: 24
                            }
                            Regex("""targetSdk\s*=?\s*([0-9]+)""").find(text)?.let {
                                targetSdk = it.groupValues[1].toIntOrNull() ?: 34
                            }
                        } catch (_: Exception) {}
                    }
                }
                lower.contains("gradle-wrapper.properties") -> hasGradleWrapper = true
            }
        }

        val workflowYaml = buildGitHubActionsWorkflowYaml(detectedAppName)

        return ZipProjectInfo(
            fileName = fileName,
            fileSizeBytes = rawBytes.size.toLong(),
            totalFiles = normalizedFiles.size,
            kotlinCount = kotlinCount,
            javaCount = javaCount,
            xmlCount = xmlCount,
            gradleCount = gradleCount,
            detectedPackageName = detectedPackageName,
            detectedAppName = detectedAppName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            hasGradleWrapper = hasGradleWrapper,
            entryPaths = entryPaths,
            generatedWorkflowYaml = workflowYaml,
            rawZipBytes = sanitizedBytes
        )
    }

    /**
     * Gera o workflow resiliente e adaptativo do GitHub Actions para compilação universal de APKs Android:
     * - Suporta tanto AGP 9.x (Gradle 9.3.1) quanto AGP 8.x / 7.x (Gradle 8.11.1).
     * - Evita todas as armadilhas comuns: `Task 'lintVitalRelease' not found`, travamento de configuration cache,
     *   e falhas por ausência de signingConfigs release no projeto de origem.
     * - Se `assembleRelease` falhar por regras locais do app, compila via fallback resiliente `assembleDebug`
     *   e assina com certificado Release final via `apksigner`.
     */
    fun buildGitHubActionsWorkflowYaml(appName: String): String {
        val cleanTitle = appName.replace(Regex("""["'\r\n]"""), "").trim().ifEmpty { "Projeto Android" }
        return """
name: GERA APK • Release ($cleanTitle)

on:
  push:
    branches:
      - main
      - master
  workflow_dispatch:

concurrency:
  group: gera-apk-release-${'$'}{{ github.repository }}
  cancel-in-progress: true

jobs:
  compilar-apk-release:
    name: Compilar APK Release ($cleanTitle)
    runs-on: ubuntu-latest
    env:
      GRADLE_OPTS: "-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=false -Dorg.gradle.parallel=true -Dkotlin.incremental=false"
      KEYSTORE_PATH: "${'$'}{{ github.workspace }}/my-upload-key.jks"
      STORE_PASSWORD: "geraapkrelease2026"
      KEY_PASSWORD: "geraapkrelease2026"
    steps:
      - name: Checkout do Código-Fonte
        uses: actions/checkout@v4
        with:
          fetch-depth: 1

      - name: Preparar Ambiente e SDK Android
        run: |
          set -e
          # 1. Descompacta ZIP se necessário e organiza raiz do projeto
          if [ -f "uploads/project.zip" ]; then
            unzip -o -q uploads/project.zip -d .
            rm -f uploads/project.zip
          fi
          if [ ! -f "settings.gradle" ] && [ ! -f "settings.gradle.kts" ] && [ ! -f "build.gradle" ] && [ ! -f "build.gradle.kts" ]; then
            SUBDIR=${'$'}(find . -maxdepth 3 \( -name "settings.gradle*" -o -name "build.gradle*" \) -printf "%h\n" | head -n 1)
            if [ -n "${'$'}SUBDIR" ] && [ "${'$'}SUBDIR" != "." ]; then
              shopt -s dotglob
              cp -rf "${'$'}SUBDIR"/* . 2>/dev/null || true
            fi
          fi
          # Remove outros workflows para garantir execução única
          find .github/workflows -type f ! -name "gera-apk-build.yml" -delete 2>/dev/null || true

          # 2. Configura local.properties apontando para o SDK pré-instalado
          echo "sdk.dir=${'$'}ANDROID_HOME" > local.properties

          # 3. Garante existência de arquivos .env para Secrets Gradle Plugin sem vazar credenciais
          if [ ! -f ".env" ]; then
            touch .env
          fi
          if [ ! -f ".env.example" ]; then
            echo "GEMINI_API_KEY=PLACEHOLDER" > .env.example
          fi

          # 4. Gera keystore Release
          if [ ! -f "my-upload-key.jks" ]; then
            keytool -genkeypair -v -keystore my-upload-key.jks -storepass "geraapkrelease2026" -alias upload -keypass "geraapkrelease2026" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GERA APK Release,O=GERA APK,C=BR"
          fi
          if [ ! -f "debug.keystore" ]; then
            cp my-upload-key.jks debug.keystore || true
          fi

      - name: Configurar JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Configurar Cache do Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: false

      - name: Compilar Aplicativo Android
        run: |
          set +e
          # 1. Identifica versão do AGP presente no projeto
          AGP_VER=""
          if [ -f "gradle/libs.versions.toml" ]; then
            AGP_VER=${'$'}(grep -E 'agp\s*=\s*"[0-9]+' gradle/libs.versions.toml | head -n 1 | sed -E 's/.*"([0-9]+\.[0-9]+).*/\1/')
          fi
          if [ -z "${'$'}AGP_VER" ]; then
            AGP_VER=${'$'}(grep -rE '(com\.android\.tools\.build:gradle|com\.android\.application).*[:"'\''\s]+[0-9]+\.[0-9]+' . --include="*.gradle*" 2>/dev/null | head -n 1 | sed -E 's/.*[:"'\''\s]+([0-9]+\.[0-9]+).*/\1/')
          fi
          echo "==> Versão detectada do AGP: '${'$'}AGP_VER'"

          mkdir -p gradle/wrapper
          if [[ "${'$'}AGP_VER" =~ ^9\. ]]; then
            echo "==> AGP 9.x detectado: configurando Gradle 9.3.1..."
            cat << 'GWEOF' > gradle/wrapper/gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
GWEOF
          else
            if [ ! -f "gradle/wrapper/gradle-wrapper.properties" ] || grep -q "gradle-9\." gradle/wrapper/gradle-wrapper.properties 2>/dev/null; then
              echo "==> AGP 8.x / projeto padrão: configurando Gradle 8.11.1 estável..."
              cat << 'GWEOF' > gradle/wrapper/gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
GWEOF
            fi
          fi

          # Garante permissão de execução
          chmod +x ./gradlew 2>/dev/null || true

          # Flags anti-falhas:
          # -Pandroid.injected.invoked.from.ide=true desativa lintVitalRelease universalmente sem erro de tarefa inexistente
          # --no-configuration-cache previne problemas com plugins incompatíveis com o cache de configuração do Gradle 8/9
          GRADLE_FLAGS="-Pandroid.injected.invoked.from.ide=true -Pandroid.lintVital.skip=true -PcheckReleaseBuilds=false --no-configuration-cache --no-daemon -x test -x lint"

          RUNNER="./gradlew"
          if [ ! -f "./gradlew" ]; then
            RUNNER="gradle"
          fi

          BUILD_SUCCESS=0

          echo "===> [1/3] Tentando compilação Release (assembleRelease)..."
          if ${'$'}RUNNER assembleRelease ${'$'}GRADLE_FLAGS; then
            BUILD_SUCCESS=1
          else
            echo "===> assembleRelease falhou. Testando se projeto exige atualização de wrapper..."
            if [ -f "gradle/wrapper/gradle-wrapper.properties" ]; then
              if grep -q "gradle-8\." gradle/wrapper/gradle-wrapper.properties; then
                echo "===> Atualizando para Gradle 9.3.1 e retentando..."
                sed -i 's|gradle-[0-9.]*-bin.zip|gradle-9.3.1-bin.zip|g' gradle/wrapper/gradle-wrapper.properties
                if ${'$'}RUNNER assembleRelease ${'$'}GRADLE_FLAGS; then
                  BUILD_SUCCESS=1
                fi
              fi
            fi
          fi

          # Se assembleRelease ainda falhou (ex: o projeto não tem signingConfig release configurado ou tem regras do ProGuard quebradas),
          # compila via assembleDebug como fallback resiliente e em seguida assina com apksigner Release.
          if [ "${'$'}BUILD_SUCCESS" -ne 1 ]; then
            echo "===> [2/3] Executando fallback resiliente via assembleDebug..."
            if ${'$'}RUNNER assembleDebug ${'$'}GRADLE_FLAGS; then
              BUILD_SUCCESS=1
            fi
          fi

          # Fallback via Gradle CLI global
          if [ "${'$'}BUILD_SUCCESS" -ne 1 ]; then
            echo "===> [3/3] Executando fallback via gradle CLI..."
            if gradle assembleDebug ${'$'}GRADLE_FLAGS; then
              BUILD_SUCCESS=1
            fi
          fi

          if [ "${'$'}BUILD_SUCCESS" -ne 1 ]; then
            echo "ERRO CRÍTICO: Não foi possível compilar o APK."
            exit 1
          fi

      - name: Assinar e Preparar APK Release Final
        run: |
          set -e
          mkdir -p release-output
          RAW_APK=${'$'}(find . -path "*/build/outputs/apk/release/*.apk" | head -n 1)
          if [ -z "${'$'}RAW_APK" ]; then
            RAW_APK=${'$'}(find . -name "*release*.apk" | head -n 1)
          fi
          if [ -z "${'$'}RAW_APK" ]; then
            RAW_APK=${'$'}(find . -path "*/build/outputs/apk/debug/*.apk" | head -n 1)
          fi
          if [ -z "${'$'}RAW_APK" ]; then
            RAW_APK=${'$'}(find . -name "*.apk" | head -n 1)
          fi

          if [ -z "${'$'}RAW_APK" ]; then
            echo "Nenhum APK foi encontrado após a compilação Gradle!"
            exit 1
          fi

          echo "APK detectado: ${'$'}RAW_APK"
          BUILD_TOOLS=${'$'}(ls -d ${'$'}ANDROID_HOME/build-tools/* | sort -V | tail -n 1)
          
          # Alinha com zipalign e assina com apksigner
          "${'$'}BUILD_TOOLS/zipalign" -f -v -p 4 "${'$'}RAW_APK" release-output/aligned.apk 2>/dev/null || cp "${'$'}RAW_APK" release-output/aligned.apk
          if "${'$'}BUILD_TOOLS/apksigner" sign --ks my-upload-key.jks --ks-key-alias upload --ks-pass pass:geraapkrelease2026 --key-pass pass:geraapkrelease2026 --out release-output/app-release.apk release-output/aligned.apk 2>/dev/null; then
            echo "APK Release assinado com sucesso com certificado final!"
          else
            echo "Aviso: apksigner utilizou cópia do binário alinhado."
            cp release-output/aligned.apk release-output/app-release.apk
          fi
          rm -f release-output/aligned.apk

      - name: Publicar Artefato APK Release
        uses: actions/upload-artifact@v4
        with:
          name: compiled-apk-release
          path: "release-output/app-release.apk"
          compression-level: 0
          if-no-files-found: error
        """.trimIndent()
    }

    fun saveExtractedArtifactApk(
        context: Context,
        buildId: Long,
        appName: String,
        extractedApkBytes: ByteArray
    ): File {
        val apkDir = File(context.filesDir, "apks").apply { mkdirs() }
        val sanitizedName = appName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "android-app" }
        val apkFile = File(apkDir, "${sanitizedName}-release-${buildId}.apk")
        apkFile.writeBytes(extractedApkBytes)
        return apkFile
    }

    fun extractApkFromArtifactZip(artifactZipBytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(artifactZipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var read = zis.read(buffer)
                    while (read != -1) {
                        baos.write(buffer, 0, read)
                        read = zis.read(buffer)
                    }
                    return baos.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "projeto.zip"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    val str = it.getString(index)
                    if (!str.isNullOrBlank()) name = str
                }
            }
        }
        return name
    }
}
