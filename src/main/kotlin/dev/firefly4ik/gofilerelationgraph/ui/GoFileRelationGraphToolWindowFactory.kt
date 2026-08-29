package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GoFileRelationGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GraphTabsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Go File Relation Graph"

        fun tabsPanel(toolWindow: ToolWindow): GraphTabsPanel? = toolWindow.contentManager.contents
            .firstNotNullOfOrNull { content -> content.component as? GraphTabsPanel }
    }
}
