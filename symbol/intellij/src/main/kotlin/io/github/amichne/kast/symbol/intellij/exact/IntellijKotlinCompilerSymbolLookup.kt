@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.localIdentityAdmitted
import io.github.amichne.kast.workspace.intellij.read.localIdentityRejected
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.javaInterop.callableSymbol
import org.jetbrains.kotlin.analysis.api.javaInterop.namedClassSymbol
import org.jetbrains.kotlin.analysis.api.projectStructure.kaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal sealed interface IntellijCompilerSymbolLookupResult {
    data class Found(val evidence: CompilerGroundedSymbolEvidence) : IntellijCompilerSymbolLookupResult

    data class Rejected(val reason: IntellijSymbolSelectorRejection) : IntellijCompilerSymbolLookupResult
}

internal fun interface IntellijCompilerSymbolLookup {
    /**
     * Proof transition: `(CompiledIntellijSearchScope, IntellijExactDeclarationLookupKey) ->
     * IntellijCompilerSymbolLookupResult`.
     *
     * A found result establishes one scope-contained Kotlin declaration resolved to a detached K2 compiler identity.
     * [IntellijSymbolSelectorRejection] is the closed expected failure. Live PSI, K2 symbols, files, and scopes remain
     * inside this request-local call.
     */
    fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijCompilerSymbolLookupResult
}

/** Request-local K2 exact-symbol lookup; no analysis-session value crosses [find]. */
internal class IntellijKotlinCompilerSymbolLookup(
    private val psiLookup: IntellijPsiExactDeclarationLookup,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val capture: IntellijExactRevalidationCapture? = null,
) : IntellijCompilerSymbolLookup {
    /**
     * Proof transition: `(CompiledIntellijSearchScope, IntellijExactDeclarationLookupKey) ->
     * IntellijCompilerSymbolLookupResult`.
     *
     * Establishes that exact scope/name/offset PSI resolution produced one [KtNamedDeclaration], then K2 analysis
     * produced a closed symbol kind, qualified identity state, and overload-aware compiler identity.
     * [IntellijSymbolSelectorRejection] is the closed expected failure. Raw K2 values are detached before the analysis
     * session ends.
     */
    override fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijCompilerSymbolLookupResult {
        val live =
            when (val lookup = psiLookup.findLive(compiledScope, key)) {
                is IntellijLiveExactDeclarationLookupResult.Found -> lookup
                is IntellijLiveExactDeclarationLookupResult.Rejected ->
                    return IntellijCompilerSymbolLookupResult.Rejected(lookup.reason.toSymbolSelectorRejection())
            }
        val declaration = live.declaration
        if (!declaration.isValid) return rejected(IntellijSymbolSelectorRejection.STALE_LOCATION)
        observation.count(IntellijReadCounter.COMPILER_REFINEMENTS)
        val projection =
            when (
                val result =
                    analyze(declaration.kaModule(null)) {
                        val symbol =
                            when (declaration) {
                                is KtNamedDeclaration -> declaration.symbol
                                is PsiClass -> declaration.namedClassSymbol
                                is PsiMember -> declaration.callableSymbol
                                else -> null
                            }
                        symbol?.toCompilerProjection(this, observation, key.file) ?: compilerProjectionRejected()
                    }
            ) {
                is IntellijCompilerSymbolProjectionResult.Projected -> result.projection
                is IntellijCompilerSymbolProjectionResult.Rejected -> {
                    observation.count(IntellijReadCounter.COMPILER_REFINEMENTS_REJECTED)
                    return rejected(result.reason)
                }
            }
        return when (
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                    file = key.file,
                    rawStartInclusive = live.evidence.range.startInclusive,
                    rawEndExclusive = live.evidence.range.endExclusive,
                    rawName = declaration.name.orEmpty(),
                    rawQualifiedIdentity = projection.qualifiedIdentity,
                    kind = projection.kind,
                    signature = projection.signature,
                )
        ) {
            is Refinement.Refined -> {
                capture?.capture(key.file, declaration.containingFile)
                IntellijCompilerSymbolLookupResult.Found(evidence.value)
            }
            is Refinement.Rejected -> rejected(IntellijSymbolSelectorRejection.INTERNAL_INVARIANT)
        }
    }
}

internal data class IntellijCompilerSymbolProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String?,
    val signature: CanonicalCompilerSignature,
)

internal sealed interface IntellijCompilerSymbolProjectionResult {
    data class Projected(val projection: IntellijCompilerSymbolProjection) : IntellijCompilerSymbolProjectionResult

    data class Rejected(val reason: IntellijSymbolSelectorRejection) : IntellijCompilerSymbolProjectionResult
}

/**
 * Proof transition: `KaSymbol -> IntellijCompilerSymbolProjectionResult`.
 *
 * A projected result establishes a closed public kind plus versioned, fixed-size, canonical-signature compiler
 * identity. Rejection is the closed [IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE] state. Raw K2
 * values remain inside the analysis-session receiver.
 */
internal fun KaSymbol.toCompilerProjection(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int = 0,
): IntellijCompilerSymbolProjectionResult {
    if (depth > LocalDeclarationAddress.MAX_OWNER_DEPTH)
        return localProjectionRejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded, observation)
    if (location == KaSymbolLocation.LOCAL) return localCompilerProjection(session, observation, file, depth)
    return qualifiedCompilerProjection(session, observation, file, depth)
}

private fun KaSymbol.localCompilerProjection(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
): IntellijCompilerSymbolProjectionResult {
    if (!hasSupportedLocalDeclaration())
        return localProjectionRejected(LocalDeclarationProjectionFailure.UnsupportedDeclaration, observation)
    when (val proof = localDeclarationCompilerTypeProof()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return localProjectionRejected(proof.failure, observation)
    }
    val address =
        when (val admitted = localAddress(session, observation, file, depth)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return localProjectionRejected(admitted.failure, observation)
        }
    val result = localSignatureProjection(address)
    when (result) {
        is IntellijCompilerSymbolProjectionResult.Projected -> observation.localIdentityAdmitted()
        is IntellijCompilerSymbolProjectionResult.Rejected ->
            return localProjectionRejected(LocalDeclarationProjectionFailure.SignatureUnavailable, observation)
    }
    return result
}

private fun KaSymbol.hasSupportedLocalDeclaration(): Boolean {
    val declaration = psi
    return (this is KaNamedFunctionSymbol && declaration is KtNamedFunction && declaration.name != null) ||
        (this is KaLocalVariableSymbol && declaration is KtProperty && declaration.isLocal)
}

private fun KaSymbol.localSignatureProjection(
    address: LocalDeclarationAddress
): IntellijCompilerSymbolProjectionResult =
    when (this) {
        is KaNamedFunctionSymbol ->
            projected(
                CompilerSymbolKind.FUNCTION,
                null,
                CanonicalCompilerSignature.localFunction(
                    address,
                    receiverParameter?.returnType?.toString(),
                    contextReceivers.map { it.type.toString() },
                    valueParameters.map { it.returnType.toString() },
                    typeParameters.size,
                    returnType.toString(),
                ),
            )
        is KaLocalVariableSymbol ->
            projected(
                CompilerSymbolKind.PROPERTY,
                null,
                CanonicalCompilerSignature.localProperty(
                    address,
                    returnType.toString(),
                    if (isVal) LocalPropertyMutability.VAL else LocalPropertyMutability.VAR,
                ),
            )
        else -> compilerProjectionRejected()
    }

private fun KaSymbol.qualifiedCompilerProjection(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
): IntellijCompilerSymbolProjectionResult {
    return when (this) {
        is KaValueParameterSymbol ->
            generatedPrimaryConstructorProperty?.toCompilerProjection(session, observation, file, depth)
                ?: compilerProjectionRejected()
        is KaConstructorSymbol -> {
            val owner = containingClassId?.asSingleFqName()?.asString() ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.CONSTRUCTOR,
                "$owner.<init>",
                functionSignature("$owner.<init>"),
            )
        }
        is KaFunctionSymbol ->
            compilerCallableIdentity(session).projectCallable(observation) { callable ->
                projected(
                    CompilerSymbolKind.FUNCTION,
                    callable,
                    functionSignature(callable),
                )
            }
        is KaKotlinPropertySymbol -> compilerPropertyProjection(session, observation)
        is KaTypeAliasSymbol -> {
            val className = classId?.asSingleFqName()?.asString() ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.TYPE_ALIAS,
                className,
                CanonicalCompilerSignature.typeAlias(className),
            )
        }
        is KaClassLikeSymbol -> {
            val className = classId?.asSingleFqName()?.asString() ?: return compilerProjectionRejected()
            projected(
                CompilerSymbolKind.CLASSLIKE,
                className,
                CanonicalCompilerSignature.classLike(className),
            )
        }
        else -> compilerProjectionRejected()
    }
}

private fun KaKotlinPropertySymbol.compilerPropertyProjection(
    session: KaSession,
    observation: IntellijReadObservation,
): IntellijCompilerSymbolProjectionResult =
    compilerCallableIdentity(session).projectCallable(observation) { callable ->
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

private inline fun IntellijCallableIdentity.projectCallable(
    observation: IntellijReadObservation,
    project: (String) -> IntellijCompilerSymbolProjectionResult,
): IntellijCompilerSymbolProjectionResult {
    return when (this) {
        is IntellijCallableIdentity.Native -> {
            observation.callableIdentity(IntellijCallableIdentityStatus.Native)
            project(identity.asString())
        }
        is IntellijCallableIdentity.EnumEntryMember -> {
            observation.callableIdentity(IntellijCallableIdentityStatus.EnumEntryMember)
            project(owner.asSingleFqName().child(name).asString())
        }
        is IntellijCallableIdentity.Unavailable -> {
            observation.callableIdentity(IntellijCallableIdentityStatus.Unavailable(reason))
            compilerProjectionRejected()
        }
    }
}

private fun KaFunctionSymbol.functionSignature(
    callable: String
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
    qualifiedIdentity: String?,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): IntellijCompilerSymbolProjectionResult =
    when (signature) {
        is Refinement.Refined ->
            IntellijCompilerSymbolProjectionResult.Projected(
                IntellijCompilerSymbolProjection(
                    kind,
                    qualifiedIdentity,
                    signature.value,
                )
            )
        is Refinement.Rejected -> compilerProjectionRejected()
    }

private fun rejected(reason: IntellijSymbolSelectorRejection): IntellijCompilerSymbolLookupResult.Rejected =
    IntellijCompilerSymbolLookupResult.Rejected(reason)

private fun compilerProjectionRejected(): IntellijCompilerSymbolProjectionResult.Rejected =
    IntellijCompilerSymbolProjectionResult.Rejected(IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE)

private fun IntellijExactDeclarationLookupRejection.toSymbolSelectorRejection(): IntellijSymbolSelectorRejection =
    when (this) {
        IntellijExactDeclarationLookupRejection.STALE_LOCATION -> IntellijSymbolSelectorRejection.STALE_LOCATION
        IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE -> IntellijSymbolSelectorRejection.OUTSIDE_SCOPE
        IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION ->
            IntellijSymbolSelectorRejection.AMBIGUOUS_DECLARATION
        IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION ->
            IntellijSymbolSelectorRejection.UNSUPPORTED_DECLARATION
    }

internal fun localProjectionRejected(
    failure: LocalDeclarationProjectionFailure,
    observation: IntellijReadObservation,
): IntellijCompilerSymbolProjectionResult.Rejected {
    observation.localIdentityRejected(failure)
    return IntellijCompilerSymbolProjectionResult.Rejected(
        when (failure) {
            LocalDeclarationProjectionFailure.WorkLimitReached,
            LocalDeclarationProjectionFailure.OwnerDepthExceeded -> IntellijSymbolSelectorRejection.WORK_LIMIT_REACHED
            LocalDeclarationProjectionFailure.CompilerTypeError,
            LocalDeclarationProjectionFailure.CompilerTypeUnsupported,
            LocalDeclarationProjectionFailure.UnsupportedDeclaration,
            LocalDeclarationProjectionFailure.SignatureUnavailable,
            LocalDeclarationProjectionFailure.SourceUnavailable,
            LocalDeclarationProjectionFailure.OwnerUnavailable,
            LocalDeclarationProjectionFailure.CompilerOwnerUnavailable,
            LocalDeclarationProjectionFailure.LexicalAncestryUnavailable,
            is LocalDeclarationProjectionFailure.InvalidAddress ->
                IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE
        }
    )
}
