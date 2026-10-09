package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException

/** Declares only the adapter paths wired to this capability. */
enum class IntellijReadActionKind {
    RELATION
}

/** Only API modes wired to an observed native adapter belong in this vocabulary. */
enum class IntellijReadActionMode {
    READ
}

interface IntellijReadActionScope : IntellijReadCallScope {
    fun enterAttempt(): IntellijReadCallScope

    data object None : IntellijReadActionScope {
        override fun enterAttempt(): IntellijReadCallScope = IntellijReadCallScope.None

        override fun finish(outcome: IntellijReadCallOutcome) = Unit
    }
}

inline fun <Value> IntellijReadActionScope.attempt(crossinline effect: () -> Value): Value =
    enterAttempt().observe(effect)

/** An explicit asynchronous scope. It owns no thread-local parent stack or native object. */
@Suppress("TooGenericExceptionCaught")
suspend inline fun <Value> IntellijReadObservation.observeReadAction(
    kind: IntellijReadActionKind,
    mode: IntellijReadActionMode,
    crossinline effect: suspend (IntellijReadActionScope) -> Value,
): Value {
    val scope = submitReadAction(kind, mode)
    val value =
        try {
            effect(scope)
        } catch (failure: Throwable) {
            scope.finish(
                when (failure) {
                    is ProcessCanceledException,
                    is CancellationException -> IntellijReadCallOutcome.CANCELLED
                    else -> IntellijReadCallOutcome.FAILED
                }
            )
            throw failure
        }
    scope.finish(IntellijReadCallOutcome.RETURNED)
    return value
}
