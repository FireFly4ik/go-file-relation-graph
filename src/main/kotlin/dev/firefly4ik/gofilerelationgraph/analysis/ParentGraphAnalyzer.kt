package dev.firefly4ik.gofilerelationgraph.analysis

import com.goide.psi.GoCallExpr
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoTypeSpec
import com.goide.stubs.index.GoMethodSpecFingerprintIndex
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.firefly4ik.gofilerelationgraph.model.CallSite
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNavigationTarget
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import dev.firefly4ik.gofilerelationgraph.model.RelationKind
import java.util.ArrayDeque

data class ParentGraphResult(
    val snapshot: GraphSnapshot,
    val fileLimitReached: Boolean,
    val depthLimitReached: Boolean,
)

class ParentGraphAnalyzer(
    private val project: Project,
    private val maxParentFiles: Int = DEFAULT_MAX_PARENT_FILES,
    private val maxLevels: Int = DEFAULT_MAX_LEVELS,
) {
    private val pointerManager = SmartPointerManager.getInstance(project)
    private val psiDocumentManager = PsiDocumentManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val projectFileIndex = ProjectFileIndex.getInstance(project)
    private val moduleRoots = mutableMapOf<VirtualFile, VirtualFile?>()

    fun findGoModuleRoot(file: VirtualFile): VirtualFile? {
        if (file in moduleRoots) return moduleRoots[file]
        var directory = file.parent
        while (directory != null) {
            if (directory.findChild("go.mod")?.isDirectory == false) {
                moduleRoots[file] = directory
                return directory
            }
            directory = directory.parent
        }
        moduleRoots[file] = null
        return null
    }

    fun analyze(anchor: GoFunctionOrMethodDeclaration, includeTests: Boolean): ParentGraphResult {
        val anchorFile = anchor.containingFile
        val moduleRoot = findGoModuleRoot(anchorFile.virtualFile)
            ?: return ParentGraphResult(GraphSnapshot.EMPTY, false, false)
        val scope = GlobalSearchScope.projectScope(project).intersectWith(
            GlobalSearchScopesCore.directoryScope(project, moduleRoot, true),
        )
        val nodes = linkedMapOf<String, FileNode>()
        val accumulated = linkedMapOf<CallableKey, MutableCallable>()
        val navigationTargets = mutableMapOf<String, LinkedHashMap<DeclarationKey, FileNavigationTarget>>()
        val queue = ArrayDeque<QueueEntry>()
        val visited = mutableSetOf<DeclarationKey>()
        val enqueued = mutableSetOf<DeclarationKey>()
        val eligibility = mutableMapOf<VirtualFile, Boolean>()
        val acceptedParentPaths = mutableSetOf<String>()
        val fileDistances = mutableMapOf(anchorFile.virtualFile.path to 0)
        val truncatedTargets = mutableSetOf<String>()
        var fileLimitReached = false
        var depthLimitReached = false

        nodes[anchorFile.virtualFile.path] = fileNode(anchorFile, true)
        val anchorKey = declarationKey(anchor)
        addNavigationTarget(navigationTargets, anchor, anchorKey)
        enqueued += anchorKey
        queue += QueueEntry(anchor, 1, ParentPath(anchorKey, null))

        search@ while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val current = queue.removeFirst()
            val currentKey = declarationKey(current.declaration)
            if (!visited.add(currentKey)) continue

            val usages = mutableListOf<ParentUsage>()
            collectReferences(
                target = current.declaration,
                relationKind = RelationKind.DIRECT,
                parentInterface = null,
                scope = scope,
                moduleRoot = moduleRoot,
                includeTests = includeTests,
                eligibility = eligibility,
                result = usages,
            )
            if (current.declaration is GoMethodDeclaration) {
                collectInterfaceReferences(
                    target = current.declaration,
                    scope = scope,
                    moduleRoot = moduleRoot,
                    includeTests = includeTests,
                    eligibility = eligibility,
                    result = usages,
                )
            }

            for (usage in usages.sortedWith(compareBy(
                { value: ParentUsage -> value.parent.containingFile.virtualFile.path },
                { value: ParentUsage -> value.usage.textOffset },
            ))) {
                ProgressManager.checkCanceled()
                val parentKey = declarationKey(usage.parent)
                var path: ParentPath? = current.path
                while (path != null && path.key != parentKey) path = path.previous
                if (path != null) continue
                val parentFile = usage.parent.containingFile
                val parentPath = parentFile.virtualFile.path
                val targetPath = current.declaration.containingFile.virtualFile.path
                val parentLevel = current.level + if (parentPath == targetPath) 0 else 1
                if (parentLevel > maxLevels) {
                    depthLimitReached = true
                    truncatedTargets += targetPath
                    continue
                }
                val isNewParentFile = parentPath != anchorFile.virtualFile.path && parentPath !in acceptedParentPaths
                if (isNewParentFile && acceptedParentPaths.size >= maxParentFiles) {
                    fileLimitReached = true
                    truncatedTargets += targetPath
                    break@search
                }
                if (isNewParentFile) acceptedParentPaths += parentPath
                fileDistances.merge(parentPath, parentLevel - 1, ::minOf)
                nodes.putIfAbsent(parentPath, fileNode(parentFile, false))
                addNavigationTarget(navigationTargets, usage.parent, parentKey)

                if (parentPath != targetPath) {
                    val document = psiDocumentManager.getDocument(parentFile)
                        ?: fileDocumentManager.getDocument(parentFile.virtualFile)
                    val label = callableLabel(current.declaration)
                    val interfacePath = usage.parentInterface?.containingFile?.virtualFile?.path
                    val key = CallableKey(
                        sourcePath = parentPath,
                        targetPath = targetPath,
                        targetOffset = current.declaration.textOffset,
                        label = label,
                        kind = usage.kind,
                        interfacePath = interfacePath,
                    )
                    val relation = accumulated.getOrPut(key) {
                        MutableCallable(
                            key = key,
                            target = pointerManager.createSmartPsiElementPointer(current.declaration),
                            parentInterface = usage.parentInterface?.let(pointerManager::createSmartPsiElementPointer),
                        )
                    }
                    relation.callSites += callSite(usage.usage, document)
                }

                if (parentKey !in visited && enqueued.add(parentKey)) {
                    val next = QueueEntry(usage.parent, parentLevel, ParentPath(parentKey, current.path))
                    if (parentLevel == current.level) queue.addFirst(next) else queue.addLast(next)
                }
            }
        }

        val edges = accumulated.values
            .groupBy { value -> value.key.sourcePath to value.key.targetPath }
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
                            kind = value.key.kind,
                        )
                    },
                    order = values.minOfOrNull { value ->
                        value.callSites.minOfOrNull(CallSite::offset) ?: Int.MAX_VALUE
                    } ?: Int.MAX_VALUE,
                )
            }
            .sortedWith(compareBy(FileEdge::sourceId, FileEdge::order, FileEdge::targetId))
        val truncatedDistances = truncatedTargets.associateWith { targetPath ->
            (fileDistances[targetPath] ?: 0) + 1
        }
        val maximumDistance = (fileDistances.values + truncatedDistances.values).maxOrNull() ?: 0
        val titles = FileTitleDisambiguator.disambiguate(nodes.keys.toList(), project.basePath)
        val visibleNodes = nodes.values.map { node ->
            node.copy(
                title = titles.getValue(node.id),
                navigationTargets = navigationTargets[node.id]?.values?.toList().orEmpty(),
                layoutLevel = maximumDistance - fileDistances.getValue(node.id),
            )
        }.toMutableList()
        val visibleEdges = edges.toMutableList()
        for (targetPath in truncatedTargets) {
            val targetNode = nodes[targetPath] ?: continue
            val placeholderId = "parent-limit:$targetPath"
            visibleNodes += FileNode(
                id = placeholderId,
                title = "More parents not shown",
                file = targetNode.file,
                isActive = false,
                isPlaceholder = true,
                layoutLevel = maximumDistance - truncatedDistances.getValue(targetPath),
            )
            visibleEdges += FileEdge(
                sourceId = placeholderId,
                targetId = targetPath,
                callables = emptyList(),
            )
        }
        return ParentGraphResult(
            snapshot = GraphSnapshot(
                visibleNodes,
                visibleEdges,
            ),
            fileLimitReached = fileLimitReached,
            depthLimitReached = depthLimitReached,
        )
    }

    private fun collectInterfaceReferences(
        target: GoMethodDeclaration,
        scope: GlobalSearchScope,
        moduleRoot: VirtualFile,
        includeTests: Boolean,
        eligibility: MutableMap<VirtualFile, Boolean>,
        result: MutableList<ParentUsage>,
    ) {
        val name = target.name ?: return
        val parameterCount = target.signature?.parameters?.parameterCount ?: return
        val fingerprint = "$name/$parameterCount"
        GoMethodSpecFingerprintIndex.process(fingerprint, project, scope) { methodSpec ->
            ProgressManager.checkCanceled()
            if (!isEligible(methodSpec.containingFile, moduleRoot, includeTests, eligibility)) return@process true
            var implementsMethod = false
            DefinitionsScopedSearch.search(methodSpec, scope, true).forEach(Processor { implementation ->
                ProgressManager.checkCanceled()
                if (implementation is GoMethodDeclaration &&
                    declarationKey(implementation) == declarationKey(target)
                ) {
                    implementsMethod = true
                    false
                } else {
                    true
                }
            })
            if (implementsMethod) {
                collectReferences(
                    target = methodSpec,
                    relationKind = RelationKind.INTERFACE,
                    parentInterface = PsiTreeUtil.getParentOfType(methodSpec, GoTypeSpec::class.java),
                    scope = scope,
                    moduleRoot = moduleRoot,
                    includeTests = includeTests,
                    eligibility = eligibility,
                    result = result,
                )
            }
            true
        }
    }

    private fun collectReferences(
        target: PsiElement,
        relationKind: RelationKind,
        parentInterface: GoTypeSpec?,
        scope: GlobalSearchScope,
        moduleRoot: VirtualFile,
        includeTests: Boolean,
        eligibility: MutableMap<VirtualFile, Boolean>,
        result: MutableList<ParentUsage>,
    ) {
        ReferencesSearch.search(target, scope).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            val element = reference.element
            val sourceFile = element.containingFile as? GoFile ?: return@Processor true
            if (!isEligible(sourceFile, moduleRoot, includeTests, eligibility)) return@Processor true
            val parent = PsiTreeUtil.getParentOfType(
                element,
                GoFunctionOrMethodDeclaration::class.java,
                false,
            ) ?: return@Processor true
            val call = PsiTreeUtil.getParentOfType(element, GoCallExpr::class.java, false)
            val isDirectCall = call != null && PsiTreeUtil.isAncestor(call.expression, element, false)
            if (!isDirectCall && call == null) return@Processor true
            result += ParentUsage(
                parent = parent,
                usage = if (isDirectCall) call else element,
                kind = if (relationKind == RelationKind.INTERFACE) {
                    RelationKind.INTERFACE
                } else if (isDirectCall) {
                    RelationKind.DIRECT
                } else {
                    RelationKind.CALLBACK_ARGUMENT
                },
                parentInterface = parentInterface,
            )
            true
        })
    }

    private fun isEligible(
        file: GoFile?,
        moduleRoot: VirtualFile,
        includeTests: Boolean,
        eligibility: MutableMap<VirtualFile, Boolean>,
    ): Boolean {
        if (file == null || file.isGenerated) return false
        return eligibility.getOrPut(file.virtualFile) {
            if (!includeTests && file.name.endsWith("_test.go")) return@getOrPut false
            if (!projectFileIndex.isInContent(file.virtualFile)) return@getOrPut false
            if (findGoModuleRoot(file.virtualFile) != moduleRoot) return@getOrPut false
            val relativePath = VfsUtilCore.getRelativePath(file.virtualFile, moduleRoot, '/')
                ?: return@getOrPut false
            relativePath.split('/').none { component -> component == "vendor" }
        }
    }

    private fun fileNode(file: GoFile, active: Boolean) = FileNode(
        id = file.virtualFile.path,
        title = file.name,
        file = file.virtualFile,
        isActive = active,
        isTest = file.name.endsWith("_test.go"),
    )

    private fun callableLabel(declaration: GoFunctionOrMethodDeclaration): String {
        if (declaration !is GoMethodDeclaration) return "${declaration.name}()"
        val receiver = declaration.receiverType?.presentationText?.takeIf(String::isNotBlank)
        return if (receiver == null) "${declaration.name}()" else "($receiver).${declaration.name}()"
    }

    private fun addNavigationTarget(
        targets: MutableMap<String, LinkedHashMap<DeclarationKey, FileNavigationTarget>>,
        declaration: GoFunctionOrMethodDeclaration,
        key: DeclarationKey,
    ) {
        val file = declaration.containingFile
        val document = psiDocumentManager.getDocument(file)
            ?: fileDocumentManager.getDocument(file.virtualFile)
        val lineNumber = (document?.getLineNumber(declaration.textOffset) ?: 0) + 1
        targets.getOrPut(file.virtualFile.path, ::linkedMapOf).putIfAbsent(
            key,
            FileNavigationTarget(
                label = callableLabel(declaration).removeSuffix("()"),
                lineNumber = lineNumber,
                pointer = pointerManager.createSmartPsiElementPointer(declaration),
            ),
        )
    }

    private fun declarationKey(declaration: GoFunctionOrMethodDeclaration) = DeclarationKey(
        path = declaration.containingFile.virtualFile.path,
        offset = declaration.textOffset,
    )

    private fun callSite(
        usage: PsiElement,
        document: Document?,
    ): CallSite {
        val line = document?.getLineNumber(usage.textOffset) ?: 0
        val lineText = if (document == null) {
            usage.text
        } else {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            document.getText(TextRange(start, end)).trim()
        }
        return CallSite(
            lineNumber = line + 1,
            lineText = lineText,
            pointer = pointerManager.createSmartPsiElementPointer(usage),
            offset = usage.textOffset,
        )
    }

    private data class QueueEntry(
        val declaration: GoFunctionOrMethodDeclaration,
        val level: Int,
        val path: ParentPath,
    )

    private data class ParentPath(
        val key: DeclarationKey,
        val previous: ParentPath?,
    )

    private data class ParentUsage(
        val parent: GoFunctionOrMethodDeclaration,
        val usage: PsiElement,
        val kind: RelationKind,
        val parentInterface: GoTypeSpec?,
    )

    private data class DeclarationKey(
        val path: String,
        val offset: Int,
    )

    private data class CallableKey(
        val sourcePath: String,
        val targetPath: String,
        val targetOffset: Int,
        val label: String,
        val kind: RelationKind,
        val interfacePath: String?,
    )

    private class MutableCallable(
        val key: CallableKey,
        val target: com.intellij.psi.SmartPsiElementPointer<out PsiElement>,
        val parentInterface: com.intellij.psi.SmartPsiElementPointer<out PsiElement>?,
        val callSites: MutableList<CallSite> = mutableListOf(),
    )

    companion object {
        const val DEFAULT_MAX_PARENT_FILES = 50
        const val DEFAULT_MAX_LEVELS = 10
    }
}
