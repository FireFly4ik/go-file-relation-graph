package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import dev.firefly4ik.gofilerelationgraph.navigation.GraphNavigator
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.Timer
import javax.swing.UIManager

class GraphCanvasTest : BasePlatformTestCase() {
    fun testRightClickNavigatesAfterMouseRelease() {
        val source = myFixture.addFileToProject("source.go", "package main\n\nfunc source() {}")
        val interfaceFile = myFixture.addFileToProject(
            "contract.go",
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
        val canvas = GraphCanvas(GraphNavigator(project)).apply {
            font = UIManager.getFont("Label.font")
            setSize(900, 600)
            setSnapshot(
                GraphSnapshot(
                    nodes = listOf(
                        FileNode("source", "source.go", source.virtualFile, false),
                        FileNode("target", "target.go", interfaceFile.virtualFile, false),
                    ),
                    edges = listOf(FileEdge("source", "target", listOf(callable))),
                ),
            )
        }
        for (name in listOf("scale", "offsetX", "offsetY")) {
            val current = GraphCanvas::class.java.getDeclaredField(name).apply { isAccessible = true }
            val target = GraphCanvas::class.java.getDeclaredField(
                when (name) {
                    "scale" -> "targetScale"
                    "offsetX" -> "targetOffsetX"
                    else -> "targetOffsetY"
                },
            ).apply { isAccessible = true }
            current.setDouble(canvas, target.getDouble(canvas))
        }
        GraphCanvas::class.java.getDeclaredField("animationTimer").apply { isAccessible = true }
            .get(canvas).let { it as Timer }.stop()
        canvas.paint(BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB).graphics)
        @Suppress("UNCHECKED_CAST")
        val label = (GraphCanvas::class.java.getDeclaredField("labelBounds").apply { isAccessible = true }
            .get(canvas) as List<Pair<Rectangle2D.Double, CallableRelation>>).single().first
        val scale = GraphCanvas::class.java.getDeclaredField("scale").apply { isAccessible = true }.getDouble(canvas)
        val offsetX = GraphCanvas::class.java.getDeclaredField("offsetX").apply { isAccessible = true }.getDouble(canvas)
        val offsetY = GraphCanvas::class.java.getDeclaredField("offsetY").apply { isAccessible = true }.getDouble(canvas)
        val x = (label.centerX * scale + offsetX).toInt()
        val y = (label.centerY * scale + offsetY).toInt()

        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON3_DOWN_MASK,
                x,
                y,
                1,
                true,
                MouseEvent.BUTTON3,
            ),
        )

        assertEquals("current.go", manager.selectedFiles.single().name)

        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                true,
                MouseEvent.BUTTON3,
            ),
        )
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(interfaceFile.virtualFile, manager.selectedFiles.single())
        assertEquals(offset, manager.selectedTextEditor?.caretModel?.offset)
    }
}
