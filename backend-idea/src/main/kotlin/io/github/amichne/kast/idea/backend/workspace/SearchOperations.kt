@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.workspace

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.result.WorkspaceSearchResult
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.shared.analysis.FileOutlineBuilder
import io.github.amichne.kast.shared.analysis.SymbolSearchMatcher
import io.github.amichne.kast.shared.analysis.toSymbolModel
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.FileSystems
import java.nio.file.Path
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal suspend fun KastPluginBackend.fileOutlineOperation(query: ParsedFileOutlineQuery): FileOutlineResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.FILE_OUTLINE, "kast.idea.fileOutline") {
            timedReadAction(telemetry, IdeaTelemetryScope.FILE_OUTLINE, "kast.idea.fileOutline.readAction") {
                val file = findKtFile(query.filePath.value)
                FileOutlineResult(symbols = FileOutlineBuilder.build(file))
            }
        }
    }

internal suspend fun KastPluginBackend.workspaceSymbolSearchOperation(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.idea.workspaceSymbolSearch") {
        val matcher = SymbolSearchMatcher.create(query.pattern.value, query.regex)
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val symbols = mutableListOf<Symbol>()

        timedReadAction(telemetry, IdeaTelemetryScope.WORKSPACE_SYMBOL_SEARCH, "kast.idea.workspaceSymbolSearch.readAction") {
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allClassNames,
                lookupByName = cache::getClassesByName,
            )
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allMethodNames,
                lookupByName = cache::getMethodsByName,
            )
            collectMatchingSymbols(
                scope = scope,
                matcher = matcher,
                query = query,
                symbols = symbols,
                allNames = cache.allFieldNames,
                lookupByName = cache::getFieldsByName,
            )
        }

        WorkspaceSymbolResult(symbols = symbols)
        }
    }

internal suspend fun KastPluginBackend.workspaceSearchOperation(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.WORKSPACE_SEARCH, "kast.idea.workspaceSearch") { span ->
            val candidateFiles = timedReadAction(
                telemetry,
                IdeaTelemetryScope.WORKSPACE_SEARCH,
                "kast.idea.workspaceSearch.listFiles",
            ) {
                val scope = GlobalSearchScope.projectScope(project)
                val fileGlob = query.fileGlob?.value
                kotlinFileType()?.let { fileType ->
                    FileTypeIndex.getFiles(fileType, scope)
                        .asSequence()
                        .filter { file -> isWorkspaceFile(file.path) }
                        .filter { file -> fileGlob == null || matchesFileGlob(file.path, fileGlob) }
                        .sortedBy { it.path }
                        .toList()
                } ?: emptyList()
            }
            span.setAttribute("kast.workspaceSearch.candidateFileCount", candidateFiles.size)
            val regex = compileWorkspaceSearchRegex(query)
            val matches = mutableListOf<SearchMatch>()
            var truncated = false

            outer@ for (file in candidateFiles) {
                ProgressManager.checkCanceled()
                val content = timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.WORKSPACE_SEARCH,
                    "kast.idea.workspaceSearch.readFile",
                ) {
                    String(file.contentsToByteArray(), file.charset)
                }
                for ((lineIndex, line) in content.lineSequence().withIndex()) {
                    for (column in searchColumns(line, query, regex)) {
                        if (matches.size >= query.maxResults.value) {
                            truncated = true
                            break@outer
                        }
                        matches += SearchMatch(
                            filePath = file.path,
                            lineNumber = lineIndex + 1,
                            columnNumber = column + 1,
                            preview = line.trimEnd(),
                        )
                    }
                }
            }

            span.setAttribute("kast.workspaceSearch.resultCount", matches.size)
            span.setAttribute("kast.workspaceSearch.truncated", truncated)
            WorkspaceSearchResult(matches = matches, truncated = truncated)
        }
    }

internal fun <T : PsiElement> KastPluginBackend.collectMatchingSymbols(
        scope: GlobalSearchScope,
        matcher: SymbolSearchMatcher,
        query: ParsedWorkspaceSymbolQuery,
        symbols: MutableList<Symbol>,
        allNames: Array<String>,
        lookupByName: (String, GlobalSearchScope) -> Array<out T>,
    ) {
        for (name in allNames) {
            if (symbols.size >= query.maxResults.value) break
            if (!matcher.matches(name)) continue
            for (element in lookupByName(name, scope)) {
                if (symbols.size >= query.maxResults.value) break
                val ktElement = element.navigationElement as? KtNamedDeclaration ?: continue
                val filePath = ktElement.containingFile?.virtualFile?.path ?: continue
                if (!isWorkspaceFile(filePath)) continue
                val symbol = ktElement.toSymbolModel(
                    containingDeclaration = null,
                    includeDeclarationScope = query.includeDeclarationScope,
                )
                if (query.kind == null || symbol.kind == query.kind) {
                    symbols += symbol
                }
            }
        }
    }

internal fun KastPluginBackend.isWorkspaceFile(filePath: String): Boolean =
        sharedWorkspaceIdentity.contains(filePath)

internal fun KastPluginBackend.compileWorkspaceSearchRegex(query: ParsedWorkspaceSearchQuery): Regex? =
        if (query.regex) {
            Regex(
                query.pattern.value,
                if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
            )
        } else {
            null
        }

internal fun KastPluginBackend.searchColumns(
        line: String,
        query: ParsedWorkspaceSearchQuery,
        regex: Regex?,
    ): Sequence<Int> = sequence {
        if (regex != null) {
            regex.findAll(line).forEach { match -> yield(match.range.first) }
            return@sequence
        }

        var searchFrom = 0
        while (true) {
            val occurrence = line.indexOf(
                query.pattern.value,
                startIndex = searchFrom,
                ignoreCase = !query.caseSensitive,
            )
            if (occurrence < 0) break
            yield(occurrence)
            searchFrom = occurrence + query.pattern.value.length.coerceAtLeast(1)
        }
    }

internal fun KastPluginBackend.matchesFileGlob(filePath: String, fileGlob: String): Boolean {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$fileGlob")
        val path = Path.of(filePath)
        val relative = sharedWorkspaceIdentity.relativizeIfContained(path)
        return listOfNotNull(relative, relative?.fileName, path.fileName).any(matcher::matches)
    }
