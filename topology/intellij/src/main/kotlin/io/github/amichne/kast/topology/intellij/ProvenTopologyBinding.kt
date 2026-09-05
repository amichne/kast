@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.TopologyBindingFailure
import io.github.amichne.kast.topology.contract.TopologyFileExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyIdentityMismatchEvidence
import io.github.amichne.kast.topology.contract.TopologySymbol
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** The exact registry symbol after independent source admission and same-session K2 equality. */
internal class ProvenTopologyBinding private constructor(val symbol: TopologySymbol) {
    companion object {
        /** Live symbols are consumed only inside the caller's analysis session. */
        fun bind(
            session: KaSession,
            candidate: TopologyProjectionRegistry.Candidate,
            current: TopologyProjectionRegistryKey,
            source: TopologyIdentitySource,
            target: KaSymbol,
            lookup: TopologyRegisteredSourceLookup,
        ): TopologyIdentityResolution = with(session) {
            fun rejected(reason: TopologyBindingFailure) = TopologyIdentityResolution.Mismatched(
                TopologyIdentityMismatchEvidence(
                    source.stage, source.file, source.occurrence,
                    candidate.symbol.file, candidate.symbol.evidence.range, reason,
                ),
            )
            if (candidate.key != current || source.file !in current.files) {
                return rejected(TopologyBindingFailure.EPOCH_CHANGED)
            }
            val declaration = when (val loaded = lookup.load(candidate)) {
                is TopologyRegisteredSource.Loaded -> loaded.declaration
                is TopologyRegisteredSource.LoadFailed -> return TopologyIdentityResolution.LoadFailed(
                    candidate.symbol.file, loaded.failure,
                )
                TopologyRegisteredSource.DeclarationUnavailable ->
                    return rejected(TopologyBindingFailure.DECLARATION_UNAVAILABLE)
            }
            val registered = declaration.symbol
            if (target.origin != KaSymbolOrigin.SOURCE || registered.origin != KaSymbolOrigin.SOURCE) {
                return rejected(TopologyBindingFailure.ORIGIN_NOT_ADMITTED)
            }
            when (val role = TopologyBindingRole.admit(
                candidate.symbol.evidence.kind, registered.sourceRole(), target.sourceRole(),
            )) {
                is Refinement.Rejected -> return rejected(role.failure)
                is Refinement.Refined -> Unit
            }
            when {
                target.containingModule != registered.containingModule ->
                    rejected(TopologyBindingFailure.MODULE_MISMATCH)
                target != registered -> rejected(TopologyBindingFailure.DECLARATION_MISMATCH)
                else -> TopologyIdentityResolution.Matched(ProvenTopologyBinding(candidate.symbol))
            }
        }
    }
}

/** Request-local source loading effect. The production owner reloads the candidate's file/range. */
internal sealed interface TopologyRegisteredSourceLookup {
    fun load(candidate: TopologyProjectionRegistry.Candidate): TopologyRegisteredSource
}

internal sealed interface TopologyRegisteredSource {
    data class Loaded(val declaration: KtNamedDeclaration) : TopologyRegisteredSource
    data class LoadFailed(val failure: TopologyFileExtractionFailure) : TopologyRegisteredSource
    data object DeclarationUnavailable : TopologyRegisteredSource
}

private fun KaSymbol.sourceRole(): TopologySourceRole = when (this) {
    is KaConstructorSymbol -> TopologySourceRole.CONSTRUCTOR
    is KaFunctionSymbol -> TopologySourceRole.FUNCTION
    is KaKotlinPropertySymbol -> TopologySourceRole.PROPERTY
    is KaTypeAliasSymbol -> TopologySourceRole.TYPE_ALIAS
    is KaClassLikeSymbol -> TopologySourceRole.CLASS_LIKE
    else -> TopologySourceRole.UNSUPPORTED
}
