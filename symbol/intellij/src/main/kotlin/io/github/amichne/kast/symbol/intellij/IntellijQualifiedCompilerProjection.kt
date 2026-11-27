@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol

/** A declaration with a qualified name projects that name and its canonical signature; others stay unavailable. */
internal fun KaSymbol.qualifiedCompilerProjection(
    session: KaSession,
    observation: IntellijReadObservation,
    file: SymbolDiscoveryFileIdentity,
    depth: Int,
    workAllowance: WorkAllowance,
): IntellijCompilerSymbolProjectionResult {
    return when (this) {
        is KaValueParameterSymbol ->
            generatedPrimaryConstructorProperty?.toCompilerProjection(session, observation, file, depth, workAllowance)
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
        is KaJavaFieldSymbol -> compilerJavaFieldProjection(session, observation)
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

/** Kotlin reads a Java field as a property without a receiver or context, so its identity takes that shape. */
private fun KaJavaFieldSymbol.compilerJavaFieldProjection(
    session: KaSession,
    observation: IntellijReadObservation,
): IntellijCompilerSymbolProjectionResult =
    compilerCallableIdentity(session).projectCallable(observation) { callable ->
        projected(
            CompilerSymbolKind.PROPERTY,
            callable,
            CanonicalCompilerSignature.property(
                rawQualifiedIdentity = callable,
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
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

internal fun projected(
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

internal fun compilerProjectionRejected(): IntellijCompilerSymbolProjectionResult.Rejected =
    IntellijCompilerSymbolProjectionResult.Rejected(IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE)
