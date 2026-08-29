package dev.firefly4ik.gofilerelationgraph.ui

import com.goide.psi.GoFile
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tabs.JBTabs
import dev.firefly4ik.gofilerelationgraph.actions.BuildParentGraphAction

class GraphTabsPanelTest : BasePlatformTestCase() {
    fun testOpenedFilesStaysFirstAndParentCloseActionLivesInTabLabel() {
        val file = myFixture.configureByText(
            "main.go",
            "package main\n\nfunc Main() {}",
        ) as GoFile
        val anchor = BuildParentGraphAction().findAnchor(file, file.text.indexOf("Main"))
            ?: error("parent graph anchor was not created")
        val panel = GraphTabsPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.addParentGraph(
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchor),
            "main.Main",
            file.virtualFile.path,
        )

        val tabs = panel.components.single() as JBTabs
        assertEquals(2, tabs.tabCount)
        assertEquals("Opened Files", tabs.getTabAt(0).text)
        assertEquals("Parents: main.Main", tabs.getTabAt(1).text)
        assertNotNull(tabs.getTabAt(1).tabLabelActions)
        assertNull(tabs.getTabAt(0).tabLabelActions)
        assertEquals(0, panel.minimumSize.width)
        assertEquals(0, tabs.getTabAt(1).component.minimumSize.width)
    }
}
