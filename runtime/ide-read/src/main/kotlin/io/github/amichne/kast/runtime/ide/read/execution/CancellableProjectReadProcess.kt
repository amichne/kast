package io.github.amichne.kast.runtime.ide.read.execution

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult

/** Factory for one non-reusable, externally cancellable read-process capability. */
internal fun interface CancellableProjectReadProcessFactory {
    fun prepare(): PreparedCancellableProjectRead
}

/** State-specific effect capability installed before one permit begins execution. */
internal interface PreparedCancellableProjectRead {
    fun <Value : Any> execute(
        port: CancellableProjectReadPort,
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value>

    fun cancel()
}

/** Live IDEA 262 process factory; platform types remain confined to this hosted child package. */
internal object LiveCancellableProjectReadProcessFactory : CancellableProjectReadProcessFactory {
    override fun prepare(): PreparedCancellableProjectRead = LiveCancellableProjectReadProcess()
}

/** Exact progress-indicator capability for one read computation. */
private class LiveCancellableProjectReadProcess : PreparedCancellableProjectRead {
    private val indicator = EmptyProgressIndicator()

    /**
     * Proof transition: `(PreparedCancellableProjectRead, operation) -> read result`.
     *
     * Runs the operation beneath the exact indicator owned by this capability. Cancellation is
     * propagated by IDEA as `ProcessCanceledException`; Project extraction remains in [port].
     */
    override fun <Value : Any> execute(
        port: CancellableProjectReadPort,
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> = ProgressManager.getInstance().runProcess(
        Computable { port.execute(operation) },
        indicator,
    )

    /** Signals cancellation to the exact process that owns this indicator. */
    override fun cancel() = indicator.cancel()
}
