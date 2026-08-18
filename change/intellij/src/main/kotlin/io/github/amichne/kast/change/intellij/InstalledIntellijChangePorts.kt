package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationSourceText
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Compiler-refined AddDeclaration intent admitted at the live IntelliJ boundary. */
data class InstalledAddDeclarationIntent(
    val declaration: AddDeclarationSourceText,
    val expectedDelta: ExpectedAddDeclarationDelta,
)

/** Finite failures while refining public declaration text against one exact target. */
enum class InstalledAddDeclarationIntentFailure {
    PROJECT_UNAVAILABLE,
    GENERATION_MOVED,
    TARGET_UNAVAILABLE,
    TARGET_NOT_KOTLIN,
    TARGET_MOVED,
    DECLARATION_REJECTED,
    COMPILER_IDENTITY_UNAVAILABLE,
}

/** Closed compiler-backed AddDeclaration intent result. */
sealed interface InstalledAddDeclarationIntentCompilation {
    data class Compiled(
        val intent: InstalledAddDeclarationIntent,
    ) : InstalledAddDeclarationIntentCompilation

    data class Rejected(
        val failure: InstalledAddDeclarationIntentFailure,
    ) : InstalledAddDeclarationIntentCompilation
}

fun interface InstalledAddDeclarationIntentCompiler {
    /**
     * Proof transition: `(SymbolSelector, String) ->
     * InstalledAddDeclarationIntentCompilation`.
     *
     * Compiled establishes canonical declaration source plus one syntactically valid named Kotlin
     * declaration and its exact package/name/kind delta against the selector's live source file.
     * [InstalledAddDeclarationIntentFailure] closes every expected rejection. Raw text, VFS, PSI,
     * and project values are extracted only inside this outer IntelliJ boundary.
     */
    fun compile(
        selector: SymbolSelector,
        rawDeclaration: String,
    ): InstalledAddDeclarationIntentCompilation
}

/** Installed exact-root source, recovery, and AddDeclaration intent capabilities. */
class InstalledIntellijChangePorts private constructor(
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val recoveryRollback: AddDeclarationRollbackPort,
    val intentCompiler: InstalledAddDeclarationIntentCompiler,
) {
    companion object {
        /**
         * Proof transition: `CanonicalWorkspaceRoot -> InstalledIntellijChangePorts`.
         *
         * Establishes request-local physical and compiler ports that locate only the live exact
         * root. Normal writes retain their [MutationAuthority] solely so a matching durable
         * applied-write record can invoke recovery. Missing or moved projects remain closed port
         * failures; no live IntelliJ object escapes a call.
         */
        fun create(root: CanonicalWorkspaceRoot): InstalledIntellijChangePorts {
            val authorities = ConcurrentHashMap<String, MutationAuthority>()
            fun adapter(): IntellijChangeSourceAdapter? = exactProject(root)
                ?.let(::IntellijChangeSourceAdapter)
            return InstalledIntellijChangePorts(
                sourceObserver = { source ->
                    adapter()?.observe(source) ?: unavailableObservation()
                },
                sourceWriter = { authority, durability ->
                    authorities[authority.binding.value] = authority
                    adapter()?.write(authority, durability) ?: unavailableWrite()
                },
                sourceRollback = { authority, record ->
                    adapter()?.rollback(authority, record) ?: unavailableRollback()
                },
                recoveryRollback = recovery@{ record ->
                    val authority = authorities[record.binding.value]
                                    ?: return@recovery unavailableRollback()
                    adapter()?.rollback(authority, record) ?: unavailableRollback()
                },
                intentCompiler = { selector, raw ->
                    compileIntent(root, selector, raw)
                },
            )
        }
    }
}

private fun compileIntent(
    root: CanonicalWorkspaceRoot,
    selector: SymbolSelector,
    rawDeclaration: String,
): InstalledAddDeclarationIntentCompilation {
    val project = exactProject(root) ?: return rejected(
        InstalledAddDeclarationIntentFailure.PROJECT_UNAVAILABLE,
    )
    if (selector.lease.workspaceRoot != root) {
        return rejected(InstalledAddDeclarationIntentFailure.GENERATION_MOVED)
    }
    val declaration = when (val parsed = AddDeclarationSourceText.parse(rawDeclaration)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return rejected(
            InstalledAddDeclarationIntentFailure.DECLARATION_REJECTED,
        )
    }
    return try {
        ProgressManager.checkCanceled()
        if (DumbService.getInstance(project).isDumb) {
            rejected(InstalledAddDeclarationIntentFailure.GENERATION_MOVED)
        } else {
            ReadAction.nonBlocking<InstalledAddDeclarationIntentCompilation> {
                compileIntentRead(project, selector, declaration)
            }.inSmartMode(project).executeSynchronously()
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejected(InstalledAddDeclarationIntentFailure.COMPILER_IDENTITY_UNAVAILABLE)
    }
}

private fun compileIntentRead(
    project: Project,
    selector: SymbolSelector,
    declaration: AddDeclarationSourceText,
): InstalledAddDeclarationIntentCompilation {
    val source = selector.file as? SymbolDiscoveryFileIdentity.Workspace
                 ?: return rejected(InstalledAddDeclarationIntentFailure.TARGET_UNAVAILABLE)
    val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(source.path.value))
               ?: return rejected(InstalledAddDeclarationIntentFailure.TARGET_UNAVAILABLE)
    if (!file.isValid) return rejected(InstalledAddDeclarationIntentFailure.TARGET_UNAVAILABLE)
    val target = PsiManager.getInstance(project).findFile(file) as? KtFile
                 ?: return rejected(InstalledAddDeclarationIntentFailure.TARGET_NOT_KOTLIN)
    val anchors = target.declarations.filter { candidate ->
        candidate.textRange.startOffset == selector.range.startInclusive &&
        candidate.textRange.endOffset == selector.range.endExclusive
    }
    if (anchors.size != 1) return rejected(InstalledAddDeclarationIntentFailure.TARGET_MOVED)
    val parsed = try {
        KtPsiFactory(project, false).createDeclaration<KtDeclaration>(declaration.value)
    } catch (_: Exception) {
        return rejected(InstalledAddDeclarationIntentFailure.DECLARATION_REJECTED)
    }
    if (PsiTreeUtil.hasErrorElements(parsed)) {
        return rejected(InstalledAddDeclarationIntentFailure.DECLARATION_REJECTED)
    }
    val name = parsed.name
               ?: return rejected(InstalledAddDeclarationIntentFailure.COMPILER_IDENTITY_UNAVAILABLE)
    val kind = parsed.addDeclarationKind()
               ?: return rejected(InstalledAddDeclarationIntentFailure.DECLARATION_REJECTED)
    val delta = when (val admitted = ExpectedAddDeclarationDelta.admit(
        target.packageFqName.asString(),
        name,
        kind,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return rejected(
            InstalledAddDeclarationIntentFailure.COMPILER_IDENTITY_UNAVAILABLE,
        )
    }
    return InstalledAddDeclarationIntentCompilation.Compiled(
        InstalledAddDeclarationIntent(declaration, delta),
    )
}

private fun KtDeclaration.addDeclarationKind(): AddDeclarationKind? = when (this) {
    is KtClass -> when {
        isInterface() -> AddDeclarationKind.INTERFACE
        isEnum() -> AddDeclarationKind.ENUM_CLASS
        isAnnotation() -> AddDeclarationKind.ANNOTATION_CLASS
        else -> AddDeclarationKind.CLASS
    }
    is KtObjectDeclaration -> AddDeclarationKind.OBJECT
    is KtNamedFunction -> AddDeclarationKind.FUNCTION
    is KtProperty -> AddDeclarationKind.PROPERTY
    is KtTypeAlias -> AddDeclarationKind.TYPE_ALIAS
    else -> null
}

private fun exactProject(root: CanonicalWorkspaceRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }

private fun rejected(
    failure: InstalledAddDeclarationIntentFailure,
): InstalledAddDeclarationIntentCompilation = InstalledAddDeclarationIntentCompilation.Rejected(
    failure,
)

private fun unavailableObservation() = io.github.amichne.kast.change.apply.SourceObservationResult
    .Rejected(io.github.amichne.kast.change.apply.SourceObservationFailure.TARGET_INVALIDATED)

private fun unavailableWrite() = io.github.amichne.kast.change.apply.SourceWriteResult
    .RejectedBeforeMutation(io.github.amichne.kast.change.apply.SourceWriteFailure.TARGET_INVALIDATED)

private fun unavailableRollback(): AddDeclarationRollbackResult = AddDeclarationRollbackResult
    .Rejected(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
