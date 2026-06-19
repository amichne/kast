@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.amichne.kast.indexstore.api.index.SourceIndexSnapshot
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

internal class KastToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val activityPanel = KastActivityPanel(project)
        val recentFilesPanel = KastRecentFilesPanel(project)

        contentManager.addContent(
            contentManager.factory.createContent(activityPanel, "Activity", false).apply {
                setPreferredFocusableComponent(activityPanel)
                setDisposer(activityPanel)
            },
        )
        contentManager.addContent(
            contentManager.factory.createContent(recentFilesPanel, "Recent files", false).apply {
                setPreferredFocusableComponent(recentFilesPanel)
                setDisposer(recentFilesPanel)
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

private class KastRecentFilesPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val diagnostics = KastDiagnosticsService.getInstance(project)
    private val recentFilesModel = KastRecentFilesIndexTableModel()
    private val recentFilesStatus = JBLabel()

    init {
        setContent(buildContent())
        diagnostics.addListener(this) { snapshot -> render(snapshot) }
    }

    override fun dispose() = Unit

    private fun buildContent(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0))
        recentFilesStatus.foreground = JBColor.GRAY
        toolbar.add(JButton("Refresh").apply {
            addActionListener { refreshRecentFilesPreview(diagnostics.snapshot()) }
        })
        toolbar.add(JBPanel<JBPanel<*>>().apply {
            border = JBUI.Borders.emptyLeft(8)
            add(recentFilesStatus)
        })
        panel.add(toolbar, BorderLayout.NORTH)

        val table = JBTable(recentFilesModel)
        table.emptyText.text = "No recent files recorded by IDEA"
        table.setShowColumns(true)
        table.setStriped(true)
        table.autoCreateRowSorter = true
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun render(snapshot: KastDiagnosticsSnapshot) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { render(snapshot) }
            return
        }

        refreshRecentFilesPreview(snapshot)
    }

    private fun refreshRecentFilesPreview(snapshot: KastDiagnosticsSnapshot) {
        val workspaceRoot = snapshot.workspaceRoot?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        if (workspaceRoot == null) {
            recentFilesModel.setRows(emptyList())
            recentFilesStatus.text = "No workspace"
            return
        }
        val recentFiles = recentFiles()
        val sourceIndexSnapshot = loadSourceIndexSnapshot(workspaceRoot)
        val recentFilePaths = recentFiles.mapNotNull { file ->
            runCatching { Path.of(file.path) }.getOrNull()
        }
        recentFilesModel.setRows(recentFileIndexRows(recentFilePaths, workspaceRoot, sourceIndexSnapshot))
        recentFilesStatus.text = when {
            recentFiles.isEmpty() -> "No recent files"
            sourceIndexSnapshot == null -> "source-index.db unavailable"
            else -> "Showing ${recentFiles.size.coerceAtMost(RECENT_FILE_LIMIT)} recent files as indexed"
        }
    }

    private fun recentFiles(): List<VirtualFile> {
        val history = EditorHistoryManager.getInstance(project).fileList
        val openFiles = FileEditorManager.getInstance(project).openFiles.toList()
        return (history + openFiles)
            .asSequence()
            .filterNot(VirtualFile::isDirectory)
            .distinctBy(VirtualFile::getPath)
            .take(RECENT_FILE_LIMIT)
            .toList()
    }

    private fun loadSourceIndexSnapshot(workspaceRoot: Path): SourceIndexSnapshot? =
        runCatching {
            SqliteSourceIndexStore(workspaceRoot).use { store ->
                if (store.dbExists()) store.loadSourceIndexSnapshot() else null
            }
        }.getOrNull()

    companion object {
        private const val RECENT_FILE_LIMIT = 20
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

private class KastRecentFilesIndexTableModel : AbstractTableModel() {
    private var rows: List<KastRecentFileIndexRow> = emptyList()

    fun setRows(nextRows: List<KastRecentFileIndexRow>) {
        rows = nextRows
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = Column.entries.size

    override fun getColumnName(column: Int): String = Column.entries[column].title

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (Column.entries[columnIndex]) {
            Column.FILE -> row.displayPath
            Column.INDEX -> row.state.displayName
            Column.MODULE -> row.moduleName.orEmpty()
            Column.PACKAGE -> row.packageName.orEmpty()
            Column.IDENTIFIERS -> row.identifierCount?.toString().orEmpty()
            Column.IMPORTS -> row.importSummary()
        }
    }

    private fun KastRecentFileIndexRow.importSummary(): String {
        val imports = importCount ?: return ""
        val wildcardImports = wildcardImportCount ?: 0
        return if (wildcardImports == 0) imports.toString() else "$imports + $wildcardImports wildcard"
    }

    private enum class Column(val title: String) {
        FILE("File"),
        INDEX("Index"),
        MODULE("Module"),
        PACKAGE("Package"),
        IDENTIFIERS("Identifiers"),
        IMPORTS("Imports"),
    }
}
