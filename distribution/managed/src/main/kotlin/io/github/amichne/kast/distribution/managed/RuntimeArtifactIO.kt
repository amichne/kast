package io.github.amichne.kast.distribution.managed

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

sealed interface RuntimeArtifactAcquisition {
    data object Acquired : RuntimeArtifactAcquisition
    data class Rejected(val failure: RuntimeStoreFailure) : RuntimeArtifactAcquisition
}

fun interface RuntimeArtifactDownloader {
    /** Downloads one admitted manifest URI into the exact partial archive path. */
    fun download(source: URI, target: Path): RuntimeArtifactAcquisition
}

/** JDK HTTP adapter for the managed semantic-runtime source. */
object JdkRuntimeArtifactDownloader : RuntimeArtifactDownloader {
    override fun download(source: URI, target: Path): RuntimeArtifactAcquisition = try {
        val connection = source.toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return RuntimeArtifactAcquisition.Rejected(
                    RuntimeStoreFailure.ARTIFACT_UNAVAILABLE,
                )
            }
        }
        connection.getInputStream().use { input ->
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use(input::copyTo)
        }
        if (connection is HttpURLConnection) connection.disconnect()
        RuntimeArtifactAcquisition.Acquired
    } catch (_: IOException) {
        RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
    } catch (_: SecurityException) {
        RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.ARTIFACT_UNAVAILABLE)
    }
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest())
}
