package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement

/** Closed relation between two opaque project-read epochs. */
enum class ProjectReadEpochRelation {
    SAME,
    MOVED,
    INCOMPARABLE,
}

/** Environment equality excludes source contents and never proves that semantic facts remain valid. */
enum class ProjectReadEnvironmentRelation {
    SAME,
    CHANGED,
    UNOBSERVED,
    INCOMPARABLE,
}

/** Finite platform stages at which a project-read epoch observation can fail. */
enum class ProjectReadEpochObservationStage {
    THREAD,
    DISPOSAL,
    OPEN,
    INITIALIZATION,
    PROJECT_ROOT,
    PROJECT_MODEL,
    PSI,
    VFS,
    ROOT_MODEL,
    DUMB_MODE,
}

/** Closed expected failures for observing one project-read epoch. */
sealed interface ProjectReadEpochObservationFailure {
    data object WrongThread : ProjectReadEpochObservationFailure

    data object ProjectDisposed : ProjectReadEpochObservationFailure

    data object ProjectNotOpen : ProjectReadEpochObservationFailure

    data object ProjectNotInitialized : ProjectReadEpochObservationFailure

    data object ProjectRootUnavailable : ProjectReadEpochObservationFailure

    data object ProjectRootMalformed : ProjectReadEpochObservationFailure

    data object DumbMode : ProjectReadEpochObservationFailure

    data object GradleModelUnavailable : ProjectReadEpochObservationFailure

    data object GradleModelIncomplete : ProjectReadEpochObservationFailure

    data object GradleModelAmbiguous : ProjectReadEpochObservationFailure

    data object GradleRootUnavailable : ProjectReadEpochObservationFailure

    data object GradleRootMalformed : ProjectReadEpochObservationFailure

    data object ImportTimestampsIncoherent : ProjectReadEpochObservationFailure

    data object VfsBatchLimitExceeded : ProjectReadEpochObservationFailure

    data object VfsPathMalformed : ProjectReadEpochObservationFailure

    data object SignalExhausted : ProjectReadEpochObservationFailure

    data object ReadPreempted : ProjectReadEpochObservationFailure

    data class ObservationFailed(val stage: ProjectReadEpochObservationStage) : ProjectReadEpochObservationFailure
}

/** Closed result of observing one opaque project-read epoch. */
sealed interface ProjectReadEpochObservation {
    class Observed internal constructor(val epoch: ProjectReadEpoch<*>) : ProjectReadEpochObservation

    data class Rejected(val failure: ProjectReadEpochObservationFailure) : ProjectReadEpochObservation
}

/**
 * Opaque identity of the IDE-visible state observed for one admitted Project/runtime.
 *
 * The private [state] remains strongly typed inside the source that observed it. Public callers can compare epochs but
 * cannot extract, alter, parse, copy, or reconstruct the signal state.
 */
class ProjectReadEpoch<State : Any>
private constructor(
    private val comparisonDomain: ComparisonDomain,
    private val state: State,
    private val environment: EnvironmentEvidence,
) {
    fun environmentRelationTo(other: ProjectReadEpoch<*>): ProjectReadEnvironmentRelation {
        if (comparisonDomain !== other.comparisonDomain) return ProjectReadEnvironmentRelation.INCOMPARABLE
        return when (val observed = environment) {
            EnvironmentEvidence.Unobserved -> ProjectReadEnvironmentRelation.UNOBSERVED
            is EnvironmentEvidence.Observed<*> ->
                when (val otherEnvironment = other.environment) {
                    EnvironmentEvidence.Unobserved -> ProjectReadEnvironmentRelation.UNOBSERVED
                    is EnvironmentEvidence.Observed<*> ->
                        if (observed == otherEnvironment) ProjectReadEnvironmentRelation.SAME
                        else ProjectReadEnvironmentRelation.CHANGED
                }
        }
    }

    /**
     * Proof transition: `(ProjectReadEpoch<*>, ProjectReadEpoch<*>) -> ProjectReadEpochRelation`.
     *
     * Establishes [ProjectReadEpochRelation.SAME] only for equal immutable signal states issued by the exact same
     * source instance. A different project/runtime source is [ProjectReadEpochRelation.INCOMPARABLE]; a changed state
     * from the same source is [ProjectReadEpochRelation.MOVED]. No raw signal extraction is permitted at this boundary.
     */
    fun relationTo(other: ProjectReadEpoch<*>): ProjectReadEpochRelation {
        if (comparisonDomain !== other.comparisonDomain) {
            return ProjectReadEpochRelation.INCOMPARABLE
        }
        @Suppress("UNCHECKED_CAST") val comparable = other as ProjectReadEpoch<State>
        return if (state == comparable.state) {
            ProjectReadEpochRelation.SAME
        } else {
            ProjectReadEpochRelation.MOVED
        }
    }

    /** Compiler-confined source-specific transition to one opaque epoch. */
    internal class Source<State : Any>
    private constructor(
        private val observer: () -> Refinement<State, ProjectReadEpochObservationFailure>,
        private val beforeWriteObserver: () -> Refinement<State, ProjectReadEpochObservationFailure>,
        private val environment: (State) -> EnvironmentEvidence,
    ) {
        private val comparisonDomain = ComparisonDomain()

        /**
         * Proof transition: `Source<State> -> ProjectReadEpochObservation`.
         *
         * Establishes a non-null refined [State] bound to this exact source instance, or returns one closed
         * [ProjectReadEpochObservationFailure]. Primitive platform counters may be extracted only inside the supplied
         * adapter observation boundary. Callers consume the returned epoch and never repeat that refinement.
         */
        @JvmSynthetic internal fun observe(): ProjectReadEpochObservation = retain(observer())

        /** Separate adapter observation at an already-held write action; ordinary reads retain their thread policy. */
        @JvmSynthetic internal fun observeBeforeWrite(): ProjectReadEpochObservation = retain(beforeWriteObserver())

        private fun retain(result: Refinement<State, ProjectReadEpochObservationFailure>): ProjectReadEpochObservation =
            when (result) {
                is Refinement.Refined ->
                    ProjectReadEpochObservation.Observed(
                        ProjectReadEpoch(comparisonDomain, result.value, environment(result.value))
                    )
                is Refinement.Rejected -> ProjectReadEpochObservation.Rejected(result.failure)
            }

        companion object {
            /**
             * Proof transition: `(() -> Refinement<State, ProjectReadEpochObservationFailure>) -> Source<State>`.
             * Establishes one compiler-confined source identity whose observer returns only a refined state or closed
             * failure. Raw extraction remains in the friend adapter; observed epochs retain only a callback-free
             * comparison identity and refined state.
             */
            @JvmSynthetic
            internal fun <State : Any> create(
                observer: () -> Refinement<State, ProjectReadEpochObservationFailure>
            ): Source<State> =
                Source(observer, { Refinement.Rejected(ProjectReadEpochObservationFailure.WrongThread) }) {
                    EnvironmentEvidence.Unobserved
                }

            /** Both probes belong to this one source; the write probe must enforce its distinct execution boundary. */
            @JvmSynthetic
            internal fun <State : Any> create(
                observer: () -> Refinement<State, ProjectReadEpochObservationFailure>,
                beforeWriteObserver: () -> Refinement<State, ProjectReadEpochObservationFailure>,
            ): Source<State> = Source(observer, beforeWriteObserver) { EnvironmentEvidence.Unobserved }

            /** The adapter supplies a detached immutable environment snapshot; epochs retain no projection callback. */
            @JvmSynthetic
            internal fun <State : Any, Environment : Any> createWithEnvironment(
                observer: () -> Refinement<State, ProjectReadEpochObservationFailure>,
                beforeWriteObserver: () -> Refinement<State, ProjectReadEpochObservationFailure>,
                environment: (State) -> Environment,
            ): Source<State> = Source(observer, beforeWriteObserver) { EnvironmentEvidence.Observed(environment(it)) }
        }
    }

    private sealed interface EnvironmentEvidence {
        data object Unobserved : EnvironmentEvidence

        data class Observed<Environment : Any>(val value: Environment) : EnvironmentEvidence
    }

    /** Callback-free identity retained by detached epochs from one exact source. */
    private class ComparisonDomain
}
