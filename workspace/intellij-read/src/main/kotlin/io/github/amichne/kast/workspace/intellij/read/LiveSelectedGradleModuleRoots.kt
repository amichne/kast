package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.model.project.IExternalSystemSourceType
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.SourceFolder
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.plugins.gradle.model.ExternalProject
import org.jetbrains.plugins.gradle.model.ExternalSourceSet

internal data class CapturedSelectedGradleModuleRoots(
    val entries: List<WorkspaceSourceRootBoundary>,
    val ideRoots: List<IdeCodeSourceRoot>,
    val excludedRoots: Set<Path>,
)

/** Source observation is inaccessible until the module carries proof of selected-build ownership. */
internal class LiveSelectedGradleModuleRoots(
    private val admitted: AdmittedSelectedBuildModule,
    private val observation: IntellijReadObservation,
    private val limits: ReadLimits,
) {
    private val moduleName = BoundedModuleName.observe(admitted.identity.value, limits)
    private val entries = ArrayList<WorkspaceSourceRootBoundary>()
    private val ideRoots = ArrayList<IdeCodeSourceRoot>()
    private val excludedRoots = linkedSetOf<Path>()
    private val absentRoots = ArrayList<IdeSourceRootEvidence>()
    private val declaredRoots = linkedSetOf<Path>()
    private var moduleRoots = 0

    fun capture(
        sourceSetsFor: (Module) -> Map<String, ExternalSourceSet>,
        sourceFolders: (Module) -> List<SourceFolder>,
    ): Refinement<CapturedSelectedGradleModuleRoots, NamedGradleSourceScopeFailure> {
        when (val roots = captureIdeRoots(sourceFolders(admitted.module))) {
            is Refinement.Rejected -> return roots
            is Refinement.Refined -> Unit
        }
        if (ideRoots.isEmpty() && absentRoots.isEmpty()) return completed()
        val owner = admitted.owner.project
        when (val captured = captureSourceSets(owner, sourceSetsFor(admitted.module))) {
            is Refinement.Rejected -> return captured
            is Refinement.Refined -> Unit
        }
        when (val checked = verifyAbsentRoots()) {
            is Refinement.Rejected -> return checked
            is Refinement.Refined -> Unit
        }
        return completed()
    }

    private fun verifyAbsentRoots(): Refinement<Unit, NamedGradleSourceScopeFailure> {
        for (evidence in absentRoots) {
            val path =
                when (val parsed = absentRootPath(evidence)) {
                    is Refinement.Rejected -> return parsed
                    is Refinement.Refined -> parsed.value
                }
            if (path !in declaredRoots) return rejected(IdeRootMappingFailure.GradleOwnerMissing(evidence))
            val absent =
                try {
                    Files.notExists(path)
                } catch (_: SecurityException) {
                    false
                }
            if (!absent) return rejected(IdeRootMappingFailure.SourceFolderUnavailable(evidence))
            observation.count(IntellijReadCounter.SOURCE_ROOTS_ABSENT)
        }
        return Refinement.Refined(Unit)
    }

    private fun absentRootPath(evidence: IdeSourceRootEvidence): Refinement<Path, NamedGradleSourceScopeFailure> =
        try {
            Refinement.Refined(Path.of(URI(evidence.root.value)))
        } catch (_: IllegalArgumentException) {
            rejected(IdeRootMappingFailure.SourceFolderUnavailable(evidence))
        } catch (_: FileSystemNotFoundException) {
            rejected(IdeRootMappingFailure.SourceFolderUnavailable(evidence))
        } catch (_: SecurityException) {
            rejected(IdeRootMappingFailure.SourceFolderUnavailable(evidence))
        }

    private fun completed() =
        Refinement.Refined(
            CapturedSelectedGradleModuleRoots(entries.toList(), ideRoots.toList(), excludedRoots.toSet())
        )

    private fun captureIdeRoots(folders: List<SourceFolder>): Refinement<Unit, NamedGradleSourceScopeFailure> {
        observation.count(IntellijReadCounter.SOURCE_ROOTS, amount = folders.size)
        if (folders.size > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value) {
            observation.terminated(IntellijReadTermination.SOURCE_ROOT_ADMISSION_LIMIT)
            return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
        }
        for (folder in folders) {
            val evidence = IdeSourceRootEvidence(moduleName, BoundedSourceRootIdentity.observe(folder.url, limits))
            val captured =
                when (val result = captureFolder(folder, evidence)) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            when (captured) {
                is CapturedIdeFolder.Code -> ideRoots += captured.root
                is CapturedIdeFolder.Resource -> excludedRoots.add(captured.path)
                is CapturedIdeFolder.Absent -> {
                    absentRoots += captured.evidence
                }
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun captureSourceSets(
        owner: ExternalProject,
        sourceSets: Map<String, ExternalSourceSet>,
    ): Refinement<Unit, NamedGradleSourceScopeFailure> {
        if (sourceSets.isEmpty()) return Refinement.Rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
        if (sourceSets.size > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value)
            return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
        for ((name, sourceSet) in sourceSets) {
            ProgressManager.checkCanceled()
            if (name != sourceSet.name) return Refinement.Rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
            when (val captured = captureSourceSet(owner, sourceSet)) {
                is Refinement.Rejected -> return captured
                is Refinement.Refined -> Unit
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun captureSourceSet(
        owner: ExternalProject,
        sourceSet: ExternalSourceSet,
    ): Refinement<Unit, NamedGradleSourceScopeFailure> {
        for ((type, directories) in sourceSet.sources) {
            for (path in directories.srcDirs) {
                when (
                    val captured = captureRoot(owner = owner, sourceSet = sourceSet, type = type, path = path.toPath())
                ) {
                    is Refinement.Rejected -> return captured
                    is Refinement.Refined -> Unit
                }
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun captureRoot(
        owner: ExternalProject,
        sourceSet: ExternalSourceSet,
        type: IExternalSystemSourceType,
        path: Path,
    ): Refinement<Unit, NamedGradleSourceScopeFailure> {
        if (++moduleRoots > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value)
            return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
        declaredRoots.add(path)
        if (type.isResource || type.isExcluded) excludedRoots.add(path)
        if (!type.isResource)
            entries +=
                WorkspaceSourceRootBoundary(
                    ideaModuleName = admitted.identity.value,
                    linkedBuildRoot = admitted.ownership.build.path,
                    gradleProjectPath = owner.path,
                    sourceSetName = sourceSet.name,
                    sourceRoot = path,
                    sourceKind = if (type.isTest) WorkspaceSourceRootKind.TEST else WorkspaceSourceRootKind.PRODUCTION,
                    provenance =
                        if (type.isGenerated) WorkspaceSourceRootProvenance.GENERATED
                        else WorkspaceSourceRootProvenance.AUTHORED,
                )
        return Refinement.Refined(Unit)
    }

    // Platform root getters may throw unchecked failures. Translate them here while identity is still available;
    // cancellation is control flow and must leave this boundary unchanged.
    @Suppress("TooGenericExceptionCaught")
    private fun captureFolder(
        folder: SourceFolder,
        evidence: IdeSourceRootEvidence,
    ): Refinement<CapturedIdeFolder, NamedGradleSourceScopeFailure> =
        try {
            observeFolder(folder, evidence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            observation.unexpected(
                IntellijReadUnexpectedFailure.capture(IntellijReadStage.MODEL_CAPTURE, failure, limits)
            )
            rejected(IdeRootMappingFailure.SourceFolderObservationFailed(evidence))
        }

    private fun observeFolder(
        folder: SourceFolder,
        evidence: IdeSourceRootEvidence,
    ): Refinement<CapturedIdeFolder, NamedGradleSourceScopeFailure> {
        val type = folder.rootType
        if (type !is JavaSourceRootType && type !is JavaResourceRootType)
            return rejected(IdeRootMappingFailure.UnsupportedRootType(evidence))
        // Gradle permits declared source directories that do not exist on disk. IDEA has no file
        // to classify or search in that case; retain the observation and admit the remaining roots.
        val file = folder.file ?: return Refinement.Refined(CapturedIdeFolder.Absent(evidence))
        if (type is JavaResourceRootType) return Refinement.Refined(CapturedIdeFolder.Resource(file.toNioPath()))
        val properties =
            folder.jpsElement.properties as? JavaSourceRootProperties
                ?: return rejected(IdeRootMappingFailure.SourcePropertiesUnavailable(evidence))
        return Refinement.Refined(
            CapturedIdeFolder.Code(
                IdeCodeSourceRoot(
                    path = file.toNioPath(),
                    kind =
                        if (type == JavaSourceRootType.TEST_SOURCE) WorkspaceSourceRootKind.TEST
                        else WorkspaceSourceRootKind.PRODUCTION,
                    provenance =
                        if (properties.isForGeneratedSources) WorkspaceSourceRootProvenance.GENERATED
                        else WorkspaceSourceRootProvenance.AUTHORED,
                    evidence = evidence,
                )
            )
        )
    }

    private fun rejected(cause: IdeRootMappingFailure) =
        Refinement.Rejected(NamedGradleSourceScopeFailure.RootMapping(cause))
}

private sealed interface CapturedIdeFolder {
    data class Absent(val evidence: IdeSourceRootEvidence) : CapturedIdeFolder

    data class Code(val root: IdeCodeSourceRoot) : CapturedIdeFolder

    data class Resource(val path: Path) : CapturedIdeFolder
}
