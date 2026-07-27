package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.validation.FileHashing
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

    val diagnosticsQuery: DiagnosticsQuery = DiagnosticsQuery(
        filePaths = listOf(declarationFile.toString(), brokenFile.toString()),
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

    val workspaceSearchQuery: WorkspaceSearchQuery = WorkspaceSearchQuery(
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
                workspaceRoot = normalizeHeadlessPath(workspaceRoot),
                declarationFile = normalizeHeadlessPath(declarationFile),
                firstUsageFile = normalizeHeadlessPath(firstUsageFile),
                secondUsageFile = normalizeHeadlessPath(secondUsageFile),
                typeDeclarationFile = normalizeHeadlessPath(typeDeclarationFile),
                brokenFile = normalizeHeadlessPath(brokenFile),
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

        private fun normalizeHeadlessPath(path: Path): Path = NormalizedPath.of(path).toJavaPath()
    }
}
