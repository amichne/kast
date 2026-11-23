package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ImportedModelRestore {
    RESTORED,
    DISPOSED,
    DEADLINE_EXCEEDED,
}

/** IntelliJ loads the stored Gradle model after project open; an earlier read observes no imported model. */
internal suspend fun awaitImportedModelRestore(
    project: Project,
    operation: NativeWorkspacePreparation,
): ImportedModelRestore =
    awaitImportedModelRestore(operation, project::isDisposed) { restored ->
        ExternalProjectsManager.getInstance(project).runWhenInitializedInBackground(restored)
    }

internal suspend fun awaitImportedModelRestore(
    operation: NativeWorkspacePreparation,
    isDisposed: () -> Boolean,
    whenRestored: (() -> Unit) -> Unit,
): ImportedModelRestore {
    val restored = AtomicBoolean(false)
    whenRestored { restored.set(true) }
    val settled = awaitLifecycleCondition(operation.deadline, operation.nanoTime) { isDisposed() || restored.get() }
    return when {
        isDisposed() -> ImportedModelRestore.DISPOSED
        settled -> ImportedModelRestore.RESTORED
        else -> ImportedModelRestore.DEADLINE_EXCEEDED
    }
}
