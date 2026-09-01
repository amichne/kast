package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import java.util.concurrent.CancellationException

/** Hosted readiness proof layered over the shared admitted-root physical refresh authority. */
internal fun hostedIndexRefresh(
    project: Project,
    physical: WorkspaceIndexRefreshOperations,
): WorkspaceIndexRefreshOperations = WorkspaceIndexRefreshOperations { workspace ->
    when (val refreshed = physical.refresh(workspace)) {
        is WorkspaceIndexRefresh.Rejected -> refreshed
        WorkspaceIndexRefresh.Refreshed -> try {
            if (project.isDisposed) {
                WorkspaceIndexRefresh.Rejected(WorkspaceIndexRefreshFailure.INDEXING_FAILED)
            } else {
                val dumbService = DumbService.getInstance(project)
                dumbService.waitForSmartMode()
                if (!project.isDisposed && !dumbService.isDumb) {
                    WorkspaceIndexRefresh.Refreshed
                } else {
                    WorkspaceIndexRefresh.Rejected(WorkspaceIndexRefreshFailure.INDEXING_FAILED)
                }
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            WorkspaceIndexRefresh.Rejected(WorkspaceIndexRefreshFailure.INDEXING_FAILED)
        }
    }
}
