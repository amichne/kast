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

internal data class ParsedProposedDeclaration(
    val declaration: KtNamedDeclaration,
    val nameOffset: Int,
    val declarationSlice: ReplacementDeclarationSlice,
)
internal fun KastIndexerBackend.parseProposedDeclaration(text: String): ParsedProposedDeclaration {
    val parsed = KtPsiFactory(project).createFile("KastProposedReplacement.kt", text)
    val declarations = parsed.declarations
    if (declarations.isEmpty()) {
        failReplacementProof(
            ReplacementProofLimitation.ZERO_REPLACEMENT_DECLARATIONS,
            "The proposed replacement must contain exactly one Kotlin declaration",
        )
    }
    if (declarations.size > 1) {
        failReplacementProof(
            ReplacementProofLimitation.MULTIPLE_REPLACEMENT_DECLARATIONS,
            "The proposed replacement contains more than one Kotlin declaration",
        )
    }
    if (PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) != null) {
        failReplacementProof(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement declaration contains Kotlin syntax errors",
        )
    }
    val declaration = declarations.single() as? KtNamedDeclaration
        ?: failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The proposed replacement is not a named Kotlin declaration",
        )
    if (declaration !is KtNamedFunction && declaration !is KtProperty) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "Replacement planning supports only Kotlin function and property declarations",
        )
    }
    val declarationRange = declaration.textRange
    if (text.substring(0, declarationRange.startOffset).isNotBlank() ||
        text.substring(declarationRange.endOffset).isNotBlank()
    ) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
            "The proposed replacement must contain only one declaration",
        )
    }
    return ParsedProposedDeclaration(
        declaration = declaration,
        nameOffset = declaration.nameIdentifier?.textRange?.startOffset
            ?: failReplacementProof(
                ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
                "The proposed replacement declaration has no compiler-visible name",
            ),
        declarationSlice = ReplacementDeclarationSlice(
            startOffset = NonNegativeInt(declarationRange.startOffset),
            endOffset = NonNegativeInt(declarationRange.endOffset),
        ),
    )
}

internal fun requireNoReplacementAnnotations(declaration: KtNamedDeclaration) {
    val accessorAnnotations = (declaration as? KtProperty)
        ?.accessors
        .orEmpty()
        .flatMap { accessor -> accessor.annotationEntries }
    if (declaration.annotationEntries.isNotEmpty() || accessorAnnotations.isNotEmpty()) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
            "Initial replacement proof does not model declaration or property-accessor annotations",
        )
    }
}

internal fun requireNoUnhandledImplicitReplacementReferences(declaration: KtNamedDeclaration) {
    if (declaration.hasUnhandledImplicitCallSyntax()) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
            "The proposed replacement contains implicit-call syntax that exact outbound proof does not model",
        )
    }
}

private fun KtNamedDeclaration.hasUnhandledImplicitCallSyntax(): Boolean =
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ||
        (this is KtProperty && delegateExpression != null) ||
        PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).any { property ->
            property.delegateExpression != null
        }
