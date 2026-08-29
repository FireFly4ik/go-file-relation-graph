package dev.firefly4ik.gofilerelationgraph.actions

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import dev.firefly4ik.gofilerelationgraph.analysis.ParentGraphAnalyzer
import dev.firefly4ik.gofilerelationgraph.ui.GoFileRelationGraphToolWindowFactory
import dev.firefly4ik.gofilerelationgraph.ui.ParentGraphPanel

class BuildParentGraphAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) as? GoFile
        val anchor = psiFile?.let { findAnchor(it, editor.caretModel.offset) }
        if (anchor == null) {
            notify(project, "Cannot build parent graph", "Place the caret inside a named Go function or method.")
            return
        }
        if (psiFile.isGenerated) {
            notify(project, "Cannot build parent graph", "Generated Go files are excluded from parent graphs.")
            return
        }
        if (ParentGraphAnalyzer(project).findGoModuleRoot(psiFile.virtualFile) == null) {
            notify(project, "Cannot build parent graph", "No go.mod was found for the current file.")
            return
        }

        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchor)
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(GoFileRelationGraphToolWindowFactory.TOOL_WINDOW_ID)
            ?: return
        toolWindow.activate({
            val symbolName = displayName(anchor)
            GoFileRelationGraphToolWindowFactory.tabsPanel(toolWindow)?.addParentGraph(
                anchor = pointer,
                symbolName = symbolName,
                filePath = anchor.containingFile.virtualFile.path,
            )
        }, true)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            event.getData(CommonDataKeys.PSI_FILE) is GoFile
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    internal fun findAnchor(file: GoFile, caretOffset: Int): GoFunctionOrMethodDeclaration? {
        if (file.textLength == 0) return null
        val offset = caretOffset.coerceIn(0, file.textLength - 1)
        val element = file.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, GoFunctionOrMethodDeclaration::class.java, false)
    }

    private fun displayName(anchor: GoFunctionOrMethodDeclaration): String {
        if (anchor is GoMethodDeclaration) {
            val receiver = anchor.receiverType?.presentationText?.takeIf(String::isNotBlank)
            if (receiver != null) return "($receiver).${anchor.name}"
        }
        val packageName = anchor.containingFile.packageName
        return listOfNotNull(packageName?.takeIf(String::isNotBlank), anchor.name).joinToString(".")
    }

    private fun notify(project: com.intellij.openapi.project.Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ParentGraphPanel.NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
