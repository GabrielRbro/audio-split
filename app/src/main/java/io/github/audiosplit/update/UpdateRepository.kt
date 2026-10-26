package io.github.audiosplit.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.audiosplit.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Public GitHub releases only. The Android package installer always asks the user to confirm. */
class UpdateRepository(context: Context) {
    private val application = context.applicationContext ?: context
    private val downloadMutex = Mutex()
    private val directory: File get() = File(application.cacheDir, "updates")

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        networkFailure {
            val body = text(UpdateProtocol.LATEST_API, MAX_RELEASE_JSON, apiRequest = true, missingAllowed = true)
                ?: return@networkFailure UpdateCheckResult.NoRelease
            val release = UpdateProtocol.parseRelease(body)
            if (release.draft || release.prerelease) return@networkFailure UpdateCheckResult.NoRelease
            val metadata = UpdateProtocol.metadataAsset(release)
            val manifest = UpdateProtocol.parseManifest(requireNotNull(text(metadata.url, UpdateProtocol.MAX_METADATA_BYTES)), release)
            if (manifest.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                UpdateCheckResult.UpToDate(manifest.versionName)
            } else {
                UpdateCheckResult.Available(manifest)
            }
        }
    }

    /** Progress callbacks run on Dispatchers.IO; StateFlow.update is safe for observing them. */
    suspend fun download(update: UpdateManifest, onProgress: (DownloadProgress) -> Unit = {}): File =
        downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                networkFailure {
                    if (update.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                        throw UpdateException("NOT_NEWER", "Esta versão já está instalada ou é anterior à versão atual.")
                    }
                    UpdateProtocol.validateTransferUrl(update.downloadUrl)
                    check(directory.mkdirs() || directory.isDirectory) { "Não foi possível preparar o download." }
                    val partial = File(directory, "update.part.apk")
                    val target = File(directory, "update.apk")
                    partial.delete()
                    val connection = open(update.downloadUrl, apiRequest = false)
                    try {
                        requireSuccess(connection)
                        val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.downloadSizeBytes
                        if (total != null && total > UpdateProtocol.MAX_APK_BYTES) {
                            throw UpdateException("TOO_LARGE", "O APK excede o tamanho permitido.")
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var downloaded = 0L
                        var notifiedAt = 0L
                        onProgress(DownloadProgress(0, total))
                        connection.inputStream.use { input ->
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    downloaded += count
                                    if (downloaded > UpdateProtocol.MAX_APK_BYTES) {
                                        throw UpdateException("TOO_LARGE", "O download excedeu o tamanho permitido.")
                                    }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    val now = System.nanoTime()
                                    if (now - notifiedAt >= 200_000_000L) {
                                        onProgress(DownloadProgress(downloaded, total))
                                        notifiedAt = now
                                    }
                                }
                            }
                        }
                        if (downloaded == 0L || (total != null && downloaded != total)) {
                            throw UpdateException("INCOMPLETE_DOWNLOAD", "O download ficou incompleto. Tente novamente.")
                        }
                        UpdateProtocol.verifyDigest(update.sha256, digest.digest())
                        validatePackage(partial, update.versionCode, update.versionName)
                        currentCoroutineContext().ensureActive()
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        onProgress(DownloadProgress(downloaded, downloaded))
                        target
                    } finally {
                        connection.disconnect()
                        partial.delete()
                    }
                }
            }
        }

    /** Call after restoring any active routing session; this method never uses Shizuku. */
    fun installOrOpenSettings(file: File): InstallResult {
        val expected = File(directory, "update.apk").canonicalFile
        if (file.canonicalFile != expected || !file.isFile) {
            throw UpdateException("MISSING_DOWNLOAD", "Baixe a atualização antes de instalar.")
        }
        validatePackage(file)
        try {
            if (!application.packageManager.canRequestPackageInstalls()) {
                application.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${application.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return InstallResult.PermissionRequired
            }
            val uri = FileProvider.getUriForFile(application, "${application.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Atualização do Audio Split", uri) }
            application.startActivity(intent)
            return InstallResult.InstallerOpened
        } catch (error: Exception) {
            throw UpdateException("INSTALL_UNAVAILABLE", "Não foi possível abrir o instalador do Android. Tente novamente.", error)
        }
    }

    private fun validatePackage(file: File, expectedCode: Long? = null, expectedName: String? = null) {
        val manager = application.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("INVALID_PACKAGE", "O arquivo baixado não é um APK válido.")
        if (candidate.packageName != UpdateProtocol.PACKAGE_NAME
            || candidate.longVersionCode <= BuildConfig.VERSION_CODE.toLong()
            || (expectedCode != null && candidate.longVersionCode != expectedCode)
            || (expectedName != null && candidate.versionName != expectedName)) {
            throw UpdateException("INVALID_PACKAGE", "O APK não corresponde à atualização do Audio Split esperada.")
        }
        val installed = manager.getPackageInfo(application.packageName, flags)
        if (signers(candidate) != signers(installed)) {
            throw UpdateException("SIGNATURE_MISMATCH", "Esta versão usa uma assinatura diferente da instalação atual. Será necessária uma troca manual uma única vez; a atualização foi cancelada.")
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val certificates = info.signingInfo?.apkContentsSigners
            ?.takeIf { it.isNotEmpty() }
            ?: throw UpdateException("INVALID_SIGNATURE", "Não foi possível verificar a assinatura do aplicativo.")
        return certificates.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
    }

    private fun text(address: String, limit: Int, apiRequest: Boolean = false, missingAllowed: Boolean = false): String? {
        val connection = open(address, apiRequest)
        try {
            if (missingAllowed && connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            requireSuccess(connection)
            if (connection.contentLengthLong > limit) {
                throw UpdateException("INVALID_METADATA", "A resposta do GitHub excede o tamanho esperado.")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) {
                        throw UpdateException("INVALID_METADATA", "A resposta do GitHub excede o tamanho esperado.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, apiRequest: Boolean): HttpURLConnection {
        var current = address
        repeat(6) {
            if (apiRequest) {
                if (current != UpdateProtocol.LATEST_API) {
                    throw UpdateException("INVALID_SOURCE", "O endereço da consulta de atualização mudou.")
                }
            } else {
                try { UpdateProtocol.validateTransferUrl(current) } catch (error: Exception) {
                    throw UpdateException("INVALID_SOURCE", "O download foi redirecionado para um endereço não autorizado.", error)
                }
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AudioSplit/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", if (apiRequest) "application/vnd.github+json" else "application/octet-stream")
                if (apiRequest) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val status = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw UpdateException("INVALID_SOURCE", "O GitHub retornou um redirecionamento incompleto.")
            current = URL(URL(current), location).toExternalForm()
        }
        throw UpdateException("INVALID_SOURCE", "O download teve redirecionamentos demais. Tente novamente mais tarde.")
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        if (status == 429 || (status == 403 && connection.getHeaderField("X-RateLimit-Remaining") == "0")) {
            throw UpdateException("RATE_LIMIT", "O GitHub limitou as consultas. Aguarde alguns minutos e tente novamente.")
        }
        throw UpdateException("HTTP_ERROR", "Não foi possível acessar a atualização no GitHub (HTTP $status). Tente novamente mais tarde.")
    }

    private inline fun <T> networkFailure(body: () -> T): T = try {
        body()
    } catch (error: CancellationException) {
        throw error
    } catch (error: UpdateException) {
        throw error
    } catch (error: IOException) {
        throw UpdateException("NETWORK", "Não foi possível concluir a conexão com o GitHub. Confira a internet e tente novamente.", error)
    } catch (error: Exception) {
        throw UpdateException("UPDATE_ERROR", "Não foi possível preparar a atualização. Tente novamente.", error)
    }

    companion object {
        private const val MAX_RELEASE_JSON = 1024 * 1024
        private const val APK_MIME = "application/vnd.android.package-archive"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
