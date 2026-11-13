package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ContainerStage {
    ARCHIVE_ADMISSION,
    RUNTIME_PUBLICATION,
    RUNTIME_METADATA,
    RUNTIME_SEALING,
    RUNTIME_RENAME,
    FILESYSTEM_IO,
    CREATION,
    PREPARATION,
    SHUTDOWN,
    RECLAMATION,
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

@Serializable
internal data class WorkspaceEntryRejection(
    val entry: String,
    val failure: ContainerFailure,
    val stage: ContainerStage = ContainerStage.SHUTDOWN,
    val outcome: ContainerStageOutcome = ContainerStageOutcome.REJECTED,
    val recovery: String = "kast uninstall",
)

internal fun rejectWorkspaceEntry(directory: Path, failure: ContainerFailure): Refinement.Rejected<ContainerFailure> {
    System.err.println(containerJson.encodeToString(WorkspaceEntryRejection(directory.fileName.toString(), failure)))
    return Refinement.Rejected(failure)
}
