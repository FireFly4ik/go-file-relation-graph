package dev.firefly4ik.gofilerelationgraph.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.firefly4ik.gofilerelationgraph.model.CallSite
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileNavigationTarget
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class GraphNavigator(
    private val project: Project,
) {
    fun openFile(file: VirtualFile) {
        openInCurrentEditor(file, 0)
    }

    fun openNode(node: FileNode, component: JComponent, point: Point, chooseNavigationTarget: Boolean = true) {
        if (!chooseNavigationTarget) {
            openFile(node.file)
            return
        }
        when (node.navigationTargets.size) {
            0 -> openFile(node.file)
            1 -> navigateTo(node.navigationTargets.single().pointer)
            else -> showNavigationTargets(node.navigationTargets, component, point)
        }
    }

    fun openTarget(callable: CallableRelation) {
        navigateTo(callable.target)
    }

    fun openParentInterface(callable: CallableRelation) {
        navigateTo(callable.parentInterface)
    }

    fun openCallSites(callable: CallableRelation, component: JComponent, point: Point) {
        val validSites = ApplicationManager.getApplication().runReadAction<List<Pair<CallSite, String>>> {
            callable.callSites.mapNotNull { callSite ->
                val element = callSite.pointer.element?.takeIf(PsiElement::isValid) ?: return@mapNotNull null
                val fileName = callSite.fileTitle ?: element.containingFile?.virtualFile?.name ?: return@mapNotNull null
                callSite to fileName
            }
        }
        if (validSites.size == 1) {
            navigateTo(validSites.single().first.pointer)
            return
        }
        if (validSites.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(validSites)
            .setTitle("${callable.label} call sites")
            .setRenderer(ListCellRenderer<Pair<CallSite, String>> { list, value, _, selected, _ ->
                val foreground = if (selected) list.selectionForeground else list.foreground
                JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                    isOpaque = true
                    background = if (selected) list.selectionBackground else list.background
                    border = JBUI.Borders.empty(4, 8)
                    add(SimpleColoredComponent().apply {
                        append(value.first.lineText, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground))
                    }, BorderLayout.CENTER)
                    add(JBLabel("${value.second}:${value.first.lineNumber}").apply {
                        this.foreground = if (selected) list.selectionForeground else SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
                        horizontalAlignment = JBLabel.RIGHT
                    }, BorderLayout.EAST)
                }
            })
            .setItemChosenCallback { navigateTo(it.first.pointer) }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(component, point))
    }

    private fun showNavigationTargets(targets: List<FileNavigationTarget>, component: JComponent, point: Point) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle("Functions in file")
            .setRenderer(ListCellRenderer<FileNavigationTarget> { list, value, _, selected, _ ->
                val foreground = if (selected) list.selectionForeground else list.foreground
                JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                    isOpaque = true
                    background = if (selected) list.selectionBackground else list.background
                    border = JBUI.Borders.empty(4, 8)
                    add(SimpleColoredComponent().apply {
                        append(value.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground))
                    }, BorderLayout.CENTER)
                    add(JBLabel(value.lineNumber.toString()).apply {
                        this.foreground = if (selected) list.selectionForeground else SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
                        horizontalAlignment = JBLabel.RIGHT
                    }, BorderLayout.EAST)
                }
            })
            .setItemChosenCallback { navigateTo(it.pointer) }
            .createPopup()
            .show(com.intellij.ui.awt.RelativePoint(component, point))
    }

    private fun navigateTo(pointer: SmartPsiElementPointer<out PsiElement>?) {
        val location = ApplicationManager.getApplication().runReadAction<Pair<VirtualFile, Int>?> {
            val element = pointer?.element
            if (element == null || !element.isValid) return@runReadAction null
            val file = element.containingFile?.virtualFile ?: return@runReadAction null
            file to element.textOffset
        } ?: return
        openInCurrentEditor(location.first, location.second)
    }

    private fun openInCurrentEditor(file: VirtualFile, offset: Int) {
        // FileEditorManager derives its open mode from the current AWT event. Calling it directly
        // from Shift+mouseReleased is interpreted by the platform as "open in a new window".
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || !file.isValid) return@invokeLater
            val descriptor = OpenFileDescriptor(project, file, offset.coerceAtLeast(0))
                .setUseCurrentWindow(true)
                .setUsePreviewTab(false)
                .also { it.setScrollType(ScrollType.CENTER) }
            val manager = FileEditorManager.getInstance(project)
            val editor = manager.openTextEditor(descriptor, true) ?: return@invokeLater
            manager.runWhenLoaded(editor) {
                descriptor.navigateIn(editor)
            }
        }
    }
}
