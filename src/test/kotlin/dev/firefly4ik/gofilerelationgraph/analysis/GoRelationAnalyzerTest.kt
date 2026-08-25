package dev.firefly4ik.gofilerelationgraph.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import kotlin.test.assertEquals

class GoRelationAnalyzerTest : BasePlatformTestCase() {
    fun testFunctionPassedAsValueCreatesRelation() {
        val target = myFixture.addFileToProject(
            "target.go",
            """
                package sample

                func Handle() {}
            """.trimIndent(),
        )
        val source = myFixture.addFileToProject(
            "source.go",
            """
                package sample

                var callback = Handle
            """.trimIndent(),
        )
        FileEditorManager.getInstance(project).openFile(target.virtualFile, true)
        FileEditorManager.getInstance(project).openFile(source.virtualFile, true)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val snapshot = ApplicationManager.getApplication().runReadAction<GraphSnapshot> {
            GoRelationAnalyzer(project).analyze(includeTests = false, hideUnconnected = false)
        }

        val relation = snapshot.edges.single { edge ->
            edge.sourceId == source.virtualFile.path && edge.targetId == target.virtualFile.path
        }
        assertEquals(listOf("Handle()"), relation.callables.map { callable -> callable.label })
        assertEquals(1, relation.callables.single().callSites.size)
    }
}
