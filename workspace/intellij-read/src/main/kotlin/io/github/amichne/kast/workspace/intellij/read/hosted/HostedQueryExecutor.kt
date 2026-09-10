package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.application.ReadAction
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
internal class HostedQueryExecutor(serviceScope: CoroutineScope) {
    private val lifetime = HostedQueryLifetime()
    private val job = SupervisorJob(serviceScope.coroutineContext[Job])
    private val scope = CoroutineScope(serviceScope.coroutineContext + job)
    val endpoint: HostedQueryEndpoint get() = lifetime.endpoint

    suspend fun <Value> execute(endpoint: HostedQueryEndpoint, computation: suspend (HostedQueryProgress) -> Value): HostedExecution<Value> {
        val permit = when (val admission = lifetime.begin(endpoint)) {
            is HostedQueryAdmission.Admitted -> admission.permit
            is HostedQueryAdmission.Rejected -> return HostedExecution.Rejected(admission.failure)
        }
        val progress = HostedQueryProgress()
        val operation = scope.async {
            withTimeout(HOSTED_QUERY_BUDGET_MILLIS) {
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
        } catch (_: RuntimeException) {
            HostedExecution.Rejected(HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME), progress.stage)
        } catch (_: LinkageError) {
            HostedExecution.Rejected(HostedQueryFailure.Platform(HostedPlatformFailureCause.LINKAGE), progress.stage)
        } finally {
            // Cancellation of the caller must not release admission while its analysis still runs.
            withContext(NonCancellable) { operation.cancelAndJoin() }
        }
        return when (val completion = lifetime.complete(permit)) {
            HostedQueryCompletion.Published -> result
            is HostedQueryCompletion.Rejected -> HostedExecution.Rejected(completion.failure, progress.stage)
        }
    }

    fun retire() { lifetime.retire(); job.cancel() }
    suspend fun drain() { job.cancelAndJoin() }
}

internal const val HOSTED_QUERY_BUDGET_MILLIS = 2_000L

/** Monotone bounded stage evidence for success, rejection, and unexpected platform failure. */
enum class HostedQueryStage { REQUEST_ADMISSION, PROJECT_ADMISSION, EPOCH_OBSERVATION, MODEL_CAPTURE, SEMANTIC_READ, CONTENT_REVALIDATION, RESULT_DETACHED }

internal class HostedQueryProgress {
    @Volatile var stage = HostedQueryStage.REQUEST_ADMISSION
        private set
    @Synchronized fun advance(next: HostedQueryStage) {
        check(next.ordinal >= stage.ordinal)
        stage = next
    }
}
