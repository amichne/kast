package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal sealed interface HostedExecution<out Value> {
    data class Completed<Value>(
        val value: Value,
        val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION,
        val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
    ) : HostedExecution<Value>

    data class Rejected(
        val failure: HostedQueryFailure,
        val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION,
        val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
    ) : HostedExecution<Nothing>
}

/** Owns admission, deadline, cancellation drainage, and the final publication decision. */
internal class HostedQueryExecutor(
    serviceScope: CoroutineScope,
    private val clock: () -> Long = System::nanoTime,
    private val diagnostics: (ReadLimits) -> HostedReadDiagnostics? = { null },
) {
    private val lifetime = HostedQueryLifetime()
    private val job = SupervisorJob(serviceScope.coroutineContext[Job])
    private val scope = CoroutineScope(serviceScope.coroutineContext + job)
    val endpoint: HostedQueryEndpoint
        get() = lifetime.endpoint

    suspend fun <Value> execute(
        endpoint: HostedQueryEndpoint,
        limits: ReadLimits = ReadLimits.Default,
        outcome: (Value) -> HostedDiagnosticOutcome = ::hostedExecutionOutcome,
        executionBudget: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
        publication: HostedReadPublicationAdmission = HostedReadPublicationAdmission.Containment,
        completion: HostedReadCompletionPolicy = HostedReadCompletionPolicy.HOST_CONTAINMENT,
        observeReadIdentity: (HostedReadTraceIdentity) -> Unit = {},
        computation: suspend (HostedQueryProgress) -> Value,
    ): HostedExecution<Value> {
        // Export is optional; request-owned work accounting exists even when diagnostics are not exported.
        val diagnostic =
            requestDiagnostics(limits).also {
                observeReadIdentity(it.identity)
                it.stage(HostedQueryStage.REQUEST_ADMISSION)
            }
        val permit =
            when (
                val admission =
                    lifetime.begin(endpoint, limits, WorkspaceReadOperationIdentity.Traced(diagnostic.identity.value))
            ) {
                is HostedQueryAdmission.Admitted -> admission.permit
                is HostedQueryAdmission.Rejected -> {
                    diagnostic.finish(HostedDiagnosticOutcome.Rejected(admission.failure))
                    return HostedExecution.Rejected(admission.failure)
                }
            }
        val progress = HostedQueryProgress(limits, clock, executionBudget, publication, completion)
        progress.observe(diagnostic)
        val operation = scope.async {
            withTimeout(limits[ReadLimitParameter.HOST_QUERY_MILLIS].value.toLong()) {
                ensureActive()
                computation(progress)
            }
        }
        val result =
            try {
                HostedExecution.Completed(operation.await(), progress.stage, progress.executionBudget)
            } catch (_: TimeoutCancellationException) {
                HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED, progress.stage, progress.executionBudget)
            } catch (_: ReadAction.CannotReadException) {
                HostedExecution.Rejected(HostedQueryFailure.READ_PREEMPTED, progress.stage, progress.executionBudget)
            } catch (_: ProcessCanceledException) {
                HostedExecution.Rejected(HostedQueryFailure.CANCELLED, progress.stage, progress.executionBudget)
            } catch (_: CancellationException) {
                HostedExecution.Rejected(HostedQueryFailure.CANCELLED, progress.stage, progress.executionBudget)
            } catch (failure: RuntimeException) {
                progress.observation.unexpected(
                    IntellijReadUnexpectedFailure.capture(IntellijReadStage.HOSTED, failure, limits)
                )
                HostedExecution.Rejected(
                    HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME),
                    progress.stage,
                    progress.executionBudget,
                )
            } catch (failure: LinkageError) {
                progress.observation.unexpected(
                    IntellijReadUnexpectedFailure.capture(IntellijReadStage.HOSTED, failure, limits)
                )
                HostedExecution.Rejected(
                    HostedQueryFailure.Platform(HostedPlatformFailureCause.LINKAGE),
                    progress.stage,
                    progress.executionBudget,
                )
            } finally {
                // Cancellation of the caller must not release admission while its analysis still runs.
                progress.observation.phase(
                    io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase.CANCELLATION_DRAINAGE
                )
                withContext(NonCancellable) { operation.cancelAndJoin() }
            }
        val completed =
            when (val completion = lifetime.complete(permit)) {
                HostedQueryCompletion.Published -> result
                is HostedQueryCompletion.Rejected ->
                    HostedExecution.Rejected(completion.failure, progress.stage, progress.executionBudget)
            }
        val evaluation = (completed as? HostedExecution.Completed)?.let { outcome(it.value) }
        val final = progress.publishAccepted(completed, evaluation)
        // Even a service cancelled before the coroutine starts emits a terminal receipt.
        diagnostic.finish(
            when (final) {
                is HostedExecution.Rejected -> HostedDiagnosticOutcome.Rejected(final.failure)
                is HostedExecution.Completed -> requireNotNull(evaluation)
            }
        )
        return final
    }

    private fun requestDiagnostics(limits: ReadLimits): HostedReadDiagnostics =
        diagnostics(limits) ?: HostedReadDiagnostics(clock, limits, publish = {})

    fun settlement(): WorkspaceNativeReadSettlement = lifetime.settlement()

    fun retire() {
        lifetime.retire()
        job.cancel()
    }

    suspend fun drain() {
        job.cancelAndJoin()
    }
}

/** Publication runs only after native drainage and lifetime admission have both completed. */
private fun <Value> HostedQueryProgress.publishAccepted(
    completed: HostedExecution<Value>,
    evaluation: HostedDiagnosticOutcome?,
): HostedExecution<Value> {
    return when (completed) {
        is HostedExecution.Rejected -> {
            publicationEffects.discard()
            completed
        }
        is HostedExecution.Completed -> {
            if (evaluation is HostedDiagnosticOutcome.Rejected) {
                publicationEffects.discard()
                return completed
            }
            val published =
                if (evaluation == HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED))
                    publicationEffects.commitRetainedRejection()
                else publicationEffects.commit()
            when (published) {
                is Refinement.Refined -> completed
                is Refinement.Rejected -> HostedExecution.Rejected(published.failure, stage, executionBudget)
            }
        }
    }
}

internal val HOSTED_QUERY_BUDGET_MILLIS = ReadLimits.Default[ReadLimitParameter.HOST_QUERY_MILLIS].value.toLong()

/** Monotone bounded stage evidence for success, rejection, and unexpected platform failure. */
enum class HostedQueryStage {
    REQUEST_ADMISSION,
    PROJECT_ADMISSION,
    EPOCH_OBSERVATION,
    MODEL_CAPTURE,
    SEMANTIC_READ,
    CONTENT_REVALIDATION,
    RESULT_DETACHED,
}

internal class HostedQueryProgress(
    val limits: ReadLimits = ReadLimits.Default,
    clock: () -> Long = System::nanoTime,
    executionBudget: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
    publication: HostedReadPublicationAdmission = HostedReadPublicationAdmission.Containment,
    private val completion: HostedReadCompletionPolicy = HostedReadCompletionPolicy.HOST_CONTAINMENT,
) {
    private val deadline = HostedReadDeadline(limits, clock, executionBudget, publication)
    val publicationEffects = HostedReadPublicationOwner { observation }

    @Volatile
    var executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent
        private set

    fun admitSemanticTime() =
        deadline.admit(diagnostics).also { admitted ->
            if (admitted is Refinement.Refined) {
                executionBudget =
                    ExecutionBudgetPresence.Present(ExecutionBudgetReport.from(admitted.value.executionBudget))
            }
        }

    fun admitSemanticRead(): Refinement<HostedAdmittedRead, HostedQueryFailure> =
        when (val admitted = admitSemanticTime()) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> Refinement.Refined(deadline.admitCompletion(admitted.value, completion))
        }

    fun validateCompletion(admitted: HostedAdmittedRead) = deadline.validateCompletion(admitted.completion)

    @Volatile
    var diagnostics: HostedReadDiagnostics? = null
        private set

    val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
        get() = diagnostics ?: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None

    fun observe(value: HostedReadDiagnostics?) {
        diagnostics = value
        value?.stage(stage)
    }

    @Volatile
    var stage = HostedQueryStage.REQUEST_ADMISSION
        private set

    @Synchronized
    fun restartAfterMovedRead() {
        check(
            stage == HostedQueryStage.EPOCH_OBSERVATION ||
                stage == HostedQueryStage.MODEL_CAPTURE ||
                stage == HostedQueryStage.CONTENT_REVALIDATION
        )
        publicationEffects.restart()
        stage = HostedQueryStage.REQUEST_ADMISSION
    }

    @Synchronized
    fun advance(next: HostedQueryStage) {
        check(next.ordinal >= stage.ordinal)
        stage = next
        diagnostics?.stage(next)
    }
}

internal fun hostedExecutionOutcome(value: Any?): HostedDiagnosticOutcome =
    when (value) {
        is HostedSemanticRead.Rejected -> HostedDiagnosticOutcome.Rejected(value.failure)
        is HostedReadPreparation.Rejected -> HostedDiagnosticOutcome.Rejected(value.failure)
        else -> HostedDiagnosticOutcome.Completed
    }
