package dev.firefly4ik.gofilerelationgraph.analysis

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.firefly4ik.gofilerelationgraph.model.RelationKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentGraphAnalyzerTest : BasePlatformTestCase() {
    fun testBuildsParentsThroughSameFileCallsAndCallbackArguments() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject(
            "target.go",
            """
                package sample

                type Handler struct{}

                func (h *Handler) Create() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "middle.go",
            """
                package sample

                func Register(callback func()) {}

                func Configure(handler *Handler) {
                    Register(handler.Create)
                }

                func start() {
                    Configure(&Handler{})
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "root.go",
            """
                package sample

                func Root() {
                    start()
                }
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoMethodDeclaration::class.java)
            ?: error("anchor method was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = false)
        }

        assertEquals(setOf("target.go", "middle.go", "root.go"), result.snapshot.nodes.mapTo(mutableSetOf()) { it.file.name })
        val callbackEdge = result.snapshot.edges.single { edge -> edge.targetId.endsWith("target.go") }
        assertEquals(RelationKind.CALLBACK_ARGUMENT, callbackEdge.callables.single().kind)
        assertEquals("(*Handler).Create()", callbackEdge.callables.single().label)
        assertTrue(result.snapshot.edges.any { edge -> edge.sourceId.endsWith("root.go") && edge.targetId.endsWith("middle.go") })
        val levels = result.snapshot.nodes.associate { node -> node.file.name to node.layoutLevel }
        assertEquals(0, levels.getValue("root.go"))
        assertEquals(1, levels.getValue("middle.go"))
        assertEquals(2, levels.getValue("target.go"))
        assertFalse(result.fileLimitReached)
        assertFalse(result.depthLimitReached)
    }

    fun testFindsPossibleInterfaceParent() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val implementationFile = myFixture.addFileToProject(
            "gateway.go",
            """
                package sample

                type BookingGateway struct{}

                func (g *BookingGateway) Search() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "interactor.go",
            """
                package sample

                type Gateway interface {
                    Search()
                }

                func Execute(gateway Gateway) {
                    gateway.Search()
                }
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(implementationFile, GoMethodDeclaration::class.java)
            ?: error("anchor method was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = false)
        }

        val relation = result.snapshot.edges.single().callables.single()
        assertEquals(RelationKind.INTERFACE, relation.kind)
        assertEquals("(*BookingGateway).Search()", relation.label)
        assertTrue(relation.parentInterface?.element != null)
    }

    fun testStopsAtConfiguredDepth() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject("target.go", "package sample\nfunc Target() {}")
        myFixture.addFileToProject("parent.go", "package sample\nfunc Parent() { Target() }")
        myFixture.addFileToProject("root.go", "package sample\nfunc Root() { Parent() }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project, maxLevels = 2).analyze(anchor, includeTests = false)
        }

        assertEquals(
            setOf("target.go", "parent.go"),
            result.snapshot.nodes.filterNot { it.isPlaceholder }.mapTo(mutableSetOf()) { it.file.name },
        )
        assertTrue(result.snapshot.nodes.any { it.isPlaceholder })
        assertTrue(result.depthLimitReached)
    }

    fun testStopsAtConfiguredFileLimit() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject("target.go", "package sample\nfunc Target() {}")
        myFixture.addFileToProject("first.go", "package sample\nfunc First() { Target() }")
        myFixture.addFileToProject("second.go", "package sample\nfunc Second() { Target() }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project, maxParentFiles = 1).analyze(anchor, includeTests = false)
        }

        assertEquals(2, result.snapshot.nodes.count { !it.isPlaceholder })
        assertTrue(result.snapshot.nodes.any { it.isPlaceholder })
        assertTrue(result.fileLimitReached)
    }

    fun testDoesNotRenderCycle() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val firstFile = myFixture.addFileToProject("first.go", "package sample\nfunc First() { Second() }")
        myFixture.addFileToProject("second.go", "package sample\nfunc Second() { First() }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(firstFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = false)
        }

        assertEquals(1, result.snapshot.edges.size)
        assertEquals("second.go", result.snapshot.nodes.single { !it.isActive }.file.name)
    }

    fun testIncludesTestParentsOnlyWhenEnabled() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject("target.go", "package sample\nfunc Target() {}")
        myFixture.addFileToProject("target_test.go", "package sample\nfunc TestTarget() { Target() }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val withoutTests = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = false)
        }
        val withTests = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = true)
        }

        assertEquals(1, withoutTests.snapshot.nodes.size)
        assertEquals(setOf("target.go", "target_test.go"), withTests.snapshot.nodes.mapTo(mutableSetOf()) { it.file.name })
    }

    fun testExcludesGeneratedParents() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject("target.go", "package sample\nfunc Target() {}")
        myFixture.addFileToProject(
            "generated.go",
            """
                // Code generated by parent graph test. DO NOT EDIT.
                package sample

                func GeneratedParent() { Target() }
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = true)
        }

        assertEquals(listOf("target.go"), result.snapshot.nodes.map { it.file.name })
    }

    fun testUsesNearestGoModule() {
        myFixture.addFileToProject("go.mod", "module example.com/root")
        val nestedModule = myFixture.addFileToProject("nested/go.mod", "module example.com/nested")
        val targetFile = myFixture.addFileToProject("nested/internal/target.go", "package internal\nfunc Target() {}")

        val moduleRoot = ParentGraphAnalyzer(project).findGoModuleRoot(targetFile.virtualFile)

        assertEquals(nestedModule.virtualFile.parent, moduleRoot)
    }

    fun testDoesNotCrossIntoNestedGoModule() {
        myFixture.addFileToProject("go.mod", "module example.com/root")
        val targetFile = myFixture.addFileToProject("target.go", "package root\nfunc Target() {}")
        myFixture.addFileToProject("nested/go.mod", "module example.com/nested")
        myFixture.addFileToProject(
            "nested/parent.go",
            "package nested\nimport root \"example.com/root\"\nfunc Parent() { root.Target() }",
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = true)
        }

        assertEquals(listOf("target.go"), result.snapshot.nodes.map { it.file.name })
        assertTrue(result.snapshot.edges.isEmpty())
    }

    fun testDoesNotTreatPlainFunctionAssignmentAsCallbackArgument() {
        myFixture.addFileToProject("go.mod", "module example.com/sample")
        val targetFile = myFixture.addFileToProject("target.go", "package sample\nfunc Target() {}")
        myFixture.addFileToProject(
            "configure.go",
            "package sample\nfunc Configure() { callback := Target; _ = callback }",
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val anchor = PsiTreeUtil.findChildOfType(targetFile, GoFunctionOrMethodDeclaration::class.java)
            ?: error("anchor function was not created")

        val result = ApplicationManager.getApplication().runReadAction<ParentGraphResult> {
            ParentGraphAnalyzer(project).analyze(anchor, includeTests = false)
        }

        assertTrue(result.snapshot.edges.isEmpty())
        assertEquals(listOf("target.go"), result.snapshot.nodes.map { it.file.name })
    }
}
