package io.github.amichne.kast.runtime.ide.read.execution

import com.intellij.openapi.project.Project
import io.github.amichne.kast.runtime.ide.read.ProjectReadContinuation
import io.github.amichne.kast.runtime.ide.read.ProjectReadExecutionAdmissionFailure
import io.github.amichne.kast.runtime.ide.read.ProjectReadCancellationCause
import io.github.amichne.kast.runtime.ide.read.ProjectReadPermitTerminal
import io.github.amichne.kast.runtime.ide.read.ProjectReadRetirement
import io.github.amichne.kast.runtime.ide.read.ProjectReadRetirementCause
import io.github.amichne.kast.runtime.ide.read.RetiredProjectReadAuthority
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult

/** Finite failures for binding the exact admitted Project and freshness capability. */
sealed interface CancellableProjectReadExecutorAdmissionFailure {
    data object WrongProject : CancellableProjectReadExecutorAdmissionFailure
    data class FreshnessRejected(val cause: VfsPassiveReadAdmissionFailure) :
        CancellableProjectReadExecutorAdmissionFailure
}

/** Finite non-disposal host rejections before an operation may return a value. */
internal enum class CancellableProjectReadHostRejection {
    WRONG_THREAD,
    EXISTING_READ_ACCESS,
    PROJECT_NOT_OPEN,
    DUMB_MODE,
}

/** Closed `AdmittedIdeProject + freshness -> CancellableProjectReadExecutor` transition. */
sealed interface CancellableProjectReadExecutorAdmission {
    data class Admitted(val executor: CancellableProjectReadExecutor) :
        CancellableProjectReadExecutorAdmission

    data class Rejected(val failure: CancellableProjectReadExecutorAdmissionFailure) :
        CancellableProjectReadExecutorAdmission
}

/** Internal operation invoked only inside the admitted Project's cancellable read. */
internal fun interface CancellableProjectReadOperation<out Value : Any> {
    fun execute(project: Project): Value
}

/** Closed observation port used to separate controller proof from the live IntelliJ effect. */
internal interface CancellableProjectReadPort {
    fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value>
}

/** Why a computed or rejected host result could not be admitted after terminalization. */
internal sealed interface CancellableProjectReadInvalidation {
    data class Terminalized(
        val terminal: ProjectReadPermitTerminal,
        val continuation: ProjectReadContinuation,
    ) : CancellableProjectReadInvalidation

    data class AlreadyEnded(val terminal: ProjectReadPermitTerminal) :
        CancellableProjectReadInvalidation

    data class Deferred(val terminal: ProjectReadPermitTerminal) :
        CancellableProjectReadInvalidation

    data class CancellationPreservedAcrossRetirement(
        val cause: ProjectReadCancellationCause,
        val retirement: ProjectReadRetirement.Retired,
    ) : CancellableProjectReadInvalidation

    data class HostAlreadyRetired(
        val cause: ProjectReadRetirementCause,
        val terminal: ProjectReadPermitTerminal,
    ) : CancellableProjectReadInvalidation

    data object ExecutionInProgress : CancellableProjectReadInvalidation
    data object NotOwned : CancellableProjectReadInvalidation
}

/** Closed result of one permit-scoped cancellable semantic read. */
internal sealed interface CancellableProjectReadResult<out Value : Any> {
    data class Completed<Value : Any>(
        val value: Value,
        val continuation: ProjectReadContinuation,
    ) : CancellableProjectReadResult<Value>

    data class HostRejected(
        val failure: CancellableProjectReadHostRejection,
        val continuation: ProjectReadContinuation,
    ) : CancellableProjectReadResult<Nothing>

    data class ProjectDisposed(
        val retiredAuthority: RetiredProjectReadAuthority,
    ) : CancellableProjectReadResult<Nothing>

    data class PermitRejected(
        val failure: ProjectReadExecutionAdmissionFailure,
    ) : CancellableProjectReadResult<Nothing>

    data class PermitInvalidated(
        val invalidation: CancellableProjectReadInvalidation,
    ) : CancellableProjectReadResult<Nothing>
}
