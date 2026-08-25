package io.github.amichne.kast.cli.codex

import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.RuntimeEndpointResolution
import io.github.amichne.kast.cli.Sha256RuntimeEndpointLocator
import io.github.amichne.kast.cli.UnixDomainWireClient
import io.github.amichne.kast.cli.WireSession
import io.github.amichne.kast.cli.WireSessionOpening
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val APP_SERVER_READ_TIMEOUT_SECONDS = 180L

internal sealed interface KastSpikeSessionOpening {
    data class Opened(val session: WireSession) : KastSpikeSessionOpening
    data class Rejected(val failure: KastSpikeBoundaryFailure) : KastSpikeSessionOpening
}

internal enum class KastSpikeBoundaryFailure {
    ROOT_REJECTED,
    INSTALLED_MANIFEST_UNAVAILABLE,
    INSTALLED_MANIFEST_REJECTED,
    ENDPOINT_REJECTED,
    ENDPOINT_UNAVAILABLE,
    APP_SERVER_START_REJECTED,
    APP_SERVER_WRITE_REJECTED,
    APP_SERVER_READ_REJECTED,
    APP_SERVER_TIMEOUT,
}

/** Connects directly to the current installed runtime's deterministic exact-root socket. */
internal object ExistingKastRuntimeConnection {
    /**
     * Proof transition: `Path -> KastSpikeSessionOpening`.
     *
     * Establishes a canonical repository root, an admitted installed runtime manifest, and one
     * connected exact-root [WireSession]. Expected boundary failures are closed in
     * [KastSpikeBoundaryFailure]. Raw paths leave only at filesystem and UDS boundaries.
     */
    fun open(start: Path): KastSpikeSessionOpening {
        val root = when (val discovery = FilesystemCanonicalRootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.ROOT_REJECTED,
            )
        }
        val manifestPath = Path.of(
            System.getProperty("user.home"),
            ".local",
            "share",
            "kast",
            "control",
            "share",
            "kast",
            "semantic-runtime.json",
        )
        val rawManifest = try {
            Files.readString(manifestPath)
        } catch (_: IOException) {
            return KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.INSTALLED_MANIFEST_UNAVAILABLE,
            )
        } catch (_: SecurityException) {
            return KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.INSTALLED_MANIFEST_UNAVAILABLE,
            )
        }
        val manifest = when (val admission = SemanticRuntimeManifest.admit(rawManifest)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admission.manifest
            is SemanticRuntimeManifestAdmission.Rejected -> return KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.INSTALLED_MANIFEST_REJECTED,
            )
        }
        val endpoint = when (
            val resolution = Sha256RuntimeEndpointLocator(
                Path.of(System.getProperty("java.io.tmpdir")).resolve("kast-runtime"),
                manifest.runtimeId,
            ).locate(root)
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.ENDPOINT_REJECTED,
            )
        }
        return when (val opening = UnixDomainWireClient().open(endpoint)) {
            is WireSessionOpening.Opened -> KastSpikeSessionOpening.Opened(opening.session)
            is WireSessionOpening.Rejected -> KastSpikeSessionOpening.Rejected(
                KastSpikeBoundaryFailure.ENDPOINT_UNAVAILABLE,
            )
        }
    }
}

internal sealed interface AppServerStart {
    data class Started(val session: AppServerJsonlSession) : AppServerStart
    data class Rejected(val failure: KastSpikeBoundaryFailure) : AppServerStart
}

internal sealed interface AppServerIncoming {
    data class Received(val document: RpcIncomingDocument) : AppServerIncoming
    data class Rejected(val failure: KastSpikeBoundaryFailure) : AppServerIncoming
}

/** One direct JSONL process session with `codex app-server`; no shell is involved. */
internal class AppServerJsonlSession private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val incoming: LinkedBlockingQueue<String>,
    val command: List<String>,
) : AutoCloseable {
    fun send(document: String): KastSpikeBoundaryFailure? = try {
        writer.write(document)
        writer.newLine()
        writer.flush()
        null
    } catch (_: IOException) {
        KastSpikeBoundaryFailure.APP_SERVER_WRITE_REJECTED
    }

    fun next(json: Json): AppServerIncoming {
        val line = try {
            incoming.poll(APP_SERVER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return AppServerIncoming.Rejected(KastSpikeBoundaryFailure.APP_SERVER_READ_REJECTED)
        } ?: return AppServerIncoming.Rejected(KastSpikeBoundaryFailure.APP_SERVER_TIMEOUT)
        return try {
            AppServerIncoming.Received(json.decodeFromString(RpcIncomingDocument.serializer(), line))
        } catch (_: SerializationException) {
            AppServerIncoming.Rejected(KastSpikeBoundaryFailure.APP_SERVER_READ_REJECTED)
        } catch (_: IllegalArgumentException) {
            AppServerIncoming.Rejected(KastSpikeBoundaryFailure.APP_SERVER_READ_REJECTED)
        }
    }

    override fun close() {
        try {
            writer.close()
        } catch (_: IOException) {
        }
        process.destroy()
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    companion object {
        fun start(root: Path, stderrLog: Path, protocolLog: Path): AppServerStart {
            val command = listOf(
                "codex",
                "app-server",
                "--disable",
                "hooks",
                "--disable",
                "plugins",
                "--disable",
                "shell_tool",
                "-c",
                "mcp_servers={}",
                "--stdio",
            )
            val process = try {
                ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectError(stderrLog.toFile())
                    .start()
            } catch (_: IOException) {
                return AppServerStart.Rejected(KastSpikeBoundaryFailure.APP_SERVER_START_REJECTED)
            } catch (_: SecurityException) {
                return AppServerStart.Rejected(KastSpikeBoundaryFailure.APP_SERVER_START_REJECTED)
            }
            val lines = LinkedBlockingQueue<String>()
            Thread({
                Files.newBufferedWriter(protocolLog).use { transcript ->
                    process.inputStream.bufferedReader().useLines { stream ->
                        stream.forEach { line ->
                            transcript.write(line)
                            transcript.newLine()
                            transcript.flush()
                            lines.put(line)
                        }
                    }
                }
            }, "kast-codex-app-server-jsonl").apply {
                isDaemon = true
                start()
            }
            return AppServerStart.Started(
                AppServerJsonlSession(
                    process,
                    process.outputStream.bufferedWriter(),
                    lines,
                    command,
                ),
            )
        }
    }
}
