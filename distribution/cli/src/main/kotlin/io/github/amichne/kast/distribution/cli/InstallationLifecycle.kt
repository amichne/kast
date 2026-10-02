package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_CAPABILITY
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val SHUTDOWN_FENCE = INSTALLATION_SHUTDOWN_FENCE
private const val RECORD_BYTES = 4096L
private const val CAPABILITY_BYTES = 64L
private val lifecycleIngressJson = Json { ignoreUnknownKeys = false }

/** Ownership is admitted before the shutdown fence or any process effect is issued. */
internal class InstallationLifecycle
private constructor(
    private val root: Path,
    private val installation: Path,
    private val home: Path,
    private val environment: Map<String, String>,
    private val manifest: BundledManifest,
    private val execution: LifecycleExecution,
) {
    private var fenceIdentity: FenceAttributes = FenceAttributes.Rejected

    fun shutdown(operation: LifecycleOperation): LifecycleOutcome {
        for ((stage, action) in
            listOf<Pair<LifecycleStage, () -> LifecycleEffect>>(
                LifecycleStage.FENCE to ::fence,
                LifecycleStage.COORDINATOR to { service(LifecycleServiceAction.DISABLE) },
                LifecycleStage.REQUESTS to { retireRequests(operation) },
                LifecycleStage.HOST to ::retireHost,
            )) {
            when (val effect = step(operation, stage, action)) {
                LifecycleEffect.Completed -> Unit
                is LifecycleEffect.Rejected -> return LifecycleOutcome.Rejected(operation, stage, effect.failure)
            }
        }
        return LifecycleOutcome.Stopped(operation, installation.toString())
    }

    fun resume(): LifecycleEffect {
        val next = readBundledManifest(installation)
        if (next !is BundledManifestRead.Read || !supportsShutdown(installation, next.manifest))
            return LifecycleEffect.Rejected(LifecycleFailure.OWNERSHIP_UNPROVEN)
        val path = root.resolve(SHUTDOWN_FENCE)
        val current = attributes(path)
        if (current !is FenceAttributes.Read || current != fenceIdentity)
            return LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED)
        return try {
            Files.delete(path)
            val activated =
                try {
                    service(LifecycleServiceAction.BOOTSTRAP)
                } catch (_: Exception) {
                    LifecycleEffect.Rejected(LifecycleFailure.FILESYSTEM_REJECTED)
                }
            when (activated) {
                LifecycleEffect.Completed -> activated
                is LifecycleEffect.Rejected -> restoreShutdown(activated)
            }
        } catch (_: Exception) {
            LifecycleEffect.Rejected(LifecycleFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun restoreShutdown(failure: LifecycleEffect.Rejected): LifecycleEffect {
        // A failed bootstrap may have started a coordinator before its readiness check failed.
        for ((stage, action) in
            listOf<Pair<LifecycleStage, () -> LifecycleEffect>>(
                LifecycleStage.FENCE to ::fence,
                LifecycleStage.COORDINATOR to { service(LifecycleServiceAction.DISABLE) },
            )) {
            when (val restored = step(LifecycleOperation.REINSTALL, stage, action)) {
                LifecycleEffect.Completed -> Unit
                is LifecycleEffect.Rejected -> return restored
            }
        }
        return failure
    }

    private fun step(
        operation: LifecycleOperation,
        stage: LifecycleStage,
        action: () -> LifecycleEffect,
    ): LifecycleEffect {
        execution.observe(LifecycleObservation.started(operation, stage))
        val result =
            try {
                action()
            } catch (_: Exception) {
                LifecycleEffect.Rejected(LifecycleFailure.FILESYSTEM_REJECTED)
            }
        execution.observe(
            when (result) {
                LifecycleEffect.Completed -> LifecycleObservation.completed(operation, stage)
                is LifecycleEffect.Rejected -> LifecycleObservation.rejected(operation, stage, result.failure)
            }
        )
        return result
    }

    private fun fence(): LifecycleEffect {
        val path = root.resolve(SHUTDOWN_FENCE)
        val expected = InstallationShutdownRequest(installation.toString())
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.writeString(
                path,
                managementJson.encodeToString(expected),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                NOFOLLOW_LINKS,
            )
        }
        val raw =
            readBoundedFile(path, RECORD_BYTES) ?: return LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED)
        val document =
            try {
                lifecycleIngressJson.decodeFromString<InstallationShutdownRequest>(raw)
            } catch (_: SerializationException) {
                return LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED)
            }
        if (document != expected) return LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED)
        return when (val observed = attributes(path)) {
            is FenceAttributes.Read -> {
                fenceIdentity = observed
                LifecycleEffect.Completed
            }
            FenceAttributes.Rejected -> LifecycleEffect.Rejected(LifecycleFailure.FENCE_REJECTED)
        }
    }

    private fun service(action: LifecycleServiceAction): LifecycleEffect {
        val selected = root.resolve("installation")
        val executable = selected.resolve("share/kast/libexec/kast-service")
        val current = readBundledManifest(selected)
        if (
            current !is BundledManifestRead.Read ||
                !payloadOwned(selected, current.manifest, "share/kast/libexec/kast-service")
        )
            return LifecycleEffect.Rejected(LifecycleFailure.OWNERSHIP_UNPROVEN)
        val selectedEnvironment =
            environment.filterKeys { it in setOf("PATH", "JAVA_HOME", "CODEX_EXECUTABLE", "TMPDIR") } +
                mapOf(
                    "HOME" to home.toString(),
                    "KAST_CONFIGURATION_FILE" to selected.resolve("config/environment").toString(),
                )
        return when (
            val result =
                execution.child.execute(listOf(executable.toString(), action.command), selected, selectedEnvironment)
        ) {
            is LifecycleChildObservation.Exited ->
                if (result.code == 0) LifecycleEffect.Completed
                else LifecycleEffect.Rejected(LifecycleFailure.CHILD_REJECTED)
            LifecycleChildObservation.DeadlineExceeded ->
                LifecycleEffect.Rejected(LifecycleFailure.CHILD_DEADLINE_EXCEEDED)
            LifecycleChildObservation.Unavailable -> LifecycleEffect.Rejected(LifecycleFailure.CHILD_REJECTED)
        }
    }

    private fun retireRequests(operation: LifecycleOperation): LifecycleEffect =
        InstallationRequestRetirement(installation, manifest, execution.processes).retire(operation)

    private fun retireHost(): LifecycleEffect {
        val raw =
            readBoundedFile(installation.resolve("config/selected-ide.json"), RECORD_BYTES)
                ?: return LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
        val selected =
            try {
                lifecycleIngressJson.decodeFromString<SelectedIdeLaunch>(raw)
            } catch (_: SerializationException) {
                return LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
            }
        return when (selected) {
            is SelectedIdeLaunch.Unavailable -> LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
            is SelectedIdeLaunch.Resolved -> {
                if (SelectedIdeInstallation.resolve(Path.of(selected.home)) != selected)
                    LifecycleEffect.Rejected(LifecycleFailure.HOST_OBSERVATION_REJECTED)
                else execution.processes.selectedHost(Path.of(selected.executable))
            }
        }
    }

    companion object {
        fun admit(
            root: Path,
            home: Path,
            environment: Map<String, String>,
            execution: LifecycleExecution,
        ): LifecycleAdmission =
            try {
                requireOwnedExecutable(root)
                val installation = selectedInstallation(root)
                val read = readBundledManifest(installation)
                val required =
                    listOf(INSTALLATION_SHUTDOWN_CAPABILITY, "share/kast/libexec/kast-service", "share/kast/install.sh")
                if (
                    read !is BundledManifestRead.Read ||
                        !supportsShutdown(installation, read.manifest) ||
                        required.any { !payloadOwned(installation, read.manifest, it) }
                )
                    LifecycleAdmission.Rejected
                else
                    LifecycleAdmission.Admitted(
                        InstallationLifecycle(
                            root,
                            installation,
                            home,
                            environment,
                            read.manifest,
                            execution,
                        )
                    )
            } catch (_: Exception) {
                LifecycleAdmission.Rejected
            }
    }
}

internal sealed interface LifecycleAdmission {
    data class Admitted(val lifecycle: InstallationLifecycle) : LifecycleAdmission

    data object Rejected : LifecycleAdmission
}

private sealed interface FenceAttributes {
    data class Read(val key: Any) : FenceAttributes

    data object Rejected : FenceAttributes
}

private fun attributes(path: Path): FenceAttributes =
    try {
        val value = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val key = value.fileKey()
        if (value.isRegularFile && key != null) FenceAttributes.Read(key) else FenceAttributes.Rejected
    } catch (_: Exception) {
        FenceAttributes.Rejected
    }

private fun supportsShutdown(installation: Path, manifest: BundledManifest): Boolean =
    payloadOwned(installation, manifest, INSTALLATION_SHUTDOWN_CAPABILITY) &&
        readBoundedFile(installation.resolve(INSTALLATION_SHUTDOWN_CAPABILITY), CAPABILITY_BYTES) == "1\n"

internal fun payloadOwned(installation: Path, manifest: BundledManifest, relative: String): Boolean {
    val entry = manifest.payloadFiles.singleOrNull { it.path == relative } ?: return false
    val path = installation.resolve(relative)
    return manifest.schemaVersion == INSTALLATION_MANIFEST_SCHEMA_VERSION &&
        manifest.installationRoot == installation.toString() &&
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        path.toRealPath() == path &&
        entry.sha256 == "sha256:${sha256(path)}"
}

private enum class LifecycleServiceAction(val command: String) {
    DISABLE("disable"),
    BOOTSTRAP("bootstrap"),
}

private const val SERVICE_DEADLINE_SECONDS = 45L
private const val FRESH_INSTALLER_DEADLINE_SECONDS = 300L
private const val SERVICE_TERMINATION_SECONDS = 5L

internal enum class LifecycleChildPurpose(val deadlineSeconds: Long) {
    SERVICE(SERVICE_DEADLINE_SECONDS),
    INSTALLER(FRESH_INSTALLER_DEADLINE_SECONDS),
}

internal object NativeLifecycleChildExecutor : LifecycleChildExecutor {
    override fun execute(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
    ): LifecycleChildObservation = executeBounded(command, directory, environment, LifecycleChildPurpose.SERVICE)

    internal fun executeBounded(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        purpose: LifecycleChildPurpose,
    ): LifecycleChildObservation {
        val child =
            try {
                ProcessBuilder(command)
                    .directory(directory.toFile())
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }
                    .redirectOutput(
                        if (purpose == LifecycleChildPurpose.INSTALLER)
                            ProcessBuilder.Redirect.appendTo(Path.of("/dev/stderr").toFile())
                        else ProcessBuilder.Redirect.DISCARD
                    )
                    .redirectError(
                        if (purpose == LifecycleChildPurpose.INSTALLER) ProcessBuilder.Redirect.INHERIT
                        else ProcessBuilder.Redirect.DISCARD
                    )
                    .start()
            } catch (_: Exception) {
                return LifecycleChildObservation.Unavailable
            }
        return try {
            if (child.waitFor(purpose.deadlineSeconds, TimeUnit.SECONDS))
                LifecycleChildObservation.Exited(child.exitValue())
            else {
                terminateChild(child, purpose)
                child.waitFor(SERVICE_TERMINATION_SECONDS, TimeUnit.SECONDS)
                LifecycleChildObservation.DeadlineExceeded
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LifecycleChildObservation.Unavailable
        } catch (_: Exception) {
            LifecycleChildObservation.Unavailable
        } finally {
            if (child.isAlive) terminateChild(child, purpose)
        }
    }

    private fun terminateChild(child: Process, purpose: LifecycleChildPurpose) {
        if (purpose == LifecycleChildPurpose.INSTALLER) {
            child.descendants().use { descendants ->
                descendants.toList().asReversed().forEach { it.destroyForcibly() }
            }
        }
        child.destroyForcibly()
    }
}
