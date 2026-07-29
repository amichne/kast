@file:Suppress("UnstableApiUsage")

package io.github.amichne.kast.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsService
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsSnapshot
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

internal class KastExplorerPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val diagnostics = KastDiagnosticsService.getInstance(project)
    private val service = KastPluginService.getInstance(project)
    private val model = KastExplorerModel()
    private val searchField = SearchTextField(false)
    private val searchButton = JButton("Search")
    private val currentSymbolButton = JButton("Current Symbol")
    private val resultModel = DefaultListModel<KastExplorerSearchItem>()
    private val results = JBList(resultModel)
    private val relationRoot = DefaultMutableTreeNode("Neighborhood")
    private val relationModel = DefaultTreeModel(relationRoot)
    private val relations = Tree(relationModel)
    private val compilerValue = JBLabel()
    private val indexValue = JBLabel()
    private val graphValue = JBLabel("Loading…")
    private val workspaceValue = JBLabel()
    private val detailTitle = JBLabel("Explore the workspace")
    private val detailMeta = JBLabel("Live K2 / PSI / Analysis API  →  SQLite FTS  →  references  →  semantic graph")
    private val detailHint = JBLabel("Search indexed Kotlin symbols or inspect the declaration under the caret.")
    private val openSourceButton = JButton("Open Source")
    private var requestSequence = 0L
    private var preferredFqName: String? = null
    private var disposed = false

    init {
        setContent(buildContent())
        configureInteractions()
        diagnostics.addListener(this, ::renderDiagnostics)
        request(KastExplorerRequest.Overview)
    }

    override fun dispose() {
        disposed = true
    }

    private fun buildContent(): JComponent {
        val content = JBPanel<JBPanel<*>>(BorderLayout())
        content.border = JBUI.Borders.empty(10)
        content.add(buildHeader(), BorderLayout.NORTH)

        val splitter = OnePixelSplitter(false, 0.42f)
        splitter.firstComponent = buildResults()
        splitter.secondComponent = buildDetail()
        content.add(splitter, BorderLayout.CENTER)
        return content
    }

    private fun buildHeader(): JComponent {
        val header = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        val heading = JBLabel("<html><b>Kast Atlas</b> &nbsp; Explore compiler and persisted workspace evidence</html>")
        heading.border = JBUI.Borders.emptyBottom(2)
        header.add(heading, BorderLayout.NORTH)

        val body = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        body.add(buildSearchRow(), BorderLayout.NORTH)
        body.add(buildEvidenceRibbon(), BorderLayout.CENTER)
        header.add(body, BorderLayout.CENTER)
        header.border = JBUI.Borders.emptyBottom(10)
        return header
    }

    private fun buildSearchRow(): JComponent {
        searchField.textEditor.emptyText.text = "Search indexed Kotlin symbols…"
        searchField.accessibleContext.accessibleName = "Kast symbol search"
        searchButton.toolTipText = "Search the persistent FTS declaration index"
        currentSymbolButton.toolTipText = "Use the Kotlin declaration under the editor caret"
        val actions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actions.add(currentSymbolButton)
        actions.add(searchButton)
        return JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
    }

    private fun buildEvidenceRibbon(): JComponent =
        JBPanel<JBPanel<*>>(GridLayout(1, 4, 8, 0)).apply {
            add(metric("LIVE MODEL", compilerValue))
            add(metric("PERSISTENT INDEX", indexValue))
            add(metric("SEMANTIC GRAPH", graphValue))
            add(metric("WORKSPACE", workspaceValue))
        }

    private fun metric(
        title: String,
        value: JBLabel,
    ): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
            JBUI.Borders.empty(6, 8),
        )
        add(JBLabel(title), BorderLayout.NORTH)
        value.border = JBUI.Borders.emptyTop(3)
        add(value, BorderLayout.CENTER)
    }

    private fun buildResults(): JComponent {
        results.selectionMode = ListSelectionModel.SINGLE_SELECTION
        results.emptyText.text = "Search to discover indexed declarations"
        results.cellRenderer = object : ColoredListCellRenderer<KastExplorerSearchItem>() {
            override fun customizeCellRenderer(
                list: JList<out KastExplorerSearchItem>,
                value: KastExplorerSearchItem?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                value ?: return
                append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${value.declaration.kind.name.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  —  ${value.ownerName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                value.declaration.modulePath?.let { module ->
                    append("  $module", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        return titledPanel("Symbols", JBScrollPane(results))
    }

    private fun buildDetail(): JComponent {
        relations.isRootVisible = false
        relations.showsRootHandles = true
        relations.emptyText.text = "Select a symbol to inspect its neighborhood"
        relations.cellRenderer = RelationRenderer()

        val summary = JBPanel<JBPanel<*>>(BorderLayout(0, 4))
        summary.border = JBUI.Borders.empty(2, 4, 8, 4)
        summary.add(detailTitle, BorderLayout.NORTH)
        summary.add(detailMeta, BorderLayout.CENTER)
        summary.add(detailHint, BorderLayout.SOUTH)

        openSourceButton.isEnabled = false
        val footer = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 4))
        footer.add(openSourceButton)

        return titledPanel(
            "Neighborhood",
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(summary, BorderLayout.NORTH)
                add(JBScrollPane(relations), BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
            },
        )
    }

    private fun titledPanel(
        title: String,
        body: JComponent,
    ): JComponent = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
        border = JBUI.Borders.empty(0, 4)
        add(JBLabel("<html><b>$title</b></html>"), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    private fun configureInteractions() {
        searchField.textEditor.addActionListener { search() }
        searchButton.addActionListener { search() }
        currentSymbolButton.addActionListener { searchCurrentSymbol() }
        openSourceButton.addActionListener { model.inspection?.selected?.navigationTarget?.let(::navigate) }
        results.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                results.selectedValue?.let { selected ->
                    renderSelection(selected)
                    request(KastExplorerRequest.Inspect(selected))
                }
            }
        }
        results.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) results.selectedValue?.navigationTarget?.let(::navigate)
            }
        })
        relations.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                val node = relations.getPathForLocation(event.x, event.y)?.lastPathComponent as? DefaultMutableTreeNode
                (node?.userObject as? KastExplorerRelation)?.navigationTarget?.let(::navigate)
            }
        })
    }

    private fun search() {
        val request = KastExplorerRequest.Search.parse(searchField.text)
        if (request == null) {
            results.emptyText.text = "Enter a symbol name"
            return
        }
        results.emptyText.text = "Searching persistent FTS…"
        searchButton.isEnabled = false
        request(request)
    }

    private fun searchCurrentSymbol() {
        val fqName = ApplicationManager.getApplication().runReadAction<String?> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction null
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
            val element = file.findElementAt(editor.caretModel.offset) ?: return@runReadAction null
            PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)?.fqName?.asString()
        }
        if (fqName == null) {
            detailHint.text = "Place the caret inside a named Kotlin declaration."
            return
        }
        preferredFqName = fqName
        searchField.text = fqName
        search()
    }

    private fun request(request: KastExplorerRequest) {
        val sequence = ++requestSequence
        service.exploreAsync(request) { result ->
            if (disposed || !shouldAcceptExplorerResult(result, sequence, requestSequence)) return@exploreAsync
            model.accept(result)
            render(result)
        }
    }

    private fun render(result: KastExplorerResult) {
        when (result) {
            is KastExplorerResult.Overview -> {
                graphValue.text = "${result.value.graphFileCount.value} files · generation ${result.value.graphGeneration.value}"
            }
            is KastExplorerResult.SearchResults -> renderSearchResults(result.items)
            is KastExplorerResult.Inspection -> renderInspection(result.value)
            is KastExplorerResult.Problem -> {
                searchButton.isEnabled = true
                detailHint.text = result.message.value
            }
        }
    }

    private fun renderSearchResults(items: List<KastExplorerSearchItem>) {
        searchButton.isEnabled = true
        resultModel.clear()
        items.forEach(resultModel::addElement)
        results.emptyText.text = "No indexed declarations match"
        val preferred = preferredFqName
        preferredFqName = null
        val selectedIndex = items.indexOfFirst { item -> item.declaration.fqName == preferred }.takeIf { it >= 0 } ?: 0
        if (items.isNotEmpty()) results.selectedIndex = selectedIndex
    }

    private fun renderSelection(selected: KastExplorerSearchItem) {
        detailTitle.text = selected.declaration.fqName
        detailMeta.text = listOfNotNull(
            selected.declaration.kind.name.lowercase(),
            selected.declaration.visibility.name.lowercase(),
            selected.declaration.modulePath,
            selected.declaration.sourceSet,
        ).joinToString("  ·  ")
        detailHint.text = "Loading indexed and semantic relationships…"
        openSourceButton.isEnabled = selected.navigationTarget != null
    }

    private fun renderInspection(inspection: KastExplorerInspection) {
        relationRoot.removeAllChildren()
        inspection.sections.forEach { section ->
            val group = DefaultMutableTreeNode("${section.layer.title} (${section.relations.size})")
            section.relations.forEach { relation -> group.add(DefaultMutableTreeNode(relation, false)) }
            relationRoot.add(group)
        }
        relationModel.reload()
        repeat(relations.rowCount) { row -> relations.expandRow(row) }
        detailHint.text = if (inspection.relations.isEmpty()) {
            "No persisted neighborhood is available for this symbol."
        } else {
            "Double-click a relationship to open its source."
        }
    }

    private fun renderDiagnostics(snapshot: KastDiagnosticsSnapshot) {
        compilerValue.text = "${snapshot.backendState.displayName} · K2 / PSI / AA"
        indexValue.text = snapshot.indexSummary.displayText()
        workspaceValue.text = snapshot.workspaceRoot?.let { Path.of(it).fileName.toString() } ?: "No workspace"
    }

    private fun navigate(target: KastSourceTarget) {
        val file = LocalFileSystem.getInstance().findFileByNioFile(target.filePath)
        if (file == null) {
            detailHint.text = "Source file is no longer available: ${target.filePath}"
            return
        }
        OpenFileDescriptor(project, file, target.offset).navigate(true)
    }

    private class RelationRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: javax.swing.JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode
            val relation = node?.userObject as? KastExplorerRelation
            if (relation != null) {
                text = buildString {
                    append(relation.title.value)
                    relation.detail?.let { append("  ·  ").append(it.value) }
                }
                toolTipText = relation.navigationTarget?.filePath?.toString()
            }
            return component
        }
    }
}
