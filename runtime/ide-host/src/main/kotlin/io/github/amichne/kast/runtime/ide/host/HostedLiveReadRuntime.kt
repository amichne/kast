package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchFailure
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntime
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Exact hosted-read dispatch selected from the same live workspace generation as effects. */
interface HostedReadRuntimeOperations {
    val canonicalRoot: IdeEndpointCanonicalRoot
    val compatibility: AdmittedIdeHostCompatibility
    suspend fun dispatch(document: String): IdeReadRuntimeDispatchResult =
        dispatch(document, KastObservability.Disabled)

    suspend fun dispatch(
        document: String,
        observability: KastObservability,
    ): IdeReadRuntimeDispatchResult
}

sealed interface HostedReadRuntimeStaging {
    data object Staged : HostedReadRuntimeStaging
    data object Rejected : HostedReadRuntimeStaging
}

internal fun interface HostedReadDispatchOperations {
    suspend fun dispatch(document: String): IdeReadRuntimeDispatchResult

    suspend fun dispatch(
        document: String,
        observability: KastObservability,
    ): IdeReadRuntimeDispatchResult = dispatch(document)
}

/**
 * Bounded current-plus-successor dispatch state. A read runtime can become active only when the
 * live workspace exposes that exact semantic generation.
 */
internal class HostedGenerationReadDispatch(
    initialGeneration: EvidenceGeneration,
    initial: HostedReadDispatchOperations,
    private val workspace: WorkspaceInspectionOperations,
) {
    private var active = GenerationReadDispatch(initialGeneration, initial)
    private var staged: GenerationReadDispatch? = null

    @Synchronized
    fun stage(
        prior: EvidenceGeneration,
        next: EvidenceGeneration,
        dispatch: HostedReadDispatchOperations,
    ): HostedReadRuntimeStaging {
        if (next.value <= prior.value) return HostedReadRuntimeStaging.Rejected
        val pending = staged
        if (pending != null && pending.generation == next) {
            return HostedReadRuntimeStaging.Staged
        }
        if (next.value <= active.generation.value ||
            pending != null && next.value <= pending.generation.value
        ) {
            return HostedReadRuntimeStaging.Rejected
        }
        staged = GenerationReadDispatch(next, dispatch)
        return HostedReadRuntimeStaging.Staged
    }

    suspend fun dispatch(
        document: String,
        observability: KastObservability = KastObservability.Disabled,
    ): IdeReadRuntimeDispatchResult {
        val operation = when (val admission = WireRequestEnvelope.admit(document)) {
            is WireRequestAdmission.Admitted -> admission.request.operation
            is WireRequestAdmission.Rejected -> return IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RequestAdmissionFailed(admission.failure),
            )
        }
        if (operation !in hostedReadOperations) {
            return IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.UnsupportedOperation(operation),
            )
        }
        val lease = when (val current = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> current.workspace.readLease
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> return unavailable()
        }
        val selected = select(lease.generation) ?: return unavailable()
        val result = selected.dispatch(document, observability)
        return when (val current = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> if (current.workspace.readLease == lease) {
                result
            } else {
                unavailable()
            }
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> unavailable()
        }
    }

    @Synchronized
    private fun select(generation: EvidenceGeneration): HostedReadDispatchOperations? {
        if (active.generation == generation) return active.dispatch
        val pending = staged
        if (pending?.generation != generation) return null
        active = pending
        staged = null
        return active.dispatch
    }
}

/** Live exact-four read runtime that advances only with a staged workspace successor. */
class HostedLiveReadRuntimeOperations(
    initial: HostedIdeReadRuntime,
    workspace: HostedWorkspaceOperations,
) : HostedReadRuntimeOperations {
    private val dispatches = HostedGenerationReadDispatch(
        initial.semanticLease.generation,
        initial.asDispatchOperations(),
        workspace,
    )

    override val canonicalRoot: IdeEndpointCanonicalRoot = initial.canonicalRoot
    override val compatibility: AdmittedIdeHostCompatibility = initial.compatibility

    fun stage(
        prior: EvidenceGeneration,
        runtime: HostedIdeReadRuntime,
    ): HostedReadRuntimeStaging {
        if (runtime.canonicalRoot != canonicalRoot || runtime.compatibility != compatibility) {
            return HostedReadRuntimeStaging.Rejected
        }
        return dispatches.stage(
            prior,
            runtime.semanticLease.generation,
            runtime.asDispatchOperations(),
        )
    }

    override suspend fun dispatch(
        document: String,
        observability: KastObservability,
    ): IdeReadRuntimeDispatchResult = dispatches.dispatch(document, observability)
}

internal class StaticHostedReadRuntimeOperations(
    private val runtime: HostedIdeReadRuntime,
) : HostedReadRuntimeOperations {
    override val canonicalRoot: IdeEndpointCanonicalRoot = runtime.canonicalRoot
    override val compatibility: AdmittedIdeHostCompatibility = runtime.compatibility
    override suspend fun dispatch(
        document: String,
        observability: KastObservability,
    ): IdeReadRuntimeDispatchResult = runtime.dispatch(document, observability)
}

private data class GenerationReadDispatch(
    val generation: EvidenceGeneration,
    val dispatch: HostedReadDispatchOperations,
)

private val hostedReadOperations = IdeHostCapability.entries.mapTo(linkedSetOf()) {
    it.operation
}

private fun HostedIdeReadRuntime.asDispatchOperations(): HostedReadDispatchOperations {
    val runtime = this
    return object : HostedReadDispatchOperations {
        override suspend fun dispatch(document: String): IdeReadRuntimeDispatchResult =
            runtime.dispatch(document)

        override suspend fun dispatch(
            document: String,
            observability: KastObservability,
        ): IdeReadRuntimeDispatchResult = runtime.dispatch(document, observability)
    }
}

private fun unavailable(): IdeReadRuntimeDispatchResult = IdeReadRuntimeDispatchResult.Rejected(
    IdeReadRuntimeDispatchFailure.RuntimeGenerationUnavailable,
)
