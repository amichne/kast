package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.UnindexedFilesScannerExecutor
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class InstalledIndexingReadiness {
    READY,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
}

internal enum class InstalledModuleAvailability {
    AVAILABLE,
    UNAVAILABLE,
    FAILED,
}

internal enum class InstalledModuleMaterialization {
    AVAILABLE,
    IMPORTED,
    UNAVAILABLE,
    FAILED,
}

internal fun interface InstalledExternalProjectsReader {
    fun read(): Collection<ExternalProjectInfo>
}

internal fun interface InstalledExternalProjectImporter {
    fun import(structure: DataNode<ProjectData>): InstalledExternalProjectImport
}

internal enum class InstalledExternalProjectImport {
    IMPORTED,
    FAILED,
}

/**
 * Proof transition: `Project -> InstalledIndexingReadiness`.
 *
 * [InstalledIndexingReadiness.READY] establishes a continuous smart, scanner-idle interval with at
 * least one live IntelliJ module after Gradle model and SDK writes. Other variants close
 * interruption, timeout, disposal, and platform failure. Live indexing and module state remains
 * inside this bootstrap boundary.
 */
internal fun awaitInstalledIndexingQuiescence(project: Project): InstalledIndexingReadiness {
    val dumbService = DumbService.getInstance(project)
    val scanner = UnindexedFilesScannerExecutor.getInstance(project)
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(READINESS_TIMEOUT_MINUTES)
    var idleSince: Long? = null
    while (true) {
        if (project.isDisposed) return InstalledIndexingReadiness.FAILED
        if (System.nanoTime() >= deadline) return InstalledIndexingReadiness.TIMED_OUT
        try {
            dumbService.waitForSmartMode()
        } catch (_: RuntimeException) {
            return InstalledIndexingReadiness.FAILED
        }
        val modulesReady = try {
            ReadAction.nonBlocking<Boolean> {
                ModuleManager.getInstance(project).modules.any { module -> !module.isDisposed }
            }.executeSynchronously()
        } catch (_: RuntimeException) {
            return InstalledIndexingReadiness.FAILED
        }
        val now = System.nanoTime()
        val idle =
            !dumbService.isDumb &&
                !scanner.isRunning.value &&
                !scanner.hasQueuedTasks &&
                modulesReady
        if (idle) {
            val since = idleSince ?: now.also { idleSince = it }
            if (now - since >= TimeUnit.MILLISECONDS.toNanos(QUIESCENCE_MILLIS)) {
                return InstalledIndexingReadiness.READY
            }
        } else {
            idleSince = null
        }
        try {
            Thread.sleep(POLL_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return InstalledIndexingReadiness.INTERRUPTED
        }
    }
}

/**
 * Proof transition: `InstalledModuleAvailability + Path + InstalledExternalProjectsReader +
 * InstalledExternalProjectImporter -> InstalledModuleMaterialization`.
 *
 * [InstalledModuleMaterialization.AVAILABLE] and [InstalledModuleMaterialization.IMPORTED]
 * establish at least one live module or one imported exact-workspace project structure.
 * [InstalledModuleMaterialization.FAILED] closes module observation failure, external-project data
 * lookup failure, malformed external project paths, and platform import failure. Raw IntelliJ
 * project data and `String -> Path` extraction are permitted only inside this bootstrap boundary.
 */
internal fun materializeImportedModules(
    moduleAvailability: InstalledModuleAvailability,
    workspaceRoot: Path,
    externalProjects: InstalledExternalProjectsReader,
    importer: InstalledExternalProjectImporter,
): InstalledModuleMaterialization {
    return when (moduleAvailability) {
        InstalledModuleAvailability.AVAILABLE -> InstalledModuleMaterialization.AVAILABLE
        InstalledModuleAvailability.FAILED -> InstalledModuleMaterialization.FAILED
        InstalledModuleAvailability.UNAVAILABLE -> {
            val exactStructure = try {
                val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize()
                externalProjects.read()
                    .filter { info ->
                        Path.of(info.externalProjectPath)
                            .toAbsolutePath()
                            .normalize() == normalizedWorkspace
                    }
                    .singleOrNull()
                    ?.externalProjectStructure
            } catch (_: RuntimeException) {
                return InstalledModuleMaterialization.FAILED
            } ?: return InstalledModuleMaterialization.UNAVAILABLE

            try {
                when (importer.import(exactStructure)) {
                    InstalledExternalProjectImport.IMPORTED ->
                        InstalledModuleMaterialization.IMPORTED
                    InstalledExternalProjectImport.FAILED ->
                        InstalledModuleMaterialization.FAILED
                }
            } catch (_: RuntimeException) {
                InstalledModuleMaterialization.FAILED
            }
        }
    }
}

private const val READINESS_TIMEOUT_MINUTES = 5L
private const val QUIESCENCE_MILLIS = 1_500L
private const val POLL_MILLIS = 100L
