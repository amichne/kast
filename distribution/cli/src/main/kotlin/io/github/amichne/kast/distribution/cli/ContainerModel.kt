package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ContainerFailure(val exit: Int = 1) {
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
    BUSY,
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
            if (Regex("[a-f0-9]{32}").matches(raw)) Refinement.Refined(ContainerId(raw))
            else Refinement.Rejected(ContainerFailure.INVALID_ID)
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
    val runtime: String,
    val gradleHome: String,
    @Required val schemaVersion: Int = 1,
)

internal class AdmittedContainer
internal constructor(
    val id: ContainerId,
    val name: ContainerName,
    val workspace: Path,
    val directory: Path,
    val runtime: AdmittedContainerRuntime,
    val gradleHome: Path,
) {
    val state: Path
        get() = directory.resolve("state")

    val home: Path
        get() = state.resolve("home")

    val configuration: Path
        get() = directory.resolve("config/environment")

    val stopped: Path
        get() = directory.resolve("stopped.json")

    fun record(): ContainerRecord =
        ContainerRecord(id.text, name.text, workspace.toString(), runtime.key, gradleHome.toString())
}

@Serializable internal data class ContainerStopRecord(val id: String, @Required val schemaVersion: Int = 1)

internal sealed interface ContainerCommand {
    data class List(val json: Boolean) : ContainerCommand

    data class Create(val name: String?, val root: String?, val gradleHome: String?, val json: Boolean) :
        ContainerCommand

    sealed interface Selected : ContainerCommand {
        val selector: String
    }

    data class Resume(override val selector: String, val json: Boolean) : Selected

    data class Stop(override val selector: String, val json: Boolean) : Selected

    data class Remove(val id: ContainerId, val json: Boolean) : Selected {
        override val selector: String
            get() = id.text
    }

    data class ImportRuntime(val archive: String, val sha256: String, val json: Boolean) : ContainerCommand
}

@Serializable
internal enum class ContainerDisposition {
    CREATED,
    RESUMED,
    STOPPED,
    REMOVED,
    RUNTIME_IMPORTED,
}

@Serializable
internal sealed interface ContainerDocument {
    @Serializable
    @SerialName("containers")
    data class Listing(val containers: kotlin.collections.List<ContainerSummary>) : ContainerDocument

    @Serializable
    @SerialName("complete")
    data class Completed(val operation: ContainerDisposition, val id: String) : ContainerDocument

    @Serializable @SerialName("created") data class Created(val container: ContainerRecord) : ContainerDocument

    @Serializable @SerialName("rejected") data class Rejected(val failure: ContainerFailure) : ContainerDocument
}

@Serializable internal data class ContainerSummary(val container: ContainerRecord, val state: ContainerState)

@Serializable
internal enum class ContainerState {
    ENABLED,
    STOPPED,
}
