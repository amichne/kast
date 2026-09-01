package io.github.amichne.kast.indexer

import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase
import io.github.amichne.kast.runtime.composition.InstalledWorkspaceRootFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IndexerBootstrapReporterTest {
    @Test
    fun `runtime observations publish monotonic starting progress and readiness`() {
        val observed = mutableListOf<InstalledIndexerBootstrapState>()
        val reporter = InstalledIndexerBootstrapReporter(
            InstalledIndexerBootstrapStateSink(observed::add),
        )

        reporter.observe(InstalledRuntimeBootstrapPhase.PROJECT_IMPORT)
        reporter.observe(InstalledRuntimeBootstrapPhase.INDEXING)
        reporter.observe(InstalledRuntimeBootstrapPhase.MODEL_CAPTURE)
        reporter.observe(InstalledRuntimeBootstrapPhase.RUNTIME_ASSEMBLY)
        reporter.beginTransportActivation()
        reporter.ready()

        assertEquals(
            listOf(
                InstalledIndexerBootstrapPhase.PROJECT_IMPORT,
                InstalledIndexerBootstrapPhase.INDEXING,
                InstalledIndexerBootstrapPhase.MODEL_CAPTURE,
                InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY,
                InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION,
            ),
            observed.filterIsInstance<InstalledIndexerBootstrapState.Starting>()
                .map { it.phase },
        )
        assertInstanceOf(
            InstalledIndexerBootstrapState.Ready::class.java,
            observed.last(),
        )
    }

    @Test
    fun `runtime rejection publishes the active phase with the exact finite failure set`() {
        val observed = mutableListOf<InstalledIndexerBootstrapState>()
        val reporter = InstalledIndexerBootstrapReporter(
            InstalledIndexerBootstrapStateSink(observed::add),
        )
        reporter.observe(InstalledRuntimeBootstrapPhase.PROJECT_IMPORT)
        reporter.observe(InstalledRuntimeBootstrapPhase.INDEXING)
        val failures = setOf<InstalledKastRuntimeFailure>(
            InstalledKastRuntimeFailure.WorkspaceRoot(
                InstalledWorkspaceRootFailure.SETTINGS_MARKER_UNAVAILABLE,
            ),
        )

        reporter.rejectRuntime(failures)

        val rejected = assertInstanceOf(
            InstalledIndexerBootstrapState.Rejected::class.java,
            observed.last(),
        )
        assertEquals(InstalledIndexerBootstrapPhase.INDEXING, rejected.phase)
        assertEquals(
            InstalledIndexerBootstrapTerminalFailure.Runtime(failures),
            rejected.failure,
        )
    }

    @Test
    fun `empty runtime failure and early readiness fail closed without false state`() {
        val observed = mutableListOf<InstalledIndexerBootstrapState>()
        val reporter = InstalledIndexerBootstrapReporter(
            InstalledIndexerBootstrapStateSink(observed::add),
        )

        assertEquals(
            InstalledIndexerBootstrapReport.Rejected(
                InstalledIndexerBootstrapReportFailure.EmptyRuntimeFailureSet,
            ),
            reporter.rejectRuntime(emptySet()),
        )
        assertEquals(
            InstalledIndexerBootstrapReport.Rejected(
                InstalledIndexerBootstrapReportFailure.Advance(
                    InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
                ),
            ),
            reporter.ready(),
        )
        assertEquals(1, observed.size)
        assertInstanceOf(InstalledIndexerBootstrapState.Starting::class.java, observed.single())
    }
}
