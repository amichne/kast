package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.skill.KastCandidate
import io.github.amichne.kast.api.contract.skill.KastDiscoverQuery
import io.github.amichne.kast.api.contract.skill.KastDiscoverRequest
import io.github.amichne.kast.api.contract.skill.KastDiscoverResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastNativeReadCompleteness
import io.github.amichne.kast.api.contract.skill.KastNativeReadQualification
import io.github.amichne.kast.api.contract.skill.KastResolveAmbiguousResponse
import io.github.amichne.kast.api.contract.skill.KastResolveCandidate
import io.github.amichne.kast.api.contract.skill.KastResolveNotFoundResponse
import io.github.amichne.kast.api.contract.skill.KastResolveQuery
import io.github.amichne.kast.api.contract.skill.KastResolveRequest
import io.github.amichne.kast.api.contract.skill.KastResolveResponse
import io.github.amichne.kast.api.contract.skill.KastResolveSuccessResponse
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.server.NativePublicSymbolReadResult
import io.github.amichne.kast.server.PublicSymbolReadBinding
import io.github.amichne.kast.server.PublicSymbolReadMatch
import io.github.amichne.kast.server.PublicSymbolReadProjection
import io.github.amichne.kast.server.PublicSymbolReadQuery
import java.nio.file.Path

internal suspend fun SkillRpcContext.resolveWithNativeIntellij(
    request: KastResolveRequest,
    binding: PublicSymbolReadBinding.Native,
): KastResolveResponse {
    val workspaceRoot = nativeWorkspaceRoot(request.workspaceRoot, binding)
    val query = KastResolveQuery(
        workspaceRoot = workspaceRoot.value,
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
    requireNativeContextSupport(request)
    val searchLimit = if (
        request.fileHint == null &&
        request.containingType == null &&
        request.kind == null
    ) {
        EXACT_CARDINALITY_LIMIT
    } else {
        config.maxResults
    }
    val completed = binding.reader.read(
        PublicSymbolReadQuery(
            workspaceRoot = workspaceRoot,
            pattern = NonBlankString(request.symbol),
            maxResults = PositiveInt(searchLimit),
            match = PublicSymbolReadMatch.EXACT_NAME,
            projection = nativeProjection(
                request.includeDeclarationScope,
                request.includeDocumentation,
            ),
            kind = request.kind?.toSymbolKind(),
        ),
    ).requireCompleted()
    requireDefinitiveResolveEvidence(completed)
    val candidates = completed.definitions
        .asSequence()
        .filter { exactIdentityMatches(request.symbol, it.symbol.fqName) }
        .filter { definition ->
            request.kind?.let { kind -> definition.symbol.kind == kind.toSymbolKind() } ?: true
        }
        .filter { definition ->
            request.fileHint?.let { hint ->
                exactFileHintMatches(hint, definition.symbol.location.filePath)
            } ?: true
        }
        .filter { definition ->
            request.containingType?.let { containingType ->
                exactContainingTypeMatches(containingType, definition.symbol)
            } ?: true
        }
        .distinctBy {
            Triple(
                it.symbol.fqName,
                it.symbol.location.filePath,
                it.symbol.location.startOffset,
            )
        }
        .sortedWith(
            compareBy(
                { it.symbol.location.filePath },
                { it.symbol.location.startOffset },
                { it.symbol.fqName },
            ),
        )
        .take(EXACT_CARDINALITY_LIMIT)
        .toList()
    if (candidates.isEmpty()) {
        return KastResolveNotFoundResponse(
            query = query,
            logFile = placeholderLogFile(),
            readEvidence = completed.evidence,
        )
    }
    if (candidates.size > 1) {
        return KastResolveAmbiguousResponse(
            query = query,
            candidates = candidates.map { definition ->
                KastResolveCandidate(
                    symbol = definition.symbol,
                    selectorHandle = definition.selectorHandle.value,
                )
            },
            logFile = placeholderLogFile(),
            readEvidence = completed.evidence,
        )
    }
    val definition = candidates.single()
    val symbol = definition.symbol
    return KastResolveSuccessResponse(
        query = query,
        symbol = symbol,
        selectorHandle = definition.selectorHandle.value,
        filePath = symbol.location.filePath,
        offset = symbol.location.startOffset,
        candidate = KastCandidate(
            line = symbol.location.startLine,
            column = symbol.location.startColumn,
            context = symbol.location.preview,
        ),
        candidateCount = 1,
        alternatives = emptyList(),
        context = null,
        logFile = placeholderLogFile(),
        readEvidence = completed.evidence,
    )
}

internal suspend fun SkillRpcContext.discoverWithNativeIntellij(
    request: KastDiscoverRequest,
    binding: PublicSymbolReadBinding.Native,
): KastDiscoverResponse {
    val workspaceRoot = nativeWorkspaceRoot(request.workspaceRoot, binding)
    val query = KastDiscoverQuery(
        workspaceRoot = workspaceRoot.value,
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
        config.maxResults.toLong(),
        maxOf(request.maxResults.toLong() + 1L, DEFAULT_DISCOVERY_SEARCH_LIMIT.toLong()),
    ).toInt()
    val completed = binding.reader.read(
        PublicSymbolReadQuery(
            workspaceRoot = workspaceRoot,
            pattern = NonBlankString(request.symbol),
            maxResults = PositiveInt(searchLimit),
            match = PublicSymbolReadMatch.FUZZY,
            projection = nativeProjection(request.includeDeclarationScope, false),
            kind = request.kind?.toSymbolKind(),
        ),
    ).requireCompleted()
    val candidates = completed.definitions
        .asSequence()
        .filter { definition ->
            request.kind?.let { kind ->
                definition.symbol.kind == kind.toSymbolKind()
            } ?: true
        }
        .filter { definition ->
            request.containingType?.let { containingType ->
                exactContainingTypeMatches(containingType, definition.symbol)
            } ?: true
        }
        .map { definition ->
            RankedNativeSymbolCandidate(
                ranked = rankCandidate(
                    candidate = definition.symbol,
                    requestedSymbol = request.symbol,
                    fileHint = request.fileHint,
                    kind = request.kind,
                    containingType = request.containingType,
                    line = request.line,
                    codeSnippet = request.codeSnippet,
                ),
                selectorHandle = definition.selectorHandle.value,
            )
        }
        .sortedWith(
            compareByDescending<RankedNativeSymbolCandidate> { it.ranked.score }
                .thenBy { it.ranked.symbol.location.filePath }
                .thenBy { it.ranked.symbol.location.startLine }
                .thenBy { it.ranked.symbol.fqName },
        )
        .toList()
    val visible = candidates.take(request.maxResults)
    return KastDiscoverSuccessResponse(
        query = query,
        candidates = visible.mapIndexed { index, candidate ->
            toDiscoveryCandidate(
                candidate = candidate.ranked,
                rank = index + 1,
                workspaceRoot = workspaceRoot.value,
                requestedSymbol = request.symbol,
                selectorHandle = candidate.selectorHandle,
            )
        },
        page = if (
            candidates.size > visible.size ||
            KastNativeReadQualification.RESULT_LIMIT_REACHED in
            completed.evidence.qualifications
        ) {
            PageInfo(truncated = true, nextPageToken = visible.size.toString())
        } else {
            null
        },
        logFile = placeholderLogFile(),
        readEvidence = completed.evidence,
    )
}

private data class RankedNativeSymbolCandidate(
    val ranked: RankedNamedSymbolCandidate,
    val selectorHandle: String,
)

private fun nativeWorkspaceRoot(
    explicit: String?,
    binding: PublicSymbolReadBinding.Native,
): NormalizedPath = explicit
                        ?.takeIf(String::isNotBlank)
                        ?.let { NormalizedPath.of(Path.of(it)) }
                    ?: binding.workspaceRoot

private fun nativeProjection(
    includeDeclarationScope: Boolean,
    includeDocumentation: Boolean,
): PublicSymbolReadProjection = when {
    includeDeclarationScope && includeDocumentation ->
        PublicSymbolReadProjection.DECLARATION_SCOPE_AND_DOCUMENTATION
    includeDeclarationScope -> PublicSymbolReadProjection.DECLARATION_SCOPE
    includeDocumentation -> PublicSymbolReadProjection.DOCUMENTATION
    else -> PublicSymbolReadProjection.BASIC
}

private fun requireNativeContextSupport(request: KastResolveRequest) {
    if (request.surroundingLines != null || request.includeSurroundingMembers) {
        throw CapabilityNotSupportedException(
            capability = "NATIVE_INTELLIJ_SYMBOL_CONTEXT",
            message = "Native IntelliJ symbol reads do not yet project surrounding text or members",
        )
    }
}

private fun NativePublicSymbolReadResult.requireCompleted(): NativePublicSymbolReadResult.Completed =
    when (this) {
        is NativePublicSymbolReadResult.Completed -> this
        is NativePublicSymbolReadResult.Rejected -> throw CapabilityNotSupportedException(
            capability = "NATIVE_INTELLIJ_SYMBOL_READ",
            message = "The native IntelliJ symbol read was rejected: ${failure.name}",
        )
    }

private fun requireDefinitiveResolveEvidence(
    completed: NativePublicSymbolReadResult.Completed,
) {
    if (completed.evidence.completeness != KastNativeReadCompleteness.EXACT) {
        throw CapabilityNotSupportedException(
            capability = "EXACT_NATIVE_INTELLIJ_SYMBOL_READ",
            message = "The native IntelliJ symbol read reached its result limit before exact cardinality was proven",
        )
    }
}
