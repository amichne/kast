package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemAutoImportAwareListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.UnindexedFilesScannerExecutor
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.workspaceModel.ide.JpsProjectLoadedListener
import com.intellij.workspaceModel.ide.impl.WorkspaceModelImpl
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 262 project-scoped import-aware operation: postpone automatic reload while Kast owns initial import. The native 262
 * listener is removed in 263; the plugin's release-line boundary excludes that API change. No IDE setting or registry
 * flag is changed. Existing imported-project state is restored on every exit.
 */
internal class InitialImportScope(
    private val project: Project,
    private val root: CanonicalWorkspaceRoot,
    managedHeadless: Boolean,
) : AutoCloseable {
    private val prior = project.getUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT)
    private val finished = AtomicBoolean(false)

    private val scanHold = if (managedHeadless) modelApplicationScanHold(project) else null

    private val modelEvents = if (managedHeadless) project.messageBus.connect() else null
    private val cachedModelApplied = AtomicBoolean(false)

    init {
        modelEvents?.subscribe(
            JpsProjectLoadedListener.LOADED,
            object : JpsProjectLoadedListener {
                override fun loaded() {
                    cachedModelApplied.set(true)
                }
            },
        )
        if (scanHold != null)
            modelEvents?.subscribe(ProjectDataImportListener.TOPIC, modelApplicationListener(scanHold))

        ExternalSystemProjectTracker.getInstance(project)
        project.putUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT, true)
        project.messageBus.syncPublisher(ExternalSystemAutoImportAwareListener.TOPIC).autoImportAwareOperationStarted()
    }

    fun canImportModel(): Boolean =
        modelEvents == null ||
            cachedModelApplied.get() ||
            (WorkspaceModel.getInstance(project) as? WorkspaceModelImpl)?.loadedFromCache == false

    fun resumeScanning() {
        scanHold?.release()
    }

    private fun modelApplicationListener(hold: ModelApplicationScanHold) =
        object : ProjectDataImportListener {
            override fun onImportStarted(projectPath: String?) {
                if (projectPath == root.value) hold.applicationStarted()
            }

            override fun onImportFailed(projectPath: String?, error: Throwable) {
                if (projectPath == root.value) hold.applicationSettled()
            }

            override fun onFinalTasksFinished(projectPath: String?) {
                if (projectPath == root.value) hold.applicationSettled()
            }
        }

    override fun close() {
        if (!finished.compareAndSet(false, true)) return
        modelEvents?.disconnect()
        if (project.isDisposed) return
        resumeScanning()
        project.putUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT, prior)
        project.messageBus
            .syncPublisher(ExternalSystemAutoImportAwareListener.TOPIC)
            .autoImportAwareOperationCompleted()
    }
}

private fun modelApplicationScanHold(project: Project): ModelApplicationScanHold {
    val executor = UnindexedFilesScannerExecutor.getInstance(project)
    return ModelApplicationScanHold(
        object : ScanQueue {
            override fun suspendQueue() = executor.suspendQueue()

            override fun resumeQueue() {
                if (!project.isDisposed) executor.resumeQueue()
            }
        }
    ) { observation ->
        Logger.getInstance(ModelApplicationScanHold::class.java)
            .info("kast_model_application_scan_hold " + Json.encodeToString(observation))
    }
}
