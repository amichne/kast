package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.skill.KastDiscoveryCandidate
import io.github.amichne.kast.api.contract.skill.KastNextRequest
import io.github.amichne.kast.api.contract.skill.KastResolveParams

internal fun SkillRpcContext.toDiscoveryCandidate(
    candidate: RankedNamedSymbolCandidate,
    rank: Int,
    workspaceRoot: String,
    requestedSymbol: String,
    selectorHandle: String? = null,
): KastDiscoveryCandidate {
    val params = KastResolveParams(
        workspaceRoot = workspaceRoot,
        symbol = requestedSymbol,
        fileHint = candidate.symbol.location.filePath,
        kind = candidate.symbol.kind.toWrapperNamedSymbolKindOrNull(),
        containingType = candidate.symbol.containingDeclaration,
    )
    return KastDiscoveryCandidate(
        rank = rank,
        confidence = candidate.score / 100.0,
        symbol = candidate.symbol,
        selectorHandle = selectorHandle ?: issueSelectorHandle(candidate.symbol),
        reasons = candidate.reasons,
        resolveParams = params,
        nextRequest = KastNextRequest(
            method = "symbol/resolve",
            params = params,
        ),
    )
}
