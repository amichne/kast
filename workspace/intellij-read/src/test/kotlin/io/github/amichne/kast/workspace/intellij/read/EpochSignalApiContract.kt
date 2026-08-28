package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compile-only contract for the exact public IDEA 262 signal APIs characterized by epoch-signal policy.
 *
 * This type must never be classloaded by the Java-21 characterization task. The positive test
 * instead reads its class resource and verifies the exact constant-pool owners and members.
 */
@Suppress("unused")
internal object EpochSignalApiContract {
    fun subscribe(
        project: Project,
        lifetime: Disposable,
        workspaceListener: WorkspaceModelChangeListener,
        vfsCounter: EpochVfsMetadataCounter,
    ) {
        project.messageBus.connect(lifetime).subscribe(
            WorkspaceModelTopics.CHANGED,
            workspaceListener,
        )
        project.messageBus.connect(lifetime).subscribe(
            VirtualFileManager.VFS_CHANGES,
            RootFilteredVfsSignal(vfsCounter),
        )
    }

    fun sample(
        project: Project,
        externalProjectInfo: ExternalProjectInfo,
    ): LongArray {
        val dumbService = DumbService.getInstance(project)
        return longArrayOf(
            externalProjectInfo.lastImportTimestamp,
            externalProjectInfo.lastSuccessfulImportTimestamp,
            PsiModificationTracker.getInstance(project).modificationCount,
            ProjectRootModificationTracker.getInstance(project).modificationCount,
            dumbService.modificationTracker.modificationCount,
            if (dumbService.isDumb) 1L else 0L,
        )
    }

    private class RootFilteredVfsSignal(
        private val counter: EpochVfsMetadataCounter,
    ) : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            counter.recordEvents(events.map(::observeEvent))
        }

        private fun observeEvent(event: VFileEvent): EpochVfsObservedEvent = when (event) {
            is VFileMoveEvent -> EpochVfsObservedEvent.Move(
                java.nio.file.Path.of(event.oldPath),
                java.nio.file.Path.of(event.newPath),
            )
            is VFilePropertyChangeEvent -> if (event.isRename) {
                EpochVfsObservedEvent.Rename(
                    java.nio.file.Path.of(event.oldPath),
                    java.nio.file.Path.of(event.newPath),
                )
            } else {
                EpochVfsObservedEvent.Change(java.nio.file.Path.of(event.path))
            }
            else -> EpochVfsObservedEvent.Change(java.nio.file.Path.of(event.path))
        }
    }
}

@Serializable
internal data class IdeEpochSignalLedgerDocument(
    val schemaVersion: Int,
    val authority: EpochReportAuthority,
    val ideBuild: String,
    val signals: List<EpochSignalDocument>,
    val rejectedConstantZeroAuthorities: List<String>,
    val cases: List<EpochCaseDocument>,
    val vfsRefreshCount: Int,
    val gradleImportCount: Int,
    val repositoryWalkCount: Int,
    val sourceHashCount: Int,
    val semanticJobCount: Int,
    val edtWorkCount: Int,
    val blockingWaitCount: Int,
)

@Serializable
internal data class EpochSignalDocument(
    val category: EpochSignalCategory,
    val authorities: List<String>,
    val observation: String,
    val movement: String,
)

@Serializable
internal data class EpochCaseDocument(
    val caseId: EpochCaseId,
    val sampleCount: Int,
    val movedSignals: List<EpochSignalCategory>,
    val projectModelTransitions: List<EpochProjectModelTransition>,
    val dumbModeSamples: List<EpochDumbModeState>,
    val dumbModeTransitions: List<EpochDumbModeTransition>,
    val vfsEventCount: Int,
    val expectedRelation: EpochSampleRelation,
    val observedRelation: EpochSampleRelation,
)

@Serializable
internal enum class EpochReportAuthority { READ_EPOCH }

@Serializable
internal enum class EpochSignalCategory { PROJECT_MODEL, PSI, VFS, ROOT_MODEL, DUMB_MODE }

@Serializable
internal enum class EpochCaseId {
    STABLE,
    WORKSPACE_MODEL_MOVEMENT,
    GRADLE_IMPORT_STARTED,
    GRADLE_IMPORT_COMPLETED,
    GRADLE_ROOT_MOVEMENT,
    PSI_MOVEMENT,
    VFS_MOVEMENT,
    ROOT_MODEL_MOVEMENT,
    SMART_DUMB_SMART,
    COMBINED_MOVEMENT,
    VFS_EVENT_STORM,
}

@Serializable
internal enum class EpochProjectModelTransition {
    WORKSPACE_MODEL_CHANGED,
    GRADLE_IMPORT_STARTED,
    GRADLE_IMPORT_COMPLETED,
    GRADLE_ROOT_CHANGED,
}

@Serializable
internal enum class EpochDumbModeState { SMART, DUMB }

@Serializable
internal enum class EpochDumbModeTransition { SMART_TO_DUMB, DUMB_TO_SMART }

@Serializable
internal enum class EpochSampleRelation { UNCHANGED, CHANGED }

internal enum class EpochLedgerFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SIGNAL_SET_MISMATCH,
    CONSTANT_ZERO_AUTHORITY_NOT_REJECTED,
    CASE_SET_MISMATCH,
    FORBIDDEN_EFFECT_OBSERVED,
}

internal sealed interface EpochLedgerAdmission {
    data class Admitted(
        val document: IdeEpochSignalLedgerDocument,
        val sampleCount: Int,
    ) : EpochLedgerAdmission

    data class Rejected(val failure: EpochLedgerFailure) : EpochLedgerAdmission
}

internal object EpochSignalLedgerContract {
    val document: IdeEpochSignalLedgerDocument = IdeEpochSignalLedgerDocument(
        schemaVersion = 1,
        authority = EpochReportAuthority.READ_EPOCH,
        ideBuild = "262.9437.185",
        signals = canonicalEpochSignals(),
        rejectedConstantZeroAuthorities = listOf(
            "VirtualFileManager.modificationCount",
            "VirtualFileManager.structureModificationCount",
        ),
        cases = canonicalEpochCases(),
        vfsRefreshCount = 0,
        gradleImportCount = 0,
        repositoryWalkCount = 0,
        sourceHashCount = 0,
        semanticJobCount = 0,
        edtWorkCount = 0,
        blockingWaitCount = 0,
    )

    val canonicalBytes: String = EPOCH_JSON.encodeToString(
        IdeEpochSignalLedgerDocument.serializer(),
        document,
    ) + "\n"

    fun encode(document: IdeEpochSignalLedgerDocument): String = EPOCH_JSON.encodeToString(
        IdeEpochSignalLedgerDocument.serializer(),
        document,
    ) + "\n"

    /**
     * Proof transition: `String -> EpochLedgerAdmission`.
     *
     * Establishes the exact generated epoch-signal policy READ_EPOCH document, ordered signals and cases,
     * 22 total samples, rejected constant-zero authorities, and zero forbidden work. Raw JSON is
     * permitted only at the generated report boundary; [EpochLedgerFailure] closes rejection.
     */
    fun admit(raw: String): EpochLedgerAdmission {
        val decoded = try {
            EPOCH_JSON.decodeFromString(IdeEpochSignalLedgerDocument.serializer(), raw)
        } catch (_: SerializationException) {
            return EpochLedgerAdmission.Rejected(EpochLedgerFailure.MALFORMED_DOCUMENT)
        } catch (_: IllegalArgumentException) {
            return EpochLedgerAdmission.Rejected(EpochLedgerFailure.MALFORMED_DOCUMENT)
        }
        val failure = when {
            decoded.signals != document.signals -> EpochLedgerFailure.SIGNAL_SET_MISMATCH
            decoded.rejectedConstantZeroAuthorities != document.rejectedConstantZeroAuthorities ->
                EpochLedgerFailure.CONSTANT_ZERO_AUTHORITY_NOT_REJECTED
            decoded.copy(signals = document.signals, rejectedConstantZeroAuthorities =
                document.rejectedConstantZeroAuthorities, cases = document.cases) != document ->
                EpochLedgerFailure.FORBIDDEN_EFFECT_OBSERVED
            decoded.cases != document.cases -> EpochLedgerFailure.CASE_SET_MISMATCH
            raw != EPOCH_JSON.encodeToString(
                IdeEpochSignalLedgerDocument.serializer(),
                decoded,
            ) + "\n" -> EpochLedgerFailure.NON_CANONICAL_DOCUMENT
            else -> null
        }
        return if (failure == null) {
            EpochLedgerAdmission.Admitted(decoded, decoded.cases.sumOf(EpochCaseDocument::sampleCount))
        } else {
            EpochLedgerAdmission.Rejected(failure)
        }
    }
}

private val EPOCH_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
