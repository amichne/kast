package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetachedSourceRootUrlObservationTest {
    @Test
    fun `missing local source root retains its platform URL identity`() {
        assertEquals(
            "/workspace/kast/src/test/resources",
            refinedValue(
                LiveDetachedModelCapture.observeLocalSourceRootPath(
                    "file:///workspace/kast/src/test/resources",
                ),
            ),
        )
    }

    @Test
    fun `non-local source root URL fails closed`() {
        assertEquals(
            DetachedModelCaptureFailure.INVALID_SOURCE_ROOT,
            rejectedFailure(
                LiveDetachedModelCapture.observeLocalSourceRootPath(
                    "jar:///workspace/kast/dependency.jar!/sources",
                ),
            ),
        )
    }

    private fun <Value, Failure> refinedValue(
        refinement: Refinement<Value, Failure>,
    ): Value = when (refinement) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> error("Expected refinement, observed ${refinement.failure}")
    }

    private fun <Value, Failure> rejectedFailure(
        refinement: Refinement<Value, Failure>,
    ): Failure = when (refinement) {
        is Refinement.Refined -> error("Expected rejection, observed ${refinement.value}")
        is Refinement.Rejected -> refinement.failure
    }
}
