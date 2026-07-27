package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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
