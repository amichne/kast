@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.workspace

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiElement
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.CodeActionsResult
import io.github.amichne.kast.api.contract.result.CompletionItem
import io.github.amichne.kast.api.contract.result.CompletionsResult
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.toSymbolModel
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal suspend fun KastPluginBackend.codeActionsOperation(query: ParsedCodeActionsQuery): CodeActionsResult = withContext(readDispatcher) {
        readAction {
            findKtFile(query.position.filePath.value)
            CodeActionsResult(actions = emptyList())
        }
    }

internal suspend fun KastPluginBackend.completionsOperation(query: ParsedCompletionsQuery): CompletionsResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.COMPLETIONS, "kast.idea.completions") {
        readAction {
            val file = findKtFile(query.position.filePath.value)
            val kindFilter = query.kindFilter
            val items = mutableListOf<CompletionItem>()
            file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtNamedDeclaration &&
                        element !is KtParameter &&
                        element.name != null &&
                        element.textOffset <= query.position.offset.value
                    ) {
                        val symbol = element.toSymbolModel(
                            containingDeclaration = null,
                            includeDocumentation = true,
                        )
                        if (kindFilter == null || symbol.kind in kindFilter) {
                            items += CompletionItem(
                                name = element.name ?: symbol.fqName.substringAfterLast('.'),
                                fqName = symbol.fqName,
                                kind = symbol.kind,
                                type = symbol.type ?: symbol.returnType,
                                parameters = symbol.parameters,
                                documentation = symbol.documentation,
                            )
                        }
                    }
                    super.visitElement(element)
                }
            })
            val deduped = items
                .distinctBy { Triple(it.fqName, it.kind, it.name) }
                .sortedWith(compareBy({ it.name }, { it.fqName }))
            val capped = deduped.take(query.maxResults.value)
            CompletionsResult(
                items = capped,
                exhaustive = deduped.size <= capped.size,
            )
        }
        }
    }

internal suspend fun KastPluginBackend.workspaceFilesOperation(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult = withContext(readDispatcher) {
        telemetry.inSpan(
            IdeaTelemetryScope.WORKSPACE_FILES,
            "kast.idea.workspaceFiles",
            attributes = mapOf(
                "kast.workspaceFiles.moduleName" to query.moduleName?.value,
                "kast.workspaceFiles.includeFiles" to query.includeFiles,
                "kast.workspaceFiles.maxFilesPerModule" to query.maxFilesPerModule?.value,
                "kast.workspaceFiles.kindDomain" to query.kindDomain.name,
                "kast.workspaceFiles.hasSnapshotToken" to (query.snapshotToken != null),
                "kast.workspaceFiles.hasPageToken" to (query.pageToken != null),
            ),
        ) { span ->
            val result = workspaceFilePaging.query(query)
            val modules = result.modules
            span.setAttribute("kast.workspaceFiles.moduleCount", modules.size)
            span.setAttribute("kast.workspaceFiles.totalFileCount", modules.sumOf { it.fileCount })
            span.setAttribute("kast.workspaceFiles.returnedFileCount", modules.sumOf { it.files.size })
            span.setAttribute("kast.workspaceFiles.truncatedModuleCount", modules.count { it.filesTruncated })
            result
        }
    }

internal suspend fun KastPluginBackend.semanticInsertionPointOperation(
        query: ParsedSemanticInsertionQuery,
    ): SemanticInsertionResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.SEMANTIC_INSERTION_POINT, "kast.idea.semanticInsertionPoint") {
        readAction {
            val file = findKtFile(query.position.filePath.value)
            SemanticInsertionPointResolver.resolve(file, query)
        }
        }
    }
