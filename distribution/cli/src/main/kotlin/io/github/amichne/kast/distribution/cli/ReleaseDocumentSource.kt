package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.CharacterCodingException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BUILD_CATALOG_URL = "https://raw.githubusercontent.com/amichne/kast/developer-latest/builds.json"
private const val STABLE_RELEASE_URL = "https://api.github.com/repos/amichne/kast/releases/latest"
private const val RELEASE_READ_TIMEOUT_MILLIS = 15_000
private const val RELEASE_DOCUMENT_BYTES = 65_536
private val releaseJson = Json { ignoreUnknownKeys = true }

internal sealed interface ReleaseDocumentRead {
    data class Read(val text: String) : ReleaseDocumentRead

    data class Unavailable(val observation: ReleaseReadObservation.Unavailable) : ReleaseDocumentRead
}

internal fun readReleaseDocument(url: String): ReleaseDocumentRead =
    readReleaseDocumentObserved(url) { System.err.println(managementJson.encodeToString(it)) }

internal fun readReleaseDocumentObserved(
    url: String,
    observe: (ReleaseReadObservation) -> Unit,
): ReleaseDocumentRead {
    var stage = ReleaseReadStage.OPEN
    val result =
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            configureReleaseConnection(connection)
            try {
                stage = ReleaseReadStage.RESPONSE
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    unavailable(stage, ReleaseReadFailure.HTTP_REJECTED)
                else {
                    stage = ReleaseReadStage.BODY
                    readReleaseBody(connection)
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: MalformedURLException) {
            unavailable(stage, ReleaseReadFailure.URL_REJECTED)
        } catch (_: SSLException) {
            unavailable(stage, ReleaseReadFailure.TLS_FAILURE)
        } catch (_: SocketTimeoutException) {
            unavailable(stage, ReleaseReadFailure.TIMED_OUT)
        } catch (_: CharacterCodingException) {
            unavailable(stage, ReleaseReadFailure.INVALID_ENCODING)
        } catch (_: IOException) {
            unavailable(stage, ReleaseReadFailure.IO_FAILURE)
        } catch (_: IllegalArgumentException) {
            unavailable(stage, ReleaseReadFailure.INVALID_ARGUMENT)
        }
    observe(
        when (result) {
            is ReleaseDocumentRead.Read -> ReleaseReadObservation.Read(result.text.toByteArray().size)
            is ReleaseDocumentRead.Unavailable -> result.observation
        }
    )
    return result
}

private fun unavailable(stage: ReleaseReadStage, failure: ReleaseReadFailure) =
    ReleaseDocumentRead.Unavailable(ReleaseReadObservation.Unavailable(stage, failure))

private fun configureReleaseConnection(connection: HttpURLConnection) {
    connection.connectTimeout = RELEASE_READ_TIMEOUT_MILLIS
    connection.readTimeout = RELEASE_READ_TIMEOUT_MILLIS
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("Cache-Control", "no-cache")
    connection.setRequestProperty("Accept", "application/vnd.github+json")
}

private fun readReleaseBody(connection: HttpURLConnection): ReleaseDocumentRead {
    return connection.inputStream.use { input ->
        val bytes = input.readNBytes(RELEASE_DOCUMENT_BYTES + 1)
        if (bytes.size > RELEASE_DOCUMENT_BYTES)
            unavailable(ReleaseReadStage.BODY, ReleaseReadFailure.DOCUMENT_TOO_LARGE)
        else ReleaseDocumentRead.Read(bytes.decodeToString(throwOnInvalidSequence = true))
    }
}

@Serializable
private data class StableReleaseDocument(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
)

internal fun latestStable(read: (String) -> ReleaseDocumentRead): SelectionAdmission<StableVersion> {
    val raw =
        when (val result = read(STABLE_RELEASE_URL)) {
            is ReleaseDocumentRead.Read -> result.text
            is ReleaseDocumentRead.Unavailable ->
                return SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
        }
    return try {
        val release = releaseJson.decodeFromString<StableReleaseDocument>(raw)
        if (release.draft || release.prerelease || !release.tag.startsWith("v"))
            SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
        else StableVersion.parse(release.tag.removePrefix("v"))
    } catch (_: SerializationException) {
        SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID)
    }
}

internal fun captureMainCatalog(read: (String) -> ReleaseDocumentRead): SelectionAdmission<AdmittedBuildCatalog> =
    when (val result = read(BUILD_CATALOG_URL)) {
        is ReleaseDocumentRead.Read -> AdmittedBuildCatalog.decode(result.text)
        is ReleaseDocumentRead.Unavailable -> SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE)
    }
