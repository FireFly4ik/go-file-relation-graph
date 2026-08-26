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
    val isPlaceholder: Boolean = false,
    val navigationTargets: List<FileNavigationTarget> = emptyList(),
    val layoutLevel: Int? = null,
)

data class FileNavigationTarget(
    val label: String,
    val lineNumber: Int,
    val pointer: SmartPsiElementPointer<out PsiElement>,
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
    val kind: RelationKind = if (parentInterface == null) RelationKind.DIRECT else RelationKind.INTERFACE,
) {
    val isInterfaceDispatch: Boolean
        get() = kind == RelationKind.INTERFACE

    val isCallbackArgument: Boolean
        get() = kind == RelationKind.CALLBACK_ARGUMENT
}

enum class RelationKind {
    DIRECT,
    INTERFACE,
    CALLBACK_ARGUMENT,
}

data class CallSite(
    val lineNumber: Int,
    val lineText: String,
    val pointer: SmartPsiElementPointer<out PsiElement>,
    val offset: Int = Int.MAX_VALUE,
)
