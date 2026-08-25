package dev.firefly4ik.gofilerelationgraph.navigation

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.firefly4ik.gofilerelationgraph.model.CallSite
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import java.awt.Point
import javax.swing.JPanel

class GraphNavigatorTest : BasePlatformTestCase() {
    fun testParentInterfaceNavigationReadsPsiInsideReadAction() {
        val interfaceFile = myFixture.addFileToProject(
            "gateway.go",
            """
                package main

                type BookingGateway interface {
                    Search()
                }
            """.trimIndent(),
        )
        myFixture.configureByText("current.go", "package main\n\nfunc current() {}")
        val manager = FileEditorManager.getInstance(project)
        val offset = interfaceFile.text.indexOf("Search")
        val element = interfaceFile.findElementAt(offset) ?: error("interface method element was not created")
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        val callable = CallableRelation(
            label = "bookingGateway.Search()",
            target = pointer,
            callSites = emptyList(),
            parentInterface = pointer,
        )

        GraphNavigator(project).openParentInterface(callable)

        assertEquals("current.go", manager.selectedFiles.single().name)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(interfaceFile.virtualFile, manager.selectedFiles.single())
        assertEquals(offset, manager.selectedTextEditor?.caretModel?.offset)
    }

    fun testFileAndSingleCallSiteNavigationRunsAfterCurrentAwtEvent() {
        val source = myFixture.addFileToProject(
            "source.go",
            """
                package main

                func source() {
                    target()
                }
            """.trimIndent(),
        )
        myFixture.configureByText("current.go", "package main\n\nfunc current() {}")
        val manager = FileEditorManager.getInstance(project)
        val offset = source.text.indexOf("target()")
        val element = source.findElementAt(offset) ?: error("call-site element was not created")
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        val callable = CallableRelation(
            label = "target()",
            target = pointer,
            callSites = listOf(CallSite(4, "target()", pointer, offset)),
            parentInterface = null,
        )

        val navigator = GraphNavigator(project)
        navigator.openFile(source.virtualFile)

        assertEquals("current.go", manager.selectedFiles.single().name)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(source.virtualFile, manager.selectedFiles.single())
        assertEquals(0, manager.selectedTextEditor?.caretModel?.offset)

        myFixture.configureByText("current.go", "package main\n\nfunc current() {}")
        navigator.openCallSites(callable, JPanel(), Point())

        assertEquals("current.go", manager.selectedFiles.single().name)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(source.virtualFile, manager.selectedFiles.single())
        assertEquals(offset, manager.selectedTextEditor?.caretModel?.offset)
    }
}
