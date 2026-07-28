package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class ClosingApplyBackend(
    private val delegate: CloseableAnalysisBackend,
    private val applyStarted: CompletableDeferred<Unit>,
    private val applyStopped: CompletableDeferred<Unit>,
    private val descriptorFile: Path,
    private val descriptorIdentity: String,
    private val descriptorRetainedDuringStop: AtomicBoolean,
) : CloseableAnalysisBackend by delegate {
    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyStarted.complete(Unit)
        return try {
            delay(250)
            delegate.applyEdits(query)
        } finally {
            descriptorRetainedDuringStop.set(
                Files.exists(descriptorFile) && Files.readString(descriptorFile).contains(descriptorIdentity),
            )
            applyStopped.complete(Unit)
        }
    }
}

internal class AdmittedApplyBackend(
    private val delegate: CloseableAnalysisBackend,
    private val applyStarted: CompletableDeferred<Unit>,
) : CloseableAnalysisBackend by delegate {
    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyStarted.complete(Unit)
        delay(100)
        return delegate.applyEdits(query)
    }
}

internal class CountingCloseBackend(
    private val delegate: CloseableAnalysisBackend,
) : CloseableAnalysisBackend by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
        delegate.close()
    }
}

internal class SuspendedStatusBackend(
    private val delegate: CloseableAnalysisBackend,
) : CloseableAnalysisBackend by delegate {
    val started = java.util.concurrent.CountDownLatch(1)
    private val continuation = AtomicReference<Continuation<Unit>?>(null)
    private val active = AtomicBoolean(false)
    var closeCount: Int = 0
        private set
    var closedWhileActive: Boolean = false
        private set

    override suspend fun runtimeStatus() = try {
        active.set(true)
        suspendCoroutine<Unit> { suspended ->
            continuation.set(suspended)
            started.countDown()
        }
        delegate.runtimeStatus()
    } finally {
        active.set(false)
    }

    fun release() {
        continuation.getAndSet(null)?.resume(Unit)
    }

    override fun close() {
        closedWhileActive = active.get()
        closeCount += 1
        delegate.close()
    }
}

internal class RecordingLocalRpcServer(
    private val closeEvents: MutableList<String>,
    private val closeFailure: Throwable? = null,
) : LocalRpcServer {
    override fun await() = Unit

    override fun close() {
        closeEvents += "transport"
        closeFailure?.let { throw it }
    }
}

internal class RecordingCloseable(
    private val closeEvents: MutableList<String>,
    private val phase: String,
    private val closeFailure: Throwable? = null,
) : java.io.Closeable {
    override fun close() {
        closeEvents += phase
        closeFailure?.let { throw it }
    }
}

internal class RecordingCloseBackend(
    private val delegate: CloseableAnalysisBackend,
    private val closeEvents: MutableList<String>,
    private val beforeClose: () -> Unit = {},
) : CloseableAnalysisBackend by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeEvents += "backend"
        beforeClose()
        closeCount += 1
        delegate.close()
    }
}
