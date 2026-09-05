@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologyIdentityMismatchEvidence
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.nio.file.Path

internal sealed interface TopologySymbolProjection {
    data class Projected(val symbol: TopologySymbol) : TopologySymbolProjection
    data object Unsupported : TopologySymbolProjection
    data object Rejected : TopologySymbolProjection
}

internal sealed interface TopologyOverrideProjection {
    data class Projected(
        val bindings: List<ProvenTopologyBinding>,
    ) : TopologyOverrideProjection

    data class Mismatched(
        val evidence: TopologyIdentityMismatchEvidence,
    ) : TopologyOverrideProjection
    data class LoadFailed(val failure: TopologyIdentityResolution.LoadFailed) : TopologyOverrideProjection
    data object Rejected : TopologyOverrideProjection
}

internal fun isRepositoryDeclaration(declaration: KtNamedDeclaration): Boolean =
    declaration is KtClassOrObject || declaration is KtConstructor<*> ||
        declaration is KtNamedFunction || declaration is KtProperty || declaration is KtTypeAlias

/**
 * Proof transition: `(TopologySourceFile, KtNamedDeclaration) -> TopologySymbolProjection`.
 *
 * Projected establishes the same overload-aware K2 identity used by exact symbol selection,
 * detached onto the exact admitted file. Unsupported local/unaddressable declarations and
 * rejected detached facts remain closed; no PSI or K2 value escapes.
 */
internal fun projectTopologySymbol(
    file: TopologySourceFile,
    declaration: KtNamedDeclaration,
): TopologySymbolProjection {
    val projection = when (val result = analyze(declaration) {
        declaration.symbol.topologyProjection()
    }) {
        is TopologyCompilerProjectionResult.Projected -> result.projection
        TopologyCompilerProjectionResult.Unsupported -> return TopologySymbolProjection.Unsupported
    }
    return detachTopologySymbol(file, declaration, projection)
}

private fun detachTopologySymbol(
    file: TopologySourceFile,
    declaration: KtNamedDeclaration,
    projection: TopologyCompilerProjection,
): TopologySymbolProjection {
    val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
    val fileIdentity = when (val detached = SymbolDiscoveryFileIdentity.fromBoundary(
        file.workspace.lease.workspaceRoot,
        absolute,
        absolute.toUri().toString(),
    )) {
        is Refinement.Refined -> detached.value
        is Refinement.Rejected -> return TopologySymbolProjection.Rejected
    }
    val evidence = when (val detached = CompilerGroundedSymbolEvidence.fromBoundary(
        fileIdentity,
        declaration.textRange.startOffset,
        declaration.textRange.endOffset,
        declaration.name.orEmpty(),
        projection.qualifiedIdentity,
        projection.kind,
        projection.signature,
    )) {
        is Refinement.Refined -> detached.value
        is Refinement.Rejected -> return TopologySymbolProjection.Rejected
    }
    return when (val symbol = TopologySymbol.admit(file, evidence)) {
        is Refinement.Refined -> TopologySymbolProjection.Projected(symbol.value)
        is Refinement.Rejected -> TopologySymbolProjection.Rejected
    }
}

/** Resolves a candidate location, then delegates all acceptance to the native binder. */
internal fun KaSession.topologyIdentityProjection(
    resolved: KaSymbol,
    registry: TopologyProjectionRegistry,
    source: TopologyIdentitySource,
    lookup: TopologyRegisteredSourceLookup,
): TopologyIdentityResolution {
    // K2 defines fakeOverrideOriginal for inherited substitutions. Never normalize a
    // delegated/intersection target or an explicit override into an arbitrary base member.
    val target = when (resolved.origin) {
        KaSymbolOrigin.SOURCE -> resolved
        KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> {
            val callable = resolved as? KaCallableSymbol
                ?: return TopologyIdentityResolution.Rejected
            // This set preserves intersection multiplicity and excludes delegated originals.
            // Inspect at most two distinct declarations: ambiguity is outside source admission.
            val declared = callable.directlyOverriddenSymbols.distinct().take(2).toList().singleOrNull()
                ?: return TopologyIdentityResolution.Unsupported
            if (declared.origin != KaSymbolOrigin.SOURCE || declared != callable.fakeOverrideOriginal) {
                return TopologyIdentityResolution.Unsupported
            }
            declared
        }
        else -> return TopologyIdentityResolution.Unsupported
    }
    if (target.origin != KaSymbolOrigin.SOURCE) return TopologyIdentityResolution.Unsupported
    val targetFile = when (val located = target.topologySourceFile(registry)) {
        is TopologyK2SourceFileProjection.Found -> located.file
        TopologyK2SourceFileProjection.Unsupported -> return TopologyIdentityResolution.Unsupported
    }
    val declaration = target.psi as? KtNamedDeclaration
        ?: return TopologyIdentityResolution.Unsupported
    val candidate = when (val found = registry.candidateAt(
        targetFile, declaration.textRange.startOffset, declaration.textRange.endOffset,
    )) {
        is TopologyRegistryCandidateLookup.Found -> found.candidate
        TopologyRegistryCandidateLookup.Unavailable -> return TopologyIdentityResolution.Unsupported
        TopologyRegistryCandidateLookup.Rejected -> return TopologyIdentityResolution.Rejected
    }
    return ProvenTopologyBinding.bind(this, candidate, registry.key, source, target, lookup)
}

/**
 * Proof transition: `(KtNamedDeclaration, TopologyProjectionRegistry) ->
 * TopologyOverrideProjection`.
 *
 * Projected establishes the exact location-bearing topology symbols directly overridden by this
 * declaration. Rejected closes invalid or mismatched K2 evidence. Raw override symbols remain
 * inside the current analysis session.
 */
internal fun KtNamedDeclaration.directOverrideTopologyIdentities(
    registry: TopologyProjectionRegistry,
    source: TopologyIdentitySource,
    lookup: TopologyRegisteredSourceLookup,
): TopologyOverrideProjection =
    analyze(this) {
        val bindings = linkedMapOf<TopologySymbol, ProvenTopologyBinding>()
        for (overridden in (symbol as? KaCallableSymbol)?.directlyOverriddenSymbols.orEmpty()) {
            when (val projection = topologyIdentityProjection(overridden, registry, source, lookup)) {
                is TopologyIdentityResolution.Matched -> bindings[projection.binding.symbol] = projection.binding
                is TopologyIdentityResolution.LoadFailed ->
                    return@analyze TopologyOverrideProjection.LoadFailed(projection)
                TopologyIdentityResolution.Unsupported -> Unit
                is TopologyIdentityResolution.Mismatched ->
                    return@analyze TopologyOverrideProjection.Mismatched(projection.evidence)
                TopologyIdentityResolution.Rejected ->
                    return@analyze TopologyOverrideProjection.Rejected
            }
        }
        TopologyOverrideProjection.Projected(bindings.values.sortedBy { it.symbol })
}

private data class TopologyCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private sealed interface TopologyCompilerProjectionResult {
    data class Projected(
        val projection: TopologyCompilerProjection,
    ) : TopologyCompilerProjectionResult

    data object Unsupported : TopologyCompilerProjectionResult
}

private fun KaSymbol.topologyProjection(): TopologyCompilerProjectionResult = when (this) {
    is KaConstructorSymbol -> {
        val owner = containingClassId?.asSingleFqName()?.asString()
                    ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.CONSTRUCTOR,
            "$owner.<init>",
            functionSignature("$owner.<init>"),
        )
    }
    is KaFunctionSymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return TopologyCompilerProjectionResult.Unsupported
        projected(CompilerSymbolKind.FUNCTION, callable, functionSignature(callable))
    }
    is KaKotlinPropertySymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.PROPERTY,
            callable,
            CanonicalCompilerSignature.property(
                rawQualifiedIdentity = callable,
                rawReceiverType = receiverParameter?.returnType?.toString(),
                rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
                rawReturnType = returnType.toString(),
            ),
        )
    }
    is KaTypeAliasSymbol -> {
        val name = classId?.asSingleFqName()?.asString()
                   ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.TYPE_ALIAS,
            name,
            CanonicalCompilerSignature.typeAlias(name),
        )
    }
    is KaClassLikeSymbol -> {
        val name = classId?.asSingleFqName()?.asString()
                   ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.CLASSLIKE,
            name,
            CanonicalCompilerSignature.classLike(name),
        )
    }
    else -> TopologyCompilerProjectionResult.Unsupported
}

private fun KaFunctionSymbol.functionSignature(
    callable: String,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        rawQualifiedIdentity = callable,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawValueParameterTypes = valueParameters.map { it.returnType.toString() },
        rawTypeParameterCount = (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun projected(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): TopologyCompilerProjectionResult = when (signature) {
    is Refinement.Refined -> TopologyCompilerProjectionResult.Projected(
        TopologyCompilerProjection(
            kind,
            qualifiedIdentity,
            signature.value,
        ),
    )
    is Refinement.Rejected -> TopologyCompilerProjectionResult.Unsupported
}

private sealed interface TopologyK2SourceFileProjection {
    data class Found(
        val file: TopologySourceFile,
    ) : TopologyK2SourceFileProjection

    data object Unsupported : TopologyK2SourceFileProjection
}

/**
 * Proof transition: `(KaSymbol, TopologyProjectionRegistry) ->
 * TopologyK2SourceFileProjection`.
 *
 * Found establishes the exact admitted content-identified source file owning this live compiler
 * symbol. Unsupported closes library, compiler-generated, synthetic, missing, and
 * outside-generation PSI. Raw K2, PSI, and paths remain inside the request-local analysis
 * boundary.
 */
private fun KaSymbol.topologySourceFile(
    registry: TopologyProjectionRegistry,
): TopologyK2SourceFileProjection {
    val virtualFile = psi?.containingFile?.virtualFile
                      ?: return TopologyK2SourceFileProjection.Unsupported
    return when (val lookup = registry.fileAt(Path.of(virtualFile.path))) {
        is TopologyRegistryFileLookup.Found -> TopologyK2SourceFileProjection.Found(lookup.file)
        TopologyRegistryFileLookup.Unavailable -> TopologyK2SourceFileProjection.Unsupported
    }
}
