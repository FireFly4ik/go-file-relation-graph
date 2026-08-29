package dev.firefly4ik.gofilerelationgraph.ui

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.firefly4ik.gofilerelationgraph.analysis.ParentGraphAnalyzer
import dev.firefly4ik.gofilerelationgraph.analysis.ParentGraphController
import dev.firefly4ik.gofilerelationgraph.analysis.ParentGraphResult
import dev.firefly4ik.gofilerelationgraph.navigation.GraphNavigator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

class ParentGraphPanel(
    private val project: Project,
    anchor: SmartPsiElementPointer<GoFunctionOrMethodDeclaration>,
) : JBPanel<ParentGraphPanel>(BorderLayout()), Disposable {
    private val canvas = GraphCanvas(GraphNavigator(project), chooseNodeNavigationTarget = false)
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this).apply {
        add(canvas, BorderLayout.CENTER)
        setLoadingText("Searching project parents…")
    }
    private val controller = ParentGraphController(
        project = project,
        anchor = anchor,
        onLoading = loadingPanel::startLoading,
        onResult = ::showResult,
    )
    private val maxFilesField = JBTextField(ParentGraphAnalyzer.DEFAULT_MAX_PARENT_FILES.toString(), 5).apply {
        toolTipText = "Maximum number of parent files to include"
        accessibleContext.accessibleName = "Maximum parent files"
        addActionListener { refreshWithLimit() }
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                if (text.trim() != controller.maxParentFiles.toString()) refreshWithLimit()
            }
        })
    }
    private var loaded = false

    init {
        Disposer.register(this, controller)
        border = JBUI.Borders.empty()
        minimumSize = Dimension(0, 0)
        add(createHeader(), BorderLayout.NORTH)
        add(loadingPanel, BorderLayout.CENTER)
    }

    fun load() {
        if (loaded) return
        loaded = true
        controller.refresh()
    }

    private fun refreshWithLimit() {
        val limit = maxFilesField.text.trim().toIntOrNull()
        if (limit == null || limit < 1) {
            maxFilesField.putClientProperty("JComponent.outline", "error")
            maxFilesField.toolTipText = "Enter a positive whole number"
            return
        }
        maxFilesField.putClientProperty("JComponent.outline", null)
        maxFilesField.toolTipText = "Maximum number of parent files to include"
        controller.maxParentFiles = limit
        controller.refresh()
    }

    private fun showResult(result: ParentGraphResult) {
        loadingPanel.stopLoading()
        canvas.setSnapshot(result.snapshot)
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        if (result.snapshot.edges.isEmpty()) {
            notificationGroup.createNotification(
                "No project parents found",
                "The function may only be called by a library, generated code, reflection, or another Go module.",
                NotificationType.INFORMATION,
            ).notify(project)
        }
        if (result.fileLimitReached) {
            notificationGroup.createNotification(
                "Parent graph is limited to ${controller.maxParentFiles} files",
                "Some parent branches are not shown.",
                NotificationType.WARNING,
            ).notify(project)
        }
        if (result.depthLimitReached) {
            notificationGroup.createNotification(
                "Parent graph reached the maximum depth of ${ParentGraphAnalyzer.DEFAULT_MAX_LEVELS} levels",
                "Some parent branches are not shown.",
                NotificationType.WARNING,
            ).notify(project)
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val actions = DefaultActionGroup()
        actions.add(object : AnAction("Refresh Parent Graph", "Rebuild this parent graph snapshot", AllIcons.Actions.Refresh) {
            override fun actionPerformed(event: AnActionEvent) {
                refreshWithLimit()
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
            add(object : ToggleAction("Include Test Files") {
                override fun isSelected(event: AnActionEvent) = controller.includeTests

                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    controller.includeTests = state
                    refreshWithLimit()
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
            .createActionToolbar("GoFileRelationGraph.ParentToolbar", actions, true)
            .apply { targetComponent = canvas }
            .component
    }

    private fun createHeader(): javax.swing.JComponent {
        val limitPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(JBLabel("Max parent files:"))
            add(maxFilesField)
        }
        return JPanel(BorderLayout()).apply {
            add(createToolbar(), BorderLayout.CENTER)
            add(limitPanel, BorderLayout.EAST)
        }
    }

    override fun dispose() = Unit

    companion object {
        const val NOTIFICATION_GROUP = "Go File Relation Graph"
    }
}
