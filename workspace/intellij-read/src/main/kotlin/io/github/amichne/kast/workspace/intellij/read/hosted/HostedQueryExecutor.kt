package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.intellij.openapi.progress.ProcessCanceledException
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
    data class Completed<Value>(val value: Value, val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION) : HostedExecution<Value>
    data class Rejected(val failure: HostedQueryFailure, val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION) : HostedExecution<Nothing>
}

/** Owns admission, deadline, cancellation drainage, and the final publication decision. */
internal class HostedQueryExecutor(
    serviceScope: CoroutineScope,
    private val diagnostics: (ReadLimits) -> HostedReadDiagnostics? = { null },
) {
    private val lifetime = HostedQueryLifetime()
    private val job = SupervisorJob(serviceScope.coroutineContext[Job])
    private val scope = CoroutineScope(serviceScope.coroutineContext + job)
    val endpoint: HostedQueryEndpoint get() = lifetime.endpoint

    suspend fun <Value> execute(endpoint: HostedQueryEndpoint, limits: ReadLimits = ReadLimits.Default, computation: suspend (HostedQueryProgress) -> Value): HostedExecution<Value> {
        val permit = when (val admission = lifetime.begin(endpoint)) {
            is HostedQueryAdmission.Admitted -> admission.permit
            is HostedQueryAdmission.Rejected -> {
                diagnostics(limits)?.finish(HostedDiagnosticOutcome.Rejected(admission.failure))
                return HostedExecution.Rejected(admission.failure)
            }
        }
        val progress = HostedQueryProgress(limits)
        val operation = scope.async {
            withTimeout(limits[ReadLimitParameter.HOST_QUERY_MILLIS].value.toLong()) {
                progress.observe(diagnostics(limits))
                ensureActive()
                computation(progress)
            }
        }
        val result = try {
            HostedExecution.Completed(operation.await(), progress.stage)
        } catch (_: TimeoutCancellationException) {
            HostedExecution.Rejected(HostedQueryFailure.BUDGET_EXCEEDED, progress.stage)
        } catch (_: ReadAction.CannotReadException) {
            HostedExecution.Rejected(HostedQueryFailure.READ_PREEMPTED, progress.stage)
        } catch (_: ProcessCanceledException) {
            HostedExecution.Rejected(HostedQueryFailure.CANCELLED, progress.stage)
        } catch (_: CancellationException) {
            HostedExecution.Rejected(HostedQueryFailure.CANCELLED, progress.stage)
        } catch (failure: RuntimeException) {
            progress.observation.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.HOSTED, failure, limits))
            HostedExecution.Rejected(HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME), progress.stage)
        } catch (failure: LinkageError) {
            progress.observation.unexpected(IntellijReadUnexpectedFailure.capture(IntellijReadStage.HOSTED, failure, limits))
            HostedExecution.Rejected(HostedQueryFailure.Platform(HostedPlatformFailureCause.LINKAGE), progress.stage)
        } finally {
            // Cancellation of the caller must not release admission while its analysis still runs.
            withContext(NonCancellable) { operation.cancelAndJoin() }
        }
        val final = when (val completion = lifetime.complete(permit)) {
            HostedQueryCompletion.Published -> result
            is HostedQueryCompletion.Rejected -> HostedExecution.Rejected(completion.failure, progress.stage)
        }
        // Even a service cancelled before the coroutine starts emits a terminal receipt.
        (progress.diagnostics ?: diagnostics(limits))?.finish(when (final) {
            is HostedExecution.Rejected -> HostedDiagnosticOutcome.Rejected(final.failure)
            is HostedExecution.Completed -> when (val value = final.value) {
                is HostedSemanticRead.Rejected -> HostedDiagnosticOutcome.Rejected(value.failure)
                is HostedReadPreparation.Rejected -> HostedDiagnosticOutcome.Rejected(value.failure)
                else -> HostedDiagnosticOutcome.Completed
            }
        })
        return final
    }

    fun retire() { lifetime.retire(); job.cancel() }
    suspend fun drain() { job.cancelAndJoin() }
}

internal const val HOSTED_QUERY_BUDGET_MILLIS = 2_000L

/** Monotone bounded stage evidence for success, rejection, and unexpected platform failure. */
enum class HostedQueryStage { REQUEST_ADMISSION, PROJECT_ADMISSION, EPOCH_OBSERVATION, MODEL_CAPTURE, SEMANTIC_READ, CONTENT_REVALIDATION, RESULT_DETACHED }

internal class HostedQueryProgress(val limits: ReadLimits = ReadLimits.Default) {
    @Volatile var diagnostics: HostedReadDiagnostics? = null
        private set
    val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
        get() = diagnostics ?: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None
    fun observe(value: HostedReadDiagnostics?) {
        diagnostics = value
        value?.stage(stage)
    }
    @Volatile var stage = HostedQueryStage.REQUEST_ADMISSION
        private set
    @Synchronized fun advance(next: HostedQueryStage) {
        check(next.ordinal >= stage.ordinal)
        stage = next
        diagnostics?.stage(next)
    }
}
