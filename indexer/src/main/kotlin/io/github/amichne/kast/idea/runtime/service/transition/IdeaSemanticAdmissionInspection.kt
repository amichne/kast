package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException

internal fun inspectSemanticAdmission(
    project: Project,
    operations: IdeaSemanticAdmissionOperations,
): IdeaIndexSemanticAdmission.Inspection =
    ApplicationManager.getApplication().runReadAction<IdeaIndexSemanticAdmission.Inspection> {
        if (DumbService.isDumb(project)) {
            return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending("IDEA indexing is still in progress")
        }
        val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
            ?: return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                "the Kotlin file type is unavailable",
            )
        val kotlinModules = ModuleManager.getInstance(project).modules
            .asSequence()
            .filterNot(Module::isDisposed)
            .mapNotNull { module ->
                val representative = FileTypeIndex.getFiles(
                    kotlinFileType,
                    GlobalSearchScope.moduleScope(module),
                ).asSequence()
                    .filter { file -> file.isValid && !file.isDirectory }
                    .minByOrNull { file -> file.path }
                representative?.let { file -> module to file }
            }
            .sortedBy { (module, _) -> module.name }
            .toList()
        if (kotlinModules.isEmpty()) {
            return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                "no Kotlin source module has been admitted to the project model",
            )
        }

        val javaPsi = JavaPsiFacade.getInstance(project)
        kotlinModules.forEach { (module, representative) ->
            val roots = ModuleRootManager.getInstance(module)
            if (roots.sdk == null) {
                return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "module ${module.name} has no SDK",
                )
            }
            if (roots.orderEntries.any { entry -> !entry.isValid }) {
                return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "module ${module.name} has unresolved order entries",
                )
            }
            val compilerScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
            if (javaPsi.findClass("java.nio.file.Path", compilerScope) == null) {
                return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "JDK symbol java.nio.file.Path is unresolved in module ${module.name}",
                )
            }
            if (javaPsi.findClass("kotlin.jvm.internal.Intrinsics", compilerScope) == null) {
                return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "Kotlin runtime symbol kotlin.jvm.internal.Intrinsics is unresolved in module ${module.name}",
                )
            }
            val ktFile = PsiManager.getInstance(project).findFile(representative) as? KtFile
                ?: return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "IDEA has not created Kotlin PSI for ${representative.path}",
                )
            try {
                operations.collectDiagnostics(ktFile)
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@runReadAction IdeaIndexSemanticAdmission.Inspection.Pending(
                    "Kotlin analysis is unavailable for ${representative.path}: " +
                        (error.message?.takeIf(String::isNotBlank) ?: error::class.qualifiedName),
                )
            }
        }
        IdeaIndexSemanticAdmission.Inspection.Ready
    }
