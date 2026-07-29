package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteSourceIndexExplorerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `declaration search uses persistent FTS and supports short literal queries`() {
        val store = SqliteSourceIndexStore(tempDir)
        store.use {
            store.ensureSchema()
            val first = declaration("demo.search.SearchService", "SearchService.kt", 18)
            val second = declaration("demo.search.ServiceRegistry", "ServiceRegistry.kt", 24)
            val unrelated = declaration("demo.other.Unrelated", "Unrelated.kt", 12)
            store.replaceDeclarationsFromFiles(
                listOf(
                    first.filePath to listOf(first),
                    second.filePath to listOf(second),
                    unrelated.filePath to listOf(unrelated),
                ),
            )

            assertEquals(
                listOf(first.fqName, second.fqName),
                store.searchDeclarations(NonBlankString("Service"), PositiveInt(10)).map(DeclarationRow::fqName),
            )
            assertEquals(
                listOf(first.fqName, second.fqName),
                store.searchDeclarations(NonBlankString("Se"), PositiveInt(10)).map(DeclarationRow::fqName),
            )
        }
    }

    private fun declaration(
        fqName: String,
        fileName: String,
        offset: Int,
    ): DeclarationRow = DeclarationRow(
        fqName = fqName,
        kind = DeclarationKind.CLASS,
        visibility = DeclarationVisibility.INTERNAL,
        filePath = tempDir.resolve(fileName).toAbsolutePath().normalize().toString(),
        declarationOffset = offset,
        modulePath = ":demo",
        sourceSet = "main",
    )
}
