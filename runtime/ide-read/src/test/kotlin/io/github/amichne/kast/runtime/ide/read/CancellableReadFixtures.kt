package io.github.amichne.kast.runtime.ide.read

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadExecutor
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadOperation
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadPort
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadProcessFactory
import io.github.amichne.kast.runtime.ide.read.execution.PreparedCancellableProjectRead
import io.github.amichne.kast.runtime.ide.read.revalidation.ProjectReadEpochObserver
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch

internal fun cancellableExecutor(
    singleFlight: ProjectReadSingleFlight,
    port: CancellableProjectReadPort,
    processFactory: CancellableProjectReadProcessFactory = DirectReadProcessFactory,
    epochObserver: ProjectReadEpochObserver = UnusedProjectReadEpochObserver,
): CancellableProjectReadExecutor {
    val constructor = CancellableProjectReadExecutor::class.java.getDeclaredConstructor(
        ProjectReadSingleFlight::class.java,
        CancellableProjectReadPort::class.java,
        CancellableProjectReadProcessFactory::class.java,
        ProjectReadEpochObserver::class.java,
    )
    constructor.isAccessible = true
    return constructor.newInstance(singleFlight, port, processFactory, epochObserver)
}

private object UnusedProjectReadEpochObserver : ProjectReadEpochObserver {
    override fun observe() = error("epoch observer was used by the non-revalidated path")
}

/** Host-free process capability used by controller-focused unit tests. */
private object DirectReadProcessFactory : CancellableProjectReadProcessFactory {
    override fun prepare(): PreparedCancellableProjectRead = DirectReadProcess
}

private object DirectReadProcess : PreparedCancellableProjectRead {
    override fun <Value : Any> execute(
        port: CancellableProjectReadPort,
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> = port.execute(operation)

    override fun cancel() = Unit
}

internal class SignalingReadProcessFactory : CancellableProjectReadProcessFactory {
    val cancelled = CountDownLatch(1)
    val cancellation = ProcessCanceledException()

    override fun prepare(): PreparedCancellableProjectRead = object : PreparedCancellableProjectRead {
        override fun <Value : Any> execute(
            port: CancellableProjectReadPort,
            operation: CancellableProjectReadOperation<Value>,
        ): AdmittedProjectReadExecutionResult<Value> {
            val result = port.execute(operation)
            if (cancelled.count == 0L) throw cancellation
            return result
        }

        override fun cancel() {
            cancelled.countDown()
        }
    }
}

internal class InvokingReadPort : CancellableProjectReadPort {
    var calls: Int = 0
        private set

    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        calls += 1
        return AdmittedProjectReadExecutionResult.Completed(operation.execute(testProject))
    }
}

internal class RejectingReadPort(
    private val failure: AdmittedProjectReadExecutionFailure,
) : CancellableProjectReadPort {
    var calls: Int = 0
        private set

    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        calls += 1
        return AdmittedProjectReadExecutionResult.Rejected(failure)
    }
}

internal class ThrowingReadPort(
    private val failure: Throwable,
) : CancellableProjectReadPort {
    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> = throw failure
}

internal class CancelOnceReadPort(
    private val cancellation: ProcessCanceledException,
) : CancellableProjectReadPort {
    private var first = true

    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        if (first) {
            first = false
            throw cancellation
        }
        return AdmittedProjectReadExecutionResult.Completed(operation.execute(testProject))
    }
}

internal class BlockingReadPort(
    private val entered: CountDownLatch,
    private val continueRead: CountDownLatch,
) : CancellableProjectReadPort {
    var calls: Int = 0
        private set

    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        calls += 1
        entered.countDown()
        continueRead.await()
        return AdmittedProjectReadExecutionResult.Completed(operation.execute(testProject))
    }
}

internal val testProject: Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
) { proxy, method, arguments ->
    when (method.name) {
        "equals" -> proxy === arguments?.single()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "cancellable-read-test-project"
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
} as Project
