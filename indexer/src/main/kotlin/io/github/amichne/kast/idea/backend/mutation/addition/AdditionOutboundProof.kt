@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.edit.IdeaLineSeparator
import io.github.amichne.kast.idea.edit.IdeaNormalizedTextEdit
import io.github.amichne.kast.idea.edit.IdeaTextImagePlanner
import io.github.amichne.kast.idea.edit.IdeaUtf16Offset
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

internal fun PsiElement?.isProposedAdditionTarget(proposed: List<KtNamedDeclaration>): Boolean =
    this != null && listOf(this, navigationElement).distinct().any { candidate ->
        proposed.any { declaration ->
            candidate === declaration || PsiTreeUtil.isAncestor(declaration, candidate, false)
        }
    }

internal fun AdditionKotlinPackage.toFqName(): FqName = when (this) {
    AdditionKotlinPackage.Root -> FqName.ROOT
    is AdditionKotlinPackage.Named -> FqName(segments.joinToString(".") { segment -> segment.value })
}

internal fun KastIndexerBackend.collectAdditionOutbound(
    proposedFile: KtFile,
    proposedDeclarations: List<KtNamedDeclaration>,
    declaration: KtNamedDeclaration,
    relativeBaseOffset: Int,
    analysisSource: AdditionAnalysisSource,
): List<ExactAdditionOutboundOccurrence> = try {
    val collect: KaSession.() -> List<ExactAdditionOutboundOccurrence> = {
        val proposalInternalSymbols = proposedDeclarations.map { proposed -> proposed.symbol }
        val exactCallTargets = mutableMapOf<KtReferenceExpression, KaSymbol>()
        PsiTreeUtil.findChildrenOfType(declaration, KtCallExpression::class.java).forEach { call ->
            val callSite = (call.parent as? KtDotQualifiedExpression)
                ?.takeIf { qualified -> qualified.selectorExpression === call }
                ?: call
            when (val callInfo = callSite.resolveToCall()) {
                null -> failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "A proposed addition call did not resolve through K2",
                )
                is KaErrorCallInfo -> failAddition(
                    if (callInfo.candidateCalls.size > 1) {
                        AdditionProofLimitation.OVERLOAD_AMBIGUOUS
                    } else {
                        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED
                    },
                    "The proposed addition call '${call.text}' did not resolve to one exact compiler target: " +
                        "${callInfo.diagnostic.factoryName}: ${callInfo.diagnostic.defaultMessage}",
                )
                else -> {
                    val exactCall = callInfo.singleFunctionCallOrNull()
                    if (exactCall is KaImplicitInvokeCall) failAddition(
                        AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
                        "An implicit invoke cannot retain every compiler target in one addition occurrence",
                    )
                    val callee = call.calleeExpression as? KtReferenceExpression
                    val symbol = exactCall?.signature?.symbol
                    if (callee != null && symbol != null) exactCallTargets[callee] = symbol
                }
            }
        }
        PsiTreeUtil.findChildrenOfType(declaration, KtReferenceExpression::class.java).mapNotNull { expression ->
            if (expression is KtCallExpression) return@mapNotNull null
            val references = expression.references.filterIsInstance<KtReference>()
            if (references.isEmpty()) failAddition(
                AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                "A proposed Kotlin reference has no compiler reference",
            )
            val targets = exactCallTargets[expression]?.let(::listOf) ?: references.map { reference ->
                reference.resolveToSymbol() ?: failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "The proposed Kotlin reference '${expression.text}' did not resolve through K2 " +
                        "via ${reference::class.qualifiedName}",
                )
            }.distinct()
            if (targets.size != 1) failAddition(
                AdditionProofLimitation.OVERLOAD_AMBIGUOUS,
                "A proposed Kotlin reference did not resolve to one exact target",
            )
            val symbol = targets.single()
            if (symbol is KaPackageSymbol) {
                if (expression.isQualifiedPackageSegment()) return@mapNotNull null
                failAddition(
                    AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "A compiler package symbol was not an exact qualified-reference segment",
                )
            }
            val compilerPsi = symbol.psi
            if (proposalInternalSymbols.any { internal -> internal == symbol } ||
                compilerPsi.isWithinProposedFile(proposedFile)
            ) {
                return@mapNotNull null
            }
            val range = expression.textRange
            ExactAdditionOutboundOccurrence.of(
                relativeStartOffset = range.startOffset - relativeBaseOffset,
                relativeEndOffset = range.endOffset - relativeBaseOffset,
                resolvedTarget = symbol.toAdditionTarget(this@collectAdditionOutbound),
            )
        }
    }
    when (analysisSource) {
        AdditionAnalysisSource.COPIED_PROPOSAL -> analyzeCopy(
            declaration,
            KaDanglingFileResolutionMode.PREFER_SELF,
            collect,
        )
        AdditionAnalysisSource.PROJECT_POSTIMAGE -> analyze(declaration, collect)
    }
} catch (failure: AdditionProofIncompleteException) {
    throw failure
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "K2 could not retain exact outbound occurrence evidence",
    )
}

private fun PsiElement?.isWithinProposedFile(proposedFile: KtFile): Boolean =
    this != null && listOf(this, navigationElement).distinct().any { candidate ->
        candidate === proposedFile || PsiTreeUtil.isAncestor(proposedFile, candidate, false)
    }

private fun KtReferenceExpression.isQualifiedPackageSegment(): Boolean {
    val qualified = parent as? KtDotQualifiedExpression ?: return false
    return qualified.receiverExpression === this || qualified.selectorExpression === this
}

private fun KaSymbol.toAdditionTarget(backend: KastIndexerBackend): AdditionResolvedTarget {
    val source = psi
    val sourcePath = source?.containingFile?.virtualFile?.path
    if (source != null && sourcePath != null && backend.isWorkspaceFile(sourcePath)) {
        return AdditionResolvedTarget.Source.of(additionSourceIdentity(source))
    }
    val fqName = when (this) {
        is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()
        is KaCallableSymbol -> callableId?.asSingleFqName()?.asString()
        else -> null
    } ?: failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "An outbound compiler target has no canonical identity",
    )
    val kind = when (this) {
        is KaNamedFunctionSymbol, is KaConstructorSymbol -> SymbolKind.FUNCTION
        is KaVariableSymbol -> SymbolKind.PROPERTY
        is KaClassLikeSymbol -> SymbolKind.CLASS
        is KaTypeAliasSymbol -> SymbolKind.CLASS
        else -> SymbolKind.UNKNOWN
    }
    if (kind == SymbolKind.UNKNOWN) failAddition(
        AdditionProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        "An outbound compiler target has an unsupported symbol kind",
    )
    return AdditionResolvedTarget.External.of(
        fqName = fqName,
        kind = kind,
        compilerSignature = AdditionCompilerTargetSignature.of(stableAdditionTargetSignature(fqName)),
    )
}

private fun KaSymbol.stableAdditionTargetSignature(fqName: String): String = when (this) {
    is KaFunctionSymbol -> buildString {
        append("function|").append(fqName).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(typeParameters.joinToString(",") { parameter ->
            parameter.upperBounds.joinToString("&") { it.toString() }
        }).append('|')
        append(valueParameters.joinToString(",") { it.returnType.toString() }).append('|')
        append(returnType)
    }
    is KaVariableSymbol -> buildString {
        append("property|").append(fqName).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(returnType)
    }
    is KaTypeAliasSymbol -> "typealias|$fqName|${expandedType}"
    is KaClassLikeSymbol -> "class|$fqName"
    else -> "symbol|$fqName|${this::class.qualifiedName}"
}

private fun additionSourceIdentity(source: PsiElement): SymbolIdentity {
    val identitySource = when (source) {
        is KtNamedDeclaration, is PsiClass, is PsiMethod, is PsiField -> source
        else -> PsiTreeUtil.getParentOfType(source, KtNamedDeclaration::class.java, false) ?: source
    }
    return when (val file = identitySource.containingFile) {
        is KtFile -> analyze(file) {
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
    }.relationshipIdentity()
}
