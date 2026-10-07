package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticCompilerDistributionTest {
    @Test
    fun `native distribution without explicit alternate input remains eligible`() {
        assertEquals(Refinement.Refined(Unit), admitSemanticCompilerDistribution(null, null))
        assertEquals(Refinement.Refined(Unit), admitSemanticCompilerDistribution("", ""))
    }

    @Test
    fun `explicit Kotlin home requires its own content inventory`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED),
            admitSemanticCompilerDistribution("/alternate/kotlin", null),
        )
    }

    @Test
    fun `explicit IntelliJ plugin root requires its own content inventory`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED),
            admitSemanticCompilerDistribution(null, "/alternate/plugin"),
        )
    }

    @Test
    fun `blank explicit input is not silently interpreted as absence`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED),
            admitSemanticCompilerDistribution(" ", null),
        )
    }

    @Test
    fun `unmodeled distribution preserves exact bounded diagnostic cause`() {
        assertEquals(
            IntellijReadTermination.SEMANTIC_INPUT_COMPILER_DISTRIBUTION_UNMODELED,
            SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED.termination(),
        )
    }
}
