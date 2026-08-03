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

internal fun KaSymbol.externalReplacementTarget(): ReplacementOutboundTarget.External? {
    val identity = when (this) {
        is KaConstructorSymbol -> containingClassId?.asSingleFqName()?.asString()?.let { owner ->
            Triple(
                "$owner.<init>",
                ReplacementCompilerSymbolKind.CONSTRUCTOR,
                compilerTargetSignature("$owner.<init>"),
            )
        }
        is KaFunctionSymbol -> callableId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.FUNCTION, compilerTargetSignature(fqName))
        }
        is KaVariableSymbol -> callableId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(
                fqName,
                ReplacementCompilerSymbolKind.PROPERTY,
                "property|$fqName|" +
                    "${receiverParameter?.returnType?.canonicalReplacementType() ?: "-"}|" +
                    returnType.canonicalReplacementType(),
            )
        }
        is KaTypeAliasSymbol -> classId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.TYPE_ALIAS, "typealias|$fqName")
        }
        is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.CLASS, "class|$fqName")
        }
        is KaPackageSymbol -> fqName.asString().takeIf(String::isNotBlank)?.let { fqName ->
            Triple(fqName, ReplacementCompilerSymbolKind.PACKAGE, "package|$fqName")
        }
        is KaValueParameterSymbol -> name.asString().takeIf(String::isNotBlank)?.let { name ->
            Triple(
                name,
                ReplacementCompilerSymbolKind.PARAMETER,
                "parameter|$name|${returnType.canonicalReplacementType()}",
            )
        }
        is KaTypeParameterSymbol -> name.asString().takeIf(String::isNotBlank)?.let { name ->
            Triple(
                name,
                ReplacementCompilerSymbolKind.TYPE_PARAMETER,
                "type-parameter|$name|" + upperBounds.joinToString("&") { it.canonicalReplacementType() },
            )
        }
        else -> null
    } ?: return null
    return ReplacementOutboundTarget.External(
        fqName = identity.first,
        kind = identity.second,
        signature = ReplacementCompilerTargetSignature(identity.third),
    )
}

private fun KaFunctionSymbol.compilerTargetSignature(fqName: String): String = buildString {
    append("function|").append(fqName).append('|')
    append(receiverParameter?.returnType?.canonicalReplacementType() ?: "-").append('|')
    append(contextReceivers.joinToString(",") { receiver -> receiver.type.canonicalReplacementType() }).append('|')
    append(valueParameters.joinToString(",") { parameter ->
        parameter.returnType.canonicalReplacementType()
    }).append('|')
    append(returnType.canonicalReplacementType()).append('|')
    append(
        when (this@compilerTargetSignature) {
            is KaNamedFunctionSymbol -> typeParameters.size
            is KaConstructorSymbol -> typeParameters.size
            else -> 0
        },
    )
}

internal fun KastIndexerBackend.sourceOutboundTarget(
    source: PsiElement,
    fallback: ReplacementOutboundTarget.External,
    syntheticFile: KtFile,
    proposed: KtNamedDeclaration,
    replacementStartOffset: Int,
    proposedDeclarationLength: Int,
    sourceIdentityBasis: ReplacementSourceIdentityBasis,
): ReplacementOutboundTarget {
    if (source.containingFile === syntheticFile) {
        if (isInside(proposed, source)) {
            failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "An internal proposed replacement target was classified as outbound",
            )
        }
        val syntheticDeclaration = source as? KtNamedDeclaration
            ?: PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false)
            ?: failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A synthetic compiler target could not be mapped to its exact source declaration",
            )
        val syntheticNameOffset = syntheticDeclaration.nameIdentifier?.textRange?.startOffset
            ?: failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A synthetic compiler target has no exact declaration offset",
            )
        val replacementEnd = replacementStartOffset + proposedDeclarationLength
        val originalNameOffset = when {
            syntheticNameOffset < replacementStartOffset -> syntheticNameOffset
            syntheticNameOffset >= replacementEnd ->
                syntheticNameOffset -
                    (proposedDeclarationLength - sourceIdentityBasis.preimageDeclarationLength.value)
            else -> failReplacementProof(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A proposed replacement target was not retained as an internal reference",
            )
        }
        val identity = when (sourceIdentityBasis) {
            is ReplacementSourceIdentityBasis.CompilerPreimage -> {
                val originalDeclaration = PsiTreeUtil.findChildrenOfType(
                    sourceIdentityBasis.file,
                    KtNamedDeclaration::class.java,
                ).filter { declaration ->
                    declaration.nameIdentifier?.textRange?.startOffset == originalNameOffset &&
                        declaration.name == syntheticDeclaration.name
                }.singleOrNull()
                    ?: failReplacementProof(
                        ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                        "A compiler target in the synthetic file did not map to one exact source declaration",
                    )
                compilerSourceIdentity(originalDeclaration)
            }
            is ReplacementSourceIdentityBasis.PersistedPreimage ->
                compilerSourceIdentity(syntheticDeclaration).copy(
                    declarationStartOffset = NonNegativeInt(originalNameOffset),
                )
        }
        return ReplacementOutboundTarget.Source(identity)
    }

    val sourcePath = source.containingFile?.virtualFile?.path
    if (sourcePath == null || !isWorkspaceFile(sourcePath)) {
        return fallback
    }
    return ReplacementOutboundTarget.Source(compilerSourceIdentity(source))
}
