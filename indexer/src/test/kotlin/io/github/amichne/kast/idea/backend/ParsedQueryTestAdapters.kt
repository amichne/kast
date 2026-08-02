package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

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
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.validation.parsed

internal suspend fun KastIndexerBackend.resolveSymbol(query: SymbolQuery) = resolveSymbol(query.parsed())
internal suspend fun KastIndexerBackend.findReferences(query: ReferencesQuery) = findReferences(query.parsed())
internal suspend fun KastIndexerBackend.callHierarchy(query: CallHierarchyQuery) = callHierarchy(query.parsed())
internal suspend fun KastIndexerBackend.typeHierarchy(query: TypeHierarchyQuery) = typeHierarchy(query.parsed())
internal suspend fun KastIndexerBackend.semanticInsertionPoint(query: SemanticInsertionQuery) = semanticInsertionPoint(query.parsed())
internal suspend fun KastIndexerBackend.diagnostics(query: DiagnosticsQuery) = diagnostics(query.parsed())
internal suspend fun KastIndexerBackend.rename(query: RenameQuery) = rename(query.parsed())
internal suspend fun KastIndexerBackend.applyEdits(query: ApplyEditsQuery) = applyEdits(query.parsed())
internal suspend fun KastIndexerBackend.optimizeImports(query: ImportOptimizeQuery) = optimizeImports(query.parsed())
internal suspend fun KastIndexerBackend.refresh(query: RefreshQuery) = refresh(query.parsed())
internal suspend fun KastIndexerBackend.fileOutline(query: FileOutlineQuery) = fileOutline(query.parsed())
internal suspend fun KastIndexerBackend.workspaceSymbolSearch(query: WorkspaceSymbolQuery) = workspaceSymbolSearch(query.parsed())
internal suspend fun KastIndexerBackend.workspaceSearch(query: WorkspaceSearchQuery) = workspaceSearch(query.parsed())
internal suspend fun KastIndexerBackend.workspaceFiles(query: WorkspaceFilesQuery) = workspaceFiles(query.parsed())
internal suspend fun KastIndexerBackend.implementations(query: ImplementationsQuery) = implementations(query.parsed())
internal suspend fun KastIndexerBackend.codeActions(query: CodeActionsQuery) = codeActions(query.parsed())
internal suspend fun KastIndexerBackend.completions(query: CompletionsQuery) = completions(query.parsed())
