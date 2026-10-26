package io.github.audiosplit.update

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateProtocolTest {
    private val repository = "https://github.com/GabrielRbro/audio-split"
    private val tag = "v0.2.0"
    private val apkName = "audio-split-0.2.0.apk"
    private val digest = MessageDigest.getInstance("SHA-256").digest("APK bytes".toByteArray())
    private val checksum = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun asset(name: String, size: Long = 500): JSONObject = JSONObject()
        .put("name", name)
        .put("browser_download_url", "$repository/releases/download/$tag/$name")
        .put("size", size)

    private fun releaseJson(): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("html_url", "$repository/releases/tag/$tag")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(asset("update.json")).put(asset(apkName, 9_000_000)))

    private fun release(): GitHubRelease = UpdateProtocol.parseRelease(releaseJson().toString())

    private fun manifestJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("versionCode", 3)
        .put("versionName", "0.2.0")
        .put("assetName", apkName)
        .put("sha256", checksum)

    private fun rejects(code: String, operation: () -> Unit) {
        try {
            operation()
            fail("Expected UpdateException with code $code")
        } catch (error: UpdateException) {
            assertEquals(code, error.code)
        }
    }

    private fun rejectsAddress(address: String) {
        try {
            UpdateProtocol.validateTransferUrl(address)
            fail("Accepted an unauthorized URL: $address")
        } catch (_: IllegalArgumentException) {
            // Invalid source must be rejected before opening a connection.
        }
    }

    @Test fun stableReleaseDerivesDownloadFromItsOwnNamedAsset() {
        val release = release()
        val update = UpdateProtocol.parseManifest(manifestJson().toString(), release)
        assertEquals(3L, update.versionCode)
        assertEquals("0.2.0", update.versionName)
        assertEquals(checksum, update.sha256)
        assertEquals("$repository/releases/download/$tag/$apkName", update.downloadUrl)
        assertEquals("$repository/releases/tag/$tag", update.releaseUrl)
        assertEquals(9_000_000L, update.downloadSizeBytes)
        assertEquals("update.json", UpdateProtocol.metadataAsset(release).name)
    }

    @Test fun arbitraryManifestUrlCannotOverrideTheReleaseAsset() {
        val json = manifestJson().put("downloadUrl", "https://attacker.example/other.apk")
        assertEquals("$repository/releases/download/$tag/$apkName",
            UpdateProtocol.parseManifest(json.toString(), release()).downloadUrl)
    }

    @Test fun releaseMustBelongToTheFixedRepository() {
        val json = releaseJson().put("html_url", "https://github.com/other/audio-split/releases/tag/$tag")
        rejects("INVALID_METADATA") { UpdateProtocol.parseRelease(json.toString()) }
    }

    @Test fun releaseCannotSmuggleAnAssetFromAnotherRepository() {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(1)
            .put("browser_download_url", "https://github.com/other/audio-split/releases/download/$tag/$apkName")
        rejects("INVALID_METADATA") { UpdateProtocol.parseRelease(json.toString()) }
    }

    @Test fun ambiguousDuplicateAssetsAreRejected() {
        val json = releaseJson()
        json.getJSONArray("assets").put(asset(apkName))
        rejects("INVALID_METADATA") { UpdateProtocol.parseRelease(json.toString()) }
    }

    @Test fun missingMetadataHasAnActionableError() {
        val json = releaseJson().put("assets", JSONArray().put(asset(apkName)))
        rejects("INVALID_METADATA") {
            UpdateProtocol.metadataAsset(UpdateProtocol.parseRelease(json.toString()))
        }
    }

    @Test fun oversizedMetadataIsRejectedBeforeDownloadingIt() {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(0)
            .put("size", UpdateProtocol.MAX_METADATA_BYTES + 1)
        rejects("INVALID_METADATA") {
            UpdateProtocol.metadataAsset(UpdateProtocol.parseRelease(json.toString()))
        }
    }

    @Test fun manifestMustAgreeWithReleaseVersionAndContainItsApk() {
        rejects("INVALID_METADATA") {
            UpdateProtocol.parseManifest(manifestJson().put("versionName", "0.3.0").toString(), release())
        }
        rejects("INVALID_METADATA") {
            UpdateProtocol.parseManifest(manifestJson().put("assetName", "missing.apk").toString(), release())
        }
    }

    @Test fun unknownSchemaAndNonIntegerVersionCodesAreRejected() {
        rejects("INVALID_METADATA") {
            UpdateProtocol.parseManifest(manifestJson().put("schemaVersion", 2).toString(), release())
        }
        for (value in listOf<Any>("3", 3.5, 0, -1, Int.MAX_VALUE.toLong() + 1)) {
            rejects("INVALID_METADATA") {
                UpdateProtocol.parseManifest(manifestJson().put("versionCode", value).toString(), release())
            }
        }
    }

    @Test fun apkNamesCannotTraverseDirectoriesAndRequireApkExtension() {
        for (name in listOf("../update.apk", "dir/update.apk", "dir\\update.apk", "update.zip")) {
            rejects("INVALID_METADATA") {
                UpdateProtocol.parseManifest(manifestJson().put("assetName", name).toString(), release())
            }
        }
    }

    @Test fun oversizeAndEmptyApksAreRejected() {
        for (size in listOf(0L, UpdateProtocol.MAX_APK_BYTES + 1)) {
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(1).put("size", size)
            rejects("INVALID_METADATA") {
                UpdateProtocol.parseManifest(manifestJson().toString(), UpdateProtocol.parseRelease(json.toString()))
            }
        }
    }

    @Test fun checksumsAcceptHexCaseButRejectMalformedAndMismatchedBytes() {
        val update = UpdateProtocol.parseManifest(manifestJson().put("sha256", checksum.uppercase()).toString(), release())
        assertEquals(checksum, update.sha256)
        UpdateProtocol.verifyDigest(update.sha256, digest)
        rejects("HASH_MISMATCH") { UpdateProtocol.verifyDigest(checksum, byteArrayOf(1, 2, 3)) }
        rejects("INVALID_METADATA") {
            UpdateProtocol.parseManifest(manifestJson().put("sha256", "bad-hash").toString(), release())
        }
    }

    @Test fun githubAssetRedirectsSupportSignedCdnUrls() {
        UpdateProtocol.validateTransferUrl("$repository/releases/download/$tag/$apkName")
        UpdateProtocol.validateTransferUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123?sig=abc")
        UpdateProtocol.validateTransferUrl("https://objects.githubusercontent.com/github-production-release-asset/123?sig=abc")
    }

    @Test fun redirectCannotDowngradeHttpsOrLeaveAuthorizedHosts() {
        for (address in listOf(
            "http://github.com/GabrielRbro/audio-split/releases/download/$tag/$apkName",
            "https://github.com.attacker.example/GabrielRbro/audio-split/releases/download/$tag/$apkName",
            "https://github.com/other/audio-split/releases/download/$tag/$apkName",
            "https://attacker.example/$apkName",
            "https://user:pass@release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com:444/file",
            "https://release-assets.githubusercontent.com/file#fragment",
            "https://github.com/GabrielRbro/audio-split/releases/download/../other.apk",
        )) rejectsAddress(address)
    }

    @Test fun releaseBooleanFlagsRequireActualBooleans() {
        val json = releaseJson().put("draft", "false")
        rejects("INVALID_METADATA") { UpdateProtocol.parseRelease(json.toString()) }
        assertTrue(UpdateProtocol.parseRelease(releaseJson().put("prerelease", true).toString()).prerelease)
    }

    @Test fun progressCanRepresentAnUnknownLengthAndNeverExceedsOneHundred() {
        assertNull(DownloadProgress(10, null).percent)
        assertNull(DownloadProgress(10, 0).percent)
        assertEquals(25, DownloadProgress(25, 100).percent)
        assertEquals(100, DownloadProgress(101, 100).percent)
        assertNotNull(DownloadProgress(100, 100).percent)
    }
}
