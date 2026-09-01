package io.github.amichne.kast.indexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IndexerBootstrapProgressTest {
    @Test
    fun `bootstrap progress advances import indexing and model phases without ambiguity`() {
        val importing = InstalledIndexerBootstrapProgress.start()

        assertEquals(InstalledIndexerBootstrapPhase.PROJECT_IMPORT, importing.phase)
        assertEquals(0, importing.completedPhases.value)
        assertEquals(5, importing.totalPhases.value)
        assertEquals(
            InstalledIndexerBootstrapAdvance.Rejected(
                InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
            ),
            importing.advance(InstalledIndexerBootstrapPhase.MODEL_CAPTURE),
        )

        val indexing = importing.advance(InstalledIndexerBootstrapPhase.INDEXING).advanced()
        val model = indexing.advance(InstalledIndexerBootstrapPhase.MODEL_CAPTURE).advanced()
        val assembly = model.advance(InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY).advanced()
        val transport = assembly.advance(
            InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION,
        ).advanced()
        val ready = transport.ready()

        assertEquals(1, indexing.completedPhases.value)
        assertEquals(2, model.completedPhases.value)
        assertEquals(3, assembly.completedPhases.value)
        assertEquals(4, transport.completedPhases.value)
        assertEquals(5, ready.completedPhases.value)
        assertEquals(ready.completedPhases, ready.totalPhases)
    }

    @Test
    fun `terminal failure retains its exact active phase and finite cause`() {
        val indexing = InstalledIndexerBootstrapProgress.start()
            .advance(InstalledIndexerBootstrapPhase.INDEXING)
            .advanced()
        val failure = InstalledIndexerBootstrapTerminalFailure.Transport(
            IndexerTransportFailure.SOCKET_BIND_FAILED,
        )

        assertEquals(
            InstalledIndexerBootstrapState.Rejected(
                InstalledIndexerBootstrapPhase.INDEXING,
                indexing.completedPhases,
                indexing.totalPhases,
                failure,
            ),
            indexing.reject(failure),
        )
    }

    private fun InstalledIndexerBootstrapAdvance.advanced(): InstalledIndexerBootstrapProgress =
        assertInstanceOf(InstalledIndexerBootstrapAdvance.Advanced::class.java, this).progress
}
