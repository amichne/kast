package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Normalize expected boundary failures before graph/module ownership records them; cancellation never becomes data. */
@Suppress("TooGenericExceptionCaught")
internal fun <Value> observeSemanticInputCapture(
    limits: ReadLimits,
    observation: IntellijReadObservation,
    action: () -> SemanticCapture<Value>,
): SemanticCapture<Value> {
    return try {
        action()
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
    } catch (failure: LinkageError) {
        observation.unexpected(
            IntellijReadUnexpectedFailure.capture(
                IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION,
                failure,
                limits,
            )
        )
        Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
    } catch (failure: RuntimeException) {
        observation.unexpected(
            IntellijReadUnexpectedFailure.capture(
                IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION,
                failure,
                limits,
            )
        )
        Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
    }
}
