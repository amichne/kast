package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalPageSize
import io.github.amichne.kast.topology.contract.historical.HistoricalReadFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalSelectedDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePage
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalSqliteQueryAuthorityTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `same digest with invented complete coverage cannot authorize a qualified artifact read`() {
        val snapshot = HistoricalUsageOracle.snapshot()
        val artifact = directory.toRealPath().resolve("qualified.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        SqliteHistoricalSnapshotStore.open(artifact).value().use { reader ->
            val source = HistoricalModuleId.parse("feature.test").value()
            val target =
                assertInstanceOf<HistoricalReadResult.Read<HistoricalSelectedDeclaration>>(
                        reader.select(HistoricalUsageOracle.integerApi)
                    )
                    .value
                    .reference
            val actual =
                HistoricalUsageQuery.admit(
                        reader.inventory,
                        setOf(source),
                        setOf(target),
                        HistoricalPageSize.parse(1).value(),
                    )
                    .value()
            val original = assertInstanceOf<HistoricalReadResult.Read<HistoricalUsagePage>>(reader.usage(actual)).value
            assertInstanceOf<HistoricalUsageCoverage.Qualified>(original.query.coverage)
            assertEquals(1, original.rows.size)
            val invented = completeInventory(reader.inventory, source)
            val forged =
                HistoricalUsageQuery.admit(
                        invented,
                        setOf(source),
                        setOf(target),
                        HistoricalPageSize.parse(1).value(),
                    )
                    .value()
            assertInstanceOf<HistoricalUsageCoverage.Complete>(forged.coverage)
            assertEquals(
                HistoricalReadFailure.QUERY_INVENTORY_MISMATCH,
                assertInstanceOf<HistoricalReadResult.Rejected>(reader.usage(forged)).failure,
            )
        }
    }

    private fun completeInventory(inventory: HistoricalSnapshotInventory, source: HistoricalModuleId) =
        HistoricalSnapshotInventory.fromStored(
                inventory.identity,
                inventory.header,
                inventory.modules,
                inventory.coverage.map { coverage ->
                    if (coverage.module == source && coverage.family == HistoricalFactFamily.REFERENCES) {
                        coverage.copy(status = HistoricalCoverageStatus.Complete)
                    } else coverage
                },
                inventory.counts,
            )
            .value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
