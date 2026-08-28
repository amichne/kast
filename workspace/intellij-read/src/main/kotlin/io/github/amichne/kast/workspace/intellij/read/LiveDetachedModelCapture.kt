package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaResourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties

/** Live IDEA 262 adapter for one bounded detached-model observation. */
internal object LiveDetachedModelCapture {
    private sealed interface LiveModuleObservation {
        data class Captured(val module: DetachedModuleBoundary) : LiveModuleObservation
        data object Aggregator : LiveModuleObservation
        data class Rejected(val failure: DetachedModelCaptureFailure) : LiveModuleObservation
    }

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) -> DetachedModelObservation`.
     *
     * Establishes the same complete detached cached-model observation as [observe] while using
     * IntelliJ's suspending write-priority read primitive. The caller thread never blocks waiting
     * for a read action; lifecycle, model, and observation failures remain finite
     * [DetachedModelCaptureFailure]. Raw Project values remain inside [observeInsideRead].
     */
    suspend fun observeAsync(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): DetachedModelObservation {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return rejected(DetachedModelCaptureFailure.WRONG_THREAD)
        }
        return try {
            readAction { observeInsideRead(project, expectedRoot) }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: RuntimeException) {
            rejected(DetachedModelCaptureFailure.OBSERVATION_FAILED)
        }
    }

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) -> DetachedModelObservation`.
     *
     * Establishes a primitive-only, bounded observation inside one IDEA 262
     * `ReadAction.computeCancellable`, or a closed [DetachedModelCaptureFailure]. The live
     * [Project] and every derived platform object remain inside this adapter boundary. The exact
     * `CannotReadException` preemption subtype becomes finite `READ_PREEMPTED` data; all other
     * [ProcessCanceledException] instances remain cancellation and are rethrown.
     */
    @Suppress("IncorrectCancellationExceptionHandling")
    fun observe(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): DetachedModelObservation {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return rejected(DetachedModelCaptureFailure.WRONG_THREAD)
        }
        return try {
            ReadAction.computeCancellable<DetachedModelObservation, RuntimeException> {
                observeInsideRead(project, expectedRoot)
            }
        } catch (_: ReadAction.CannotReadException) {
            rejected(DetachedModelCaptureFailure.READ_PREEMPTED)
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: RuntimeException) {
            rejected(DetachedModelCaptureFailure.OBSERVATION_FAILED)
        }
    }

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) -> DetachedModelObservation` while the
     * cancellable read is held. Establishes current lifecycle, exact root, cached Gradle
     * completeness, and bounded primitive module observations. The closed expected failure is
     * [DetachedModelCaptureFailure]. Raw Project values may be extracted only inside this live
     * IDEA 262 adapter.
     */
    private fun observeInsideRead(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): DetachedModelObservation {
        ProgressManager.checkCanceled()
        if (project.isDisposed) return rejected(DetachedModelCaptureFailure.PROJECT_DISPOSED)
        if (!project.isOpen) return rejected(DetachedModelCaptureFailure.PROJECT_NOT_OPEN)
        if (!project.isInitialized) {
            return rejected(DetachedModelCaptureFailure.PROJECT_NOT_INITIALIZED)
        }
        if (DumbService.isDumb(project)) return rejected(DetachedModelCaptureFailure.PROJECT_DUMB)
        when (observeCanonicalPath(project.basePath, expectedRoot)) {
            ExistingProjectPathMatch.EXACT -> Unit
            ExistingProjectPathMatch.MISMATCH -> return rejected(
                DetachedModelCaptureFailure.ROOT_MISMATCH,
            )
            ExistingProjectPathMatch.UNAVAILABLE -> return rejected(
                DetachedModelCaptureFailure.ROOT_UNAVAILABLE,
            )
        }
        when (val gradleModel = observeGradleModel(project, expectedRoot)) {
            is Refinement.Rejected -> return rejected(gradleModel.failure)
            is Refinement.Refined -> if (
                gradleModel.value != ExistingProjectGradleModelState.COMPLETE
            ) {
                return rejected(DetachedModelCaptureFailure.GRADLE_MODEL_INCOMPLETE)
            }
        }
        val liveModules = ModuleManager.getInstance(project).modules
        if (liveModules.isEmpty()) return rejected(DetachedModelCaptureFailure.NO_MODULES)
        if (liveModules.size > DetachedModelLimits.MAX_MODULES) {
            return rejected(DetachedModelCaptureFailure.TOO_MANY_MODULES)
        }
        val modules = ArrayList<DetachedModuleBoundary>(liveModules.size)
        for (module in liveModules) {
            ProgressManager.checkCanceled()
            when (val observed = observeModule(module)) {
                is LiveModuleObservation.Captured -> modules += observed.module
                LiveModuleObservation.Aggregator -> Unit
                is LiveModuleObservation.Rejected -> return rejected(observed.failure)
            }
        }
        return DetachedModelObservation.Observed(
            DetachedModelBoundary(
                disposed = false,
                smart = true,
                projectRoot = project.basePath,
                gradleModelComplete = true,
                modules = modules,
            ),
        )
    }

    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) ->
     * Refinement<ExistingProjectGradleModelState, DetachedModelCaptureFailure>`. Establishes a
     * bounded classification of cached exact-root Gradle model evidence without import or repair.
     * The closed expected failure is [DetachedModelCaptureFailure]. Raw Gradle values may be
     * extracted only inside this live cached-model adapter.
     */
    private fun observeGradleModel(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): Refinement<ExistingProjectGradleModelState, DetachedModelCaptureFailure> {
        val infos = ProjectDataManager.getInstance()
            .getExternalProjectsData(project, ProjectSystemId("GRADLE"))
        if (infos.size > DetachedModelLimits.MAX_CACHED_GRADLE_MODELS) {
            return Refinement.Rejected(DetachedModelCaptureFailure.TOO_MANY_GRADLE_MODELS)
        }
        val observations = ArrayList<ExistingProjectGradleModelObservation>(infos.size)
        for (info in infos) {
            ProgressManager.checkCanceled()
            observations += ExistingProjectGradleModelObservation(
                pathMatch = observeCanonicalPath(info.externalProjectPath, expectedRoot),
                structure = if (info.externalProjectStructure?.isReady == true) {
                    ExistingProjectStructureState.READY
                } else {
                    ExistingProjectStructureState.INCOMPLETE
                },
                importState = observeImportState(
                    info.lastSuccessfulImportTimestamp,
                    info.lastImportTimestamp,
                ),
            )
        }
        return Refinement.Refined(classifyCachedGradleModel(observations))
    }

    /**
     * Proof transition: `Module -> LiveModuleObservation`.
     *
     * Establishes either one bounded primitive source-bearing module, one closed Gradle aggregator
     * exclusion, or finite [DetachedModelCaptureFailure]. Raw IntelliJ module objects may be
     * extracted only inside this live adapter.
     */
    private fun observeModule(
        module: Module,
    ): LiveModuleObservation {
        if (module.isDisposed) {
            return LiveModuleObservation.Rejected(DetachedModelCaptureFailure.MODULE_DISPOSED)
        }
        val rootManager = ModuleRootManager.getInstance(module)
        val sourceRoots = when (val observed = observeSourceRoots(rootManager)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return LiveModuleObservation.Rejected(observed.failure)
        }
        if (sourceRoots.isEmpty()) return LiveModuleObservation.Aggregator
        val classpath = when (val observed = observeClasspath(rootManager)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return LiveModuleObservation.Rejected(observed.failure)
        }
        val sdk = rootManager.sdk?.let { liveSdk ->
            DetachedSdkBoundary(
                name = liveSdk.name,
                type = liveSdk.sdkType.name,
                version = liveSdk.versionString,
            )
        }
        return LiveModuleObservation.Captured(
            DetachedModuleBoundary(
                disposed = false,
                name = module.name,
                gradleOwned = ExternalSystemApiUtil.isExternalSystemAwareModule("GRADLE", module),
                gradleBuildRoot = ExternalSystemApiUtil.getExternalRootProjectPath(module),
                gradleProjectRoot = ExternalSystemApiUtil.getExternalProjectPath(module),
                gradleProjectIdentity = ExternalSystemApiUtil.getExternalProjectId(module),
                sourceRoots = sourceRoots,
                sdk = sdk,
                classpath = classpath,
            ),
        )
    }

    /**
     * Proof transition: `ModuleRootManager -> Refinement<List<DetachedSourceRootBoundary>,
     * DetachedModelCaptureFailure>`. Establishes an explicitly bounded primitive root list. The
     * closed expected failure is [DetachedModelCaptureFailure]. Raw source folders and virtual
     * files may be extracted only inside this live adapter.
     */
    private fun observeSourceRoots(
        rootManager: ModuleRootManager,
    ): Refinement<List<DetachedSourceRootBoundary>, DetachedModelCaptureFailure> {
        val entries = rootManager.contentEntries
        if (entries.size > DetachedModelLimits.MAX_SOURCE_ROOTS_PER_MODULE) {
            return Refinement.Rejected(DetachedModelCaptureFailure.TOO_MANY_SOURCE_ROOTS)
        }
        val roots = ArrayList<DetachedSourceRootBoundary>()
        for (entry in entries) {
            ProgressManager.checkCanceled()
            for (folder in entry.sourceFolders) {
                ProgressManager.checkCanceled()
                if (roots.size == DetachedModelLimits.MAX_SOURCE_ROOTS_PER_MODULE) {
                    return Refinement.Rejected(DetachedModelCaptureFailure.TOO_MANY_SOURCE_ROOTS)
                }
                roots += DetachedSourceRootBoundary(
                    path = folder.file?.path,
                    kind = when (folder.rootType) {
                        JavaSourceRootType.SOURCE -> DetachedSourceRootKind.PRODUCTION
                        JavaSourceRootType.TEST_SOURCE -> DetachedSourceRootKind.TEST
                        JavaResourceRootType.RESOURCE -> DetachedSourceRootKind.RESOURCE
                        JavaResourceRootType.TEST_RESOURCE -> DetachedSourceRootKind.TEST_RESOURCE
                        else -> null
                    },
                    provenance = when (val properties = folder.jpsElement.properties) {
                        is JavaSourceRootProperties -> properties.isForGeneratedSources
                        is JavaResourceRootProperties -> properties.isForGeneratedSources
                        else -> null
                    }?.let { generated ->
                        if (generated) {
                            DetachedSourceRootProvenance.GENERATED
                        } else {
                            DetachedSourceRootProvenance.AUTHORED
                        }
                    },
                )
            }
        }
        return Refinement.Refined(roots)
    }

    /**
     * Proof transition: `ModuleRootManager -> Refinement<List<DetachedClasspathBoundary>,
     * DetachedModelCaptureFailure>`. Establishes an explicitly bounded primitive classpath-URL
     * list without retaining order entries or virtual files. IDEA 262 exposes a stoppable
     * order-entry processor but exposes each entry's roots as one array; this consumes only those
     * per-entry arrays and stops both loops immediately upon observing the 513th root.
     * [DetachedModelCaptureFailure] is the closed expected failure. Raw order entries and virtual
     * files may be extracted only inside this live adapter.
     */
    private fun observeClasspath(
        rootManager: ModuleRootManager,
    ): Refinement<List<DetachedClasspathBoundary>, DetachedModelCaptureFailure> {
        val entries = ArrayList<DetachedClasspathBoundary>(
            DetachedModelLimits.MAX_CLASSPATH_ENTRIES_PER_MODULE,
        )
        var traversal = ClasspathTraversal.OPEN
        rootManager.orderEntries().forEach(
            Processor { orderEntry ->
                ProgressManager.checkCanceled()
                for (root in orderEntry.getFiles(OrderRootType.CLASSES)) {
                    ProgressManager.checkCanceled()
                    if (entries.size == DetachedModelLimits.MAX_CLASSPATH_ENTRIES_PER_MODULE) {
                        traversal = ClasspathTraversal.LIMIT_EXCEEDED
                        break
                    }
                    entries += DetachedClasspathBoundary(root.url)
                }
                traversal == ClasspathTraversal.OPEN
            },
        )
        return when (traversal) {
            ClasspathTraversal.OPEN -> Refinement.Refined(entries)
            ClasspathTraversal.LIMIT_EXCEEDED -> Refinement.Rejected(
                DetachedModelCaptureFailure.TOO_MANY_CLASSPATH_ENTRIES,
            )
        }
    }

    private fun rejected(failure: DetachedModelCaptureFailure): DetachedModelObservation =
        DetachedModelObservation.Rejected(failure)

    private enum class ClasspathTraversal {
        OPEN,
        LIMIT_EXCEEDED,
    }
}
