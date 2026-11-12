package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
enum class ContainerFailure(val exit: Int = 1) {
    INVALID_NAME,
    INVALID_ID,
    INVALID_WORKSPACE,
    INVALID_GRADLE_HOME,
    UNSUPPORTED_PLATFORM,
    NOT_FOUND,
    AMBIGUOUS_WORKSPACE,
    NAME_EXISTS,
    WORKSPACE_EXISTS,
    INVALID_RECORD,
    FOREIGN_STATE,
    FILESYSTEM_UNAVAILABLE,
    FENCE_REJECTED,
    BUSY,
    LEASE_CLOSED,
    PREPARATION_WAIT_EXPIRED,
    INVALID_CONFIGURATION,
    CAPACITY_EXCEEDED,
    STOPPED,
    RUNTIME_UNAVAILABLE,
    RUNTIME_MISMATCH,
    INVALID_ARCHIVE,
    DIGEST_MISMATCH,
    DOWNLOAD_FAILED,
    PREPARATION_FAILED,
    SHUTDOWN_FAILED,
    CANCELLED_INTERRUPT(PublicQuerySignal.INTERRUPT.exit),
    CANCELLED_TERMINATE(PublicQuerySignal.TERMINATE.exit),
}

@JvmInline
internal value class ContainerId private constructor(val text: String) {
    companion object {
        fun parse(raw: String): Refinement<ContainerId, ContainerFailure> =
            if (Regex("[a-f0-9]{32}|[a-f0-9]{64}").matches(raw)) Refinement.Refined(ContainerId(raw))
            else Refinement.Rejected(ContainerFailure.INVALID_ID)

        /** The store validates the canonical root and compares the stored root on every lookup. */
        fun forWorkspace(workspace: Path): ContainerId =
            ContainerId(
                java.util.HexFormat.of()
                    .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(workspace.toString().toByteArray(Charsets.UTF_8))
                    )
            )
    }
}

@JvmInline
internal value class ContainerName private constructor(val text: String) {
    companion object {
        fun parse(raw: String): Refinement<ContainerName, ContainerFailure> =
            if (Regex("[A-Za-z][A-Za-z0-9._-]{0,63}").matches(raw)) Refinement.Refined(ContainerName(raw))
            else Refinement.Rejected(ContainerFailure.INVALID_NAME)
    }
}

/** Serialized records are untrusted ingress. Only ContainerStore constructs admitted containers. */
@Serializable
internal data class ContainerRecord(
    val id: String,
    val name: String,
    val workspace: String,
    val gradleHome: String,
    @Required val schemaVersion: Int = 2,
)

/** Version 1 ingress remains bounded; runtime selection is not a durable workspace fact. */
@Serializable
internal data class LegacyContainerRecord(
    val id: String,
    val name: String,
    val workspace: String,
    val runtime: String,
    val gradleHome: String,
    @Required val schemaVersion: Int = 1,
)

@Serializable
internal data class ContainerGenerationRecord(
    val runtime: String,
    val archiveSha256: String,
    @Required val schemaVersion: Int = 1,
    val generation: String? = null,
)

@Serializable
internal data class ContainerGenerationRetirement(
    val workspaceRecordSha256: String,
    val generationRecordSha256: String,
    @Required val schemaVersion: Int = 1,
)

internal sealed interface ContainerGenerationSource {
    data object Current : ContainerGenerationSource

    data class Legacy(val runtime: String) : ContainerGenerationSource
}

internal class AdmittedContainerCreation
internal constructor(
    val id: ContainerId,
    val name: ContainerName,
    val workspace: Path,
    val directory: Path,
    val gradleHome: Path,
)

internal class StagedContainerCreation
internal constructor(
    val creation: AdmittedContainerCreation,
    val container: AdmittedContainer,
    val staging: Path,
)

/** Durable ownership and exact trust survive missing or retired runtime bytes. */
internal class AdmittedWorkspace
internal constructor(
    val id: ContainerId,
    val name: ContainerName,
    val workspace: Path,
    val directory: Path,
    val gradleHome: Path,
    val generation: ContainerGenerationSource,
) {
    fun record(): ContainerRecord = ContainerRecord(id.text, name.text, workspace.toString(), gradleHome.toString())
}

internal sealed interface ContainerStoreEntry {
    data class Admitted(val workspace: AdmittedWorkspace) : ContainerStoreEntry

    data class Rejected(val directory: Path, val failure: ContainerFailure) : ContainerStoreEntry
}

internal class AdmittedContainer
internal constructor(
    val owned: AdmittedWorkspace,
    val runtime: AdmittedContainerRuntime,
    val profile: Path = owned.directory,
) {
    val id: ContainerId
        get() = owned.id

    val name: ContainerName
        get() = owned.name

    val workspace: Path
        get() = owned.workspace

    val directory: Path
        get() = owned.directory

    val gradleHome: Path
        get() = owned.gradleHome

    val state: Path
        get() = profile.resolve("state")

    val home: Path
        get() = state.resolve("home")

    val configuration: Path
        get() = profile.resolve("config/environment")

    val stopped: Path
        get() = directory.resolve("stopped.json")

    fun record(): ContainerRecord = owned.record()
}

@Serializable internal data class ContainerStopRecord(val id: String, @Required val schemaVersion: Int = 1)

internal sealed interface ContainerCommand {
    data class Create(val name: String?, val root: String?, val gradleHome: String?, val json: Boolean) :
        ContainerCommand
}

@Serializable
internal enum class ContainerState {
    ENABLED,
    STOPPED,
}
