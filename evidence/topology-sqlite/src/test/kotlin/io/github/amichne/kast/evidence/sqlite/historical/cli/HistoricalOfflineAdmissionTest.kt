package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotReader
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HistoricalOfflineAdmissionTest {
    @Test
    fun `known request rejection precedes every declaration lookup`() {
        val snapshot = HistoricalUsageOracle.snapshot()
        val request = HistoricalUsageOracle.request(snapshot)
        val reader = ForbiddenHistoricalReader(HistoricalSnapshotInventory.from(snapshot))
        val cases =
            listOf(
                request.copy(
                    targets =
                        request.targets.map {
                            it.copy(declaration = it.declaration.copy(compiler = "id\uD800"))
                        }
                ) to OfflineFailureCode.INVALID_TEXT,
                request.copy(size = 0) to OfflineFailureCode.PAGE_SIZE_OUTSIDE_LIMIT,
                request.copy(size = 1001) to OfflineFailureCode.PAGE_SIZE_OUTSIDE_LIMIT,
                request.copy(sourceModules = listOf("absent")) to OfflineFailureCode.SOURCE_OUTSIDE_CAPTURE,
                request.copy(cursor = UsageCursorDocument(snapshot.identity.value, "f".repeat(64), 0)) to
                    OfflineFailureCode.CURSOR_MISMATCH,
                request.copy(targets = request.targets + request.targets.first()) to OfflineFailureCode.INVALID_REQUEST,
                request.copy(targets = request.targets.map { it.copy(snapshot = "e".repeat(64)) }) to
                    OfflineFailureCode.SNAPSHOT_MISMATCH,
                request.copy(
                    targets = request.targets.map { it.copy(declaration = it.declaration.copy(start = -1)) }
                ) to OfflineFailureCode.INVALID_REQUEST,
            )
        cases.forEach { (input, expected) ->
            assertEquals(
                expected,
                assertInstanceOf<Refinement.Rejected<OfflineFailureCode>>(input.admit(reader)).failure,
            )
        }
    }

    @Test
    fun `page budgets preserve actionable finite rejection`() {
        listOf(0, 1001).forEach { size ->
            assertEquals(
                OfflineFailureCode.PAGE_SIZE_OUTSIDE_LIMIT,
                assertInstanceOf<Refinement.Rejected<OfflineFailureCode>>(PageRequestDocument(size).admit()).failure,
            )
        }
    }

    @Test
    fun `uncaptured references reject before every declaration lookup`() {
        val snapshot = HistoricalUsageOracle.snapshot(HistoricalProfile.STRUCTURE_V1)
        val reader = ForbiddenHistoricalReader(HistoricalSnapshotInventory.from(snapshot))
        assertEquals(
            OfflineFailureCode.REFERENCES_NOT_CAPTURED,
            assertInstanceOf<Refinement.Rejected<OfflineFailureCode>>(
                    HistoricalUsageOracle.request(snapshot).admit(reader)
                )
                .failure,
        )
    }
}

private class ForbiddenHistoricalReader(override val inventory: HistoricalSnapshotInventory) :
    HistoricalSnapshotReader {
    override val openWork
        get() = error("No artifact work request was expected")

    override fun files(request: HistoricalPageRequest) = error("No file read was expected")

    override fun declarations(request: HistoricalPageRequest) = error("No declaration read was expected")

    override fun select(id: HistoricalDeclarationId) = error("No target lookup was expected")

    override fun usage(query: HistoricalUsageQuery) = error("No usage read was expected")

    override fun close() = error("No close was expected")
}
