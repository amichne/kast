package io.github.amichne.kast.runtime.ide.read.preparation

import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatch
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDescribeReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDiscoverReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolResolveReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.WorkspaceInspectReadPort
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import java.nio.file.Path

/** Closed result of retaining one admitted exact-root IntelliJ Project for hosted composition. */
sealed interface HostedIdeReadProjectAdmission {
    data class Admitted(
        val project: HostedIdeReadProject,
    ) : HostedIdeReadProjectAdmission

    data class Rejected(
        val failure: HostedIdeReadProjectAdmissionFailure,
    ) : HostedIdeReadProjectAdmission
}

/** Closed failures before an admitted IntelliJ Project becomes hosted read authority. */
sealed interface HostedIdeReadProjectAdmissionFailure {
    data object InvalidEndpointRoot : HostedIdeReadProjectAdmissionFailure

    data class ProjectRejected(
        val cause: ExistingProjectAdmissionFailure,
    ) : HostedIdeReadProjectAdmissionFailure
}

/** Exact-root live Project authority retained without exposing the raw IntelliJ Project. */
class HostedIdeReadProject private constructor(
    private val retainedProject: RetainedHostedIdeProject,
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val compatibility: AdmittedIdeHostCompatibility,
) {
    /**
     * Proof transition: `HostedIdeReadProject -> HostedProjectCurrentRead`.
     *
     * Observes one opaque epoch and immediately re-admits it against the same retained Project.
     * Success issues only a same-root current read capability; observation and freshness failures
     * remain closed [HostedProjectCurrentRead]. The raw Project never leaves its physical owner.
     */
    internal fun admitCurrentRead(): HostedProjectCurrentRead = retainedProject.admitCurrentRead()

    companion object {
        /**
         * Proof transition: `(AdmittedIdeProject, IdeEndpointCanonicalRoot) ->
         * HostedIdeReadProjectAdmission`.
         *
         * Preserves the Project's non-forgeable live admission and establishes exact equality with
         * the endpoint root before retaining it as [HostedIdeReadProject]. Root mismatch remains
         * finite [HostedIdeReadProjectAdmissionFailure.InvalidEndpointRoot]. No raw Project or path
         * leaves this runtime-composition boundary.
         */
        fun retain(
            project: AdmittedIdeProject,
            expectedRoot: IdeEndpointCanonicalRoot,
        ): HostedIdeReadProjectAdmission {
            if (project.canonicalRoot.value != expectedRoot.value) {
                return HostedIdeReadProjectAdmission.Rejected(
                    HostedIdeReadProjectAdmissionFailure.InvalidEndpointRoot,
                )
            }
            return HostedIdeReadProjectAdmission.Admitted(
                HostedIdeReadProject(
                    RetainedHostedIdeProject.Live(project),
                    expectedRoot,
                    project.compatibility,
                ),
            )
        }

        /** Test-only friend-module fixture; production callers cannot bypass [retain]. */
        @JvmSynthetic
        internal fun testing(
            canonicalRoot: IdeEndpointCanonicalRoot,
            compatibility: AdmittedIdeHostCompatibility,
            read: HostedIdeReadProjectTestRead = HostedIdeReadProjectTestRead.CURRENT,
        ): HostedIdeReadProject = HostedIdeReadProject(
            RetainedHostedIdeProject.TestFixture(
                testingCurrentRead(canonicalRoot, read),
            ),
            canonicalRoot,
            compatibility,
        )
    }
}

/** Retains the live admitted Project proof; the fixture variant is invisible outside friend tests. */
private sealed interface RetainedHostedIdeProject {
    fun admitCurrentRead(): HostedProjectCurrentRead

    data class Live(
        val project: AdmittedIdeProject,
    ) : RetainedHostedIdeProject {
        override fun admitCurrentRead(): HostedProjectCurrentRead = when (
            val observed = project.observeReadEpoch()
        ) {
            is ProjectReadEpochObservation.Observed -> when (
                val admission = project.admitVfsPassiveRead(observed.epoch)
            ) {
                is VfsPassiveReadAdmission.Admitted ->
                    HostedProjectCurrentRead.Admitted(admission.capability)
                is VfsPassiveReadAdmission.Rejected ->
                    HostedProjectCurrentRead.FreshnessRejected(admission.failure)
            }
            is ProjectReadEpochObservation.Rejected ->
                HostedProjectCurrentRead.EpochRejected(observed.failure)
        }
    }

    data class TestFixture(
        val read: () -> HostedProjectCurrentRead,
    ) : RetainedHostedIdeProject {
        override fun admitCurrentRead(): HostedProjectCurrentRead = read()
    }
}

/** Friend-test epoch states; production construction cannot select one. */
internal enum class HostedIdeReadProjectTestRead {
    CURRENT,
    READ_PREEMPTED,
    MOVED_AFTER_SEMANTIC_READ,
}

/** Closed result of observing and re-admitting the retained Project's current opaque epoch. */
internal sealed interface HostedProjectCurrentRead {
    data class Admitted(
        val capability: VfsPassiveReadCapability,
    ) : HostedProjectCurrentRead

    data class EpochRejected(
        val failure: ProjectReadEpochObservationFailure,
    ) : HostedProjectCurrentRead

    data class FreshnessRejected(
        val failure: io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure,
    ) : HostedProjectCurrentRead
}

/**
 * Proof transition: `(IdeEndpointCanonicalRoot, HostedIdeReadProjectTestRead) ->
 * (() -> HostedProjectCurrentRead)`.
 *
 * Constructs only friend-test current, preempted, or post-semantic moved observations from one
 * retained opaque source. Root parsing and epoch construction remain inside this boundary;
 * production callers cannot select any fixture state.
 */
private fun testingCurrentRead(
    root: IdeEndpointCanonicalRoot,
    read: HostedIdeReadProjectTestRead,
): () -> HostedProjectCurrentRead {
    if (read == HostedIdeReadProjectTestRead.READ_PREEMPTED) return {
        HostedProjectCurrentRead.EpochRejected(ProjectReadEpochObservationFailure.ReadPreempted)
    }
    val workspaceRoot = when (val parsed = CanonicalWorkspaceRoot.fromCanonicalPath(
        Path.of(root.value),
    )) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return {
            HostedProjectCurrentRead.EpochRejected(
                ProjectReadEpochObservationFailure.ProjectRootMalformed,
            )
        }
    }
    var observationCount = 0
    val source = ProjectReadEpoch.Source.create<Int> {
        val state = when (read) {
            HostedIdeReadProjectTestRead.CURRENT -> 0
            HostedIdeReadProjectTestRead.MOVED_AFTER_SEMANTIC_READ ->
                if (observationCount < 2) 0 else 1
            HostedIdeReadProjectTestRead.READ_PREEMPTED -> error("handled above")
        }
        observationCount += 1
        Refinement.Refined(state)
    }
    return {
        when (val observed = source.observe()) {
            is ProjectReadEpochObservation.Observed -> HostedProjectCurrentRead.Admitted(
                VfsPassiveReadCapability.issue(workspaceRoot, observed.epoch),
            )
            is ProjectReadEpochObservation.Rejected ->
                HostedProjectCurrentRead.EpochRejected(observed.failure)
        }
    }
}

/** Module-internal hosted-runtime construction state. */
internal sealed interface HostedIdeReadRuntimeCandidate {
    data class Complete(
        val project: HostedIdeReadProject,
        val workspaceInspect: WorkspaceInspectReadPort,
        val symbolDiscover: SymbolDiscoverReadPort,
        val symbolResolve: SymbolResolveReadPort,
        val symbolDescribe: SymbolDescribeReadPort,
    ) : HostedIdeReadRuntimeCandidate

    /** The project service has not yet received all four route capabilities. */
    data class Partial(
        val project: HostedIdeReadProject,
    ) : HostedIdeReadRuntimeCandidate
}

enum class HostedIdeReadRuntimePreparationFailure {
    PARTIAL_RUNTIME,
}

sealed interface HostedIdeReadRuntimePreparation {
    data class Prepared(
        val runtime: HostedIdeReadRuntime,
    ) : HostedIdeReadRuntimePreparation

    data class Rejected(
        val failure: HostedIdeReadRuntimePreparationFailure,
    ) : HostedIdeReadRuntimePreparation
}

/** Complete exact-four-operation dispatch capability; partial route state cannot construct it. */
class HostedIdeReadRuntime private constructor(
    private val project: HostedIdeReadProject,
    private val dispatch: IdeReadRuntimeDispatch,
) {
    val canonicalRoot: IdeEndpointCanonicalRoot get() = project.canonicalRoot
    val compatibility: AdmittedIdeHostCompatibility get() = project.compatibility

    /**
     * Proof transition: `String -> IdeReadRuntimeDispatchResult`.
     *
     * Preserves the complete four-operation construction proof while the runtime refines a raw
     * frame through the generated wire boundary. Closed request, operation, decoding, and encoding
     * failures remain [IdeReadRuntimeDispatchResult]. Raw wire text may leave only at the endpoint
     * frame boundary.
     */
    suspend fun dispatch(document: String): IdeReadRuntimeDispatchResult = dispatch.dispatch(document)

    companion object {
        /**
         * Proof transition: `HostedIdeReadProject -> HostedIdeReadRuntimePreparation`.
         *
         * The endpoint retains the admitted live Project but fails closed until its operation handlers
         * provide all four concrete route capabilities. The returned partial failure cannot bind
         * or publish an endpoint. Expected incomplete construction is finite
         * [HostedIdeReadRuntimePreparationFailure].
         */
        fun prepare(project: HostedIdeReadProject): HostedIdeReadRuntimePreparation =
            prepare(HostedIdeReadRuntimeCandidate.Partial(project))

        /**
         * Proof transition: `HostedIdeReadRuntimeCandidate -> HostedIdeReadRuntimePreparation`.
         *
         * Establishes one nominal port for each exact hosted operation, plus the retained admitted
         * Project root and compatibility, before issuing [HostedIdeReadRuntime]. Expected partial
         * construction is [HostedIdeReadRuntimePreparationFailure]. Candidate assembly remains
         * module-internal; the IDE plugin can consume only the stronger result.
         */
        internal fun prepare(
            candidate: HostedIdeReadRuntimeCandidate,
        ): HostedIdeReadRuntimePreparation =
            when (candidate) {
                is HostedIdeReadRuntimeCandidate.Complete -> HostedIdeReadRuntimePreparation.Prepared(
                    HostedIdeReadRuntime(
                        candidate.project,
                        IdeReadRuntimeDispatch(
                            candidate.workspaceInspect,
                            candidate.symbolDiscover,
                            candidate.symbolResolve,
                            candidate.symbolDescribe,
                        ),
                    ),
                )
                is HostedIdeReadRuntimeCandidate.Partial ->
                    HostedIdeReadRuntimePreparation.Rejected(
                    HostedIdeReadRuntimePreparationFailure.PARTIAL_RUNTIME,
                )
            }
    }
}
