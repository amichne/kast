package io.github.amichne.kast.source.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceDeclarationVisibility
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContextPort
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.readScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Admits source reads and revalidates the exact content context before publishing detached output. */
class SourceReadService(
    private val contexts: SourceReadContextPort,
    private val source: SourceReadPort,
) : SourceReadOperations {
    constructor(
        workspaces: WorkspaceInspectionOperations,
        source: SourceReadPort,
    ) : this(workspaces.sourceContexts(), source)

    override suspend fun read(request: SourceReadRequest): SourceReadResult {
        val anchor = request.anchor
        val expected = anchor.lease()
        val initial =
            when (val admitted = contexts.admit(expected)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return SourceReadResult.Rejected(
                        if (admitted.failure == SourceReadRejection.STALE_GENERATION)
                            when (anchor) {
                                is SourceReadAnchor.Candidate -> SourceReadRejection.CANDIDATE_STALE
                                is SourceReadAnchor.Symbol -> SourceReadRejection.STALE_GENERATION
                                is SourceReadAnchor.Source -> SourceReadRejection.SOURCE_SELECTOR_STALE
                            }
                        else admitted.failure
                    )
            }
        if (initial.lease != expected) return SourceReadResult.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        if (anchor is SourceReadAnchor.Source && anchor.selector.snapshot.context != initial) {
            return SourceReadResult.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
        }
        val result = source.read(initial, request)
        val current =
            when (val admitted = contexts.admit(expected)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return SourceReadResult.Rejected(
                        if (admitted.failure == SourceReadRejection.WORKSPACE_NOT_READY)
                            SourceReadRejection.STALE_GENERATION
                        else admitted.failure
                    )
            }
        if (initial != current) return SourceReadResult.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
        val snapshot =
            when (result) {
                is SourceReadResult.Complete -> result.snapshot
                is SourceReadResult.Qualified -> result.snapshot
                is SourceReadResult.Rejected -> return result
            }
        if ((request.entities as? EntitySelection.Matching)?.containment == Containment.SELF) {
            if (anchor !is SourceReadAnchor.Symbol || request.region != RegionSelection.Anchor) {
                return SourceReadResult.Rejected(SourceReadRejection.REGION_NOT_APPLICABLE)
            }
            if (
                result is SourceReadResult.Complete &&
                    SourceDeclarationVisibility.admit(anchor.selector, result) is Refinement.Rejected
            ) {
                return SourceReadResult.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
        }
        return if (
            snapshot.context == initial &&
                snapshot.readScope == anchor.readScope() &&
                (anchor !is SourceReadAnchor.Source || snapshot == anchor.selector.snapshot)
        )
            result
        else SourceReadResult.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    }
}

private fun SourceReadAnchor.lease(): SemanticReadAuthority =
    when (this) {
        is SourceReadAnchor.Candidate -> selector.lease
        is SourceReadAnchor.Symbol -> selector.lease
        is SourceReadAnchor.Source -> selector.snapshot.lease
    }

private fun WorkspaceInspectionOperations.sourceContexts(): SourceReadContextPort = SourceReadContextPort { expected ->
    when (val state = inspect()) {
        is WorkspaceRuntimeState.Ready ->
            when {
                state.workspace.root != expected.workspaceRoot ->
                    Refinement.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
                state.workspace.readLease != expected -> Refinement.Rejected(SourceReadRejection.STALE_GENERATION)
                else ->
                    Refinement.Refined(
                        SourceReadContext.Published(state.workspace.readLease, state.workspace.sourceState)
                    )
            }
        WorkspaceRuntimeState.Absent,
        WorkspaceRuntimeState.Starting,
        WorkspaceRuntimeState.Reconciling,
        is WorkspaceRuntimeState.Blocked,
        WorkspaceRuntimeState.Stopping -> Refinement.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
    }
}
