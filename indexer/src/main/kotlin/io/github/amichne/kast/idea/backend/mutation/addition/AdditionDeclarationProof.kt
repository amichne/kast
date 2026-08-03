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

internal data class AdditionProofParts(
    val declarations: List<AdditionTopLevelDeclaration>,
    val outbound: ExactAdditionOutboundEvidence,
)

internal fun KastIndexerBackend.proveAdditionDeclarations(
    parsed: ParsedAddition,
    relativeBaseOffset: Int,
): AdditionProofParts {
    val declarations = parsed.declarations.map { declaration ->
        requireNoUnhandledImplicitAdditionReferences(declaration)
        AdditionTopLevelDeclaration.of(
            packageIdentity = parsed.packageIdentity,
            name = declaration.name ?: failAddition(
                AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
                "The proposed declaration has no compiler-visible name",
            ),
            kind = declaration.additionKind(),
            relativeStartOffset = declaration.textRange.startOffset - relativeBaseOffset,
            relativeEndOffset = declaration.textRange.endOffset - relativeBaseOffset,
            collisionSignature = compilerAdditionSignature(declaration),
        )
    }
    if (declarations.distinctBy { declaration ->
            when (declaration.kind) {
                AdditionTopLevelDeclarationKind.CLASS,
                AdditionTopLevelDeclarationKind.INTERFACE,
                AdditionTopLevelDeclarationKind.OBJECT,
                AdditionTopLevelDeclarationKind.ENUM_CLASS,
                AdditionTopLevelDeclarationKind.ANNOTATION_CLASS,
                AdditionTopLevelDeclarationKind.TYPE_ALIAS,
                -> listOf(declaration.packageIdentity, declaration.name, "CLASSIFIER")

                AdditionTopLevelDeclarationKind.FUNCTION,
                AdditionTopLevelDeclarationKind.PROPERTY,
                -> listOf(
                    declaration.packageIdentity,
                    declaration.name,
                    declaration.kind,
                    declaration.collisionSignature,
                )
            }
        }.size != declarations.size
    ) failAddition(
        AdditionProofLimitation.DECLARATION_COLLISION,
        "Proposed declarations contain a compiler collision that differs only outside declaration identity",
    )
    proveNoCompilerCollision(parsed, declarations)
    val outbound = parsed.declarations.flatMap { declaration ->
        collectAdditionOutbound(
            parsed.file,
            parsed.declarations,
            declaration,
            relativeBaseOffset,
            parsed.analysisSource,
        )
    }
    return AdditionProofParts(declarations, ExactAdditionOutboundEvidence.complete(outbound))
}
private fun requireNoUnhandledImplicitAdditionReferences(declaration: KtNamedDeclaration) {
    if (declaration.hasUnhandledImplicitCallSyntax()) failAddition(
        AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
        "The proposed addition contains implicit-call syntax that exact outbound proof does not model",
    )
}

private fun KtNamedDeclaration.hasUnhandledImplicitCallSyntax(): Boolean =
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ||
        (this is KtProperty && delegateExpression != null) ||
        PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).any { property ->
            property.delegateExpression != null
        }

private fun KtNamedDeclaration.additionKind(): AdditionTopLevelDeclarationKind = when (this) {
    is KtClass -> when {
        isInterface() -> AdditionTopLevelDeclarationKind.INTERFACE
        isEnum() -> AdditionTopLevelDeclarationKind.ENUM_CLASS
        isAnnotation() -> AdditionTopLevelDeclarationKind.ANNOTATION_CLASS
        else -> AdditionTopLevelDeclarationKind.CLASS
    }
    is KtObjectDeclaration -> AdditionTopLevelDeclarationKind.OBJECT
    is KtNamedFunction -> AdditionTopLevelDeclarationKind.FUNCTION
    is KtProperty -> AdditionTopLevelDeclarationKind.PROPERTY
    is KtTypeAlias -> AdditionTopLevelDeclarationKind.TYPE_ALIAS
    else -> failAddition(
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        "The proposed Kotlin top-level declaration kind is unsupported",
    )
}

private fun compilerAdditionSignature(declaration: KtNamedDeclaration): AdditionDeclarationCollisionSignature {
    val signature = try {
        analyze(declaration) {
            declaration.symbol.additionCollisionSignature()
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    } ?: failAddition(
        AdditionProofLimitation.DECLARATION_COLLISION,
        "K2 could not produce an exact declaration collision signature",
    )
    return AdditionDeclarationCollisionSignature.of(FileHashing.sha256(signature))
}

private fun KaSymbol.additionCollisionSignature(): String? = when (this) {
    is KaFunctionSymbol -> buildString {
        append("function|").append(callableId?.asSingleFqName()?.asString()).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() }).append('|')
        append(typeParameters.joinToString(",") { parameter ->
            parameter.upperBounds.joinToString("&") { it.toString() }
        }).append('|')
        append(valueParameters.joinToString(",") { it.returnType.toString() })
    }
    is KaVariableSymbol -> buildString {
        append("property|").append(callableId?.asSingleFqName()).append('|')
        append(receiverParameter?.returnType ?: "-").append('|')
        append(contextReceivers.joinToString(",") { it.type.toString() })
    }
    is KaClassLikeSymbol -> "class|${classId?.asSingleFqName()}"
    is KaTypeAliasSymbol -> "typealias|${classId?.asSingleFqName()}"
    else -> null
}

private fun KastIndexerBackend.proveNoCompilerCollision(
    parsed: ParsedAddition,
    declarations: List<AdditionTopLevelDeclaration>,
) {
    val packageFqName = parsed.packageIdentity.toFqName()
    try {
        analyze(parsed.file) {
            declarations.zip(parsed.declarations).forEach { (evidence, proposedPsi) ->
                when (evidence.kind) {
                    AdditionTopLevelDeclarationKind.CLASS,
                    AdditionTopLevelDeclarationKind.INTERFACE,
                    AdditionTopLevelDeclarationKind.OBJECT,
                    AdditionTopLevelDeclarationKind.ENUM_CLASS,
                    AdditionTopLevelDeclarationKind.ANNOTATION_CLASS,
                    AdditionTopLevelDeclarationKind.TYPE_ALIAS,
                    -> {
                        val fqName = packageFqName.child(Name.identifier(evidence.name))
                        val kotlinCollision = findClassLike(ClassId.topLevel(fqName))
                            ?.psi
                            ?.takeUnless { target -> target.isProposedAdditionTarget(parsed.declarations) }
                        val jvmCollision = JavaPsiFacade.getInstance(project)
                            .findClasses(fqName.asString(), GlobalSearchScope.allScope(project))
                            .any { target -> !target.isProposedAdditionTarget(parsed.declarations) }
                        if (kotlinCollision != null || jvmCollision) failAddition(
                            AdditionProofLimitation.DECLARATION_COLLISION,
                            "A proposed classifier already exists in source or dependency scope",
                        )
                    }

                    AdditionTopLevelDeclarationKind.FUNCTION,
                    AdditionTopLevelDeclarationKind.PROPERTY,
                    -> {
                        val collision = findTopLevelCallables(packageFqName, Name.identifier(evidence.name))
                            .filter { symbol -> !symbol.psi.isProposedAdditionTarget(parsed.declarations) }
                            .any { symbol ->
                                symbol.additionCollisionSignature()?.let(FileHashing::sha256) ==
                                    evidence.collisionSignature.value
                            }
                        if (collision) failAddition(
                            AdditionProofLimitation.DECLARATION_COLLISION,
                            "A proposed callable signature already exists in source or dependency scope",
                        )
                    }
                }
                check(proposedPsi.name == evidence.name)
            }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: AdditionProofIncompleteException) {
        throw failure
    } catch (_: Exception) {
        failAddition(
            AdditionProofLimitation.COMPILER_COLLISION_SCOPE_INCOMPLETE,
            "K2 could not exhaust source and dependency collision lookup",
        )
    }
}
