package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A live service generation correlated with the private readiness record and its socket. */
class InstalledSemanticCoordinator
private constructor(
    private val client: InstalledDaemonOperationClient,
    val generation: String,
    val processId: Long,
) {
    suspend fun execute(
        root: CanonicalRoot,
        call: DaemonOperationCall,
        settings: RequestedRuntimeSettings = RequestedRuntimeSettings.Unstated,
    ): DaemonOperationResult = client.exchange(root, call, settings)

    companion object {
        fun readinessPath(installation: Path, home: Path): Path =
            BrokerInstallationLayout.from(installation.resolve("bin/kast"), home.resolve(".codex"))
                .broker
                .resolve("service-readiness.json")

        suspend fun observe(
            installation: Path,
            readiness: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> =
            observe(installation, readiness, installation.resolve(MANAGEMENT_EXECUTABLE))

        /** Passive observation: an absent publication is [WorkerControlFailure.UNAVAILABLE]; nothing starts. */
        internal suspend fun observe(
            installation: Path,
            readiness: Path,
            executable: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> =
            when (val state = SemanticPublicationState.inspect(installation, readiness, executable)) {
                is SemanticPublicationState.Published ->
                    when (val live = live(state.publication)) {
                        is Refinement.Refined -> live
                        is Refinement.Rejected -> Refinement.Rejected(live.failure.workerFailure())
                    }
                SemanticPublicationState.Missing -> Refinement.Rejected(WorkerControlFailure.UNAVAILABLE)
                is SemanticPublicationState.Starting -> Refinement.Rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
                is SemanticPublicationState.Stale -> Refinement.Rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                is SemanticPublicationState.Rejected -> Refinement.Rejected(state.rejection.workerFailure())
            }

        /**
         * Starts or joins this installation's semantic coordinator. [submit] runs only when no owned generation is live
         * or starting; the supervisor repeats that decision under the installation start lock.
         */
        suspend fun connect(
            installation: Path,
            home: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            connect(
                installation,
                readinessPath(installation, home),
                installation.resolve(MANAGEMENT_EXECUTABLE),
                submit,
                BrokerOperationalLimits.serviceStartup.value,
            ) {
                delay(READINESS_POLL_MILLIS)
            }

        internal suspend fun connect(
            installation: Path,
            readiness: Path,
            executable: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
            budgetMillis: Long,
            pause: suspend () -> Unit,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            withTimeoutOrNull(budgetMillis) { startup(installation, readiness, executable, submit, pause) }
                ?: Refinement.Rejected(SemanticCoordinatorStartupFailure.StartupTimedOut)

        private suspend fun startup(
            installation: Path,
            readiness: Path,
            executable: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
            pause: suspend () -> Unit,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> {
            var progress: SemanticStartupProgress = SemanticStartupProgress.BeforeSubmission
            while (true) {
                when (val next = progress.next(SemanticPublicationState.inspect(installation, readiness, executable))) {
                    is SemanticStartupStep.Connect -> return connectPublished(next.publication)
                    is SemanticStartupStep.Rejected -> return Refinement.Rejected(next.failure)
                    is SemanticStartupStep.Await -> {
                        progress = next.progress
                        pause()
                    }
                    SemanticStartupStep.Submit ->
                        when (val admitted = submit()) {
                            is Refinement.Refined -> progress = SemanticStartupProgress.AwaitingPublication
                            is Refinement.Rejected ->
                                return Refinement.Rejected(
                                    SemanticCoordinatorStartupFailure.SubmissionRejected(admitted.failure)
                                )
                        }
                }
            }
        }

        private suspend fun connectPublished(
            publication: SemanticPublication
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            when (val connected = live(publication)) {
                is Refinement.Refined -> connected
                is Refinement.Rejected -> Refinement.Rejected(connected.failure.startupFailure())
            }

        private suspend fun live(
            publication: SemanticPublication
        ): Refinement<InstalledSemanticCoordinator, SemanticPublicationRejection> {
            val snapshot =
                when (val status = readCoordinatorStatus(publication.socket.path)) {
                    is Refinement.Refined -> status.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                }
            if (!publication.admits(snapshot))
                return Refinement.Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
            val target = DaemonManagementTarget.from(snapshot)
            return Refinement.Refined(
                InstalledSemanticCoordinator(
                    InstalledDaemonOperationClient(publication.socket.path, target) { publication.admits(snapshot) },
                    target.serviceGeneration,
                    publication.processId,
                )
            )
        }
    }
}

private sealed interface SemanticStartupStep {
    data object Submit : SemanticStartupStep

    data class Await(val progress: SemanticStartupProgress) : SemanticStartupStep

    data class Connect(val publication: SemanticPublication) : SemanticStartupStep

    data class Rejected(val failure: SemanticCoordinatorStartupFailure) : SemanticStartupStep
}

private sealed interface SemanticStartupProgress {
    data object BeforeSubmission : SemanticStartupProgress

    data object AwaitingPublication : SemanticStartupProgress

    data class AwaitingGeneration(val generation: String) : SemanticStartupProgress

    fun next(state: SemanticPublicationState): SemanticStartupStep =
        when (state) {
            is SemanticPublicationState.Published ->
                if (allows(state.publication.generation)) SemanticStartupStep.Connect(state.publication) else replaced()
            is SemanticPublicationState.Starting ->
                if (allows(state.generation)) SemanticStartupStep.Await(AwaitingGeneration(state.generation))
                else replaced()
            SemanticPublicationState.Missing ->
                when (this) {
                    BeforeSubmission -> SemanticStartupStep.Submit
                    AwaitingPublication -> SemanticStartupStep.Await(this)
                    is AwaitingGeneration -> replaced()
                }
            is SemanticPublicationState.Stale ->
                if (this == BeforeSubmission) SemanticStartupStep.Submit else replaced()
            is SemanticPublicationState.Rejected -> SemanticStartupStep.Rejected(state.rejection.startupFailure())
        }

    private fun allows(generation: String): Boolean = this !is AwaitingGeneration || this.generation == generation

    private fun replaced() = SemanticStartupStep.Rejected(SemanticCoordinatorStartupFailure.GenerationReplaced)
}

/** Closed outcomes for a client that starts or joins the installed semantic coordinator. */
sealed interface SemanticCoordinatorStartupFailure {
    /** An installation lifecycle transition, such as replacement, fences service admission. */
    data object InstallationFenced : SemanticCoordinatorStartupFailure

    /** The publication, socket, or recorded process does not prove this installation's ownership. */
    data object OwnershipRejected : SemanticCoordinatorStartupFailure

    /** The owned startup of the observed generation published a rejection. */
    data object StartupRejected : SemanticCoordinatorStartupFailure

    /** The awaited generation was replaced or retired before it became ready. */
    data object GenerationReplaced : SemanticCoordinatorStartupFailure

    /** The explicit startup budget expired before the awaited generation became ready. */
    data object StartupTimedOut : SemanticCoordinatorStartupFailure

    /** The supervisor submission or its admission rejected with this exact cause. */
    data class SubmissionRejected(val failure: PersistentBrokerServiceFailure) : SemanticCoordinatorStartupFailure
}

private const val READINESS_POLL_MILLIS = 25L
