package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory

class GoFileRelationGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoFileRelationGraphPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Open Files", false)
        content.setDisposer(panel)
        content.isCloseable = false
        content.isPinned = true
        content.isPinnable = false
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                ParentGraphTabTitles.update(toolWindow.contentManager, project.basePath)
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) ParentGraphTabTitles.update(toolWindow.contentManager, project.basePath)
                }
            }
        })
    }

    companion object {
        const val TOOL_WINDOW_ID = "Go File Relation Graph"
    }
}
