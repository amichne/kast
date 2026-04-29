package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class AnalysisBackendContractFixture(
    val workspaceRoot: Path,
    val declarationFile: Path,
    val firstUsageFile: Path,
    val secondUsageFile: Path,
    val typeDeclarationFile: Path,
    val brokenFile: Path,
    val declarationLocation: Location,
    val firstUsageLocation: Location,
    val secondUsageLocation: Location,
    val typeHierarchyRootLocation: Location,
    val typeHierarchySupertypeLocation: Location,
    val typeHierarchySubtypeLocation: Location,
    val brokenPreview: String,
) {
    val symbolFqName: String = "sample.greet"
    val typeHierarchyRootFqName: String = "sample.FriendlyGreeter"
    private val renameTarget: String = "welcome"
    val typeHierarchyRootSupertypes: List<String> = listOf("sample.Greeter")
    val typeHierarchyChildFqNames: List<String> = listOf("sample.Greeter", "sample.LoudGreeter")

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

    val callHierarchyQuery: CallHierarchyQuery = CallHierarchyQuery(
        position = symbolQuery.position,
        direction = CallDirection.INCOMING,
        depth = 1,
        maxTotalCalls = 16,
        maxChildrenPerNode = 16,
    )

    val typeHierarchyQuery: TypeHierarchyQuery = TypeHierarchyQuery(
        position = FilePosition(
            filePath = typeHierarchyRootLocation.filePath,
            offset = typeHierarchyRootLocation.startOffset,
        ),
        direction = TypeHierarchyDirection.BOTH,
        depth = 1,
        maxResults = 16,
    )

    val renameQuery: RenameQuery = RenameQuery(
        position = symbolQuery.position,
        newName = renameTarget,
    )

    val fileOutlineQuery: FileOutlineQuery = FileOutlineQuery(
        filePath = declarationFile.toString(),
    )

    val workspaceSymbolQuery: WorkspaceSymbolQuery = WorkspaceSymbolQuery(
        pattern = "greet",
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
            val typeContent = """
                package sample

                interface Greeter
                open class FriendlyGreeter : Greeter
                class LoudGreeter : FriendlyGreeter()
            """.trimIndent() + "\n"
            val brokenContent = """
                package sample

                fun broken( = "oops"
            """.trimIndent() + "\n"

            val declarationFile = writeFile("src/main/kotlin/sample/Greeter.kt", declarationContent)
            val firstUsageFile = writeFile("src/main/kotlin/sample/Use.kt", firstUsageContent)
            val secondUsageFile = writeFile("src/main/kotlin/sample/SecondaryUse.kt", secondUsageContent)
            val typeDeclarationFile = writeFile("src/main/kotlin/sample/Types.kt", typeContent)
            val brokenFile = writeFile("src/main/kotlin/sample/Broken.kt", brokenContent)

            return AnalysisBackendContractFixture(
                workspaceRoot = normalizeStandalonePath(workspaceRoot),
                declarationFile = normalizeStandalonePath(declarationFile),
                firstUsageFile = normalizeStandalonePath(firstUsageFile),
                secondUsageFile = normalizeStandalonePath(secondUsageFile),
                typeDeclarationFile = normalizeStandalonePath(typeDeclarationFile),
                brokenFile = normalizeStandalonePath(brokenFile),
                declarationLocation = createLocation(
                    declarationFile,
                    declarationContent,
                    symbolText = "greet",
                    line = 3,
                    column = 5,
                ),
                firstUsageLocation = createLocation(
                    firstUsageFile,
                    firstUsageContent,
                    symbolText = "greet",
                    line = 3,
                    column = 21,
                ),
                secondUsageLocation = createLocation(
                    secondUsageFile,
                    secondUsageContent,
                    symbolText = "greet",
                    line = 3,
                    column = 26,
                ),
                typeHierarchyRootLocation = createLocation(
                    typeDeclarationFile,
                    typeContent,
                    symbolText = "FriendlyGreeter",
                    line = 4,
                    column = 12,
                ),
                typeHierarchySupertypeLocation = createLocation(
                    typeDeclarationFile,
                    typeContent,
                    symbolText = "Greeter",
                    line = 3,
                    column = 11,
                ),
                typeHierarchySubtypeLocation = createLocation(
                    typeDeclarationFile,
                    typeContent,
                    symbolText = "LoudGreeter",
                    line = 5,
                    column = 7,
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
            symbolText: String,
            line: Int,
            column: Int,
        ): Location {
            val symbolOffset = content.indexOf(symbolText)
            return Location(
                filePath = normalizePath(file),
                startOffset = symbolOffset,
                endOffset = symbolOffset + symbolText.length,
                startLine = line,
                startColumn = column,
                preview = content.lineSequence().drop(line - 1).first().trimEnd(),
            )
        }

        private fun normalizePath(path: Path): String = NormalizedPath.of(path).value

        private fun normalizeStandalonePath(path: Path): Path = NormalizedPath.of(path).toJavaPath()
    }
}

object AnalysisBackendContractAssertions {
    suspend fun assertCommonContract(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        assertResolveSymbol(backend, fixture)
        assertFindReferences(backend, fixture)
        assertCallHierarchy(backend, fixture)
        assertTypeHierarchy(backend, fixture)
        assertFileOutline(backend, fixture)
        assertWorkspaceSymbolSearch(backend, fixture)
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

    private suspend fun assertCallHierarchy(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.callHierarchy(fixture.callHierarchyQuery)

        expectEquals(fixture.symbolFqName, result.root.symbol.fqName, "call hierarchy root fqName")
        expectEquals(
            fixture.referenceLocations.map(Location::filePath),
            result.root.children.map { child -> checkNotNull(child.callSite).filePath },
            "call hierarchy call site files",
        )
        expectEquals(
            fixture.referenceLocations.map(Location::preview),
            result.root.children.map { child -> checkNotNull(child.callSite).preview },
            "call hierarchy call site previews",
        )
        expectEquals(1 + fixture.referenceLocations.size, result.stats.totalNodes, "call hierarchy total nodes")
        expectEquals(fixture.referenceLocations.size, result.stats.totalEdges, "call hierarchy total edges")
        expectEquals(0, result.stats.truncatedNodes, "call hierarchy truncated nodes")
    }

    private suspend fun assertTypeHierarchy(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.typeHierarchy(fixture.typeHierarchyQuery)

        expectEquals(fixture.typeHierarchyRootFqName, result.root.symbol.fqName, "type hierarchy root fqName")
        expectEquals(fixture.typeHierarchyRootSupertypes, result.root.symbol.supertypes, "type hierarchy root supertypes")
        expectEquals(
            fixture.typeHierarchyChildFqNames,
            result.root.children.map { child -> child.symbol.fqName },
            "type hierarchy child fqNames",
        )
        expectEquals(3, result.stats.totalNodes, "type hierarchy total nodes")
        expectEquals(1, result.stats.maxDepthReached, "type hierarchy max depth")
        expectEquals(false, result.stats.truncated, "type hierarchy truncated")
    }

    private suspend fun assertFileOutline(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.fileOutline(fixture.fileOutlineQuery)

        check(result.symbols.isNotEmpty()) { "file outline should return at least one symbol" }
        val fqNames = result.symbols.map { it.symbol.fqName }
        check(fixture.symbolFqName in fqNames) {
            "file outline expected to contain <${fixture.symbolFqName}> but had <$fqNames>"
        }
    }

    private suspend fun assertWorkspaceSymbolSearch(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.workspaceSymbolSearch(fixture.workspaceSymbolQuery)

        check(result.symbols.isNotEmpty()) { "workspace symbol search should return at least one symbol" }
        val fqNames = result.symbols.map { it.fqName }
        check(fixture.symbolFqName in fqNames) {
            "workspace symbol search expected to contain <${fixture.symbolFqName}> but had <$fqNames>"
        }
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
