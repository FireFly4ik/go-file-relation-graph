package dev.firefly4ik.gofilerelationgraph.ui

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.components.JBPanel
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import dev.firefly4ik.gofilerelationgraph.analysis.FileTitleDisambiguator
import java.awt.BorderLayout
import java.awt.Dimension

class GraphTabsPanel(
    private val project: Project,
) : JBPanel<GraphTabsPanel>(BorderLayout()), Disposable {
    private val tabs = JBTabsFactory.createEditorTabs(project, this).apply {
        presentation
            .setSingleRow(true)
            .setSupportsCompression(false)
            .setTabDraggingEnabled(false)
            .setTabLabelActionsAutoHide(true)
    }
    private val parentTabs = mutableListOf<ParentTab>()

    init {
        minimumSize = Dimension(0, 0)
        val openFilesPanel = GoFileRelationGraphPanel(project)
        Disposer.register(this, openFilesPanel)
        tabs.addTab(
            TabInfo(openFilesPanel)
                .setText("Opened Files")
                .setTooltipText("Opened Files"),
        )
        add(tabs.component, BorderLayout.CENTER)
    }

    fun addParentGraph(
        anchor: SmartPsiElementPointer<GoFunctionOrMethodDeclaration>,
        symbolName: String,
        filePath: String,
    ) {
        val panel = ParentGraphPanel(project, anchor)
        Disposer.register(this, panel)
        lateinit var tabInfo: TabInfo
        val closeAction = object : AnAction("Close", "Close this parent graph", AllIcons.Actions.Close) {
            override fun actionPerformed(event: AnActionEvent) {
                tabs.removeTab(tabInfo)
                parentTabs.removeAll { parentTab -> parentTab.info === tabInfo }
                Disposer.dispose(panel)
                updateParentTitles()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        tabInfo = TabInfo(panel)
            .setText("Parents: $symbolName")
            .setTooltipText("Parents: $symbolName")
            .setTabLabelActions(DefaultActionGroup(closeAction), TAB_ACTION_PLACE)
            .setActionsContextComponent(panel)
        parentTabs += ParentTab(tabInfo, symbolName, filePath)
        tabs.addTab(tabInfo)
        updateParentTitles()
        tabs.select(tabInfo, true)
        panel.load()
    }

    private fun updateParentTitles() {
        for ((symbolName, entries) in parentTabs.groupBy(ParentTab::symbolName)) {
            val distinctPaths = entries.map(ParentTab::filePath).distinct()
            val fileTitles = if (distinctPaths.size > 1) {
                FileTitleDisambiguator.disambiguate(distinctPaths, project.basePath)
            } else {
                emptyMap()
            }
            for (entry in entries) {
                val title = if (fileTitles.isEmpty()) {
                    "Parents: $symbolName"
                } else {
                    "Parents: ${fileTitles.getValue(entry.filePath)} · $symbolName"
                }
                entry.info.setText(title).setTooltipText(title)
            }
        }
    }

    override fun dispose() = Unit

    private data class ParentTab(
        val info: TabInfo,
        val symbolName: String,
        val filePath: String,
    )

    companion object {
        private const val TAB_ACTION_PLACE = "GoFileRelationGraph.ParentTab"
    }
}
