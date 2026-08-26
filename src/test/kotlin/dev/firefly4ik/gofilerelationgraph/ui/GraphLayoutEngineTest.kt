package dev.firefly4ik.gofilerelationgraph.ui

import com.intellij.testFramework.LightVirtualFile
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphLayoutEngineTest {
    @Test
    fun `top to bottom places callee below caller`() {
        val snapshot = snapshot("handler.go" to "service.go", "service.go" to "repository.go")

        val positions = GraphLayoutEngine.layout(snapshot)

        assertTrue(positions.getValue("handler.go").y < positions.getValue("service.go").y)
        assertTrue(positions.getValue("service.go").y < positions.getValue("repository.go").y)
    }

    @Test
    fun `adjacent levels include four additional pixels`() {
        val positions = GraphLayoutEngine.layout(snapshot("source.go" to "target.go"))

        assertEquals(82.0, positions.getValue("target.go").y - positions.getValue("source.go").y)
    }

    @Test
    fun `direct caller of a deep file is placed on the nearest valid level`() {
        val snapshot = snapshot(
            "handler.go" to "service.go",
            "service.go" to "repository.go",
            "repository_test.go" to "repository.go",
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertEquals(positions.getValue("service.go").y, positions.getValue("repository_test.go").y)
        assertTrue(positions.getValue("repository_test.go").y < positions.getValue("repository.go").y)
    }

    @Test
    fun `cycle keeps every file in layout`() {
        val snapshot = snapshot("a.go" to "b.go", "b.go" to "a.go")

        val positions = GraphLayoutEngine.layout(snapshot)

        assertEquals(setOf("a.go", "b.go"), positions.keys)
    }

    @Test
    fun `cycle is unfolded into a vertical hierarchy`() {
        val snapshot = snapshot(
            "configure.go" to "gateway.go",
            "gateway.go" to "get.go",
            "get.go" to "rpc.go",
            "rpc.go" to "gateway.go",
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertTrue(positions.getValue("configure.go").y < positions.getValue("gateway.go").y)
        assertTrue(positions.getValue("gateway.go").y < positions.getValue("get.go").y)
        assertTrue(positions.getValue("get.go").y < positions.getValue("rpc.go").y)
    }

    @Test
    fun `wide files on the same level do not overlap`() {
        val snapshot = snapshot("very-long-handler.go" to "target.go", "another-long-handler.go" to "target.go")

        val positions = GraphLayoutEngine.layout(snapshot) {
            Dimension(420, 36)
        }

        val distance = kotlin.math.abs(
            positions.getValue("very-long-handler.go").x - positions.getValue("another-long-handler.go").x,
        )
        assertTrue(distance >= 420)
    }

    @Test
    fun `files on the same level reserve horizontal space for relation labels`() {
        val snapshot = snapshot("root.go" to "left.go", "root.go" to "right.go")

        val positions = GraphLayoutEngine.layout(
            snapshot = snapshot,
            edgeLabelWidth = { 160.0 },
        )

        val distance = kotlin.math.abs(
            positions.getValue("left.go").x - positions.getValue("right.go").x,
        )
        assertTrue(distance >= 344.0)
    }

    @Test
    fun `independent relations do not reserve double label width`() {
        val snapshot = snapshot("left-parent.go" to "left.go", "right-parent.go" to "right.go")

        val positions = GraphLayoutEngine.layout(
            snapshot = snapshot,
            edgeLabelWidth = { 160.0 },
        )

        val distance = kotlin.math.abs(
            positions.getValue("left.go").x - positions.getValue("right.go").x,
        )
        assertTrue(distance in 184.0..<344.0)
    }

    @Test
    fun `earlier call is placed to the left on the same level`() {
        val ids = listOf("root.go", "first.go", "second.go")
        val snapshot = GraphSnapshot(
            nodes = ids.map { id -> FileNode(id, id, LightVirtualFile(id), false) },
            edges = listOf(
                FileEdge("root.go", "second.go", emptyList(), order = 80),
                FileEdge("root.go", "first.go", emptyList(), order = 20),
            ),
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertTrue(positions.getValue("first.go").x < positions.getValue("second.go").x)
    }

    @Test
    fun `explicit breadth first levels keep all direct parents on one row`() {
        val nodes = listOf(
            FileNode("root.go", "root.go", LightVirtualFile("root.go"), false, layoutLevel = 2),
            FileNode("first.go", "first.go", LightVirtualFile("first.go"), false, layoutLevel = 1),
            FileNode("second.go", "second.go", LightVirtualFile("second.go"), false, layoutLevel = 1),
            FileNode("deep.go", "deep.go", LightVirtualFile("deep.go"), false, layoutLevel = 0),
        )
        val snapshot = GraphSnapshot(
            nodes = nodes,
            edges = listOf(
                FileEdge("first.go", "root.go", emptyList()),
                FileEdge("second.go", "root.go", emptyList()),
                FileEdge("deep.go", "first.go", emptyList()),
            ),
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertEquals(positions.getValue("first.go").y, positions.getValue("second.go").y)
        assertTrue(positions.getValue("deep.go").y < positions.getValue("first.go").y)
        assertTrue(positions.getValue("first.go").y < positions.getValue("root.go").y)
    }

    @Test
    fun `large breadth first graph uses bounded layout optimization`() {
        val parentIds = (1..60).map { index -> "parent-$index.go" }
        val snapshot = GraphSnapshot(
            nodes = listOf(
                FileNode("target.go", "target.go", LightVirtualFile("target.go"), false, layoutLevel = 1),
            ) + parentIds.map { id -> FileNode(id, id, LightVirtualFile(id), false, layoutLevel = 0) },
            edges = parentIds.map { id -> FileEdge(id, "target.go", emptyList()) },
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertEquals(61, positions.size)
        assertEquals(1, parentIds.map { id -> positions.getValue(id).y }.distinct().size)
    }

    @Test
    fun `independent branches with different depth stay visually separated`() {
        val ids = listOf("apply.go", "handler.go", "promocode.go", "pricing.go", "deep.go")
        val snapshot = GraphSnapshot(
            nodes = ids.map { id -> FileNode(id, id, LightVirtualFile(id), false) },
            edges = listOf(
                FileEdge("apply.go", "promocode.go", emptyList(), order = 90),
                FileEdge("handler.go", "pricing.go", emptyList(), order = 10),
                FileEdge("pricing.go", "deep.go", emptyList(), order = 20),
            ),
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        val shortBranchRight = maxOf(
            positions.getValue("apply.go").x,
            positions.getValue("promocode.go").x,
        ) + 66.0
        val deepBranchLeft = minOf(
            positions.getValue("handler.go").x,
            positions.getValue("pricing.go").x,
            positions.getValue("deep.go").x,
        ) - 66.0
        val deepBranchRight = maxOf(
            positions.getValue("handler.go").x,
            positions.getValue("pricing.go").x,
            positions.getValue("deep.go").x,
        ) + 66.0
        val shortBranchLeft = minOf(
            positions.getValue("apply.go").x,
            positions.getValue("promocode.go").x,
        ) - 66.0

        assertTrue(shortBranchRight + 43.0 <= deepBranchLeft || deepBranchRight + 43.0 <= shortBranchLeft)
    }

    @Test
    fun `test callers stay grouped with their production targets`() {
        val ids = listOf(
            "left-root.go",
            "right-root.go",
            "z_left_test.go",
            "a_right_test.go",
            "left-target.go",
            "right-target.go",
        )
        val snapshot = GraphSnapshot(
            nodes = ids.map { id -> FileNode(id, id, LightVirtualFile(id), false, id.endsWith("_test.go")) },
            edges = listOf(
                FileEdge("left-root.go", "left-target.go", emptyList()),
                FileEdge("right-root.go", "right-target.go", emptyList()),
                FileEdge("z_left_test.go", "left-target.go", emptyList()),
                FileEdge("a_right_test.go", "right-target.go", emptyList()),
            ),
        )

        val positions = GraphLayoutEngine.layout(snapshot)
        val leftTargetIsFirst = positions.getValue("left-target.go").x < positions.getValue("right-target.go").x
        val leftSources = listOf("left-root.go", "z_left_test.go").map { positions.getValue(it).x }
        val rightSources = listOf("right-root.go", "a_right_test.go").map { positions.getValue(it).x }

        if (leftTargetIsFirst) {
            assertTrue(leftSources.max() < rightSources.min())
        } else {
            assertTrue(rightSources.max() < leftSources.min())
        }
    }

    @Test
    fun `adding tests does not invert production branches`() {
        val productionEdges = listOf(
            FileEdge("root.go", "left-parent.go", emptyList(), order = 10),
            FileEdge("root.go", "right-parent.go", emptyList(), order = 20),
            FileEdge("left-parent.go", "left-target.go", emptyList()),
            FileEdge("right-parent.go", "right-target.go", emptyList()),
        )
        val productionIds = productionEdges.flatMap { edge -> listOf(edge.sourceId, edge.targetId) }.toSet()
        val productionSnapshot = GraphSnapshot(
            nodes = productionIds.map { id -> FileNode(id, id, LightVirtualFile(id), false) },
            edges = productionEdges,
        )
        val withTests = GraphSnapshot(
            nodes = productionSnapshot.nodes + listOf(
                FileNode("z_left_test.go", "z_left_test.go", LightVirtualFile("z_left_test.go"), false, true),
                FileNode("a_right_test.go", "a_right_test.go", LightVirtualFile("a_right_test.go"), false, true),
            ),
            edges = productionEdges + listOf(
                FileEdge("z_left_test.go", "left-target.go", emptyList()),
                FileEdge("a_right_test.go", "right-target.go", emptyList()),
            ),
        )

        val productionPositions = GraphLayoutEngine.layout(productionSnapshot)
        val positionsWithTests = GraphLayoutEngine.layout(withTests)

        assertEquals(
            productionPositions.getValue("left-target.go").x < productionPositions.getValue("right-target.go").x,
            positionsWithTests.getValue("left-target.go").x < positionsWithTests.getValue("right-target.go").x,
        )
    }

    @Test
    fun `long edges crossing different levels are kept in production branch order`() {
        val ids = listOf(
            "root.go",
            "context.go",
            "source-pricing.go",
            "calculation-request.go",
            "calculation-context.go",
            "str-pricing.go",
            "z_methods_test.go",
            "a_pricing_test.go",
        )
        val snapshot = GraphSnapshot(
            nodes = ids.map { id ->
                FileNode(id, id, LightVirtualFile(id), false, id.endsWith("_test.go"))
            },
            edges = listOf(
                FileEdge("root.go", "context.go", emptyList()),
                FileEdge("context.go", "calculation-context.go", emptyList(), order = 10),
                FileEdge("context.go", "source-pricing.go", emptyList(), order = 20),
                FileEdge("context.go", "calculation-request.go", emptyList(), order = 30),
                FileEdge("calculation-request.go", "calculation-context.go", emptyList()),
                FileEdge("source-pricing.go", "str-pricing.go", emptyList()),
                FileEdge("z_methods_test.go", "calculation-request.go", emptyList()),
                FileEdge("z_methods_test.go", "calculation-context.go", emptyList()),
                FileEdge("a_pricing_test.go", "source-pricing.go", emptyList()),
            ),
        )

        val positions = GraphLayoutEngine.layout(snapshot)

        assertTrue(positions.getValue("calculation-context.go").x < positions.getValue("str-pricing.go").x)
    }

    @Test
    fun `disconnected groups keep their own shape and a visible gap`() {
        val snapshot = snapshot(
            "main-root.go" to "main-middle.go",
            "main-middle.go" to "main-leaf.go",
            "small-root.go" to "small-leaf.go",
        )

        val positions = GraphLayoutEngine.layout(snapshot)
        val mainX = listOf("main-root.go", "main-middle.go", "main-leaf.go")
            .map { id -> positions.getValue(id).x }
        val smallX = listOf("small-root.go", "small-leaf.go").map { id -> positions.getValue(id).x }
        val mainLeft = mainX.min() - 66.0
        val mainRight = mainX.max() + 66.0
        val smallLeft = smallX.min() - 66.0
        val smallRight = smallX.max() + 66.0

        assertTrue(mainX.max() - mainX.min() < 0.1)
        assertTrue(smallX.max() - smallX.min() < 0.1)
        assertTrue(mainRight + 43.0 <= smallLeft || smallRight + 43.0 <= mainLeft)
    }

    private fun snapshot(vararg relations: Pair<String, String>): GraphSnapshot {
        val ids = relations.flatMap { listOf(it.first, it.second) }.toSet()
        return GraphSnapshot(
            nodes = ids.map { id -> FileNode(id, id, LightVirtualFile(id), false) },
            edges = relations.map { (source, target) -> FileEdge(source, target, emptyList()) },
        )
    }
}
