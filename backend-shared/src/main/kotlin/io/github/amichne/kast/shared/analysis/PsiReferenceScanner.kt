package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
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

data class PsiRelationshipScanResult(
    val contentHash: FileContentHash,
    val references: List<SymbolReferenceRow>,
    val declarations: List<DeclarationRow>,
    val limitations: List<FileStageLimitation>,
)

class PsiReferenceScanner(
    private val environment: ReferenceIndexEnvironment,
    private val moduleNameForFile: (String) -> String? = { null },
) {
    fun scanFileRelationships(filePath: String): PsiRelationshipScanResult =
        // One epoch binds every persisted relationship fact to one immutable PSI revision.
        environment.withExclusiveAccess {
            val psiFile = requirePsiFile(filePath)
            val sourceFilePath = psiFile.sourceFilePath(filePath)
            val referenceResult = scanFileReferenceCoverage(psiFile, sourceFilePath)
            val declarationResult = scanFileDeclarationCoverage(psiFile, sourceFilePath)
            PsiRelationshipScanResult(
                contentHash = psiFile.contentHash(),
                references = referenceResult.rows,
                declarations = declarationResult.rows,
                limitations = (referenceResult.limitations + declarationResult.limitations).distinct(),
            )
        }

    fun scanFileReferences(filePath: String): List<SymbolReferenceRow> =
        scanFileReferenceCoverage(filePath).rows

    fun scanFileDeclarations(filePath: String): List<DeclarationRow> =
        scanFileDeclarationCoverage(filePath).rows

    private fun scanFileReferenceCoverage(filePath: String): ScanCoverage<SymbolReferenceRow> =
        // Exclusive access required: the headless backend's K2 FIR lazy declaration
        // resolver is not thread-safe for concurrent resolution within a single session.
        environment.withExclusiveAccess {
            val psiFile = requirePsiFile(filePath)
            scanFileReferenceCoverage(psiFile, psiFile.sourceFilePath(filePath))
        }

    private fun scanFileReferenceCoverage(
        psiFile: PsiFile,
        sourceFilePath: String,
    ): ScanCoverage<SymbolReferenceRow> {
        val rows = mutableListOf<SymbolReferenceRow>()
        val limitations = linkedSetOf<FileStageLimitation>()
        val markUnresolved: () -> Unit = {
            limitations += FileStageLimitation.UNRESOLVED_RELATIONSHIP
        }
        psiFile.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    try {
                        if (environment.isCancelled()) {
                            stopWalking()
                            return
                        }
                        ProgressManager.checkCanceled()
                        recoverRuntimePsiFailure(
                            onFailure = markUnresolved,
                        ) {
                            element.references
                        }.orEmpty().forEach { reference ->
                            try {
                                val resolvedElement = recoverRuntimePsiFailure(
                                    onFailure = markUnresolved,
                                ) {
                                    reference.resolve()
                                }
                                if (resolvedElement == null) {
                                    markUnresolved()
                                    return@forEach
                                }
                                val resolved = resolvedElement as? KtNamedDeclaration ?: return@forEach
                                val target = recoverRuntimePsiFailure(
                                    onFailure = markUnresolved,
                                ) {
                                    resolved.targetFqNameAndPackage()
                                }
                                if (target == null) {
                                    markUnresolved()
                                    return@forEach
                                }
                                val (fqName, _) = target
                                val targetPath = recoverRuntimePsiFailure { resolved.resolvedFilePath().value }
                                val targetOffset = recoverRuntimePsiFailure {
                                    resolved.declarationIdentityOffset()
                                }
                                val sourceElementStart = recoverRuntimePsiFailure(
                                    onFailure = markUnresolved,
                                ) {
                                    reference.element.textRange.startOffset
                                }
                                if (sourceElementStart == null) {
                                    markUnresolved()
                                    return@forEach
                                }
                                val sourceOffset = sourceElementStart +
                                                   reference.rangeInElement.startOffset
                                rows += SymbolReferenceRow(
                                    sourcePath = sourceFilePath,
                                    sourceOffset = sourceOffset,
                                    sourceFqName = recoverRuntimePsiFailure {
                                        reference.element.enclosingDeclarationFqName()
                                    },
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
                                markUnresolved()
                            }
                        }
                        recoverRuntimePsiFailure(
                            onFailure = markUnresolved,
                        ) {
                            super.visitElement(element)
                        }
                    } catch (error: ProcessCanceledException) {
                        throw error
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        markUnresolved()
                    }
                }
            },
        )
        return ScanCoverage(rows = rows, limitations = limitations.toList())
    }

    private fun scanFileDeclarationCoverage(filePath: String): ScanCoverage<DeclarationRow> =
        environment.withExclusiveAccess {
            val psiFile = requirePsiFile(filePath)
            scanFileDeclarationCoverage(psiFile, psiFile.sourceFilePath(filePath))
        }

    private fun scanFileDeclarationCoverage(
        psiFile: PsiFile,
        sourceFilePath: String,
    ): ScanCoverage<DeclarationRow> {
        val rows = mutableListOf<DeclarationRow>()
        val limitations = linkedSetOf<FileStageLimitation>()
        val markUnresolved: () -> Unit = {
            limitations += FileStageLimitation.UNRESOLVED_RELATIONSHIP
        }
        val (modulePath, sourceSet) = splitModuleName(moduleNameForFile(sourceFilePath))
        psiFile.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    try {
                        if (environment.isCancelled()) {
                            stopWalking()
                            return
                        }
                        ProgressManager.checkCanceled()
                        recoverRuntimePsiFailure(
                            onFailure = markUnresolved,
                        ) {
                            element.declarationRow(sourceFilePath, modulePath, sourceSet)
                        }?.let(rows::add)
                        recoverRuntimePsiFailure(
                            onFailure = markUnresolved,
                        ) {
                            super.visitElement(element)
                        }
                    } catch (error: ProcessCanceledException) {
                        throw error
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        markUnresolved()
                    }
                }
            },
        )
        return ScanCoverage(rows = rows, limitations = limitations.toList())
    }

    private fun requirePsiFile(filePath: String): PsiFile =
        requireNotNull(environment.findPsiFile(filePath)) {
            "PSI file is unavailable for relationship indexing: $filePath"
        }

    private fun PsiFile.sourceFilePath(fallback: String): String =
        runCatching { resolvedFilePath().value }.getOrElse { fallback }

    private fun PsiFile.contentHash(): FileContentHash =
        FileContentHash.parse(FileHashing.sha256(text))

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

    private fun KtNamedDeclaration.declarationIdentityOffset(): Int? =
        nameIdentifier?.textRange?.startOffset ?: textRange?.startOffset

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

}

private data class ScanCoverage<T>(
    val rows: List<T>,
    val limitations: List<FileStageLimitation>,
)

internal inline fun <T> recoverRuntimePsiFailure(
    onFailure: () -> Unit = {},
    action: () -> T,
): T? =
    try {
        action()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: StackOverflowError) {
        onFailure()
        null
    } catch (_: Exception) {
        onFailure()
        null
    }
