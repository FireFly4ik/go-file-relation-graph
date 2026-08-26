package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.firefly4ik.gofilerelationgraph.model.CallSite
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import dev.firefly4ik.gofilerelationgraph.navigation.GraphNavigator
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.Timer
import javax.swing.UIManager

class GraphCanvasTest : BasePlatformTestCase() {
    fun testIdenticalOutgoingRelationsUseSharedOutputAndKeepIndividualInputs() {
        val parentA = myFixture.addFileToProject("parent_a.go", "package main\n\nfunc ParentA() {}")
        val parentB = myFixture.addFileToProject("parent_b.go", "package main\n\nfunc ParentB() {}")
        val sourceA = myFixture.addFileToProject("source_a.go", "package main\n\nfunc SourceA() { Search() }")
        val sourceB = myFixture.addFileToProject("source_b.go", "package main\n\nfunc SourceB() { Search() }")
        val target = myFixture.addFileToProject("target.go", "package main\n\nfunc Search() {}")
        val pointerManager = SmartPointerManager.getInstance(project)
        val targetElement = target.findElementAt(target.text.indexOf("Search"))
            ?: error("target element was not created")
        val targetPointer = pointerManager.createSmartPsiElementPointer(targetElement)
        val sourceACall = sourceA.findElementAt(sourceA.text.lastIndexOf("Search"))
            ?: error("first call element was not created")
        val sourceBCall = sourceB.findElementAt(sourceB.text.lastIndexOf("Search"))
            ?: error("second call element was not created")
        val relationA = CallableRelation(
            label = "gateway.Search()",
            target = targetPointer,
            callSites = listOf(
                CallSite(3, "func SourceA() { Search() }", pointerManager.createSmartPsiElementPointer(sourceACall)),
            ),
            parentInterface = null,
        )
        val relationB = relationA.copy(
            callSites = listOf(
                CallSite(3, "func SourceB() { Search() }", pointerManager.createSmartPsiElementPointer(sourceBCall)),
            ),
        )
        val incomingA = relationA.copy(label = "ParentA()", callSites = emptyList())
        val incomingB = relationA.copy(label = "ParentB()", callSites = emptyList())
        val snapshot = GraphSnapshot(
            nodes = listOf(
                FileNode("parent-a", "parent_a.go", parentA.virtualFile, false, layoutLevel = 0),
                FileNode("parent-b", "parent_b.go", parentB.virtualFile, false, layoutLevel = 0),
                FileNode("source-a", "source_a.go", sourceA.virtualFile, false, layoutLevel = 1),
                FileNode("source-b", "source_b.go", sourceB.virtualFile, false, layoutLevel = 1),
                FileNode("target", "target.go", target.virtualFile, false, layoutLevel = 2),
            ),
            edges = listOf(
                FileEdge("parent-a", "source-a", listOf(incomingA)),
                FileEdge("parent-b", "source-b", listOf(incomingB)),
                FileEdge("source-a", "target", listOf(relationA)),
                FileEdge("source-b", "target", listOf(relationB)),
            ),
        )

        val group = OutgoingRelationGroups.find(snapshot).single()
        assertEquals(listOf("source-a", "source-b"), group.memberIds)
        assertEquals(2, group.sharedRelations.single().callables.single().callSites.size)

        val automaticPositions = GraphLayoutEngine.layout(snapshot)
        assertEquals(automaticPositions.getValue("source-a").y, automaticPositions.getValue("source-b").y)
        assertTrue(automaticPositions.getValue("source-a").x < automaticPositions.getValue("source-b").x)

        val canvas = GraphCanvas(GraphNavigator(project)).apply {
            font = UIManager.getFont("Label.font")
            setSize(900, 600)
            setSnapshot(snapshot)
        }
        for (name in listOf("scale", "offsetX", "offsetY")) {
            val current = GraphCanvas::class.java.getDeclaredField(name).apply { isAccessible = true }
            val targetField = GraphCanvas::class.java.getDeclaredField(
                when (name) {
                    "scale" -> "targetScale"
                    "offsetX" -> "targetOffsetX"
                    else -> "targetOffsetY"
                },
            ).apply { isAccessible = true }
            current.setDouble(canvas, targetField.getDouble(canvas))
        }
        GraphCanvas::class.java.getDeclaredField("animationTimer").apply { isAccessible = true }
            .get(canvas).let { it as Timer }.stop()
        canvas.paint(BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB).graphics)

        @Suppress("UNCHECKED_CAST")
        val curves = GraphCanvas::class.java.getDeclaredField("edgeCurves").apply { isAccessible = true }
            .get(canvas) as List<Pair<CubicCurve2D.Double, Pair<String, String>>>
        assertEquals(3, curves.size)
        @Suppress("UNCHECKED_CAST")
        val bounds = GraphCanvas::class.java.getDeclaredField("nodeBounds").apply { isAccessible = true }
            .get(canvas) as Map<String, Rectangle2D.Double>
        val incomingCurve = curves.single { (_, key) -> key == "parent-a" to "source-a" }.first
        assertEquals(bounds.getValue("source-a").centerX, incomingCurve.x2, 0.01)
        assertEquals(bounds.getValue("source-a").minY, incomingCurve.y2, 0.01)

        @Suppress("UNCHECKED_CAST")
        val positions = GraphCanvas::class.java.getDeclaredField("positions").apply { isAccessible = true }
            .get(canvas) as MutableMap<String, java.awt.geom.Point2D.Double>
        val beforeMemberDragA = java.awt.geom.Point2D.Double(
            positions.getValue("source-a").x,
            positions.getValue("source-a").y,
        )
        val beforeMemberDragB = java.awt.geom.Point2D.Double(
            positions.getValue("source-b").x,
            positions.getValue("source-b").y,
        )
        val scale = GraphCanvas::class.java.getDeclaredField("scale").apply { isAccessible = true }.getDouble(canvas)
        val offsetX = GraphCanvas::class.java.getDeclaredField("offsetX").apply { isAccessible = true }.getDouble(canvas)
        val offsetY = GraphCanvas::class.java.getDeclaredField("offsetY").apply { isAccessible = true }.getDouble(canvas)
        val memberX = (bounds.getValue("source-a").centerX * scale + offsetX).toInt()
        val memberY = (bounds.getValue("source-a").centerY * scale + offsetY).toInt()
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                memberX,
                memberY,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                memberX + 42,
                memberY + 18,
                0,
                false,
                MouseEvent.NOBUTTON,
            ),
        )
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                memberX + 42,
                memberY + 18,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        val memberDeltaAX = positions.getValue("source-a").x - beforeMemberDragA.x
        val memberDeltaAY = positions.getValue("source-a").y - beforeMemberDragA.y
        assertEquals(memberDeltaAX, positions.getValue("source-b").x - beforeMemberDragB.x, 0.01)
        assertEquals(memberDeltaAY, positions.getValue("source-b").y - beforeMemberDragB.y, 0.01)
        assertEquals(beforeMemberDragB.x - beforeMemberDragA.x, positions.getValue("source-b").x - positions.getValue("source-a").x, 0.01)

        canvas.paint(BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB).graphics)
        @Suppress("UNCHECKED_CAST")
        val groupBounds = GraphCanvas::class.java.getDeclaredField("groupBoundsById").apply { isAccessible = true }
            .get(canvas) as Map<String, Rectangle2D.Double>
        val frame = groupBounds.values.single()
        val frameX = ((frame.minX + 6.0) * scale + offsetX).toInt()
        val frameY = ((frame.minY + 6.0) * scale + offsetY).toInt()
        val beforeFrameDragA = java.awt.geom.Point2D.Double(
            positions.getValue("source-a").x,
            positions.getValue("source-a").y,
        )
        val beforeFrameDragB = java.awt.geom.Point2D.Double(
            positions.getValue("source-b").x,
            positions.getValue("source-b").y,
        )
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                frameX,
                frameY,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK,
                frameX - 30,
                frameY + 12,
                0,
                false,
                MouseEvent.NOBUTTON,
            ),
        )
        canvas.dispatchEvent(
            MouseEvent(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                frameX - 30,
                frameY + 12,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        assertEquals(
            positions.getValue("source-a").x - beforeFrameDragA.x,
            positions.getValue("source-b").x - beforeFrameDragB.x,
            0.01,
        )
        assertEquals(
            positions.getValue("source-a").y - beforeFrameDragA.y,
            positions.getValue("source-b").y - beforeFrameDragB.y,
            0.01,
        )
    }

    fun testLimitPlaceholderEdgeWithoutCallablesPaints() {
        val target = myFixture.addFileToProject("target.go", "package main\n\nfunc target() {}")
        val canvas = GraphCanvas(GraphNavigator(project)).apply {
            font = UIManager.getFont("Label.font")
            setSize(900, 600)
            setSnapshot(
                GraphSnapshot(
                    nodes = listOf(
                        FileNode("limit", "More parents not shown", target.virtualFile, false, isPlaceholder = true),
                        FileNode("target", "target.go", target.virtualFile, true),
                    ),
                    edges = listOf(FileEdge("limit", "target", emptyList())),
                ),
            )
        }

        canvas.paint(BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB).graphics)
    }

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
