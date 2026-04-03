package io.github.amichne.kast.testing

import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class AnalysisBackendContractFixture(
    val workspaceRoot: Path,
    val declarationFile: Path,
    val firstUsageFile: Path,
    val secondUsageFile: Path,
    val brokenFile: Path,
    val declarationLocation: Location,
    val firstUsageLocation: Location,
    val secondUsageLocation: Location,
    val brokenPreview: String,
) {
    val symbolFqName: String = "sample.greet"
    private val renameTarget: String = "welcome"

    val symbolQuery: SymbolQuery = SymbolQuery(
        position = FilePosition(
            filePath = firstUsageLocation.filePath,
            offset = firstUsageLocation.startOffset,
        ),
    )

    val referencesQuery: ReferencesQuery = ReferencesQuery(
        position = symbolQuery.position,
        includeDeclaration = true,
    )

    val diagnosticsQuery: DiagnosticsQuery = DiagnosticsQuery(
        filePaths = listOf(normalizePath(brokenFile)),
    )

    val renameQuery: RenameQuery = RenameQuery(
        position = symbolQuery.position,
        newName = renameTarget,
    )

    val referenceLocations: List<Location> = listOf(secondUsageLocation, firstUsageLocation)

    val renameEdits: List<TextEdit> = listOf(declarationLocation, secondUsageLocation, firstUsageLocation).map { location ->
        TextEdit(
            filePath = location.filePath,
            startOffset = location.startOffset,
            endOffset = location.endOffset,
            newText = renameTarget,
        )
    }.sortedWith(compareBy({ it.filePath }, { it.startOffset }))

    val renameFileHashes: List<Pair<String, String>> = renameEdits
        .map(TextEdit::filePath)
        .distinct()
        .sorted()
        .map { filePath ->
            filePath to FileHashing.sha256(Path.of(filePath).readText())
        }

    companion object {
        fun create(
            workspaceRoot: Path,
            writeFile: (relativePath: String, content: String) -> Path = defaultWriter(workspaceRoot),
        ): AnalysisBackendContractFixture {
            val declarationContent = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n"
            val firstUsageContent = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n"
            val secondUsageContent = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n"
            val brokenContent = """
                package sample

                fun broken( = "oops"
            """.trimIndent() + "\n"

            val declarationFile = writeFile("src/main/kotlin/sample/Greeter.kt", declarationContent)
            val firstUsageFile = writeFile("src/main/kotlin/sample/Use.kt", firstUsageContent)
            val secondUsageFile = writeFile("src/main/kotlin/sample/SecondaryUse.kt", secondUsageContent)
            val brokenFile = writeFile("src/main/kotlin/sample/Broken.kt", brokenContent)

            return AnalysisBackendContractFixture(
                workspaceRoot = normalizeStandalonePath(workspaceRoot),
                declarationFile = normalizeStandalonePath(declarationFile),
                firstUsageFile = normalizeStandalonePath(firstUsageFile),
                secondUsageFile = normalizeStandalonePath(secondUsageFile),
                brokenFile = normalizeStandalonePath(brokenFile),
                declarationLocation = createLocation(
                    declarationFile,
                    declarationContent,
                    line = 3,
                    column = 5,
                ),
                firstUsageLocation = createLocation(
                    firstUsageFile,
                    firstUsageContent,
                    line = 3,
                    column = 21,
                ),
                secondUsageLocation = createLocation(
                    secondUsageFile,
                    secondUsageContent,
                    line = 3,
                    column = 26,
                ),
                brokenPreview = """fun broken( = "oops"""",
            )
        }

        private fun defaultWriter(workspaceRoot: Path): (String, String) -> Path = { relativePath, content ->
            val path = workspaceRoot.resolve(relativePath)
            Files.createDirectories(path.parent)
            path.writeText(content)
            path
        }

        private fun createLocation(
            file: Path,
            content: String,
            line: Int,
            column: Int,
        ): Location {
            val symbolOffset = content.indexOf("greet")
            return Location(
                filePath = normalizePath(file),
                startOffset = symbolOffset,
                endOffset = symbolOffset + "greet".length,
                startLine = line,
                startColumn = column,
                preview = content.lineSequence().drop(line - 1).first().trimEnd(),
            )
        }

        private fun normalizePath(path: Path): String = normalizeStandalonePath(path).toString()

        private fun normalizeStandalonePath(path: Path): Path {
            val absolutePath = path.toAbsolutePath().normalize()
            return runCatching { absolutePath.toRealPath().normalize() }.getOrDefault(absolutePath)
        }
    }
}

object AnalysisBackendContractAssertions {
    suspend fun assertCommonContract(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        assertResolveSymbol(backend, fixture)
        assertFindReferences(backend, fixture)
        assertRename(backend, fixture)
    }

    private suspend fun assertResolveSymbol(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.resolveSymbol(fixture.symbolQuery)

        expectEquals(fixture.symbolFqName, result.symbol.fqName, "resolved symbol fqName")
        expectEquals(SymbolKind.FUNCTION, result.symbol.kind, "resolved symbol kind")
        expectEquals(fixture.declarationLocation.filePath, result.symbol.location.filePath, "resolved symbol file")
        expectEquals(fixture.declarationLocation.startOffset, result.symbol.location.startOffset, "resolved symbol start offset")
    }

    private suspend fun assertFindReferences(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.findReferences(fixture.referencesQuery)

        expectEquals(fixture.symbolFqName, result.declaration?.fqName, "references declaration fqName")
        expectEquals(
            fixture.referenceLocations.map(Location::filePath),
            result.references.map(Location::filePath),
            "reference files",
        )
        expectEquals(
            fixture.referenceLocations.map(Location::preview),
            result.references.map(Location::preview),
            "reference previews",
        )
    }

    suspend fun assertDiagnostics(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.diagnostics(fixture.diagnosticsQuery)
        val diagnostic = result.diagnostics.firstOrNull()
            ?: error("expected at least one diagnostic for ${fixture.brokenFile}")

        expectEquals(DiagnosticSeverity.ERROR, diagnostic.severity, "diagnostic severity")
        expectEquals(fixture.brokenFile.toString(), diagnostic.location.filePath, "diagnostic file")
        expectEquals(fixture.brokenPreview, diagnostic.location.preview, "diagnostic preview")
    }

    private suspend fun assertRename(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.rename(fixture.renameQuery)

        expectEquals(
            fixture.renameEdits.map { edit -> edit.filePath to edit.newText },
            result.edits.map { edit -> edit.filePath to edit.newText },
            "rename edit targets",
        )
        expectEquals(
            fixture.renameEdits.map(TextEdit::filePath).distinct(),
            result.affectedFiles,
            "rename affected files",
        )
        expectEquals(
            fixture.renameFileHashes,
            result.fileHashes.map { hash -> hash.filePath to hash.hash },
            "rename file hashes",
        )
    }

    private fun expectEquals(
        expected: Any?,
        actual: Any?,
        label: String,
    ) {
        check(expected == actual) {
            "$label expected <$expected> but was <$actual>"
        }
    }
}
