package io.github.amichne.kast.source.service

import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Publication-admission and stale-evidence owner for public `source.read`. */
class SourceReadService(
    private val workspaces: WorkspaceInspectionOperations,
    private val source: SourceReadPort,
) : SourceReadOperations {
    override suspend fun read(request: SourceReadRequest): SourceReadResult {
        val initial = readyWorkspace()
            ?: return SourceReadResult.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
        val expected = request.anchor.lease()
        when (val admission = admitAnchor(request.anchor, expected, initial)) {
            SourceReadAdmission.Admitted -> Unit
            is SourceReadAdmission.Rejected -> return SourceReadResult.Rejected(admission.reason)
        }

        val result = source.read(
            SourceReadContext(initial.readLease, initial.sourceState),
            request,
        )

        val after = readyWorkspace()
            ?: return SourceReadResult.Rejected(SourceReadRejection.STALE_GENERATION)
        when (val movement = admitUnmoved(initial, after)) {
            SourceReadAdmission.Admitted -> Unit
            is SourceReadAdmission.Rejected -> return SourceReadResult.Rejected(movement.reason)
        }
        return if (result.admittedFor(request.anchor, initial)) {
            result
        } else {
            SourceReadResult.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
    }

    private fun readyWorkspace(): PublishedWorkspace? = when (val state = workspaces.inspect()) {
        is WorkspaceRuntimeState.Ready -> state.workspace
        WorkspaceRuntimeState.Absent,
        WorkspaceRuntimeState.Starting,
        WorkspaceRuntimeState.Reconciling,
        is WorkspaceRuntimeState.Blocked,
        WorkspaceRuntimeState.Stopping,
            -> null
    }
}

private sealed interface SourceReadAdmission {
    data object Admitted : SourceReadAdmission
    data class Rejected(val reason: SourceReadRejection) : SourceReadAdmission
}

private fun SourceReadAnchor.lease(): SemanticReadLease = when (this) {
    is SourceReadAnchor.Candidate -> selector.lease
    is SourceReadAnchor.Symbol -> selector.lease
    is SourceReadAnchor.Source -> selector.snapshot.lease
}

private fun admitAnchor(
    anchor: SourceReadAnchor,
    expected: SemanticReadLease,
    current: PublishedWorkspace,
): SourceReadAdmission = when {
    expected.workspaceRoot != current.root ->
        SourceReadAdmission.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
    expected.generation != current.generation -> SourceReadAdmission.Rejected(
        when (anchor) {
            is SourceReadAnchor.Candidate -> SourceReadRejection.CANDIDATE_STALE
            is SourceReadAnchor.Symbol -> SourceReadRejection.STALE_GENERATION
            is SourceReadAnchor.Source -> SourceReadRejection.SOURCE_SELECTOR_STALE
        },
    )
    anchor is SourceReadAnchor.Source &&
        anchor.selector.snapshot.sourceState != current.sourceState ->
        SourceReadAdmission.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
    else -> SourceReadAdmission.Admitted
}

private fun admitUnmoved(
    before: PublishedWorkspace,
    after: PublishedWorkspace,
): SourceReadAdmission = when {
    before.root != after.root ->
        SourceReadAdmission.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
    before.generation != after.generation ->
        SourceReadAdmission.Rejected(SourceReadRejection.STALE_GENERATION)
    before.sourceState != after.sourceState ->
        SourceReadAdmission.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
    else -> SourceReadAdmission.Admitted
}

private fun SourceReadResult.admittedFor(
    anchor: SourceReadAnchor,
    publication: PublishedWorkspace,
): Boolean {
    val snapshot = when (this) {
        is SourceReadResult.Complete -> snapshot
        is SourceReadResult.Qualified -> snapshot
        is SourceReadResult.Rejected -> return true
    }
    if (!snapshot.belongsTo(publication)) return false
    return anchor !is SourceReadAnchor.Source || snapshot == anchor.selector.snapshot
}

private fun SourceSnapshot.belongsTo(publication: PublishedWorkspace): Boolean =
    lease == publication.readLease && sourceState == publication.sourceState
