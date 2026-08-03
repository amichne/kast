@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSignature
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementPropertySignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeVariance
import io.github.amichne.kast.api.contract.result.ReplacementValueParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.protocol.ReplacementProofFailureEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression

internal fun compilerReplacementSignature(
    declaration: KtNamedDeclaration,
): ReplacementDeclarationSignature = try {
    analyze(declaration) {
        when (val symbol = declaration.symbol) {
            is KaNamedFunctionSymbol -> symbol.replacementSignature()
            is KaKotlinPropertySymbol -> symbol.replacementSignature()
            else -> null
        }
    } ?: failReplacementProof(
        ReplacementProofLimitation.COMPILER_SIGNATURE_UNPROVEN,
        "K2 could not prove every required replacement signature property",
    )
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (failure: ReplacementProofIncompleteException) {
    throw failure
} catch (_: Exception) {
    failReplacementProof(
        ReplacementProofLimitation.COMPILER_SIGNATURE_UNPROVEN,
        "K2 could not prove every required replacement signature property",
    )
}

private fun KaNamedFunctionSymbol.replacementSignature(): ReplacementFunctionSignature? =
    ReplacementFunctionSignature.of(
        name = name.asString(),
        receiverType = receiverParameter?.returnType?.canonicalReplacementType(),
        contextReceiverTypes = contextReceivers.map { receiver -> receiver.type.canonicalReplacementType() },
        typeParameters = typeParameters.map(KaTypeParameterSymbol::replacementSignature),
        valueParameters = valueParameters.map(KaValueParameterSymbol::replacementSignature),
        returnType = returnType.canonicalReplacementType(),
        visibility = visibility.toReplacementVisibility() ?: return null,
        modality = modality.toReplacementModality(),
        hasStableParameterNames = hasStableParameterNames,
        suspend = isSuspend,
        operator = isOperator,
        inline = isInline,
        override = isOverride,
        infix = isInfix,
        static = isStatic,
        tailrec = isTailRec,
        external = isExternal,
        expect = isExpect,
        actual = isActual,
    )

private fun KaKotlinPropertySymbol.replacementSignature(): ReplacementPropertySignature? {
    val exactGetter = getter ?: return null
    if (!hasGetter) return null
    val exactSetter = setter
    if (hasSetter != (exactSetter != null)) return null
    return ReplacementPropertySignature.of(
        name = name.asString(),
        receiverType = receiverParameter?.returnType?.canonicalReplacementType(),
        contextReceiverTypes = contextReceivers.map { receiver -> receiver.type.canonicalReplacementType() },
        typeParameters = typeParameters.map(KaTypeParameterSymbol::replacementSignature),
        returnType = returnType.canonicalReplacementType(),
        visibility = visibility.toReplacementVisibility() ?: return null,
        modality = modality.toReplacementModality(),
        getterVisibility = exactGetter.visibility.toReplacementVisibility() ?: return null,
        setterVisibility = exactSetter?.visibility?.toReplacementVisibility(),
        hasGetter = hasGetter,
        hasSetter = hasSetter,
        hasBackingField = hasBackingField,
        isVal = isVal,
        const = isConst,
        lateinit = isLateInit,
        delegated = isDelegatedProperty,
        override = isOverride,
        static = isStatic,
        external = isExternal,
        expect = isExpect,
        actual = isActual,
    )
}

private fun KaTypeParameterSymbol.replacementSignature(): ReplacementTypeParameterSignature =
    ReplacementTypeParameterSignature(
        name = name.asString(),
        upperBounds = upperBounds.joinToString(" & ") { bound -> bound.canonicalReplacementType() },
        variance = when (variance) {
            org.jetbrains.kotlin.types.Variance.INVARIANT -> ReplacementTypeVariance.INVARIANT
            org.jetbrains.kotlin.types.Variance.IN_VARIANCE -> ReplacementTypeVariance.IN
            org.jetbrains.kotlin.types.Variance.OUT_VARIANCE -> ReplacementTypeVariance.OUT
        },
        reified = isReified,
    )

private fun KaValueParameterSymbol.replacementSignature(): ReplacementValueParameterSignature =
    ReplacementValueParameterSignature(
        name = name.asString(),
        type = returnType.canonicalReplacementType(),
        vararg = isVararg,
        hasDefaultValue = hasDefaultValue,
        noinline = isNoinline,
        crossinline = isCrossinline,
    )

internal fun org.jetbrains.kotlin.analysis.api.types.KaType.canonicalReplacementType(): String =
    toString().replace('/', '.')

private fun KaSymbolVisibility.toReplacementVisibility(): ReplacementVisibility? = when (this) {
    KaSymbolVisibility.PUBLIC -> ReplacementVisibility.PUBLIC
    KaSymbolVisibility.PROTECTED -> ReplacementVisibility.PROTECTED
    KaSymbolVisibility.INTERNAL -> ReplacementVisibility.INTERNAL
    KaSymbolVisibility.PACKAGE_PROTECTED -> ReplacementVisibility.PACKAGE_PROTECTED
    KaSymbolVisibility.PACKAGE_PRIVATE -> ReplacementVisibility.PACKAGE_PRIVATE
    KaSymbolVisibility.PRIVATE -> ReplacementVisibility.PRIVATE
    KaSymbolVisibility.LOCAL -> ReplacementVisibility.LOCAL
    KaSymbolVisibility.UNKNOWN -> null
}

private fun KaSymbolModality.toReplacementModality(): ReplacementModality = when (this) {
    KaSymbolModality.FINAL -> ReplacementModality.FINAL
    KaSymbolModality.SEALED -> ReplacementModality.SEALED
    KaSymbolModality.OPEN -> ReplacementModality.OPEN
    KaSymbolModality.ABSTRACT -> ReplacementModality.ABSTRACT
}

internal fun compilerSourceIdentity(source: PsiElement): SymbolIdentity {
    val identitySource = when (source) {
        is KtNamedDeclaration, is PsiClass, is PsiMethod, is PsiField -> source
        else -> PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false) ?: source
    }
    val symbol = when (val containingFile = identitySource.containingFile) {
        is KtFile -> analyze(containingFile) {
            identitySource.toSymbolModel(
                containingDeclaration = compilerContainingDeclarationName(identitySource),
            )
        }
        else -> identitySource.toSymbolModel(
            containingDeclaration = when (identitySource) {
                is PsiMethod -> identitySource.containingClass?.qualifiedName
                is PsiField -> identitySource.containingClass?.qualifiedName
                is PsiClass -> identitySource.containingClass?.qualifiedName
                else -> null
            },
        )
    }
    return symbol.relationshipIdentity()
}
