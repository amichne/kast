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

internal suspend fun SkillRpcContext.scaffold(request: KastScaffoldRequest): KastScaffoldResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    val targetFile = request.targetFile.normalizedAbsolutePath()
    val query = KastScaffoldQuery(
        workspaceRoot = workspaceRoot,
        targetFile = request.targetFile,
        targetSymbol = request.targetSymbol,
        mode = request.mode,
        kind = request.kind,
    )
    requireReadCapability(ReadCapability.FILE_OUTLINE)
    val outline = backend.fileOutline(FileOutlineQuery(filePath = targetFile).parsed()).symbols
    val resolvedSymbol = request.targetSymbol?.let { symbolName ->
        resolveNamedSymbol(
            symbolName = symbolName,
            fileHint = request.targetFile,
            kind = request.kind,
            containingType = null,
        )
    }
    val references = resolvedSymbol?.let { resolved ->
        requireReadCapability(ReadCapability.FIND_REFERENCES)
        val result = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                includeDeclaration = true,
                maxResults = config.maxResults,
            ).parsed(),
        )
        KastScaffoldReferences(
            locations = result.references,
            count = result.references.size,
            cardinality = result.cardinality,
            page = result.page,
            searchScope = result.searchScope,
            declaration = result.declaration,
        )
    }
    val typeHierarchy = resolvedSymbol?.takeIf { it.symbol.kind in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT) }?.let { resolved ->
        requireReadCapability(ReadCapability.TYPE_HIERARCHY)
        val result = backend.typeHierarchy(
            TypeHierarchyQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
            ).parsed(),
        )
        KastScaffoldTypeHierarchy(root = result.root, stats = result.stats)
    }
    val insertionPoint = resolvedSymbol?.let { resolved ->
        requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
        backend.semanticInsertionPoint(
            io.github.amichne.kast.api.contract.SemanticInsertionQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                target = request.mode.toInsertionTarget(),
            ).parsed(),
        )
    }
    val fileContent = targetFile.readTextIfPresent()
    return KastScaffoldSuccessResponse(
        query = query,
        outline = outline,
        fileContent = fileContent,
        symbol = resolvedSymbol?.symbol,
        references = references,
        typeHierarchy = typeHierarchy,
        insertionPoint = insertionPoint,
        logFile = placeholderLogFile(),
    )
}


internal fun SkillRpcContext.exactIdentityMatches(requested: String, candidateFqName: String): Boolean {
    val normalizedRequested = normalizedKotlinIdentity(requested)
    val normalizedCandidate = normalizedKotlinIdentity(candidateFqName)
    return if (normalizedRequested.contains('.')) {
        normalizedCandidate == normalizedRequested
    } else {
        normalizedCandidate.substringAfterLast('.') == normalizedRequested
    }
}

internal fun SkillRpcContext.normalizedKotlinIdentity(value: String): String = value
    .split('.')
    .joinToString(".") { segment ->
        segment.removeSurrounding("`")
    }

internal fun SkillRpcContext.exactWorkspaceSymbolPattern(value: String): String =
    "^${Regex.escape(normalizedKotlinIdentity(value).substringAfterLast('.'))}$"

internal fun SkillRpcContext.exactFileHintMatches(fileHint: String, candidateFile: String): Boolean {
    val normalizedHint = Path.of(fileHint).normalize()
    val normalizedCandidate = Path.of(candidateFile).normalize()
    return if (normalizedHint.isAbsolute) {
        normalizedCandidate == normalizedHint
    } else {
        normalizedCandidate.endsWith(normalizedHint)
    }
}

internal fun SkillRpcContext.exactContainingTypeMatches(containingType: String, candidate: Symbol): Boolean {
    val candidateContainer = candidate.containingDeclaration ?: return false
    val normalizedRequested = normalizedKotlinIdentity(containingType)
    val normalizedCandidate = normalizedKotlinIdentity(candidateContainer)
    return if (normalizedRequested.contains('.')) {
        normalizedCandidate == normalizedRequested
    } else {
        normalizedCandidate.substringAfterLast('.') == normalizedRequested
    }
}

internal fun SkillRpcContext.symbolSearchPatterns(symbolName: String): List<String> =
    listOf(
        symbolName,
        symbolName.substringAfterLast('.'),
        normalizedKotlinIdentity(symbolName),
        normalizedKotlinIdentity(symbolName).substringAfterLast('.'),
    )
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

internal fun SkillRpcContext.rankCandidate(
    candidate: Symbol,
    requestedSymbol: String,
    fileHint: String?,
    kind: WrapperNamedSymbolKind?,
    containingType: String?,
    line: Int?,
    codeSnippet: String?,
): RankedNamedSymbolCandidate {
    var score = 20
    val reasons = mutableListOf<String>()
    val simpleName = candidate.fqName.substringAfterLast('.')
    val requestedSimpleName = requestedSymbol.substringAfterLast('.')
    if (candidate.fqName == requestedSymbol) {
        score += 50
        reasons += "exact fully-qualified match"
    } else if (simpleName == requestedSimpleName) {
        score += 35
        reasons += "exact simple-name match"
    } else if (simpleName.contains(requestedSimpleName, ignoreCase = true)) {
        score += 10
        reasons += "simple name contains query"
    }

    if (kind != null && candidate.kind == kind.toSymbolKind()) {
        score += 15
        reasons += "kind matches ${kind.name.lowercase()}"
    } else if (kind != null) {
        score -= 30
    }

    if (!containingType.isNullOrBlank() && candidate.containingDeclaration?.endsWith(containingType) == true) {
        score += 15
        reasons += "containing declaration matches hint"
    }

    val fileHintMatches = !fileHint.isNullOrBlank() && candidate.location.filePath.endsWith(fileHint.removePrefix("/"))
    if (fileHintMatches) {
        score += 15
        reasons += "file matches hint"
    }

    if (line != null && (fileHint.isNullOrBlank() || fileHintMatches)) {
        val distance = kotlin.math.abs(candidate.location.startLine - line)
        val lineScore = when {
            distance == 0 -> 10
            distance <= 2 -> 7
            distance <= 5 -> 4
            else -> 0
        }
        if (lineScore > 0) {
            score += lineScore
            reasons += "line is $distance away"
        }
    }

    val snippetOverlap = snippetOverlap(codeSnippet, candidate)
    if (snippetOverlap > 0) {
        val snippetScore = minOf(10, snippetOverlap * 2)
        score += snippetScore
        reasons += "snippet overlaps $snippetOverlap token(s)"
    }

    return RankedNamedSymbolCandidate(
        symbol = candidate,
        score = score.coerceAtMost(100),
        reasons = reasons.ifEmpty { listOf("matched workspace symbol search") },
    )
}

internal fun SkillRpcContext.snippetOverlap(codeSnippet: String?, candidate: Symbol): Int {
    val queryTokens = codeSnippet?.tokens().orEmpty()
    if (queryTokens.isEmpty()) return 0
    val candidateTokens = listOf(
        candidate.fqName,
        candidate.location.preview,
        candidate.location.filePath,
        candidate.containingDeclaration.orEmpty(),
    ).joinToString(" ").tokens()
    return queryTokens.intersect(candidateTokens).size
}

internal fun RankedNamedSymbolCandidate.toDiscoveryCandidate(
    rank: Int,
    workspaceRoot: String,
    requestedSymbol: String,
): KastDiscoveryCandidate {
    val params = KastResolveParams(
        workspaceRoot = workspaceRoot,
        symbol = requestedSymbol,
        fileHint = symbol.location.filePath,
        kind = symbol.kind.toWrapperNamedSymbolKindOrNull(),
        containingType = symbol.containingDeclaration,
    )
    return KastDiscoveryCandidate(
        rank = rank,
        confidence = score / 100.0,
        symbol = symbol,
        reasons = reasons,
        resolveParams = params,
        nextRequest = KastNextRequest(
            method = "symbol/resolve",
            params = params,
        ),
    )
}

internal suspend fun SkillRpcContext.resolveContext(
    symbol: Symbol,
    request: KastResolveRequest,
): KastResolveContext? {
    val surroundingText = request.surroundingLines?.let { lines ->
        sourceTextWindow(symbol, lines)
    }
    val surroundingMembers = if (request.includeSurroundingMembers) {
        surroundingMembers(symbol)
    } else {
        null
    }
    return if (surroundingText == null && surroundingMembers == null) {
        null
    } else {
        KastResolveContext(
            surroundingText = surroundingText,
            surroundingMembers = surroundingMembers,
        )
    }
}

internal fun SkillRpcContext.sourceTextWindow(symbol: Symbol, surroundingLines: Int): KastSourceTextWindow? {
    val path = Path.of(symbol.location.filePath)
    if (!Files.exists(path)) return null
    val lines = Files.readString(path).lines()
    if (lines.isEmpty()) return null
    val declarationStartLine = symbol.declarationScope?.startLine ?: symbol.location.startLine
    val declarationEndLine = symbol.declarationScope?.endLine ?: symbol.location.startLine
    val startLine = (declarationStartLine - surroundingLines).coerceAtLeast(1)
    val endLine = (declarationEndLine + surroundingLines).coerceAtMost(lines.size)
    return KastSourceTextWindow(
        filePath = symbol.location.filePath,
        startLine = startLine,
        endLine = endLine,
        text = lines.subList(startLine - 1, endLine).joinToString("\n"),
    )
}

internal suspend fun SkillRpcContext.surroundingMembers(symbol: Symbol): List<Symbol> {
    requireReadCapability(ReadCapability.FILE_OUTLINE)
    val outline = backend.fileOutline(FileOutlineQuery(filePath = symbol.location.filePath).parsed())
    return outline.symbols
        .flatMap(OutlineSymbol::flatten)
        .filter { candidate ->
            candidate.location.filePath == symbol.location.filePath &&
                candidate.fqName != symbol.fqName &&
                candidate.containingDeclaration == symbol.containingDeclaration
        }
        .map(Symbol::withoutHeavyContext)
        .sortedWith(compareBy({ it.location.startLine }, { it.fqName }))
}

internal fun SkillRpcContext.validateResolveQuery(query: KastResolveQuery) {
    if (query.symbol.isBlank()) {
        throw ValidationException("symbol must not be blank")
    }
    val surroundingLines = query.surroundingLines ?: return
    if (surroundingLines < 0 || surroundingLines > MAX_SURROUNDING_LINES) {
        throw ValidationException("surroundingLines must be between 0 and $MAX_SURROUNDING_LINES")
    }
}

internal fun SkillRpcContext.validateDiscoverQuery(query: KastDiscoverQuery) {
    if (query.symbol.isBlank()) {
        throw ValidationException("symbol must not be blank")
    }
    if (query.maxResults <= 0) {
        throw ValidationException("maxResults must be greater than 0")
    }
    if (query.maxResults > config.maxResults) {
        throw ValidationException("maxResults must be less than or equal to server maxResults (${config.maxResults})")
    }
    val line = query.line
    if (line != null && line <= 0) {
        throw ValidationException("line must be greater than 0")
    }
}

internal fun SkillRpcContext.validateReferencesQuery(query: KastReferencesQuery) {
    if (query.selector.fqName.isBlank()) {
        throw ValidationException("selector.fqName must not be blank")
    }
    if (query.selector.declarationFile.isBlank()) {
        throw ValidationException("selector.declarationFile must not be blank")
    }
    if (query.selector.declarationStartOffset < 0) {
        throw ValidationException("selector.declarationStartOffset must not be negative")
    }
    if (query.maxResults <= 0) {
        throw ValidationException("maxResults must be greater than 0")
    }
    if (query.maxResults > config.maxResults) {
        throw ValidationException("maxResults must be less than or equal to server maxResults (${config.maxResults})")
    }
}


private fun String.readTextIfPresent(): String? {
    val path = Path.of(this)
    return if (Files.exists(path)) Files.readString(path) else null
}

internal fun WrapperNamedSymbolKind.toSymbolKind(): SymbolKind = when (this) {
    WrapperNamedSymbolKind.CLASS -> SymbolKind.CLASS
    WrapperNamedSymbolKind.INTERFACE -> SymbolKind.INTERFACE
    WrapperNamedSymbolKind.OBJECT -> SymbolKind.OBJECT
    WrapperNamedSymbolKind.FUNCTION -> SymbolKind.FUNCTION
    WrapperNamedSymbolKind.PROPERTY -> SymbolKind.PROPERTY
}

private fun SymbolKind.toWrapperNamedSymbolKindOrNull(): WrapperNamedSymbolKind? = when (this) {
    SymbolKind.CLASS -> WrapperNamedSymbolKind.CLASS
    SymbolKind.INTERFACE -> WrapperNamedSymbolKind.INTERFACE
    SymbolKind.OBJECT -> WrapperNamedSymbolKind.OBJECT
    SymbolKind.FUNCTION -> WrapperNamedSymbolKind.FUNCTION
    SymbolKind.PROPERTY -> WrapperNamedSymbolKind.PROPERTY
    else -> null
}

private fun OutlineSymbol.flatten(): List<Symbol> =
    listOf(symbol) + children.flatMap(OutlineSymbol::flatten)

private fun Symbol.withoutHeavyContext(): Symbol = copy(declarationScope = null)

private fun String.tokens(): Set<String> =
    lowercase()
        .split(Regex("[^a-z0-9_]+"))
        .filter { it.length >= 2 }
        .toSet()

private fun WrapperScaffoldMode.toInsertionTarget(): SemanticInsertionTarget = when (this) {
    WrapperScaffoldMode.IMPLEMENT -> SemanticInsertionTarget.CLASS_BODY_END
    WrapperScaffoldMode.REPLACE -> SemanticInsertionTarget.CLASS_BODY_START
    WrapperScaffoldMode.CONSOLIDATE -> SemanticInsertionTarget.FILE_BOTTOM
    WrapperScaffoldMode.EXTRACT -> SemanticInsertionTarget.AFTER_IMPORTS
}

internal fun workspaceSymbolPageToken(limit: Int): String = limit.toString()

@Suppress("UNCHECKED_CAST")
internal fun <T, R : PageableResult<T>> R.withLimit(
    limit: Int,
    nextPageToken: (T) -> String,
): R {
    if (items.size <= limit) return this
    return withItems(
        items = items.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = nextPageToken(items[limit - 1]),
        ),
    ) as R
}
