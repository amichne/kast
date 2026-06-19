@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

internal class KastToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val activityPanel = KastActivityPanel(project)

        contentManager.addContent(
            contentManager.factory.createContent(activityPanel, "Activity", false).apply {
                setPreferredFocusableComponent(activityPanel)
                setDisposer(activityPanel)
            },
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = !project.isDisposed
}

private class KastActivityPanel(
    project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val diagnostics = KastDiagnosticsService.getInstance(project)
    private val stateValue = JBLabel()
    private val backendValue = JBLabel()
    private val indexValue = JBLabel()
    private val requestsValue = JBLabel()
    private val capabilitiesValue = JBLabel()
    private val workspaceValue = JBLabel()
    private val activityModel = KastActivityTableModel()

    init {
        setContent(buildContent())
        diagnostics.addListener(this) { snapshot -> render(snapshot) }
    }

    override fun dispose() = Unit

    private fun buildContent(): JComponent {
        val content = JBPanel<JBPanel<*>>(BorderLayout())
        content.border = JBUI.Borders.empty(8)
        content.add(buildSummaryPanel(), BorderLayout.NORTH)

        val table = JBTable(activityModel)
        table.emptyText.text = "No Kast activity recorded yet"
        table.setShowColumns(true)
        table.setStriped(true)
        table.autoCreateRowSorter = true
        content.add(JBScrollPane(table), BorderLayout.CENTER)
        return content
    }

    private fun buildSummaryPanel(): JComponent {
        val summary = JBPanel<JBPanel<*>>(GridLayout(2, 3, 8, 6))
        summary.border = JBUI.Borders.emptyBottom(8)
        summary.add(metric("State", stateValue))
        summary.add(metric("Backend", backendValue))
        summary.add(metric("Index", indexValue))
        summary.add(metric("Requests", requestsValue))
        summary.add(metric("Capabilities", capabilitiesValue))
        summary.add(metric("Workspace", workspaceValue))
        return summary
    }

    private fun metric(label: String, value: JBLabel): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        val title = JBLabel(label)
        title.border = JBUI.Borders.emptyBottom(2)
        value.setCopyable(true)
        panel.add(title, BorderLayout.NORTH)
        panel.add(value, BorderLayout.CENTER)
        return panel
    }

    private fun render(snapshot: KastDiagnosticsSnapshot) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { render(snapshot) }
            return
        }

        stateValue.text = snapshot.backendState.displayName
        backendValue.text = listOfNotNull(snapshot.backendName, snapshot.backendVersion).joinToString(" ")
            .ifBlank { "Unknown" }
        indexValue.text = snapshot.indexSummary.displayText()
        requestsValue.text = "${snapshot.activeRequests} active, ${snapshot.completedRequests} ok, ${snapshot.failedRequests} failed"
        capabilitiesValue.text = snapshot.capabilities?.let { capabilities ->
            "${capabilities.readCapabilities.size} read, ${capabilities.mutationCapabilities.size} mutation"
        } ?: "Unknown"
        workspaceValue.text = snapshot.workspaceRoot?.substringAfterLast('/') ?: "No workspace"
        activityModel.setEvents(snapshot.recentEvents)
    }
}

private class KastActivityTableModel : AbstractTableModel() {
    private var events: List<KastActivityEvent> = emptyList()

    fun setEvents(nextEvents: List<KastActivityEvent>) {
        events = nextEvents
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = events.size

    override fun getColumnCount(): Int = Column.entries.size

    override fun getColumnName(column: Int): String = Column.entries[column].title

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val event = events[rowIndex]
        return when (Column.entries[columnIndex]) {
            Column.TIME -> TIME_FORMATTER.format(event.timestamp)
            Column.SEVERITY -> event.severity.name
            Column.AREA -> event.kind.displayName
            Column.OPERATION -> event.operation?.displayName.orEmpty()
            Column.MESSAGE -> event.detail ?: event.title
            Column.DURATION -> event.durationMillis?.let { "${it}ms" }.orEmpty()
        }
    }

    private enum class Column(val title: String) {
        TIME("Time"),
        SEVERITY("Severity"),
        AREA("Area"),
        OPERATION("Operation"),
        MESSAGE("Message"),
        DURATION("Duration"),
    }

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
