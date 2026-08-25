package dev.firefly4ik.gofilerelationgraph.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import javax.swing.Timer

class GraphDataController(
    private val project: Project,
    private val onSnapshot: (GraphSnapshot) -> Unit,
    private val onActiveFilesChanged: (Set<String>) -> Unit,
) : Disposable {
    private val analyzer = GoRelationAnalyzer(project)
    private val debounceTimer = Timer(350) { refreshNow() }.apply { isRepeats = false }

    var autoRefresh: Boolean = true
    var includeTests: Boolean = false
        set(value) {
            field = value
            requestRefresh(force = true)
        }
    var showUnconnected: Boolean = false
        set(value) {
            field = value
            requestRefresh(force = true)
        }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                    requestRefresh()
                }

                override fun fileClosed(source: com.intellij.openapi.fileEditor.FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                    requestRefresh()
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    onActiveFilesChanged(
                        FileEditorManager.getInstance(project).selectedFiles.mapTo(mutableSetOf()) { it.path },
                    )
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    requestRefresh()
                }
            },
            this,
        )
        refreshNow()
    }

    fun requestRefresh(force: Boolean = false) {
        if (!force && !autoRefresh) return
        if (force) {
            debounceTimer.stop()
            refreshNow()
            return
        }
        debounceTimer.restart()
    }

    fun refreshNow() {
        ReadAction.nonBlocking<GraphSnapshot> { analyzer.analyze(includeTests, !showUnconnected) }
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), onSnapshot)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() {
        debounceTimer.stop()
    }
}
