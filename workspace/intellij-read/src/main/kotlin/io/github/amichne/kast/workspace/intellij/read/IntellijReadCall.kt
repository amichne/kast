package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException

/** Counts invocation of the named boundary, never the hidden work inside a native executor. */
enum class IntellijReadCall {
    K2_ANALYSIS,
    RELATION_READ_ATTEMPT,
    RELATION_SCOPE_COMPILE,
    /** Request-scope API calls, including pre-processor index checks and provider rechecks. */
    RELATION_SCOPE_FILE_MEMBERSHIP,
    /** Live file-index membership inside a request scope predicate. */
    RELATION_SCOPE_SOURCE_MEMBERSHIP,
    RELATION_SCOPE_MODULE_MEMBERSHIP,
    RELATION_SCOPE_MODULE_SOURCE_KIND_MEMBERSHIP,
    RELATION_SCOPE_LIBRARY_POLICY,
    RELATION_FILE_ENUMERATION_PREPARATION,
    RELATION_SCOPE_FILE_ID_MEMBERSHIP,
    RELATION_SCOPE_FILE_ID_ARRAY,
    RELATION_SCOPE_FILE_COLLECTION,
    RELATION_SUBJECT_RESTORE,
    CALLBACK_FACT_PREPARATION,
    SEMANTIC_DEPENDENCY_CAPTURE,
    MODULE_INVENTORY,
    MODULE_DEPENDENCIES,
    KOTLIN_FACET,
    MODULE_SOURCE_ROOTS,
    SDK_CLASS_ROOTS,
    SDK_FILE_PLAN,
    RECURSIVE_CLASSPATH_ROOTS,
    VFS_FIND_FILE,
    VFS_CHILDREN,
    DOCUMENT_DIRTY_CHECK,
    DOCUMENT_CACHE_LOOKUP,
    DOCUMENT_COMMIT_CHECK,
    VFS_OPEN_STREAM,
    FILE_STREAM_READ,
    REFERENCE_SEARCH,
    DEFINITION_SEARCH,
    REFERENCE_CALLBACK,
    DEFINITION_CALLBACK,
    SCOPED_FILE_INDEX,
    EXACT_PACKAGE_INDEX,
    SCOPED_FILE_CALLBACK,
    PSI_FIND_FILE,
    PSI_FIND_ELEMENT,
    PSI_REFERENCES,
    DECLARATION_PSI_SCAN,
}

/** RETURNED proves only that control returned; a typed domain rejection is still RETURNED. */
enum class IntellijReadCallOutcome {
    RETURNED,
    CANCELLED,
    FAILED,
}

interface IntellijReadCallScope {
    fun finish(outcome: IntellijReadCallOutcome)

    data object None : IntellijReadCallScope {
        override fun finish(outcome: IntellijReadCallOutcome) = Unit
    }
}

/** Synchronous effect scope: parentage cannot escape to another thread or across suspension. */
// Observe every exceptional exit, including fatal native failures, then preserve the original throwable.
inline fun <Value> IntellijReadObservation.call(call: IntellijReadCall, crossinline effect: () -> Value): Value {
    return enterCall(call).observe(effect)
}

/** Retains the original exceptional exit while closing its explicit effect scope. */
@Suppress("TooGenericExceptionCaught")
inline fun <Value> IntellijReadCallScope.observe(crossinline effect: () -> Value): Value {
    val scope = this
    val value =
        try {
            effect()
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
