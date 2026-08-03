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

internal fun failReplacementProof(
    limitation: ReplacementProofLimitation,
    message: String,
    knownMinimumCount: Int = 0,
): Nothing = throw ReplacementProofIncompleteException(
    evidence = ReplacementProofFailureEvidence.of(limitation, knownMinimumCount = knownMinimumCount),
    message = message,
)
