@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ContainingSymbolUnavailableReason
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.idea.backend.references.absoluteTextRange
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class KastPluginPsiSupport(private val backend: KastPluginBackend) {
    fun toReferenceOccurrence(
        reference: PsiReference,
        includeUsageSiteScope: Boolean,
    ): ReferenceOccurrence? {
        val referenceElement = reference.element
        if (!referenceElement.isValid) return null
        val location = referenceElement.toKastLocation(reference.absoluteTextRange())
        if (!backend.isWorkspaceFile(location.filePath)) return null
        val enrichedLocation = if (includeUsageSiteScope) {
            location.copy(usageSiteScope = referenceElement.usageSiteDeclarationScope())
        } else {
            location
        }
        return ReferenceOccurrence(
            location = enrichedLocation,
            containingSymbol = containingSymbolEvidence(referenceElement),
        )
    }

    fun containingSymbolEvidence(element: PsiElement): ContainingSymbolEvidence {
        val owner = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
            ?: return ContainingSymbolEvidence.TopLevel
        return try {
            val symbol = analyze(owner.containingKtFile) {
                owner.toSymbolModel(containingDeclaration = compilerContainingDeclarationName(owner))
            }
            ContainingSymbolEvidence.Known(
                SymbolIdentity(
                    fqName = symbol.fqName,
                    kind = symbol.kind,
                    declarationFile = NormalizedPath.parse(symbol.location.filePath),
                    declarationStartOffset = NonNegativeInt(symbol.location.startOffset),
                    containingType = symbol.containingDeclaration,
                ),
            )
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ContainingSymbolEvidence.Unavailable(ContainingSymbolUnavailableReason.NO_SEMANTIC_OWNER)
        }
    }

    fun isConcreteType(target: PsiElement): Boolean = when (target) {
        is KtClass -> !target.isInterface() && !target.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is KtObjectDeclaration -> !target.isCompanion()
        is PsiClass -> !target.isInterface && !target.hasModifierProperty(PsiModifier.ABSTRACT)
        else -> false
    }

    fun findKtFile(filePath: String): KtFile {
        val normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString()
        if (!backend.isWorkspaceFile(normalizedPath)) {
            throw NotFoundException("File is outside the active workspace: $filePath")
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            ?: throw NotFoundException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(backend.project).findFile(virtualFile)
            ?: throw NotFoundException("Cannot resolve PSI for: $filePath")
        return psiFile as? KtFile
            ?: throw NotFoundException("Not a Kotlin file: $filePath")
    }
}
