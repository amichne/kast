package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.validation.parsed

internal suspend fun StandaloneAnalysisBackend.resolveSymbol(query: SymbolQuery) = resolveSymbol(query.parsed())
internal suspend fun StandaloneAnalysisBackend.findReferences(query: ReferencesQuery) = findReferences(query.parsed())
internal suspend fun StandaloneAnalysisBackend.callHierarchy(query: CallHierarchyQuery) = callHierarchy(query.parsed())
internal suspend fun StandaloneAnalysisBackend.typeHierarchy(query: TypeHierarchyQuery) = typeHierarchy(query.parsed())
internal suspend fun StandaloneAnalysisBackend.semanticInsertionPoint(query: SemanticInsertionQuery) = semanticInsertionPoint(query.parsed())
internal suspend fun StandaloneAnalysisBackend.diagnostics(query: DiagnosticsQuery) = diagnostics(query.parsed())
internal suspend fun StandaloneAnalysisBackend.rename(query: RenameQuery) = rename(query.parsed())
internal suspend fun StandaloneAnalysisBackend.applyEdits(query: ApplyEditsQuery) = applyEdits(query.parsed())
internal suspend fun StandaloneAnalysisBackend.optimizeImports(query: ImportOptimizeQuery) = optimizeImports(query.parsed())
internal suspend fun StandaloneAnalysisBackend.refresh(query: RefreshQuery) = refresh(query.parsed())
internal suspend fun StandaloneAnalysisBackend.fileOutline(query: FileOutlineQuery) = fileOutline(query.parsed())
internal suspend fun StandaloneAnalysisBackend.workspaceSymbolSearch(query: WorkspaceSymbolQuery) = workspaceSymbolSearch(query.parsed())
internal suspend fun StandaloneAnalysisBackend.workspaceFiles(query: WorkspaceFilesQuery) = workspaceFiles(query.parsed())
internal suspend fun StandaloneAnalysisBackend.implementations(query: ImplementationsQuery) = implementations(query.parsed())
internal suspend fun StandaloneAnalysisBackend.codeActions(query: CodeActionsQuery) = codeActions(query.parsed())
internal suspend fun StandaloneAnalysisBackend.completions(query: CompletionsQuery) = completions(query.parsed())
