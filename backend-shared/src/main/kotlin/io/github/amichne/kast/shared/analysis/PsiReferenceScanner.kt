package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.api.index.splitModuleName
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import java.util.concurrent.CancellationException

class PsiReferenceScanner(
    private val environment: ReferenceIndexEnvironment,
    private val moduleNameForFile: (String) -> String? = { null },
) {
    fun scanFileReferences(filePath: String): List<SymbolReferenceRow> =
        // Exclusive access required: the headless backend's K2 FIR lazy declaration
        // resolver is not thread-safe for concurrent resolution within a single session.
        environment.withExclusiveAccess {
            val rows = mutableListOf<SymbolReferenceRow>()
            val psiFile = environment.findPsiFile(filePath) ?: return@withExclusiveAccess emptyList()
            val sourceFilePath = runCatching { psiFile.resolvedFilePath().value }.getOrElse { filePath }

            psiFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        try {
                            if (environment.isCancelled()) {
                                stopWalking()
                                return
                            }
                            ProgressManager.checkCanceled()
                            recoverRuntimePsiFailure { element.references }.orEmpty().forEach { reference ->
                                try {
                                    val resolved = reference.resolve() ?: return@forEach
                                    val (fqName, _) = resolved.targetFqNameAndPackage() ?: return@forEach
                                    val targetPath = recoverRuntimePsiFailure { resolved.resolvedFilePath().value }
                                    val targetOffset = recoverRuntimePsiFailure {
                                        resolved.declarationIdentityOffset()
                                    }
                                    val sourceElementStart = recoverRuntimePsiFailure {
                                        reference.element.textRange.startOffset
                                    } ?: return@forEach
                                    val sourceOffset = sourceElementStart +
                                                       reference.rangeInElement.startOffset
                                    rows += SymbolReferenceRow(
                                        sourcePath = sourceFilePath,
                                        sourceOffset = sourceOffset,
                                        sourceFqName = reference.element.enclosingDeclarationFqName(),
                                        targetFqName = fqName.value,
                                        targetPath = targetPath,
                                        targetOffset = targetOffset,
                                        edgeKind = reference.element.edgeKind(),
                                    )
                                } catch (error: ProcessCanceledException) {
                                    throw error
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    // Skip one bad reference while continuing to index the file.
                                }
                            }
                            recoverRuntimePsiFailure { super.visitElement(element) }
                        } catch (error: ProcessCanceledException) {
                            throw error
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Skip elements with invalid PSI mirrors (e.g., compiled JDK classes)
                            // and continue walking the tree
                        }
                    }
                },
            )
            rows
        }

    fun scanFileDeclarations(filePath: String): List<DeclarationRow> =
        environment.withExclusiveAccess {
            val rows = mutableListOf<DeclarationRow>()
            val psiFile = environment.findPsiFile(filePath) ?: return@withExclusiveAccess emptyList()
            val sourceFilePath = runCatching { psiFile.resolvedFilePath().value }.getOrElse { filePath }
            val (modulePath, sourceSet) = splitModuleName(moduleNameForFile(sourceFilePath))
            psiFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (environment.isCancelled()) {
                            stopWalking()
                            return
                        }
                        ProgressManager.checkCanceled()
                        element.declarationRow(sourceFilePath, modulePath, sourceSet)?.let(rows::add)
                        super.visitElement(element)
                    }
                },
            )
            rows
        }

    private fun PsiElement.declarationRow(
        sourceFilePath: String,
        modulePath: String?,
        sourceSet: String?,
    ): DeclarationRow? {
        val declaration = this as? KtNamedDeclaration ?: return null
        val fqName = declaration.targetFqNameAndPackage()?.first?.value ?: return null
        return DeclarationRow(
            fqName = fqName,
            kind = declaration.declarationKind() ?: return null,
            visibility = declaration.visibility().toDeclarationVisibility(),
            filePath = sourceFilePath,
            declarationOffset = declaration.nameIdentifier?.textRange?.startOffset ?: declaration.textRange?.startOffset,
            modulePath = modulePath,
            sourceSet = sourceSet,
            supertypes = (declaration as? KtClassOrObject)?.superTypeListEntries
                ?.mapNotNull { entry ->
                    entry.typeReference?.references?.firstOrNull()
                        ?.resolve()?.let { resolved ->
                            (resolved as? KtNamedDeclaration)?.targetFqNameAndPackage()?.first?.value
                        }
                }
                ?: emptyList(),
        )
    }

    private fun KtNamedDeclaration.declarationKind(): DeclarationKind? = when (this) {
        is KtEnumEntry -> DeclarationKind.ENUM_ENTRY
        is KtClass -> when {
            isEnum() -> DeclarationKind.ENUM_CLASS
            isInterface() -> DeclarationKind.INTERFACE
            else -> DeclarationKind.CLASS
        }
        is KtObjectDeclaration -> DeclarationKind.OBJECT
        is KtNamedFunction -> DeclarationKind.FUNCTION
        is KtProperty -> DeclarationKind.PROPERTY
        is KtTypeAlias -> DeclarationKind.TYPEALIAS
        is KtConstructor<*> -> DeclarationKind.CONSTRUCTOR
        is KtClassOrObject -> DeclarationKind.CLASS
        else -> null
    }

    private fun SymbolVisibility.toDeclarationVisibility(): DeclarationVisibility = when (this) {
        SymbolVisibility.PUBLIC -> DeclarationVisibility.PUBLIC
        SymbolVisibility.INTERNAL -> DeclarationVisibility.INTERNAL
        SymbolVisibility.PROTECTED -> DeclarationVisibility.PROTECTED
        SymbolVisibility.PRIVATE -> DeclarationVisibility.PRIVATE
        SymbolVisibility.LOCAL -> DeclarationVisibility.LOCAL
        SymbolVisibility.UNKNOWN -> DeclarationVisibility.LOCAL
    }

    private fun PsiElement.enclosingDeclarationFqName(): String? =
        generateSequence(this as PsiElement?) { it.parent }
            .filterIsInstance<KtNamedDeclaration>()
            .firstNotNullOfOrNull { declaration -> declaration.targetFqNameAndPackage()?.first?.value }

    private fun PsiElement.declarationIdentityOffset(): Int? =
        (this as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
            ?: textRange?.startOffset

    private fun PsiElement.edgeKind(): EdgeKind {
        val parents = generateSequence(this as PsiElement?) { it.parent }.take(8).toList()
        return when {
            parents.any { it is KtImportDirective } -> EdgeKind.IMPORT
            parents.any { it is KtAnnotationEntry } -> EdgeKind.ANNOTATION
            parents.any { it is KtSuperTypeListEntry } -> EdgeKind.INHERITANCE
            parents.any { it is KtTypeReference } -> EdgeKind.TYPE_REF
            parents.any { it is KtCallExpression } -> EdgeKind.CALL
            else -> EdgeKind.UNKNOWN
        }
    }

    private inline fun <T> recoverRuntimePsiFailure(action: () -> T): T? =
        try {
            action()
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}
