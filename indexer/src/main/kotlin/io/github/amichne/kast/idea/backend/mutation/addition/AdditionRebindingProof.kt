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

internal fun KastIndexerBackend.proveZeroRebindingCandidates(
    declarations: List<KtNamedDeclaration>,
    packageIdentity: AdditionKotlinPackage,
    sourceFiles: List<Path>,
    excludedRanges: Map<String, List<IntRange>> = emptyMap(),
) {
    val admittedFiles = sourceFiles.mapNotNull { path ->
        LocalFileSystem.getInstance().findFileByNioFile(path)
    }
    if (admittedFiles.size != sourceFiles.size) failAddition(
        AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
        "Every model-owned Kotlin and Java source file must have IntelliJ index admission",
    )
    val exactSourceScope = GlobalSearchScope.filesScope(project, admittedFiles)
    val names = declarations.map { declaration -> declaration.name ?: failAddition(
        AdditionProofLimitation.UNSUPPORTED_TOP_LEVEL_DECLARATION,
        "A proposed declaration has no compiler-visible name",
    ) }.distinct()
    declarations.forEach { declaration ->
        if (declaration.hasModifier(KtTokens.OPERATOR_KEYWORD) || declaration.name in KOTLIN_CONVENTION_NAMES) {
            failAddition(
                AdditionProofLimitation.IMPLICIT_LOOKUP_UNACCOUNTED,
                "Operator and Kotlin convention declarations cannot prove an empty implicit lookup scope",
            )
        }
    }
    names.forEach { name ->
        var collision = false
        var occurrence = false
        val complete = try {
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                { element, offsetInElement ->
                    val file = element.containingFile
                    val virtualFile = file?.virtualFile
                    if (virtualFile == null || !ProjectFileIndex.getInstance(project).isInSource(virtualFile) ||
                        (file !is KtFile && file !is PsiJavaFile)
                    ) return@processElementsWithWord true
                    val occurrenceOffset = element.textRange.startOffset + offsetInElement
                    val excluded = excludedRanges[virtualFile.path].orEmpty().any { range ->
                        occurrenceOffset in range
                    }
                    if (excluded) return@processElementsWithWord true
                    occurrence = true
                    val occurrenceElement = file.findElementAt(occurrenceOffset) ?: element
                    val kotlinDeclaration = PsiTreeUtil.getParentOfType(
                        occurrenceElement,
                        KtNamedDeclaration::class.java,
                        false,
                    )
                    if (kotlinDeclaration?.name == name && kotlinDeclaration.parent is KtFile &&
                        (kotlinDeclaration.containingKtFile.packageFqName.toAdditionPackage() == packageIdentity)
                    ) collision = true
                    true
                },
                exactSourceScope,
                name,
                UsageSearchContext.IN_CODE,
                true,
            )
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        }
        if (!complete) failAddition(
            AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
            "Indexed Kotlin and Java rebinding scope traversal did not complete",
        )
        if (collision) failAddition(
            AdditionProofLimitation.DECLARATION_COLLISION,
            "A proposed declaration collides with an existing declaration in its Kotlin package",
        )
        if (occurrence) failAddition(
            AdditionProofLimitation.REBINDING_SCOPE_INCOMPLETE,
            "A proposed declaration name already occurs in Kotlin or Java source; virtual rebinding is unproven",
        )
    }
}
