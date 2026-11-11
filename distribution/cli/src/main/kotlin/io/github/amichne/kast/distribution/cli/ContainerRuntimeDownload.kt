package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ContainerStage {
    DOWNLOAD,
    ARCHIVE_ADMISSION,
    RUNTIME_PUBLICATION,
    RUNTIME_METADATA,
    RUNTIME_SEALING,
    RUNTIME_RENAME,
    FILESYSTEM_IO,
    CREATION,
    PREPARATION,
    SHUTDOWN,
}

@Serializable
internal enum class ContainerStageOutcome {
    STARTED,
    COMPLETED,
    REJECTED,
}

@Serializable
internal data class ContainerProgress(
    val stage: ContainerStage,
    val outcome: ContainerStageOutcome,
    val component: String = "kast-container",
)

internal fun containerProgress(stage: ContainerStage, outcome: ContainerStageOutcome) {
    System.err.println(containerJson.encodeToString(ContainerProgress(stage, outcome)))
}

private const val RUNTIME_DOWNLOAD_MAXIMUM_BYTES = 4_294_967_296L
private const val RUNTIME_DOWNLOAD_TIMEOUT_MINUTES = 20L
private const val RUNTIME_DOWNLOAD_BUFFER_BYTES = 65_536
private const val RUNTIME_CHECKSUM_BYTES = 1024

/** Publication owns this exact name. Missing targets reject; donor assembly never runs on a user's machine. */
internal fun ensureContainerRuntime(
    store: ContainerStore,
    version: String,
): Refinement<AdmittedContainerRuntime, ContainerFailure> {
    val installed = store.runtimes.resolve("$version-${store.platform.publicName}")
    if (Files.exists(installed, NOFOLLOW_LINKS))
        return AdmittedContainerRuntime.read(installed, version, store.platform)
    containerProgress(ContainerStage.DOWNLOAD, ContainerStageOutcome.STARTED)
    val result =
        try {
            fetchContainerRuntime(store, version)
        } catch (_: IOException) {
            Refinement.Rejected(ContainerFailure.DOWNLOAD_FAILED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        }
    containerProgress(
        ContainerStage.DOWNLOAD,
        if (result is Refinement.Refined) ContainerStageOutcome.COMPLETED else ContainerStageOutcome.REJECTED,
    )
    return result
}

private fun fetchContainerRuntime(
    store: ContainerStore,
    version: String,
): Refinement<AdmittedContainerRuntime, ContainerFailure> {
    val name = "kast-runtime-v$version-${store.platform.publicName}.zip"
    val base = "https://github.com/amichne/kast/releases/download/v$version/"
    val client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    val expected =
        when (val digest = downloadRuntimeDigest(client, URI.create(base + name + ".sha256"), name)) {
            is Refinement.Rejected -> return digest
            is Refinement.Refined -> digest.value
        }
    val temporary = Files.createTempFile("kast-runtime-", ".zip")
    return try {
        when (val fetched = downloadRuntime(client, URI.create(base + name), temporary)) {
            is Refinement.Rejected -> fetched
            is Refinement.Refined ->
                importContainerRuntime(temporary, expected, store.runtimes, version, store.platform)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun downloadRuntimeDigest(client: HttpClient, uri: URI, name: String): Refinement<String, ContainerFailure> {
    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    return response.body().use { input ->
        val bytes = input.readNBytes(RUNTIME_CHECKSUM_BYTES + 1)
        if (response.statusCode() != HTTP_OK || bytes.size > RUNTIME_CHECKSUM_BYTES)
            return Refinement.Rejected(ContainerFailure.DOWNLOAD_FAILED)
        val text = bytes.decodeToString(throwOnInvalidSequence = true).trim()
        val match = Regex("([a-f0-9]{64})(?:  \\Q$name\\E)?").matchEntire(text)
        if (match == null) Refinement.Rejected(ContainerFailure.DOWNLOAD_FAILED)
        else Refinement.Refined(match.groupValues[1])
    }
}

private fun copyDownload(input: java.io.InputStream, output: java.io.OutputStream): Refinement<Unit, ContainerFailure> {
    val buffer = ByteArray(RUNTIME_DOWNLOAD_BUFFER_BYTES)
    var total = 0L
    var count = input.read(buffer)
    while (count >= 0) {
        total += count
        if (total > RUNTIME_DOWNLOAD_MAXIMUM_BYTES) return Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
        output.write(buffer, 0, count)
        count = input.read(buffer)
    }
    return Refinement.Refined(Unit)
}

private fun downloadRuntime(client: HttpClient, uri: URI, destination: Path): Refinement<Unit, ContainerFailure> {
    val request =
        HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(RUNTIME_DOWNLOAD_TIMEOUT_MINUTES)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    return response.body().use { input ->
        if (response.statusCode() != HTTP_OK) return Refinement.Rejected(ContainerFailure.DOWNLOAD_FAILED)
        Files.newOutputStream(destination).use { output -> copyDownload(input, output) }
    }
}
