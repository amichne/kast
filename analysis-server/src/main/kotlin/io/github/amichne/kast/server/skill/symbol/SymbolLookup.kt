package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun SkillRpcContext.resolve(request: KastResolveRequest): KastResolveResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    val query = KastResolveQuery(
        workspaceRoot = workspaceRoot,
        symbol = request.symbol,
        fileHint = request.fileHint,
        kind = request.kind,
        containingType = request.containingType,
        includeDeclarationScope = request.includeDeclarationScope,
        includeDocumentation = request.includeDocumentation,
        surroundingLines = request.surroundingLines,
        includeSurroundingMembers = request.includeSurroundingMembers,
    )
    validateResolveQuery(query)
    val candidates = exactNamedSymbolCandidates(
        symbolName = request.symbol,
        fileHint = request.fileHint,
        kind = request.kind,
        containingType = request.containingType,
        includeDeclarationScope = false,
    )
    if (candidates.isEmpty()) {
        return KastResolveNotFoundResponse(
            query = query,
            logFile = placeholderLogFile(),
        )
    }
    if (candidates.size > 1) {
        return KastResolveAmbiguousResponse(
            query = query,
            candidates = candidates.map { candidate ->
                candidate.resolvedConstraintSymbol ?: candidate.ranked.symbol
            },
            logFile = placeholderLogFile(),
        )
    }
    val candidate = candidates.single()
    val resolved = resolveNamedSymbol(
        candidate = candidate.ranked,
        includeDeclarationScope = request.includeDeclarationScope,
        includeDocumentation = request.includeDocumentation,
    ) ?: return KastResolveNotFoundResponse(
        query = query,
        logFile = placeholderLogFile(),
    )
    val context = resolveContext(resolved.symbol, request)
    return KastResolveSuccessResponse(
        query = query,
        symbol = resolved.symbol,
        selectorHandle = issueSelectorHandle(resolved.symbol),
        filePath = resolved.filePath,
        offset = resolved.offset,
        candidate = KastCandidate(
            line = resolved.symbol.location.startLine,
            column = resolved.symbol.location.startColumn,
            context = resolved.symbol.location.preview,
        ),
        candidateCount = 1,
        alternatives = emptyList(),
        context = context,
        logFile = placeholderLogFile(),
    )
}

internal suspend fun SkillRpcContext.selectorIdentity(
    request: KastSelectorIdentityRequest,
): KastSelectorIdentityResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    return when (
        val resolution = backend.selectorHandles.resolve(
            handle = request.selectorHandle,
            workspaceRoot = workspaceRoot,
            family = request.family,
        )
    ) {
        is SelectorHandleAuthority.Resolution.Resolved -> KastSelectorIdentityAvailableResponse(
            resolution.selector.normalizedFor(workspaceRoot).toHandleSubject(),
        )
        is SelectorHandleAuthority.Resolution.Rejected ->
            KastSelectorHandleRejectedResponse(resolution.reason)
    }
}

internal suspend fun SkillRpcContext.discover(request: KastDiscoverRequest): KastDiscoverResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    val query = KastDiscoverQuery(
        workspaceRoot = workspaceRoot,
        symbol = request.symbol,
        fileHint = request.fileHint,
        line = request.line,
        codeSnippet = request.codeSnippet,
        kind = request.kind,
        containingType = request.containingType,
        maxResults = request.maxResults,
        includeDeclarationScope = request.includeDeclarationScope,
    )
    validateDiscoverQuery(query)
    val searchLimit = minOf(
        config.maxResults,
        maxOf(request.maxResults + 1, DEFAULT_DISCOVERY_SEARCH_LIMIT),
    )
    val candidates = rankedNamedSymbolCandidates(
        symbolName = request.symbol,
        fileHint = request.fileHint,
        kind = request.kind,
        containingType = request.containingType,
        line = request.line,
        codeSnippet = request.codeSnippet,
        includeDeclarationScope = request.includeDeclarationScope,
        searchLimit = searchLimit,
    )
    val visibleCandidates = candidates.take(request.maxResults)
    return KastDiscoverSuccessResponse(
        query = query,
        candidates = visibleCandidates.mapIndexed { index, candidate ->
            candidate.toDiscoveryCandidate(
                rank = index + 1,
                workspaceRoot = workspaceRoot,
                requestedSymbol = request.symbol,
            )
        },
        page = if (candidates.size > visibleCandidates.size) {
            PageInfo(
                truncated = true,
                nextPageToken = visibleCandidates.size.toString(),
            )
        } else {
            null
        },
        logFile = placeholderLogFile(),
    )
}


internal suspend fun SkillRpcContext.resolveNamedSymbol(
    symbolName: String,
    fileHint: String? = null,
    kind: WrapperNamedSymbolKind? = null,
    containingType: String? = null,
    includeDeclarationScope: Boolean = false,
): ResolvedNamedSymbol? {
    val candidates = rankedNamedSymbolCandidates(
        symbolName = symbolName,
        fileHint = fileHint,
        kind = kind,
        containingType = containingType,
        line = null,
        codeSnippet = null,
        includeDeclarationScope = includeDeclarationScope,
        searchLimit = minOf(config.maxResults, DEFAULT_DISCOVERY_SEARCH_LIMIT),
    )
    val best = candidates.firstOrNull() ?: return null
    val resolved = resolveNamedSymbol(
        candidate = best,
        includeDeclarationScope = includeDeclarationScope,
        includeDocumentation = false,
    ) ?: return null
    val alternativeFqNames = candidates
        .asSequence()
        .map { it.symbol.fqName }
        .filter { it != best.symbol.fqName }
        .distinct()
        .take(3)
        .toList()
    return resolved.copy(
        candidateCount = candidates.size,
        alternativeFqNames = alternativeFqNames,
    )
}

internal suspend fun SkillRpcContext.resolveNamedSymbol(
    candidate: RankedNamedSymbolCandidate,
    includeDeclarationScope: Boolean,
    includeDocumentation: Boolean,
): ResolvedNamedSymbol? {
    requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
    val resolved = backend.resolveSymbol(
        SymbolQuery(
            position = FilePosition(
                filePath = candidate.symbol.location.filePath,
                offset = candidate.symbol.location.startOffset,
            ),
            includeDeclarationScope = includeDeclarationScope,
            includeDocumentation = includeDocumentation,
        ).parsed(),
    )
    return ResolvedNamedSymbol(
        symbol = resolved.symbol,
        filePath = candidate.symbol.location.filePath,
        offset = candidate.symbol.location.startOffset,
        candidateCount = 1,
        alternativeFqNames = emptyList(),
    )
}

internal suspend fun SkillRpcContext.rankedNamedSymbolCandidates(
    symbolName: String,
    fileHint: String?,
    kind: WrapperNamedSymbolKind?,
    containingType: String?,
    line: Int?,
    codeSnippet: String?,
    includeDeclarationScope: Boolean,
    searchLimit: Int,
): List<RankedNamedSymbolCandidate> {
    requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
    val symbols = symbolSearchPatterns(symbolName)
        .flatMap { pattern ->
            backend.workspaceSymbolSearch(
                WorkspaceSymbolQuery(
                    pattern = pattern,
                    maxResults = searchLimit,
                    includeDeclarationScope = includeDeclarationScope,
                ).parsed(),
            ).withLimit(searchLimit) { workspaceSymbolPageToken(searchLimit) }.symbols
        }
        .distinctBy { symbol -> Triple(symbol.fqName, symbol.location.filePath, symbol.location.startOffset) }
    val filteredSymbols = if (symbolName.contains('.')) {
        symbols.filter { symbol -> symbol.fqName == symbolName }
    } else {
        symbols
    }

    return filteredSymbols
        .asSequence()
        .map { candidate ->
            rankCandidate(
                candidate = candidate,
                requestedSymbol = symbolName,
                fileHint = fileHint,
                kind = kind,
                containingType = containingType,
                line = line,
                codeSnippet = codeSnippet,
            )
        }
        .sortedWith(
            compareByDescending<RankedNamedSymbolCandidate> { it.score }
                .thenBy { it.symbol.location.filePath }
                .thenBy { it.symbol.location.startLine }
                .thenBy { it.symbol.fqName },
        )
        .toList()
}

internal suspend fun SkillRpcContext.exactNamedSymbolCandidates(
    symbolName: String,
    fileHint: String?,
    kind: WrapperNamedSymbolKind?,
    containingType: String?,
    includeDeclarationScope: Boolean,
): List<ExactNamedSymbolCandidate> {
    requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
    val searchLimit = if (fileHint == null && containingType == null) {
        EXACT_CARDINALITY_LIMIT
    } else {
        EXACT_CONSTRAINED_SEARCH_LIMIT
    }
    val rankedCandidates = symbolSearchPatterns(symbolName)
        .flatMap { pattern ->
            backend.workspaceSymbolSearch(
                WorkspaceSymbolQuery(
                    pattern = exactWorkspaceSymbolPattern(pattern),
                    kind = kind?.toSymbolKind(),
                    maxResults = searchLimit,
                    regex = true,
                    includeDeclarationScope = includeDeclarationScope,
                ).parsed(),
            ).withLimit(searchLimit) { workspaceSymbolPageToken(searchLimit) }.symbols
        }
        .distinctBy { symbol -> Triple(symbol.fqName, symbol.location.filePath, symbol.location.startOffset) }
        .asSequence()
        .filter { symbol -> exactIdentityMatches(symbolName, symbol.fqName) }
        .filter { symbol -> kind == null || symbol.kind == kind.toSymbolKind() }
        .filter { symbol -> fileHint == null || exactFileHintMatches(fileHint, symbol.location.filePath) }
        .sortedWith(
            compareBy<Symbol> { it.location.filePath }
                .thenBy { it.location.startOffset }
                .thenBy { it.fqName },
        )
        .map { symbol ->
            RankedNamedSymbolCandidate(
                symbol = symbol,
                score = 100,
                reasons = listOf("exact identity and constraints match"),
            )
        }
        .toList()
    return if (containingType == null) {
        rankedCandidates
            .take(EXACT_CARDINALITY_LIMIT)
            .map { candidate -> ExactNamedSymbolCandidate(candidate, resolvedConstraintSymbol = null) }
    } else {
        rankedCandidates
            .mapNotNull { candidate ->
                val resolved = resolveNamedSymbol(
                    candidate = candidate,
                    includeDeclarationScope = false,
                    includeDocumentation = false,
                ) ?: return@mapNotNull null
                if (exactContainingTypeMatches(containingType, resolved.symbol)) {
                    ExactNamedSymbolCandidate(candidate, resolved.symbol)
                } else {
                    null
                }
            }
            .take(EXACT_CARDINALITY_LIMIT)
    }
}
