package dev.firefly4ik.gofilerelationgraph.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

data class GraphSnapshot(
    val nodes: List<FileNode>,
    val edges: List<FileEdge>,
) {
    companion object {
        val EMPTY = GraphSnapshot(emptyList(), emptyList())
    }
}

data class FileNode(
    val id: String,
    val title: String,
    val file: VirtualFile,
    val isActive: Boolean,
    val isTest: Boolean = false,
)

data class FileEdge(
    val sourceId: String,
    val targetId: String,
    val callables: List<CallableRelation>,
    val order: Int = Int.MAX_VALUE,
)

data class CallableRelation(
    val label: String,
    val target: SmartPsiElementPointer<out PsiElement>,
    val callSites: List<CallSite>,
    val parentInterface: SmartPsiElementPointer<out PsiElement>?,
) {
    val isInterfaceDispatch: Boolean
        get() = parentInterface != null
}

data class CallSite(
    val lineNumber: Int,
    val lineText: String,
    val pointer: SmartPsiElementPointer<out PsiElement>,
    val offset: Int = Int.MAX_VALUE,
)
