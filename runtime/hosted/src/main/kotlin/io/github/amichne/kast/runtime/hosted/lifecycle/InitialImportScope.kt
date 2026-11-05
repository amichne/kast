package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.externalSystem.autoimport.ExternalSystemAutoImportAwareListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-scoped import-aware operation: postpone automatic reload while Kast owns initial import. The selected SDK
 * supplies this internal API; target compilation and live lifecycle checks qualify its use. No IDE setting or registry
 * flag is changed. Existing imported-project state is restored on every exit.
 */
internal class InitialImportScope(private val project: Project) : AutoCloseable {
    private val prior = project.getUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT)
    private val finished = AtomicBoolean(false)

    init {
        ExternalSystemProjectTracker.getInstance(project)
        project.putUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT, true)
        project.messageBus.syncPublisher(ExternalSystemAutoImportAwareListener.TOPIC).autoImportAwareOperationStarted()
    }

    override fun close() {
        if (!finished.compareAndSet(false, true) || project.isDisposed) return
        project.putUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT, prior)
        project.messageBus
            .syncPublisher(ExternalSystemAutoImportAwareListener.TOPIC)
            .autoImportAwareOperationCompleted()
    }
}
