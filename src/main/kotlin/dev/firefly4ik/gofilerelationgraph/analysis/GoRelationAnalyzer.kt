package dev.firefly4ik.gofilerelationgraph.analysis

import com.goide.psi.GoCallExpr
import com.goide.psi.GoExpression
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoTypeSpec
import com.goide.psi.impl.GoTypeUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.firefly4ik.gofilerelationgraph.model.CallSite
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot

class GoRelationAnalyzer(
    private val project: Project,
) {
    fun analyze(includeTests: Boolean, hideUnconnected: Boolean): GraphSnapshot {
        val editorManager = FileEditorManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val selectedFiles = editorManager.selectedFiles.mapTo(mutableSetOf()) { it.path }
        val goFiles = editorManager.openFiles
            .asSequence()
            .filter { it.extension == "go" }
            .mapNotNull { psiManager.findFile(it) as? GoFile }
            .filterNot(GoFile::isGenerated)
            .filter { includeTests || !it.name.endsWith("_test.go") }
            .toList()

        if (goFiles.isEmpty()) return GraphSnapshot.EMPTY

        val openPaths = goFiles.mapTo(mutableSetOf()) { it.virtualFile.path }
        val scope = GlobalSearchScope.filesScope(project, goFiles.map(GoFile::getVirtualFile))
        val nodes = goFiles.map { goFile ->
            val file = goFile.virtualFile
            FileNode(
                id = file.path,
                title = file.name,
                file = file,
                isActive = file.path in selectedFiles,
                isTest = file.name.endsWith("_test.go"),
            )
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val accumulated = linkedMapOf<CallableKey, MutableCallable>()
        val interfaceImplementations = mutableMapOf<GoMethodSpec, List<GoMethodDeclaration>>()

        for (sourceFile in goFiles) {
            val sourcePath = sourceFile.virtualFile.path
            val document = PsiDocumentManager.getInstance(project).getDocument(sourceFile)
                ?: FileDocumentManager.getInstance().getDocument(sourceFile.virtualFile)
            val references = PsiTreeUtil.findChildrenOfType(sourceFile, GoReferenceExpression::class.java)

            for (reference in references) {
                val resolved = reference.reference.resolve() ?: continue
                val containingCall = PsiTreeUtil.getParentOfType(reference, GoCallExpr::class.java, false)
                val usage = if (
                    containingCall != null && PsiTreeUtil.isAncestor(containingCall.expression, reference, false)
                ) {
                    containingCall
                } else {
                    reference
                }

                when (resolved) {
                    is GoFunctionDeclaration -> addDirectCall(
                        accumulated = accumulated,
                        sourcePath = sourcePath,
                        target = resolved,
                        label = "${resolved.name}()",
                        usage = usage,
                        document = document,
                        openPaths = openPaths,
                        pointerManager = pointerManager,
                    )

                    is GoMethodDeclaration -> addDirectCall(
                        accumulated = accumulated,
                        sourcePath = sourcePath,
                        target = resolved,
                        label = methodLabel(resolved),
                        usage = usage,
                        document = document,
                        openPaths = openPaths,
                        pointerManager = pointerManager,
                    )

                    is GoMethodSpec -> {
                        val parentInterface = findStaticParentInterface(reference, resolved, usage)
                        val interfaceName = parentInterface?.name
                            ?: PsiTreeUtil.getParentOfType(resolved, GoTypeSpec::class.java)?.name
                            ?: "interface"
                        val implementations = interfaceImplementations.getOrPut(resolved) {
                            DefinitionsScopedSearch.search(resolved, scope, true)
                                .findAll()
                                .filterIsInstance<GoMethodDeclaration>()
                        }

                        for (implementation in implementations) {
                            addInterfaceCall(
                                accumulated = accumulated,
                                sourcePath = sourcePath,
                                target = implementation,
                                label = "$interfaceName.${resolved.name}()",
                                usage = usage,
                                document = document,
                                parentInterface = parentInterface
                                    ?: PsiTreeUtil.getParentOfType(resolved, GoTypeSpec::class.java),
                                openPaths = openPaths,
                                pointerManager = pointerManager,
                            )
                        }
                    }
                }
            }
        }

        val edges = accumulated.values
            .groupBy { it.key.sourcePath to it.key.targetPath }
            .map { (paths, values) ->
                FileEdge(
                    sourceId = paths.first,
                    targetId = paths.second,
                    callables = values.sortedBy { value ->
                        value.callSites.minOfOrNull(CallSite::offset) ?: Int.MAX_VALUE
                    }.map { value ->
                        CallableRelation(
                            label = value.key.label,
                            target = value.target,
                            callSites = value.callSites.sortedBy(CallSite::lineNumber),
                            parentInterface = value.parentInterface,
                        )
                    },
                    order = values.minOfOrNull { value -> value.callSites.minOfOrNull(CallSite::offset) ?: Int.MAX_VALUE }
                        ?: Int.MAX_VALUE,
                )
            }
            .sortedWith(compareBy(FileEdge::sourceId, FileEdge::order, FileEdge::targetId))

        val connectedPaths = edges.flatMapTo(mutableSetOf()) { edge -> listOf(edge.sourceId, edge.targetId) }
        val visibleNodes = if (hideUnconnected) nodes.filter { it.id in connectedPaths } else nodes
        val titles = FileTitleDisambiguator.disambiguate(visibleNodes.map(FileNode::id), project.basePath)
        return GraphSnapshot(
            visibleNodes.map { node -> node.copy(title = titles.getValue(node.id)) }.sortedBy(FileNode::title),
            edges,
        )
    }

    private fun addDirectCall(
        accumulated: MutableMap<CallableKey, MutableCallable>,
        sourcePath: String,
        target: PsiElement,
        label: String,
        usage: PsiElement,
        document: Document?,
        openPaths: Set<String>,
        pointerManager: SmartPointerManager,
    ) {
        val targetPath = target.containingFile.virtualFile.path
        if (targetPath == sourcePath || targetPath !in openPaths) return
        val key = CallableKey(sourcePath, targetPath, target.textOffset, label, null)
        val value = accumulated.getOrPut(key) {
            MutableCallable(key, pointerManager.createSmartPsiElementPointer(target), null)
        }
        value.callSites += callSite(usage, document, pointerManager)
    }

    private fun addInterfaceCall(
        accumulated: MutableMap<CallableKey, MutableCallable>,
        sourcePath: String,
        target: GoMethodDeclaration,
        label: String,
        usage: PsiElement,
        document: Document?,
        parentInterface: GoTypeSpec?,
        openPaths: Set<String>,
        pointerManager: SmartPointerManager,
    ) {
        val targetPath = target.containingFile.virtualFile.path
        if (targetPath == sourcePath || targetPath !in openPaths) return
        val interfacePath = parentInterface?.containingFile?.virtualFile?.path
        val key = CallableKey(sourcePath, targetPath, target.textOffset, label, interfacePath)
        val value = accumulated.getOrPut(key) {
            MutableCallable(
                key = key,
                target = pointerManager.createSmartPsiElementPointer(target),
                parentInterface = parentInterface?.let(pointerManager::createSmartPsiElementPointer),
            )
        }
        value.callSites += callSite(usage, document, pointerManager)
    }

    private fun findStaticParentInterface(
        reference: GoReferenceExpression,
        resolvedMethod: GoMethodSpec,
        context: PsiElement,
    ): GoTypeSpec? {
        val qualifier = reference.qualifier as? GoExpression
        val qualifierType = qualifier?.getGoType(ResolveState.initial())
        val typeSpec = qualifierType?.let { GoTypeUtil.findTypeSpec(it, context) }
        if (typeSpec != null && GoTypeUtil.isInterface(typeSpec)) return typeSpec
        return PsiTreeUtil.getParentOfType(resolvedMethod, GoTypeSpec::class.java)
    }

    private fun callSite(
        usage: PsiElement,
        document: Document?,
        pointerManager: SmartPointerManager,
    ): CallSite {
        val line = document?.getLineNumber(usage.textOffset) ?: 0
        val lineText = if (document == null) {
            usage.text
        } else {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            document.getText(com.intellij.openapi.util.TextRange(start, end)).trim()
        }
        return CallSite(
            lineNumber = line + 1,
            lineText = lineText,
            pointer = pointerManager.createSmartPsiElementPointer(usage),
            offset = usage.textOffset,
        )
    }

    private fun methodLabel(method: GoMethodDeclaration): String {
        val receiver = method.receiverType?.presentationText?.takeIf(String::isNotBlank)
        return if (receiver == null) "${method.name}()" else "($receiver).${method.name}()"
    }

    private data class CallableKey(
        val sourcePath: String,
        val targetPath: String,
        val targetOffset: Int,
        val label: String,
        val interfacePath: String?,
    )

    private class MutableCallable(
        val key: CallableKey,
        val target: com.intellij.psi.SmartPsiElementPointer<out PsiElement>,
        val parentInterface: com.intellij.psi.SmartPsiElementPointer<out PsiElement>?,
        val callSites: MutableList<CallSite> = mutableListOf(),
    )
}
