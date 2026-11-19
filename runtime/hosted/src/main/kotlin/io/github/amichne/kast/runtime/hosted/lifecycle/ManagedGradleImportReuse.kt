package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.autoimport.AutoImportProjectTracker
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.calculateCrc
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.runtime.hosted.isWithin
import io.github.amichne.kast.runtime.hosted.workspace.IntellijWorkspaceRefreshPort
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class ManagedImportIdentity(val root: String, val timestamp: Long, val environment: String)

private const val LAST_MANAGED_IMPORT = "kast.managed.gradle.import.v1"

private fun managedImportIdentity(project: Project, root: CanonicalWorkspaceRoot): ManagedImportIdentity? {
    val host = ManagedHeadlessHost.current() ?: return null
    val model =
        ProjectDataManager.getInstance()
            .getExternalProjectData(project, IntellijWorkspaceRefreshPort.GRADLE, root.value) ?: return null
    val stamp = model.lastSuccessfulImportTimestamp
    if (stamp <= 0 || stamp != model.lastImportTimestamp || model.externalProjectStructure == null) return null
    val environment = host.environment.gradleImportEnvironmentIdentity ?: GradleImportEnvironment.Empty.identity.value
    return ManagedImportIdentity(root.value, stamp, environment)
}

internal fun recordManagedGradleImport(project: Project, root: CanonicalWorkspaceRoot) {
    val identity = managedImportIdentity(project, root) ?: return
    PropertiesComponent.getInstance(project).setValue(LAST_MANAGED_IMPORT, Json.encodeToString(identity))
}

internal enum class ManagedImportReuse {
    CURRENT,
    NO_IMPORTED_MODEL,
    IMPORT_IDENTITY_CHANGED,
    TRACKER_UNAVAILABLE,
    INPUTS_UNAVAILABLE,
    DIRTY,
    INPUTS_CHANGED,
}

/** Compare the native import's saved checksums with the current Gradle settings after the VFS refresh. */
internal suspend fun managedGradleImportReuse(project: Project, root: CanonicalWorkspaceRoot): ManagedImportReuse {
    val identity = managedImportIdentity(project, root) ?: return ManagedImportReuse.NO_IMPORTED_MODEL
    if (PropertiesComponent.getInstance(project).getValue(LAST_MANAGED_IMPORT) != Json.encodeToString(identity))
        return ManagedImportReuse.IMPORT_IDENTITY_CHANGED
    val gradle = IntellijWorkspaceRefreshPort.GRADLE
    val tracker =
        ExternalSystemProjectTracker.getInstance(project) as? AutoImportProjectTracker
            ?: return ManagedImportReuse.TRACKER_UNAVAILABLE
    val saved = tracker.state.projectData[gradle.id]?.get(root.value) ?: return ManagedImportReuse.TRACKER_UNAVAILABLE
    val settings = saved.settingsTracker ?: return ManagedImportReuse.INPUTS_UNAVAILABLE
    if (saved.isDirty || settings.isDirty) return ManagedImportReuse.DIRTY
    if (settings.settingsFiles.isEmpty()) return ManagedImportReuse.INPUTS_UNAVAILABLE
    return compareGradleSettings(project, root, settings.settingsFiles)
}

private suspend fun compareGradleSettings(
    project: Project,
    root: CanonicalWorkspaceRoot,
    savedChecksums: Map<String, Long>,
): ManagedImportReuse {
    val gradle = IntellijWorkspaceRefreshPort.GRADLE
    val awareness =
        ExternalSystemApiUtil.getManager(gradle) as? ExternalSystemAutoImportAware
            ?: return ManagedImportReuse.INPUTS_UNAVAILABLE
    val files = readAction { awareness.getAffectedExternalProjectFiles(root.value, project) }
    val local = LocalFileSystem.getInstance()
    return readAction {
        // The completed lifecycle refresh covers model inputs inside this workspace.
        val current = files.mapNotNull { input ->
            if (java.nio.file.Files.notExists(input.toPath())) return@mapNotNull null
            if (!input.path.isWithin(root)) return@readAction ManagedImportReuse.INPUTS_UNAVAILABLE
            local.findFileByIoFile(input) ?: return@readAction ManagedImportReuse.INPUTS_UNAVAILABLE
        }
        val checksums = current.associate { it.path to it.calculateCrc(project, gradle) }.filterValues { it != 0L }
        if (savedChecksums == checksums) ManagedImportReuse.CURRENT else ManagedImportReuse.INPUTS_CHANGED
    }
}
