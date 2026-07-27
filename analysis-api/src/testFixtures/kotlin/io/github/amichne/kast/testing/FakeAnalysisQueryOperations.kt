package io.github.amichne.kast.testing

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.validation.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun FakeAnalysisBackend.capabilitiesResult(): BackendCapabilities = BackendCapabilities(
    backendName = backendName,
    backendVersion = "0.1.0-test",
    workspaceRoot = workspaceRoot.toString(),
    readCapabilities = setOf(
        ReadCapability.RESOLVE_SYMBOL,
        ReadCapability.FIND_REFERENCES,
        ReadCapability.CALL_HIERARCHY,
        ReadCapability.TYPE_HIERARCHY,
        ReadCapability.SEMANTIC_INSERTION_POINT,
        ReadCapability.DIAGNOSTICS,
        ReadCapability.FILE_OUTLINE,
        ReadCapability.WORKSPACE_SYMBOL_SEARCH,
        ReadCapability.WORKSPACE_SEARCH,
        ReadCapability.WORKSPACE_FILES,
        ReadCapability.SEMANTIC_GRAPH,
        ReadCapability.IMPLEMENTATIONS,
        ReadCapability.CODE_ACTIONS,
        ReadCapability.COMPLETIONS,
    ),
    mutationCapabilities = setOf(
        MutationCapability.RENAME,
        MutationCapability.APPLY_EDITS,
        MutationCapability.FILE_OPERATIONS,
        MutationCapability.OPTIMIZE_IMPORTS,
        MutationCapability.REFRESH_WORKSPACE,
    ),
    limits = limits,
)

internal suspend fun FakeAnalysisBackend.healthResult(): HealthResponse {
    val capabilities = capabilitiesResult()
    return HealthResponse(
        backendName = capabilities.backendName,
        backendVersion = capabilities.backendVersion,
        workspaceRoot = capabilities.workspaceRoot,
    )
}

internal suspend fun FakeAnalysisBackend.refreshResult(query: ParsedRefreshQuery): RefreshResult {
    if (query.filePaths.isEmpty()) return RefreshResult.full()
    val fileStatuses = query.filePaths.map { filePath ->
        if (filePath.value in availableFiles && Files.exists(filePath.toJavaPath())) {
            SemanticAdmissionStatus.admitted(filePath)
        } else {
            SemanticAdmissionStatus.removed(filePath)
        }
    }
    return RefreshResult.focused(
        fileStatuses = fileStatuses,
        attemptCount = 1,
        elapsedMillis = 0,
    )
}

internal suspend fun FakeAnalysisBackend.fileOutlineResult(query: ParsedFileOutlineQuery): FileOutlineResult {
    requireKnownFile(query.filePath.value)
    val allSymbols = buildList {
        add(symbol)
        add(typeHierarchyRootSymbol)
        add(typeHierarchySupertypeSymbol)
        add(typeHierarchySubtypeSymbol)
    }
    val fileSymbols = allSymbols
        .filter { it.location.filePath == query.filePath.value }
        .map { OutlineSymbol(symbol = it) }
    return FileOutlineResult(symbols = fileSymbols)
}

internal suspend fun FakeAnalysisBackend.workspaceSymbolSearchResult(query: ParsedWorkspaceSymbolQuery): WorkspaceSymbolResult {
    val allSymbols = buildList {
        add(symbol)
        add(typeHierarchyRootSymbol)
        add(typeHierarchySupertypeSymbol)
        add(typeHierarchySubtypeSymbol)
    }
    val pattern = query.pattern.value
    val matcher: (String) -> Boolean = if (query.regex) {
        val regex = Regex(pattern);
        { name -> regex.containsMatchIn(name) }
    } else {
        { name -> name.contains(pattern, ignoreCase = true) }
    }
    val matched = allSymbols
        .filter { sym ->
            val simpleName = sym.fqName.substringAfterLast('.')
            matcher(simpleName) && (query.kind == null || sym.kind == query.kind)
        }
        .take(query.maxResults.value)
    return WorkspaceSymbolResult(symbols = matched)
}

internal suspend fun FakeAnalysisBackend.workspaceSearchResult(query: ParsedWorkspaceSearchQuery): WorkspaceSearchResult {
    val regex = compileWorkspaceSearchRegex(query)
    val fileGlob = query.fileGlob?.value
    val matches = mutableListOf<SearchMatch>()
    var truncated = false

    outer@ for (filePath in availableFiles.filter { it.endsWith(".kt") }.sorted()) {
        if (fileGlob != null && !matchesFileGlob(filePath, fileGlob)) continue
        val content = runCatching { Files.readString(Path.of(filePath)) }.getOrElse { continue }
        for ((lineIndex, line) in content.lineSequence().withIndex()) {
            for (column in searchColumns(line, query, regex)) {
                if (matches.size >= query.maxResults.value) {
                    truncated = true
                    break@outer
                }
                matches += SearchMatch(
                    filePath = filePath,
                    lineNumber = lineIndex + 1,
                    columnNumber = column + 1,
                    preview = line.trimEnd(),
                )
            }
        }
    }

    return WorkspaceSearchResult(matches = matches, truncated = truncated)
}

internal suspend fun FakeAnalysisBackend.implementationsResult(query: ParsedImplementationsQuery): ImplementationsResult {
    requireTypeHierarchyAnchor(query.position)
    return ImplementationsResult(
        declaration = typeHierarchySupertypeSymbol,
        implementations = listOf(typeHierarchySubtypeSymbol).take(query.maxResults.value),
        exhaustive = query.maxResults.value >= 1,
    )
}

internal suspend fun FakeAnalysisBackend.codeActionsResult(query: ParsedCodeActionsQuery): CodeActionsResult {
    requireKnownFile(query.position.filePath.value)
    return CodeActionsResult(actions = emptyList())
}

internal suspend fun FakeAnalysisBackend.completionsResult(query: ParsedCompletionsQuery): CompletionsResult {
    requireKnownFile(query.position.filePath.value)
    val kindFilter = query.kindFilter
    val items = listOf(
        CompletionItem(
            name = "greet",
            fqName = symbol.fqName,
            kind = symbol.kind,
            type = symbol.returnType ?: symbol.type,
            parameters = symbol.parameters,
            documentation = symbol.documentation,
        ),
    ).filter { item -> kindFilter == null || item.kind in kindFilter }
    val capped = items.take(query.maxResults.value)
    return CompletionsResult(
        items = capped,
        exhaustive = items.size <= capped.size,
    )
}

internal fun FakeAnalysisBackend.requireAnchor(position: ParsedFilePosition) {
    requireKnownFile(position.filePath.value)
    if (!hasMatchingAnchor(symbolAnchors, position)) {
        throw missingSymbol(position)
    }
}

internal fun FakeAnalysisBackend.requireTypeHierarchyAnchor(position: ParsedFilePosition) {
    requireKnownFile(position.filePath.value)
    if (!hasMatchingAnchor(typeHierarchyAnchors, position)) {
        throw missingSymbol(position)
    }
}

internal fun FakeAnalysisBackend.requireKnownFile(filePath: String) {
    if (filePath !in availableFiles) {
        throw NotFoundException(
            message = "The fake backend only exposes its fixture files",
            details = mapOf("filePath" to filePath),
        )
    }
}

internal fun hasMatchingAnchor(
    anchors: List<Location>,
    position: ParsedFilePosition,
): Boolean = anchors.any { anchor ->
    anchor.filePath == position.filePath.value &&
        position.offset.value in anchor.startOffset until anchor.endOffset
}

internal fun FakeAnalysisBackend.missingSymbol(position: ParsedFilePosition): NotFoundException = NotFoundException(
    message = "No symbol was found at the requested offset",
    details = mapOf(
        "filePath" to position.filePath.value,
        "offset" to position.offset.value.toString(),
    ),
)

internal fun Symbol.withDeclarationScopeIfRequested(query: ParsedSymbolQuery): Symbol {
    if (!query.includeDeclarationScope || declarationScope != null) {
        return this
    }
    val content = Files.readString(Path.of(location.filePath))
    val startOffset = lineStartOffsetForOffset(content, location.startOffset)
    val endOffset = lineEndOffsetForOffset(content, location.startOffset)
    val startLine = content.take(startOffset).count { it == '\n' } + 1
    val endLine = content.take(endOffset).count { it == '\n' } + 1
    return copy(
        declarationScope = DeclarationScope(
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = startLine,
            endLine = endLine,
            sourceText = content.substring(startOffset, endOffset),
        ),
    )
}

private fun lineStartOffsetForOffset(content: String, offset: Int): Int =
    content.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { index ->
        if (index >= 0) index + 1 else 0
    }

private fun lineEndOffsetForOffset(content: String, offset: Int): Int {
    val newline = content.indexOf('\n', offset)
    return if (newline >= 0) newline else content.length
}

private fun compileWorkspaceSearchRegex(query: ParsedWorkspaceSearchQuery): Regex? =
    if (query.regex) {
        Regex(
            query.pattern.value,
            if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
        )
    } else {
        null
    }

private fun searchColumns(
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

private fun FakeAnalysisBackend.matchesFileGlob(filePath: String, fileGlob: String): Boolean {
    val matcher = FileSystems.getDefault().getPathMatcher("glob:$fileGlob")
    val path = Path.of(filePath)
    val relative = runCatching { workspaceRoot.relativize(path) }.getOrNull()
    return listOfNotNull(relative, relative?.fileName, path.fileName).any(matcher::matches)
}


internal fun afterImportsOffset(content: String): Int {
    val importMatch = Regex("^import .*$", RegexOption.MULTILINE).findAll(content).lastOrNull()
    if (importMatch != null) {
        return offsetAfterLineBreak(content, importMatch.range.last + 1)
    }
    val packageMatch = Regex("^package .*$", RegexOption.MULTILINE).find(content)
    if (packageMatch != null) {
        return offsetAfterLineBreak(content, packageMatch.range.last + 1)
    }
    return 0
}

private fun offsetAfterLineBreak(
    content: String,
    offset: Int,
): Int {
    var cursor = offset
    if (content.getOrNull(cursor) == '\r') {
        cursor += 1
    }
    if (content.getOrNull(cursor) == '\n') {
        cursor += 1
    }
    return cursor
}
