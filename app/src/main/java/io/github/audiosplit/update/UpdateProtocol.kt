package io.github.audiosplit.update

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val assetName: String,
    val sha256: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val downloadSizeBytes: Long? = null,
)

sealed interface UpdateCheckResult {
    data class Available(val update: UpdateManifest) : UpdateCheckResult
    data class UpToDate(val versionName: String) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
}

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?) {
    val percent: Int? get() = totalBytes?.takeIf { it > 0 }
        ?.let { ((downloadedBytes * 100) / it).coerceIn(0, 100).toInt() }
}

sealed interface InstallResult {
    data object PermissionRequired : InstallResult
    data object InstallerOpened : InstallResult
}

class UpdateException(val code: String, message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

internal data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long?)
internal data class GitHubRelease(
    val tag: String,
    val url: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** The updater only accepts stable releases from this repository, never caller-supplied URLs. */
internal object UpdateProtocol {
    const val PACKAGE_NAME = "io.github.audiosplit"
    const val REPOSITORY = "GabrielRbro/audio-split"
    const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
    private val stableVersion = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
    private val apkName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val hash = Regex("[0-9a-fA-F]{64}")
    private val cdnHosts = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    fun parseRelease(text: String): GitHubRelease = invalidMetadata {
        val json = JSONObject(text)
        val draft = boolean(json, "draft")
        val prerelease = boolean(json, "prerelease")
        val tag = json.getString("tag_name")
        require(stableVersion.matches(tag.removePrefix("v"))) { "A versão do release não é estável." }
        val url = json.getString("html_url")
        val uri = secureUri(url)
        require(uri.host.equals("github.com", true)
            && uri.path == "/$REPOSITORY/releases/tag/$tag" && uri.rawQuery == null) {
            "O release não pertence ao repositório do Audio Split."
        }
        val array = json.getJSONArray("assets")
        val assets = List(array.length()) { index ->
            val asset = array.getJSONObject(index)
            val name = asset.getString("name")
            val address = asset.getString("browser_download_url")
            validateAssetUrl(address, tag, name)
            val size = asset.opt("size")?.takeUnless { it == JSONObject.NULL }
                ?.let { integer(it, "size") }
            require(size == null || size >= 0) { "Tamanho de arquivo inválido." }
            ReleaseAsset(name, address, size)
        }
        require(assets.map { it.name }.distinct().size == assets.size) { "Há arquivos duplicados no release." }
        GitHubRelease(tag, url, draft, prerelease, assets)
    }

    fun metadataAsset(release: GitHubRelease): ReleaseAsset =
        release.assets.singleOrNull { it.name == "update.json" }
            ?.also { requireMetadataSize(it.sizeBytes) }
            ?: throw UpdateException("INVALID_METADATA", "A versão publicada ainda não contém o arquivo de atualização. Tente novamente mais tarde.")

    fun parseManifest(text: String, release: GitHubRelease): UpdateManifest = invalidMetadata {
        val json = JSONObject(text)
        require(integer(json.get("schemaVersion"), "schemaVersion") == 1L) { "Formato de atualização incompatível." }
        val code = integer(json.get("versionCode"), "versionCode")
        require(code in 1..Int.MAX_VALUE.toLong()) { "Código de versão inválido." }
        val version = json.getString("versionName")
        require(version.length <= 100 && stableVersion.matches(version)) { "Nome de versão estável inválido." }
        require(release.tag.removePrefix("v") == version) { "A versão do manifesto difere do release." }
        val name = json.getString("assetName")
        require(apkName.matches(name)) { "Nome do APK inválido." }
        val checksum = json.getString("sha256")
        require(hash.matches(checksum)) { "Checksum inválido." }
        val asset = requireNotNull(release.assets.singleOrNull { it.name == name }) { "O APK informado não está no release." }
        require(asset.sizeBytes == null || asset.sizeBytes in 1..MAX_APK_BYTES) { "O APK excede o tamanho permitido." }
        validateAssetUrl(asset.url, release.tag, name)
        UpdateManifest(code, version, name, checksum.lowercase(Locale.ROOT), asset.url, release.url, asset.sizeBytes)
    }

    fun validateAssetUrl(address: String, tag: String, name: String) {
        val uri = secureUri(address)
        require(uri.host.equals("github.com", true) && uri.rawQuery == null
            && uri.path == "/$REPOSITORY/releases/download/$tag/$name"
            && !name.contains('/') && !name.contains('\\') && name !in setOf(".", "..")) {
            "O arquivo não pertence ao release esperado do Audio Split."
        }
    }

    fun validateTransferUrl(address: String) {
        val uri = secureUri(address)
        val host = uri.host.lowercase(Locale.ROOT)
        require(host in cdnHosts || (host == "github.com"
            && uri.path.startsWith("/$REPOSITORY/releases/download/"))) {
            "O servidor de download não é um servidor de releases do GitHub autorizado."
        }
    }

    fun verifyDigest(expected: String, actual: ByteArray) {
        if (!hash.matches(expected) || !MessageDigest.isEqual(hexBytes(expected), actual)) {
            throw UpdateException("HASH_MISMATCH", "O download não passou na verificação de integridade. Baixe novamente.")
        }
    }

    fun requireMetadataSize(bytes: Long?) {
        if (bytes != null && bytes > MAX_METADATA_BYTES) {
            throw UpdateException("INVALID_METADATA", "O arquivo de atualização excede o tamanho esperado.")
        }
    }

    private fun secureUri(address: String): URI {
        val uri = URI(address)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
            && uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443)
            && uri.normalize().path == uri.path) { "Endereço de atualização inválido." }
        return uri
    }

    private fun boolean(json: JSONObject, key: String): Boolean =
        (json.get(key) as? Boolean) ?: error("Campo $key inválido.")

    private fun integer(value: Any, field: String): Long {
        require(value is Number && Regex("[0-9]+").matches(value.toString())) { "Campo $field deve ser um inteiro." }
        return value.toString().toLongOrNull() ?: error("Campo $field fora do intervalo permitido.")
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private inline fun <T> invalidMetadata(body: () -> T): T = try {
        body()
    } catch (error: UpdateException) {
        throw error
    } catch (error: Exception) {
        throw UpdateException("INVALID_METADATA", "O GitHub retornou dados de atualização incompatíveis: ${error.message.orEmpty().take(180)}", error)
    }
}
