@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal sealed interface ReplacementExternalTargetAdmission {
    data class Admitted(
        val target: ReplacementOutboundTarget.External,
    ) : ReplacementExternalTargetAdmission

    data object Unsupported : ReplacementExternalTargetAdmission
}

/**
 * Proof transition: [KaSymbol] -> [ReplacementExternalTargetAdmission].
 *
 * Establishes an exact supported external compiler target or a closed unsupported state. Raw K2
 * symbol identity may be extracted only by outbound-reference collection.
 */
internal fun KaSymbol.externalReplacementTarget(): ReplacementExternalTargetAdmission {
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
    }
    return if (identity == null) {
        ReplacementExternalTargetAdmission.Unsupported
    } else {
        ReplacementExternalTargetAdmission.Admitted(
            ReplacementOutboundTarget.External(
                fqName = identity.first,
                kind = identity.second,
                signature = ReplacementCompilerTargetSignature(identity.third),
            ),
        )
    }
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

/**
 * Proof transition: resolved source PSI plus exact synthetic/source mapping authority ->
 * [ReplacementAdmission] of [ReplacementOutboundTarget].
 *
 * Establishes the original exact workspace identity for a synthetic postimage target. Failure is a
 * closed [ReplacementProofRejection]. Raw PSI offsets may be extracted only by outbound-reference
 * collection.
 */
internal fun KastIndexerBackend.sourceOutboundTarget(
    source: PsiElement,
    fallback: ReplacementOutboundTarget.External,
    syntheticFile: KtFile,
    proposed: KtNamedDeclaration,
    replacementStartOffset: Int,
    proposedBodyLength: Int,
    sourceIdentityBasis: ReplacementSourceIdentityBasis,
): ReplacementAdmission<ReplacementOutboundTarget> {
    if (source.containingFile === syntheticFile) {
        if (containment(proposed, source) is ReplacementContainment.Inside) {
            return replacementRejection(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "An internal proposed replacement target was classified as outbound",
            )
        }
        val syntheticDeclaration = when (source) {
            is KtNamedDeclaration -> source
            else -> PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false)
                ?: return replacementRejection(
                    ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                    "A synthetic compiler target could not be mapped to its exact source declaration",
                )
        }
        val syntheticNameOffset = syntheticDeclaration.nameIdentifier?.textRange?.startOffset
            ?: return replacementRejection(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A synthetic compiler target has no exact declaration offset",
            )
        val replacementEnd = replacementStartOffset + proposedBodyLength
        val originalNameOffset = when {
            syntheticNameOffset < replacementStartOffset -> syntheticNameOffset
            syntheticNameOffset >= replacementEnd ->
                syntheticNameOffset -
                    (proposedBodyLength - sourceIdentityBasis.preimageBodyLength.value)

            else -> return replacementRejection(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                "A proposed replacement target was not retained as an internal reference",
            )
        }
        val identity = when (sourceIdentityBasis) {
            is ReplacementSourceIdentityBasis.CompilerPreimage -> {
                val candidates = PsiTreeUtil.findChildrenOfType(
                    sourceIdentityBasis.file,
                    KtNamedDeclaration::class.java,
                ).filter { declaration ->
                    declaration.nameIdentifier?.textRange?.startOffset == originalNameOffset &&
                        declaration.name == syntheticDeclaration.name
                }
                if (candidates.size != 1) {
                    return replacementRejection(
                        ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                        "A compiler target in the synthetic file did not map to one exact source declaration",
                    )
                }
                compilerSourceIdentity(candidates.single())
            }

            is ReplacementSourceIdentityBasis.PersistedPreimage ->
                compilerSourceIdentity(syntheticDeclaration).copy(
                    declarationStartOffset = NonNegativeInt(originalNameOffset),
                )
        }
        return ReplacementAdmission.Admitted(ReplacementOutboundTarget.Source(identity))
    }

    val containingFile = source.containingFile
    val virtualFile = containingFile?.virtualFile
    return if (virtualFile == null || !isWorkspaceFile(virtualFile.path)) {
        ReplacementAdmission.Admitted(fallback)
    } else {
        ReplacementAdmission.Admitted(
            ReplacementOutboundTarget.Source(compilerSourceIdentity(source)),
        )
    }
}
