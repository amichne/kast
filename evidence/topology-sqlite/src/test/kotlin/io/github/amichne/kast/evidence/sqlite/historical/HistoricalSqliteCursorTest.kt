package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationReference
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalPageSize
import io.github.amichne.kast.topology.contract.historical.HistoricalReadFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalRowOrdinal
import io.github.amichne.kast.topology.contract.historical.HistoricalRowPosition
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePosition
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalSqliteCursorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `inventory cursor must name an existing row`() = opened { reader ->
        val request =
            HistoricalPageRequest(
                HistoricalPageSize.parse(1).value(),
                HistoricalRowPosition.After(
                    reader.inventory.identity,
                    HistoricalFactFamily.FILES,
                    HistoricalRowOrdinal.parse(99).value(),
                ),
            )
        assertEquals(
            HistoricalReadFailure.CURSOR_MISMATCH,
            assertInstanceOf<HistoricalReadResult.Rejected>(reader.files(request)).failure,
        )
    }

    @Test
    fun `usage cursor must name an existing occurrence from the exact query`() = opened { reader ->
        val first = query(reader)
        for (ordinal in listOf(99L, 0L)) {
            val position = HistoricalUsagePosition.After(first.cursorAfter(HistoricalRowOrdinal.parse(ordinal).value()))
            val after = query(reader, position)
            assertEquals(
                HistoricalReadFailure.CURSOR_MISMATCH,
                assertInstanceOf<HistoricalReadResult.Rejected>(reader.usage(after)).failure,
            )
        }
    }

    @Test
    fun `valid usage cursor adds one examined row and preserves exact next occurrence`() = opened { reader ->
        val first = query(reader)
        val after =
            query(reader, HistoricalUsagePosition.After(first.cursorAfter(HistoricalRowOrdinal.parse(1).value())))
        val page = assertInstanceOf<HistoricalReadResult.Read<*>>(reader.usage(after)).value
        val usage = assertInstanceOf<io.github.amichne.kast.topology.contract.historical.HistoricalUsagePage>(page)
        assertEquals(listOf(74), usage.rows.map { it.occurrence.range.startInclusive })
        assertEquals(3L, usage.work.rowsExamined.value)
    }

    @Test
    fun `syntactically valid absent exact target cannot produce empty success`() = opened { reader ->
        val source = HistoricalUsageOracle.snapshot().declarations.first()
        val absent = source.copy(id = source.id.copy(compiler = CompilerSymbolIdentity.parse("absent").value()))
        val query =
            HistoricalUsageQuery.admit(
                    reader.inventory,
                    setOf(HistoricalModuleId.parse("feature.main").value()),
                    setOf(HistoricalDeclarationReference.fromStored(reader.inventory.identity, absent)),
                    HistoricalPageSize.parse(1).value(),
                )
                .value()
        assertEquals(
            HistoricalReadFailure.DECLARATION_NOT_FOUND,
            assertInstanceOf<HistoricalReadResult.Rejected>(reader.usage(query)).failure,
        )
    }

    private fun query(
        reader: SqliteHistoricalSnapshotReader,
        position: HistoricalUsagePosition = HistoricalUsagePosition.First,
    ): HistoricalUsageQuery {
        val snapshot = HistoricalUsageOracle.snapshot()
        return HistoricalUsageQuery.admit(
                reader.inventory,
                setOf(HistoricalModuleId.parse("feature.main").value()),
                setOf(snapshot.reference(HistoricalUsageOracle.integerApi).value()),
                HistoricalPageSize.parse(1).value(),
                position,
            )
            .value()
    }

    private fun opened(block: (SqliteHistoricalSnapshotReader) -> Unit) {
        val path = directory.toRealPath().resolve("cursor.sqlite")
        SqliteHistoricalSnapshotStore.publish(path, HistoricalUsageOracle.snapshot()).value()
        SqliteHistoricalSnapshotStore.open(path).value().use(block)
    }

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
