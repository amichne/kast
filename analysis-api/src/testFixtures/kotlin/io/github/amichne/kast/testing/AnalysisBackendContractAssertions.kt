package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import java.nio.file.Files
import java.nio.file.Path

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
        assertWorkspaceSearch(backend, fixture)
        assertDiagnostics(backend, fixture)
        assertRename(backend, fixture)
    }

    private suspend fun assertResolveSymbol(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.resolveSymbol(fixture.symbolQuery.parsed())

        expectEquals(fixture.symbolFqName, result.symbol.fqName, "resolved symbol fqName")
        expectEquals(SymbolKind.FUNCTION, result.symbol.kind, "resolved symbol kind")
        expectEquals(fixture.declarationLocation.filePath, result.symbol.location.filePath, "resolved symbol file")
        expectEquals(fixture.declarationLocation.startOffset, result.symbol.location.startOffset, "resolved symbol start offset")
    }

    private suspend fun assertFindReferences(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.findReferences(fixture.referencesQuery.parsed())

        expectEquals(fixture.symbolFqName, result.declaration?.fqName, "references declaration fqName")
        expectEquals(
            fixture.referenceLocations.map(Location::filePath),
            result.references.map { occurrence -> occurrence.location.filePath },
            "reference files",
        )
        expectEquals(
            fixture.referenceLocations.map(Location::preview),
            result.references.map { occurrence -> occurrence.location.preview },
            "reference previews",
        )
    }

    private suspend fun assertCallHierarchy(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.callHierarchy(fixture.callHierarchyQuery.parsed())

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
        val result = backend.typeHierarchy(fixture.typeHierarchyQuery.parsed())

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
        val result = backend.fileOutline(fixture.fileOutlineQuery.parsed())

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
        val result = backend.workspaceSymbolSearch(fixture.workspaceSymbolQuery.parsed())

        check(result.symbols.isNotEmpty()) { "workspace symbol search should return at least one symbol" }
        val fqNames = result.symbols.map { it.fqName }
        check(fixture.symbolFqName in fqNames) {
            "workspace symbol search expected to contain <${fixture.symbolFqName}> but had <$fqNames>"
        }
    }

    private suspend fun assertWorkspaceSearch(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.workspaceSearch(fixture.workspaceSearchQuery.parsed())

        check(result.matches.isNotEmpty()) { "workspace search should return at least one match" }
        val matchedFiles = result.matches.map { match -> NormalizedPath.of(Path.of(match.filePath)).value }
        check(fixture.declarationFile.toString() in matchedFiles) {
            "workspace search expected to include <${fixture.declarationFile}> but had <$matchedFiles>"
        }
        check(result.matches.any { match -> match.preview.contains("greet") }) {
            "workspace search expected a preview containing greet but had <${result.matches.map { it.preview }}>"
        }
        expectEquals(false, result.truncated, "workspace search truncated")
    }

    private suspend fun assertDiagnostics(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.diagnostics(fixture.diagnosticsQuery.parsed())

        expectEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome, "diagnostics semantic outcome")
        expectEquals(2, result.requestedFileCount, "diagnostics requested files")
        expectEquals(2, result.analyzedFileCount, "diagnostics analyzed files")
        expectEquals(0, result.skippedFileCount, "diagnostics skipped files")
        expectEquals(
            listOf(FileAnalysisState.ANALYZED, FileAnalysisState.ANALYZED),
            result.fileStatuses.map { it.state },
            "diagnostics file states",
        )
        expectEquals(
            fixture.diagnosticsQuery.filePaths,
            result.fileHashes.map { fileHash -> fileHash.filePath },
            "diagnostics hash paths",
        )
        expectEquals(
            fixture.diagnosticsQuery.filePaths.map { filePath ->
                FileHashing.sha256(Files.readString(Path.of(filePath)))
            },
            result.fileHashes.map { fileHash -> fileHash.hash },
            "diagnostics current content hashes",
        )
        check(result.diagnostics.any { it.code == "FAKE_PARSE_ERROR" }) {
            "ordinary compiler diagnostics must remain analyzed evidence"
        }
        check(result.diagnostics.none { it.code == "ANALYSIS_FAILURE" }) {
            "ordinary compiler diagnostics must not be reported as analysis failures"
        }
    }

    private suspend fun assertRename(
        backend: AnalysisBackend,
        fixture: AnalysisBackendContractFixture,
    ) {
        val result = backend.rename(fixture.renameQuery.parsed())

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
