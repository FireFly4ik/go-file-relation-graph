package dev.firefly4ik.gofilerelationgraph.analysis

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.AppExecutorUtil

class ParentGraphController(
    private val project: Project,
    private val anchor: SmartPsiElementPointer<GoFunctionOrMethodDeclaration>,
    private val onLoading: () -> Unit,
    private val onResult: (ParentGraphResult) -> Unit,
) : Disposable {
    var includeTests: Boolean = anchor.virtualFile.name.endsWith("_test.go")
    var maxParentFiles: Int = ParentGraphAnalyzer.DEFAULT_MAX_PARENT_FILES

    fun refresh() {
        onLoading()
        val includeTestsForRequest = includeTests
        val maxParentFilesForRequest = maxParentFiles
        ReadAction.nonBlocking<ParentGraphResult> {
            val declaration = anchor.element
                ?: return@nonBlocking ParentGraphResult(
                    snapshot = dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot.EMPTY,
                    fileLimitReached = false,
                    depthLimitReached = false,
                )
            ParentGraphAnalyzer(
                project = project,
                maxParentFiles = maxParentFilesForRequest,
            ).analyze(declaration, includeTestsForRequest)
        }
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), onResult)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() = Unit
}
