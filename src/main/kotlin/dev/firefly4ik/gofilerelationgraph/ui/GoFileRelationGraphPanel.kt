package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.firefly4ik.gofilerelationgraph.analysis.GraphDataController
import dev.firefly4ik.gofilerelationgraph.navigation.GraphNavigator
import java.awt.BorderLayout

class GoFileRelationGraphPanel(
    project: Project,
) : JBPanel<GoFileRelationGraphPanel>(BorderLayout()), Disposable {
    private val canvas = GraphCanvas(GraphNavigator(project))
    private val controller = GraphDataController(project, canvas::setSnapshot, canvas::setActiveFiles)

    init {
        Disposer.register(this, controller)
        border = JBUI.Borders.empty()
        add(createToolbar(), BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
    }

    private fun createToolbar(): javax.swing.JComponent {
        val actions = DefaultActionGroup()
        actions.add(object : AnAction("Refresh", "Rebuild graph from open Go files", AllIcons.Actions.Refresh) {
            override fun actionPerformed(event: AnActionEvent) {
                controller.refreshNow()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        actions.add(object : ToggleAction("Show Labels", "Show function and method names on relations", AllIcons.Nodes.Tag) {
            override fun isSelected(event: AnActionEvent) = canvas.showLabels

            override fun setSelected(event: AnActionEvent, state: Boolean) {
                canvas.showLabels = state
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        actions.add(Separator.getInstance())
        actions.add(object : AnAction("Fit Graph", "Fit and center all files", AllIcons.General.FitContent) {
            override fun actionPerformed(event: AnActionEvent) {
                canvas.fitGraph()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        actions.add(object : AnAction("Rebuild Layout", "Discard manual positions and rebuild layout", AllIcons.Actions.Restart) {
            override fun actionPerformed(event: AnActionEvent) {
                canvas.resetLayout()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        actions.add(Separator.getInstance())

        val viewOptions = DefaultActionGroup("View Options", true).apply {
            add(object : ToggleAction("Auto Refresh") {
                override fun isSelected(event: AnActionEvent) = controller.autoRefresh

                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    controller.autoRefresh = state
                    if (state) controller.requestRefresh(force = true)
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : ToggleAction("Include Test Files") {
                override fun isSelected(event: AnActionEvent) = controller.includeTests

                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    controller.includeTests = state
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : ToggleAction("Show Files Without Relations") {
                override fun isSelected(event: AnActionEvent) = controller.showUnconnected

                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    controller.showUnconnected = state
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        actions.add(object : ComboBoxAction() {
            init {
                templatePresentation.text = ""
                templatePresentation.description = "View Options"
                templatePresentation.icon = AllIcons.Actions.Show
                templatePresentation.disabledIcon = AllIcons.Actions.Show
            }

            override fun createPopupActionGroup(
                button: javax.swing.JComponent,
                dataContext: com.intellij.openapi.actionSystem.DataContext,
            ) = viewOptions

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })

        return ActionManager.getInstance()
            .createActionToolbar("GoFileRelationGraph.Toolbar", actions, true)
            .apply { targetComponent = canvas }
            .component
    }

    override fun dispose() = Unit

}
