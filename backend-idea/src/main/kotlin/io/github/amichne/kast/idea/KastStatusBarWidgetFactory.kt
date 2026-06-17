package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

internal class KastStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = KAST_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "Kast"

    override fun isAvailable(project: Project): Boolean = !project.isDisposed

    override fun createWidget(project: Project): StatusBarWidget = KastStatusBarWidget(project)

    override fun isEnabledByDefault(): Boolean = true
}

private class KastStatusBarWidget(
    private val kastProject: Project,
) : EditorBasedWidget(kastProject) {
    private val diagnostics = KastDiagnosticsService.getInstance(kastProject)

    override fun ID(): String = KAST_STATUS_WIDGET_ID

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        diagnostics.addListener(this) {
            statusBar.updateWidget(ID())
        }
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = object : StatusBarWidget.TextPresentation {
        override fun getText(): String = diagnostics.snapshot().statusText()

        override fun getTooltipText(): String = diagnostics.snapshot().tooltipText()

        override fun getAlignment(): Float = 0.5f

        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
            ToolWindowManager.getInstance(kastProject)
                .getToolWindow(KAST_TOOL_WINDOW_ID)
                ?.show()
        }
    }
}

private fun KastDiagnosticsSnapshot.statusText(): String {
    val active = activeRequests.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
    return "Kast: ${visibleStatus().displayName}$active"
}

private fun KastDiagnosticsSnapshot.tooltipText(): String = buildString {
    append("Kast: ").append(message)
    backendName?.let { append("\nBackend: ").append(it) }
    backendVersion?.let { append(" ").append(it) }
    transport?.let { append("\nTransport: ").append(it) }
    append("\nIndex: ").append(indexSummary.displayText())
    append("\nRequests: ").append(activeRequests).append(" active, ")
        .append(completedRequests).append(" completed, ")
        .append(failedRequests).append(" failed")
}

private fun KastDiagnosticsSnapshot.visibleStatus(): KastBackendUiState =
    if (
        backendState == KastBackendUiState.READY &&
        indexSummary.state in setOf(KastIndexState.WAITING_FOR_IDE, KastIndexState.HYDRATING, KastIndexState.INDEXING)
    ) {
        KastBackendUiState.INDEXING
    } else {
        backendState
    }
