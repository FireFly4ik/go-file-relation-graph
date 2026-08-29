package dev.firefly4ik.gofilerelationgraph.ui

import com.goide.GoIcons
import com.intellij.ui.JBColor
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.JBUI
import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import dev.firefly4ik.gofilerelationgraph.navigation.GraphNavigator
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.PathIterator
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GraphCanvas(
    private val navigator: GraphNavigator,
    private val chooseNodeNavigationTarget: Boolean = true,
) : JComponent() {
    private var snapshot = GraphSnapshot.EMPTY
    private var nodesById = emptyMap<String, FileNode>()
    private var outgoingGroups = emptyList<OutgoingRelationGroup>()
    private var outgoingGroupByMemberId = emptyMap<String, OutgoingRelationGroup>()
    private var edgeKeysByNode = emptyMap<String, Set<Pair<String, String>>>()
    private var edgeKeysByCallable = emptyMap<CallableRelation, Set<Pair<String, String>>>()
    private var nodeIdsByEdgeKey = emptyMap<Pair<String, String>, Set<String>>()
    private val positions = mutableMapOf<String, Point2D.Double>()
    private val targetPositions = mutableMapOf<String, Point2D.Double>()
    private val nodeBounds = mutableMapOf<String, Rectangle2D.Double>()
    private val groupBoundsById = mutableMapOf<String, Rectangle2D.Double>()
    private val labelBounds = mutableListOf<Pair<Rectangle2D.Double, CallableRelation>>()
    private val labelGroupBounds = mutableListOf<Rectangle2D.Double>()
    private val edgeCurves = mutableListOf<Pair<CubicCurve2D.Double, Pair<String, String>>>()

    private var scale = 1.0
    private var targetScale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var targetOffsetX = 0.0
    private var targetOffsetY = 0.0
    private var draggedNodeId: String? = null
    private var draggedGroupId: String? = null
    private var dragStart: Point? = null
    private var lastMousePoint: Point? = null
    private var lastPointerPoint: Point? = null
    private var pressedCallable: CallableRelation? = null
    private var hoveredCallable: CallableRelation? = null
    private var hoveredEdgeKey: Pair<String, String>? = null
    private var hoveredGroupId: String? = null
    private var highlightedNodeIds = emptySet<String>()
    private var highlightedEdgeKeys = emptySet<Pair<String, String>>()
    private var hasDragged = false
    private var pendingInitialFit = false
    private var popupGestureStarted = false
    private var pendingWheelZoomFactor = 1.0
    private var pendingWheelZoomAnchor = Point()
    private var touchpadWheelSuppressedUntil = 0L

    var showLabels: Boolean = true
        set(value) {
            field = value
            repaint()
        }

    private val animationTimer = Timer(16, null)
    private val resizeTimer = Timer(120) { fitGraph() }.apply { isRepeats = false }
    private val detailRestoreTimer = Timer(120) { repaint() }.apply { isRepeats = false }
    private val mouseWheelZoomTimer = Timer(70) {
        if (System.currentTimeMillis() >= touchpadWheelSuppressedUntil && pendingWheelZoomFactor != 1.0) {
            zoomAt(pendingWheelZoomAnchor, targetScale * pendingWheelZoomFactor)
        }
        pendingWheelZoomFactor = 1.0
    }.apply { isRepeats = false }

    init {
        animationTimer.addActionListener { animateFrame() }
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(640), JBUI.scale(420))
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                requestFocusInWindow()
                lastPointerPoint = event.point
                lastMousePoint = event.point
                dragStart = event.point
                hasDragged = false
                popupGestureStarted = false
                pressedCallable = callableAt(event.point)

                if (event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
                    // Open only after mouseReleased. Otherwise the release returns focus to the graph
                    // while the editor is being selected and GoLand rejects the focus transfer.
                    popupGestureStarted = true
                    return
                }

                if (pressedCallable == null) {
                    val pressedNode = nodeAt(event.point)
                    val pressedGroup = pressedNode?.id?.let(outgoingGroupByMemberId::get) ?: groupAt(event.point)
                    draggedGroupId = pressedGroup?.id
                    draggedNodeId = pressedNode?.id?.takeIf { pressedGroup == null }
                    cursor = Cursor.getPredefinedCursor(
                        if (draggedNodeId == null || draggedGroupId != null) Cursor.MOVE_CURSOR else Cursor.HAND_CURSOR,
                    )
                }
            }

            override fun mouseDragged(event: MouseEvent) {
                lastPointerPoint = event.point
                val previous = lastMousePoint ?: event.point
                val dx = event.x - previous.x
                val dy = event.y - previous.y
                if (abs(event.x - (dragStart?.x ?: event.x)) + abs(event.y - (dragStart?.y ?: event.y)) > 3) {
                    hasDragged = true
                }

                val groupId = draggedGroupId
                val nodeId = draggedNodeId
                if (groupId != null) {
                    val group = outgoingGroups.firstOrNull { candidate -> candidate.id == groupId } ?: return
                    for (memberId in group.memberIds) {
                        val position = positions[memberId] ?: continue
                        position.x += dx / scale
                        position.y += dy / scale
                        targetPositions[memberId] = Point2D.Double(position.x, position.y)
                    }
                } else if (nodeId != null) {
                    val position = positions[nodeId] ?: return
                    position.x += dx / scale
                    position.y += dy / scale
                    targetPositions[nodeId] = Point2D.Double(position.x, position.y)
                } else if (pressedCallable == null) {
                    offsetX += dx
                    offsetY += dy
                    targetOffsetX = offsetX
                    targetOffsetY = offsetY
                }
                lastMousePoint = event.point
                if (isLargeGraph) detailRestoreTimer.restart()
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (popupGestureStarted || event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
                    (pressedCallable ?: callableAt(event.point))
                        ?.takeIf(CallableRelation::isInterfaceDispatch)
                        ?.let(navigator::openParentInterface)
                    event.consume()
                } else if (!hasDragged) {
                    val callable = pressedCallable ?: callableAt(event.point)
                    when {
                        callable != null && event.isShiftDown -> navigator.openCallSites(callable, this@GraphCanvas, event.point)
                        callable != null -> navigator.openTarget(callable)
                        else -> nodeAt(event.point)?.takeUnless(FileNode::isPlaceholder)?.let { node ->
                            navigator.openNode(node, this@GraphCanvas, event.point, chooseNodeNavigationTarget)
                        }
                    }
                }
                pressedCallable = null
                draggedNodeId = null
                draggedGroupId = null
                dragStart = null
                lastMousePoint = null
                cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                popupGestureStarted = false
                if (detailRestoreTimer.isRunning) detailRestoreTimer.stop()
                repaint()
            }

            override fun mouseMoved(event: MouseEvent) {
                lastPointerPoint = event.point
                val hovered = callableAt(event.point)
                val hoveredNode = if (hovered == null) nodeAt(event.point) else null
                val edgeKey = if (hovered == null && hoveredNode == null) edgeAt(event.point) else null
                val hoveredGroup = if (hovered == null && hoveredNode == null && edgeKey == null) {
                    groupAt(event.point)
                } else {
                    null
                }
                val edgeKeys = when {
                    hovered != null -> edgeKeysByCallable[hovered].orEmpty()
                    hoveredNode != null -> edgeKeysByNode[hoveredNode.id].orEmpty()
                    edgeKey != null -> setOf(edgeKey)
                    hoveredGroup != null -> hoveredGroup.memberIds.flatMapTo(mutableSetOf()) { memberId ->
                        edgeKeysByNode[memberId].orEmpty()
                    }
                    else -> emptySet()
                }
                val nodeIds = edgeKeys.flatMapTo(mutableSetOf()) { key -> nodeIdsByEdgeKey[key].orEmpty() }
                if (hoveredNode != null) nodeIds += hoveredNode.id
                if (hoveredGroup != null) nodeIds += hoveredGroup.memberIds
                if (
                    hovered != hoveredCallable || edgeKey != hoveredEdgeKey || hoveredGroup?.id != hoveredGroupId ||
                    edgeKeys != highlightedEdgeKeys || nodeIds != highlightedNodeIds
                ) {
                    hoveredCallable = hovered
                    hoveredEdgeKey = edgeKey
                    hoveredGroupId = hoveredGroup?.id
                    highlightedEdgeKeys = edgeKeys
                    highlightedNodeIds = nodeIds
                    repaint()
                }
                cursor = Cursor.getPredefinedCursor(
                    if (hoveredGroup != null) {
                        Cursor.MOVE_CURSOR
                    } else if (hovered != null || edgeKey != null || hoveredNode != null) {
                        Cursor.HAND_CURSOR
                    } else {
                        Cursor.DEFAULT_CURSOR
                    },
                )
            }

            override fun mouseExited(event: MouseEvent) {
                if (
                    hoveredCallable != null || hoveredEdgeKey != null || hoveredGroupId != null ||
                    highlightedNodeIds.isNotEmpty() || highlightedEdgeKeys.isNotEmpty()
                ) {
                    hoveredCallable = null
                    hoveredEdgeKey = null
                    hoveredGroupId = null
                    highlightedNodeIds = emptySet()
                    highlightedEdgeKeys = emptySet()
                    repaint()
                }
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                lastPointerPoint = event.point
                val discreteMouseWheel = abs(event.wheelRotation) == 1 &&
                    abs(event.preciseWheelRotation - event.wheelRotation.toDouble()) < 0.001
                if (!discreteMouseWheel) {
                    pendingWheelZoomFactor = 1.0
                    mouseWheelZoomTimer.stop()
                    touchpadWheelSuppressedUntil = System.currentTimeMillis() + 300
                    val movement = event.preciseWheelRotation * JBUI.scale(18)
                    if (event.isShiftDown) {
                        offsetX -= movement
                        targetOffsetX = offsetX
                    } else {
                        offsetY -= movement
                        targetOffsetY = offsetY
                    }
                    event.consume()
                    if (isLargeGraph) detailRestoreTimer.restart()
                    repaint()
                    return
                }
                val factor = if (event.preciseWheelRotation < 0) 1.13 else 1.0 / 1.13
                if (!SystemInfo.isMac) {
                    zoomAt(event.point, targetScale * factor)
                    return
                }
                if (System.currentTimeMillis() < touchpadWheelSuppressedUntil) return
                pendingWheelZoomAnchor = event.point
                pendingWheelZoomFactor *= factor
                mouseWheelZoomTimer.restart()
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
        MacTrackpadMagnification.install(this) { magnification ->
            val anchor = lastPointerPoint ?: Point(width / 2, height / 2)
            zoomAt(anchor, targetScale * (1.0 + magnification).coerceAtLeast(0.1))
        }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (pendingInitialFit && width > 0 && height > 0) {
                    pendingInitialFit = false
                    fitGraph()
                } else if (snapshot.nodes.isNotEmpty()) {
                    resizeTimer.restart()
                }
            }
        })
    }

    private fun animateFrame() {
        var moving = false
        for ((id, target) in targetPositions) {
            val current = positions.getOrPut(id) { Point2D.Double(target.x, target.y) }
            val dx = target.x - current.x
            val dy = target.y - current.y
            if (abs(dx) > 0.15 || abs(dy) > 0.15) {
                current.x += dx * 0.22
                current.y += dy * 0.22
                moving = true
            } else {
                current.setLocation(target)
            }
        }

        val ds = targetScale - scale
        val dox = targetOffsetX - offsetX
        val doy = targetOffsetY - offsetY
        if (abs(ds) > 0.001 || abs(dox) > 0.2 || abs(doy) > 0.2) {
            scale += ds * 0.22
            offsetX += dox * 0.22
            offsetY += doy * 0.22
            moving = true
        } else {
            scale = targetScale
            offsetX = targetOffsetX
            offsetY = targetOffsetY
        }

        if (!moving) animationTimer.stop()
        repaint()
    }

    fun setSnapshot(value: GraphSnapshot) {
        val previousSnapshot = snapshot
        val previousGroups = outgoingGroups
        val previousIds = positions.keys.toSet()
        val newActiveNode = value.nodes.firstOrNull { node -> node.id !in previousIds && node.isActive }
        snapshot = value
        nodesById = value.nodes.associateBy(FileNode::id)
        outgoingGroups = OutgoingRelationGroups.find(value)
        outgoingGroupByMemberId = outgoingGroups.flatMap { group ->
            group.memberIds.map { memberId -> memberId to group }
        }.toMap()
        val mutableEdgeKeysByNode = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val mutableEdgeKeysByCallable = mutableMapOf<CallableRelation, MutableSet<Pair<String, String>>>()
        val mutableNodeIdsByEdgeKey = mutableMapOf<Pair<String, String>, Set<String>>()
        val groupedSourceEdges = outgoingGroups.flatMapTo(hashSetOf()) { group ->
            group.sharedRelations.flatMap(SharedOutgoingRelation::sourceEdges)
        }
        for (edge in value.edges.filterNot(groupedSourceEdges::contains)) {
            val key = if (edge.sourceId <= edge.targetId) {
                edge.sourceId to edge.targetId
            } else {
                edge.targetId to edge.sourceId
            }
            mutableNodeIdsByEdgeKey[key] = setOf(edge.sourceId, edge.targetId)
            mutableEdgeKeysByNode.getOrPut(edge.sourceId, ::mutableSetOf) += key
            mutableEdgeKeysByNode.getOrPut(edge.targetId, ::mutableSetOf) += key
            for (callable in edge.callables) {
                mutableEdgeKeysByCallable.getOrPut(callable, ::mutableSetOf) += key
            }
        }
        for (group in outgoingGroups) {
            for (relation in group.sharedRelations) {
                val key = group.id to relation.targetId
                val connectedNodeIds = group.memberIds.toSet() + relation.targetId
                mutableNodeIdsByEdgeKey[key] = connectedNodeIds
                for (nodeId in connectedNodeIds) {
                    mutableEdgeKeysByNode.getOrPut(nodeId, ::mutableSetOf) += key
                }
                for (callable in relation.callables) {
                    mutableEdgeKeysByCallable.getOrPut(callable, ::mutableSetOf) += key
                }
            }
        }
        edgeKeysByNode = mutableEdgeKeysByNode
        edgeKeysByCallable = mutableEdgeKeysByCallable
        nodeIdsByEdgeKey = mutableNodeIdsByEdgeKey
        hoveredCallable = null
        hoveredEdgeKey = null
        hoveredGroupId = null
        highlightedNodeIds = emptySet()
        highlightedEdgeKeys = emptySet()
        val automatic = automaticLayout(value)
        val currentIds = value.nodes.mapTo(mutableSetOf()) { it.id }
        val structureChanged = previousSnapshot.nodes.mapTo(mutableSetOf()) { it.id } != currentIds ||
            previousSnapshot.edges.map { edge -> edge.sourceId to edge.targetId } !=
            value.edges.map { edge -> edge.sourceId to edge.targetId } ||
            previousGroups.map { group -> group.id to group.memberIds } !=
            outgoingGroups.map { group -> group.id to group.memberIds }
        positions.keys.retainAll(currentIds)
        targetPositions.keys.retainAll(currentIds)

        for (node in value.nodes) {
            val target = automatic.getValue(node.id)
            if (node.id !in previousIds) {
                val parentPosition = value.edges.firstOrNull { it.targetId == node.id }
                    ?.sourceId
                    ?.let(positions::get)
                val targetOnScreen = worldToScreen(target)
                val isVisible = targetOnScreen.x in 0.0..width.toDouble() && targetOnScreen.y in 0.0..height.toDouble()
                positions[node.id] = if (parentPosition != null && isVisible) {
                    Point2D.Double(parentPosition.x, parentPosition.y)
                } else {
                    Point2D.Double(target.x, target.y)
                }
            }
            if (structureChanged || node.id !in targetPositions) {
                targetPositions[node.id] = Point2D.Double(target.x, target.y)
            }
        }

        if (previousIds.isEmpty() && value.nodes.isNotEmpty()) {
            positions.clear()
            positions.putAll(automatic.mapValues { Point2D.Double(it.value.x, it.value.y) })
            targetPositions.clear()
            targetPositions.putAll(automatic.mapValues { Point2D.Double(it.value.x, it.value.y) })
            if (width > 0 && height > 0) fitGraph() else pendingInitialFit = true
        } else {
            if (newActiveNode != null && width > 0 && height > 0) {
                val target = targetPositions[newActiveNode.id]
                if (target != null) {
                    val size = nodeSize(newActiveNode, getFontMetrics(font))
                    val padding = JBUI.scale(20).toDouble()
                    val screenLeft = (target.x - size.width / 2.0) * targetScale + targetOffsetX
                    val screenRight = (target.x + size.width / 2.0) * targetScale + targetOffsetX
                    val screenTop = target.y * targetScale + targetOffsetY
                    val screenBottom = (target.y + size.height) * targetScale + targetOffsetY

                    if (screenRight - screenLeft > width - padding * 2) {
                        targetOffsetX = width / 2.0 - target.x * targetScale
                    } else if (screenLeft < padding) {
                        targetOffsetX += padding - screenLeft
                    } else if (screenRight > width - padding) {
                        targetOffsetX -= screenRight - (width - padding)
                    }

                    if (screenBottom - screenTop > height - padding * 2) {
                        targetOffsetY = height / 2.0 - (target.y + size.height / 2.0) * targetScale
                    } else if (screenTop < padding) {
                        targetOffsetY += padding - screenTop
                    } else if (screenBottom > height - padding) {
                        targetOffsetY -= screenBottom - (height - padding)
                    }
                }
            }
            startAnimation()
        }
        repaint()
    }

    fun setActiveFiles(paths: Set<String>) {
        snapshot = snapshot.copy(
            nodes = snapshot.nodes.map { node -> node.copy(isActive = node.id in paths) },
        )
        nodesById = snapshot.nodes.associateBy(FileNode::id)
        repaint()
    }

    fun resetLayout() {
        val automatic = automaticLayout(snapshot)
        targetPositions.clear()
        targetPositions.putAll(automatic.mapValues { Point2D.Double(it.value.x, it.value.y) })
        startAnimation()
        fitGraph()
    }

    fun fitGraph() {
        if (snapshot.nodes.isEmpty() || width <= 0 || height <= 0) return
        val bounds = graphBounds(targetPositions)
        val padding = JBUI.scale(8).toDouble()
        val availableWidth = max(1.0, width - padding * 2)
        val availableHeight = max(1.0, height - padding * 2)
        targetScale = min(2.8, max(0.45, min(availableWidth / bounds.width, availableHeight / bounds.height)))
        targetOffsetX = width / 2.0 - bounds.centerX * targetScale
        targetOffsetY = height / 2.0 - bounds.centerY * targetScale
        startAnimation()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = CANVAS_BACKGROUND
        g.fillRect(0, 0, width, height)

        if (snapshot.nodes.isEmpty()) {
            g.color = JBColor.GRAY
            val message = "No Go file relations to display"
            val metrics = g.fontMetrics
            g.drawString(message, (width - metrics.stringWidth(message)) / 2, height / 2)
            g.dispose()
            return
        }

        g.transform(AffineTransform(scale, 0.0, 0.0, scale, offsetX, offsetY))
        nodeBounds.clear()
        groupBoundsById.clear()
        labelBounds.clear()
        labelGroupBounds.clear()
        edgeCurves.clear()
        val metrics = g.fontMetrics
        for (node in snapshot.nodes) {
            val position = positions[node.id] ?: continue
            val size = nodeSize(node, metrics)
            nodeBounds[node.id] = Rectangle2D.Double(
                position.x - size.width / 2.0,
                position.y,
                size.width.toDouble(),
                size.height.toDouble(),
            )
        }

        val groupVisuals = buildGroupVisuals(nodeBounds)
        for (visual in groupVisuals) groupBoundsById[visual.group.id] = visual.bounds
        drawGroupBackgrounds(g, groupVisuals)
        drawGroupMemberConnections(g, groupVisuals)
        drawEdges(g, metrics, groupVisuals)
        drawGroupCollectors(g, groupVisuals)
        for (node in snapshot.nodes) drawNode(g, metrics, node)
        g.dispose()
    }

    private fun drawEdges(g: Graphics2D, metrics: FontMetrics, groupVisuals: List<GroupVisual>) {
        val groupedSourceEdges = outgoingGroups.flatMapTo(hashSetOf()) { group ->
            group.sharedRelations.flatMap(SharedOutgoingRelation::sourceEdges)
        }
        val visualEdges = snapshot.edges.filterNot(groupedSourceEdges::contains).groupBy { edge ->
            if (edge.sourceId <= edge.targetId) {
                edge.sourceId to edge.targetId
            } else {
                edge.targetId to edge.sourceId
            }
        }.entries.mapNotNull { (edgeKey, pairedEdges) ->
            val primaryEdge = pairedEdges.minWith(compareBy(FileEdge::order, FileEdge::sourceId, FileEdge::targetId))
            val bidirectional = pairedEdges.any { candidate ->
                candidate.sourceId == primaryEdge.targetId && candidate.targetId == primaryEdge.sourceId
            }
            val callables = pairedEdges.sortedBy(FileEdge::order).flatMap(FileEdge::callables)
            val source = nodeBounds[primaryEdge.sourceId] ?: return@mapNotNull null
            val target = nodeBounds[primaryEdge.targetId] ?: return@mapNotNull null
            val downward = target.centerY >= source.centerY
            val x1 = source.centerX
            val y1 = if (downward) source.maxY else source.minY
            val x2 = target.centerX
            val y2 = if (downward) target.minY else target.maxY
            val middle = (y1 + y2) / 2.0
            val curve = CubicCurve2D.Double(x1, y1, x1, middle, x2, middle, x2, y2)
            VisualEdge(
                key = edgeKey,
                source = source,
                target = target,
                curve = curve,
                downward = downward,
                bidirectional = bidirectional,
                interfaceDispatch = callables.any(CallableRelation::isInterfaceDispatch),
                callbackArgument = callables.any(CallableRelation::isCallbackArgument),
                testRelation = pairedEdges.any { edge ->
                    nodesById[edge.sourceId]?.isTest == true || nodesById[edge.targetId]?.isTest == true
                },
                activeRelation = pairedEdges.any { edge ->
                    nodesById[edge.sourceId]?.isActive == true || nodesById[edge.targetId]?.isActive == true
                },
                order = pairedEdges.minOf(FileEdge::order),
                callables = callables,
            )
        }.toMutableList()
        val groupVisualsById = groupVisuals.associateBy { visual -> visual.group.id }
        for (group in outgoingGroups) {
            val groupVisual = groupVisualsById[group.id] ?: continue
            for (relation in group.sharedRelations) {
                val target = nodeBounds[relation.targetId] ?: continue
                val source = Rectangle2D.Double(
                    groupVisual.collector.x - 1.0,
                    groupVisual.collector.y - 1.0,
                    2.0,
                    2.0,
                )
                val downward = target.centerY >= source.centerY
                val x1 = source.centerX
                val y1 = source.centerY
                val x2 = target.centerX
                val y2 = if (downward) target.minY else target.maxY
                val middle = (y1 + y2) / 2.0
                visualEdges += VisualEdge(
                    key = group.id to relation.targetId,
                    source = source,
                    target = target,
                    curve = CubicCurve2D.Double(x1, y1, x1, middle, x2, middle, x2, y2),
                    downward = downward,
                    bidirectional = false,
                    interfaceDispatch = relation.callables.any(CallableRelation::isInterfaceDispatch),
                    callbackArgument = relation.callables.any(CallableRelation::isCallbackArgument),
                    testRelation = group.memberIds.any { memberId -> nodesById[memberId]?.isTest == true } ||
                        nodesById[relation.targetId]?.isTest == true,
                    activeRelation = group.memberIds.any { memberId -> nodesById[memberId]?.isActive == true } ||
                        nodesById[relation.targetId]?.isActive == true,
                    order = relation.order,
                    callables = relation.callables,
                )
            }
        }
        edgeCurves += visualEdges.map { edge -> edge.curve to edge.key }

        for (edge in visualEdges) {
            val highlighted = edge.key in highlightedEdgeKeys
            g.color = when {
                edge.testRelation -> TEST_EDGE
                edge.interfaceDispatch -> INTERFACE_EDGE
                edge.callbackArgument -> CALLBACK_EDGE
                else -> DIRECT_EDGE
            }
            g.stroke = if (edge.interfaceDispatch || edge.callbackArgument) {
                BasicStroke(
                    if (highlighted) 2.2f else 1.4f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    10f,
                    if (edge.interfaceDispatch) floatArrayOf(6f, 5f) else floatArrayOf(2f, 5f),
                    0f,
                )
            } else {
                BasicStroke(if (highlighted) 2.2f else 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            }
            g.draw(edge.curve)
            drawArrowHead(g, edge.target, edge.downward)
            if (edge.bidirectional) drawArrowHead(g, edge.source, !edge.downward)
        }

        if (!showLabels || isLargeGraph && (animationTimer.isRunning || detailRestoreTimer.isRunning)) return
        val labelEdges = visualEdges
            .filter { edge -> !edge.testRelation && edge.callables.isNotEmpty() }
            .sortedWith(
                compareByDescending<VisualEdge> { edge ->
                    edge.key in highlightedEdgeKeys
                }.thenByDescending(VisualEdge::activeRelation)
                    .thenByDescending { edge -> edge.callables.size }
                    .thenByDescending(VisualEdge::interfaceDispatch)
                    .thenByDescending(VisualEdge::callbackArgument)
                    .thenBy(VisualEdge::order),
            )
        for (edge in labelEdges) {
            val plannedGroup = planCallableLabels(
                metrics = metrics,
                callables = edge.callables,
                curve = edge.curve,
            )
            if (plannedGroup != null) {
                val plannedLabels = plannedGroup.labels
                val highlighted = edge.key in highlightedEdgeKeys
                val groupBounds = Rectangle2D.Double(
                    plannedLabels.minOf { label -> label.bounds.minX },
                    plannedLabels.minOf { label -> label.bounds.minY },
                    plannedLabels.maxOf { label -> label.bounds.maxX } -
                        plannedLabels.minOf { label -> label.bounds.minX },
                    plannedLabels.maxOf { label -> label.bounds.maxY } -
                        plannedLabels.minOf { label -> label.bounds.minY },
                )
                val anchor = plannedGroup.anchor
                if (!groupBounds.contains(anchor)) {
                    val connectionX = anchor.x.coerceIn(groupBounds.minX, groupBounds.maxX)
                    val connectionY = anchor.y.coerceIn(groupBounds.minY, groupBounds.maxY)
                    g.color = when {
                        edge.interfaceDispatch -> INTERFACE_EDGE
                        edge.callbackArgument -> CALLBACK_EDGE
                        else -> DIRECT_EDGE
                    }
                    g.stroke = BasicStroke(if (highlighted) 1.8f else 1.0f)
                    g.draw(Line2D.Double(anchor.x, anchor.y, connectionX, connectionY))
                    g.fill(Ellipse2D.Double(anchor.x - 2.0, anchor.y - 2.0, 4.0, 4.0))
                }
                for (label in plannedLabels) {
                    drawCallableLabel(g, label, highlighted)
                    labelBounds += label.bounds to label.callable
                }
                labelGroupBounds += groupBounds
            }
        }
    }

    private fun buildGroupVisuals(boundsByNode: Map<String, Rectangle2D.Double>): List<GroupVisual> =
        outgoingGroups.mapNotNull { group ->
            val memberBounds = group.memberIds.mapNotNull(boundsByNode::get)
            if (memberBounds.size != group.memberIds.size) return@mapNotNull null
            val minX = memberBounds.minOf(Rectangle2D.Double::getMinX)
            val maxX = memberBounds.maxOf(Rectangle2D.Double::getMaxX)
            val minY = memberBounds.minOf(Rectangle2D.Double::getMinY)
            val maxY = memberBounds.maxOf(Rectangle2D.Double::getMaxY)
            val bounds = Rectangle2D.Double(
                minX - OutgoingRelationGroups.HORIZONTAL_INSET,
                minY - OutgoingRelationGroups.TOP_INSET,
                maxX - minX + OutgoingRelationGroups.HORIZONTAL_INSET * 2,
                maxY - minY + OutgoingRelationGroups.TOP_INSET + OutgoingRelationGroups.BOTTOM_INSET,
            )
            GroupVisual(
                group = group,
                bounds = bounds,
                collector = Point2D.Double(bounds.centerX, maxY + OutgoingRelationGroups.BOTTOM_INSET / 2.0),
            )
        }

    private fun drawGroupBackgrounds(g: Graphics2D, groupVisuals: List<GroupVisual>) {
        val previousFont = g.font
        for (visual in groupVisuals) {
            val highlighted = visual.group.memberIds.any { memberId -> memberId in highlightedNodeIds }
            g.color = GROUP_BACKGROUND
            g.fillRoundRect(
                visual.bounds.x.toInt(),
                visual.bounds.y.toInt(),
                visual.bounds.width.toInt(),
                visual.bounds.height.toInt(),
                14,
                14,
            )
            g.color = if (highlighted) HOVER_NODE_BORDER else GROUP_BORDER
            g.stroke = BasicStroke(
                if (highlighted) 1.8f else 1.0f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(3f, 5f),
                0f,
            )
            g.drawRoundRect(
                visual.bounds.x.toInt(),
                visual.bounds.y.toInt(),
                visual.bounds.width.toInt(),
                visual.bounds.height.toInt(),
                14,
                14,
            )
            g.font = previousFont.deriveFont(max(9f, previousFont.size2D - 2f))
            g.color = GROUP_TEXT
            g.drawString(
                "${visual.group.memberIds.size} files · shared outgoing",
                (visual.bounds.x + 10.0).toFloat(),
                (visual.bounds.y + 16.0).toFloat(),
            )
        }
        g.font = previousFont
    }

    private fun drawGroupMemberConnections(g: Graphics2D, groupVisuals: List<GroupVisual>) {
        for (visual in groupVisuals) {
            val sharedKeys = visual.group.sharedRelations.mapTo(hashSetOf()) { relation ->
                visual.group.id to relation.targetId
            }
            val highlighted = sharedKeys.any(highlightedEdgeKeys::contains)
            g.color = if (highlighted) HOVER_NODE_BORDER else GROUP_CONNECTION
            g.stroke = BasicStroke(if (highlighted) 2.2f else 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (memberId in visual.group.memberIds) {
                val memberBounds = nodeBounds[memberId] ?: continue
                val bendY = visual.collector.y - 8.0
                val path = Path2D.Double()
                path.moveTo(memberBounds.centerX, memberBounds.maxY)
                path.lineTo(memberBounds.centerX, bendY)
                path.quadTo(memberBounds.centerX, visual.collector.y, visual.collector.x, visual.collector.y)
                g.draw(path)
            }
        }
    }

    private fun drawGroupCollectors(g: Graphics2D, groupVisuals: List<GroupVisual>) {
        for (visual in groupVisuals) {
            val highlighted = visual.group.sharedRelations.any { relation ->
                visual.group.id to relation.targetId in highlightedEdgeKeys
            }
            g.color = if (highlighted) HOVER_NODE_BORDER else GROUP_COLLECTOR
            g.fill(Ellipse2D.Double(visual.collector.x - 4.0, visual.collector.y - 4.0, 8.0, 8.0))
        }
    }

    private fun drawArrowHead(g: Graphics2D, target: Rectangle2D.Double, downward: Boolean) {
        val path = Path2D.Double()
        val x = target.centerX
        val y = if (downward) target.minY else target.maxY
        val direction = if (downward) -1 else 1
        path.moveTo(x, y)
        path.lineTo(x - 5, y + direction * 8)
        path.lineTo(x + 5, y + direction * 8)
        path.closePath()
        g.fill(path)
    }

    private fun planCallableLabels(
        metrics: FontMetrics,
        callables: List<CallableRelation>,
        curve: CubicCurve2D.Double,
    ): PlannedLabelGroup? {
        if (callables.isEmpty()) return null
        val normalHeight = metrics.height + 6.0
        val minimumHeight = JBUI.scale(12).toDouble()
        val nodeGap = JBUI.scale(3).toDouble()
        val normalLabelsGap = JBUI.scale(6).toDouble()
        val availableHeight = abs(curve.y2 - curve.y1)
        val availableForLabels = availableHeight - nodeGap * 2
        if (availableForLabels + 0.1 < minimumHeight * callables.size) return null

        val minimumY = min(curve.y1, curve.y2) + nodeGap
        val maximumY = max(curve.y1, curve.y2) - nodeGap
        var height = min(normalHeight, availableForLabels / callables.size)
        while (height + 0.1 >= minimumHeight) {
            val labelsGap = if (callables.size == 1) {
                0.0
            } else {
                min(
                    normalLabelsGap,
                    max(0.0, (availableForLabels - height * callables.size) / (callables.size - 1)),
                )
            }
            val labelsHeight = height * callables.size + labelsGap * (callables.size - 1)
            var labelFont = font
            var labelMetrics = metrics
            if (height < metrics.height) {
                var fontSize = font.size2D * (height / metrics.height).toFloat()
                labelFont = font.deriveFont(fontSize)
                labelMetrics = getFontMetrics(labelFont)
                while (labelMetrics.height > height && fontSize > 5f) {
                    fontSize -= 0.5f
                    labelFont = font.deriveFont(fontSize)
                    labelMetrics = getFontMetrics(labelFont)
                }
            }

            for (curvePosition in listOf(0.5, 0.42, 0.58, 0.34, 0.66)) {
                val anchor = pointOnCurve(curve, curvePosition)
                for (verticalOffset in listOf(0.0, -2.0, 2.0, -4.0, 4.0, -6.0, 6.0, -8.0, 8.0)) {
                    for (lane in listOf(0, 1, -1, 2, -2)) {
                        val group = callables.mapIndexed { index, callable ->
                            val width = labelMetrics.stringWidth(callable.label) + 18.0
                            PlannedCallableLabel(
                                bounds = Rectangle2D.Double(
                                    anchor.x - width / 2.0 + lane * 18.0,
                                    anchor.y - labelsHeight / 2.0 + verticalOffset + index * (height + labelsGap),
                                    width,
                                    height,
                                ),
                                callable = callable,
                                font = labelFont,
                            )
                        }
                        val fits = group.all { label ->
                            val bounds = label.bounds
                            val nodeClearanceBounds = Rectangle2D.Double(
                                bounds.x - nodeGap,
                                bounds.y - nodeGap,
                                bounds.width + nodeGap * 2,
                                bounds.height + nodeGap * 2,
                            )
                            bounds.minY >= minimumY &&
                                bounds.maxY <= maximumY &&
                                labelBounds.none { bounds.intersects(it.first) } &&
                                nodeBounds.values.none(nodeClearanceBounds::intersects)
                        }
                        if (fits) {
                            val groupBounds = Rectangle2D.Double(
                                group.minOf { label -> label.bounds.minX },
                                group.minOf { label -> label.bounds.minY },
                                group.maxOf { label -> label.bounds.maxX } - group.minOf { label -> label.bounds.minX },
                                group.maxOf { label -> label.bounds.maxY } - group.minOf { label -> label.bounds.minY },
                            )
                            val protectedGroupBounds = Rectangle2D.Double(
                                groupBounds.x - nodeGap,
                                groupBounds.y - nodeGap,
                                groupBounds.width + nodeGap * 2,
                                groupBounds.height + nodeGap * 2,
                            )
                            if (labelGroupBounds.none(protectedGroupBounds::intersects)) {
                                return PlannedLabelGroup(group, anchor)
                            }
                        }
                    }
                }
            }
            height -= 1.0
        }
        return null
    }

    private fun drawCallableLabel(
        g: Graphics2D,
        label: PlannedCallableLabel,
        highlighted: Boolean,
    ) {
        val bounds = label.bounds
        val callable = label.callable
        val previousFont = g.font
        g.font = label.font
        val metrics = g.fontMetrics
        g.color = when {
            callable.isInterfaceDispatch -> INTERFACE_LABEL_BACKGROUND
            callable.isCallbackArgument -> CALLBACK_LABEL_BACKGROUND
            else -> LABEL_BACKGROUND
        }
        g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 12, 12)
        g.color = when {
            callable.isInterfaceDispatch -> INTERFACE_EDGE
            callable.isCallbackArgument -> CALLBACK_EDGE
            else -> DIRECT_LABEL_TEXT
        }
        g.stroke = BasicStroke(if (highlighted) 1.8f else 1.0f)
        g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 12, 12)
        g.drawString(
            callable.label,
            (bounds.x + 9).toFloat(),
            (bounds.y + (bounds.height - metrics.height) / 2.0 + metrics.ascent).toFloat(),
        )
        g.font = previousFont
    }

    private fun pointOnCurve(curve: CubicCurve2D.Double, t: Double): Point2D.Double {
        val inverse = 1.0 - t
        return Point2D.Double(
            inverse * inverse * inverse * curve.x1 +
                3 * inverse * inverse * t * curve.ctrlX1 +
                3 * inverse * t * t * curve.ctrlX2 +
                t * t * t * curve.x2,
            inverse * inverse * inverse * curve.y1 +
                3 * inverse * inverse * t * curve.ctrlY1 +
                3 * inverse * t * t * curve.ctrlY2 +
                t * t * t * curve.y2,
        )
    }

    private fun drawNode(g: Graphics2D, metrics: FontMetrics, node: FileNode) {
        val bounds = nodeBounds[node.id] ?: return
        if (node.isPlaceholder) {
            g.color = PLACEHOLDER_BACKGROUND
            g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)
            g.color = PLACEHOLDER_BORDER
            g.stroke = BasicStroke(
                1.2f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(5f, 4f),
                0f,
            )
            g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)
            g.color = PLACEHOLDER_TEXT
            g.drawString(
                node.title,
                (bounds.x + 10).toFloat(),
                (bounds.y + (bounds.height - metrics.height) / 2 + metrics.ascent).toFloat(),
            )
            return
        }
        val highlighted = node.id in highlightedNodeIds
        g.color = when {
            node.isTest -> TEST_NODE_BACKGROUND
            node.isActive -> ACTIVE_NODE_BACKGROUND
            else -> NODE_BACKGROUND
        }
        g.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)
        g.color = when {
            highlighted -> HOVER_NODE_BORDER
            node.isActive -> ACTIVE_NODE_BORDER
            else -> NODE_BORDER
        }
        g.stroke = BasicStroke(if (highlighted) 2.2f else if (node.isActive) 1.8f else 1.0f)
        g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 8, 8)

        val icon = if (node.isTest) GoIcons.TEST_RUN else node.file.fileType.icon
        val iconX = bounds.x.toInt() + 10
        val iconY = bounds.y.toInt() + ((bounds.height - 16) / 2).toInt()
        icon?.paintIcon(this, g, iconX, iconY)
        g.color = NODE_TEXT
        g.drawString(
            node.title,
            (bounds.x + 34).toFloat(),
            (bounds.y + (bounds.height - metrics.height) / 2 + metrics.ascent).toFloat(),
        )
    }

    private fun nodeSize(node: FileNode, metrics: FontMetrics): Dimension {
        val horizontalPadding = if (node.isPlaceholder) JBUI.scale(20) else JBUI.scale(52)
        val width = max(JBUI.scale(132), metrics.stringWidth(node.title) + horizontalPadding)
        return Dimension(width, JBUI.scale(36))
    }

    private fun automaticLayout(value: GraphSnapshot): Map<String, Point2D.Double> {
        val metrics = getFontMetrics(font)
        return GraphLayoutEngine.layout(
            snapshot = value,
            labelHeight = metrics.height + 6.0,
            labelNodeGap = JBUI.scale(14).toDouble(),
            labelGap = JBUI.scale(6).toDouble(),
            edgeLabelWidth = { edge ->
                edge.callables.maxOfOrNull { callable -> metrics.stringWidth(callable.label) + 18.0 } ?: 0.0
            },
        ) { node -> nodeSize(node, metrics) }
    }

    private fun graphBounds(points: Map<String, Point2D.Double>): Rectangle2D.Double {
        if (points.isEmpty()) return Rectangle2D.Double(0.0, 0.0, 1.0, 1.0)
        val nodesById = snapshot.nodes.associateBy(FileNode::id)
        val metrics = getFontMetrics(font)
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        val boundsByNode = mutableMapOf<String, Rectangle2D.Double>()
        for ((id, point) in points) {
            val size = nodesById[id]?.let { nodeSize(it, metrics) } ?: Dimension(220, 42)
            boundsByNode[id] = Rectangle2D.Double(
                point.x - size.width / 2.0,
                point.y,
                size.width.toDouble(),
                size.height.toDouble(),
            )
            minX = min(minX, point.x - size.width / 2.0)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x + size.width / 2.0)
            maxY = max(maxY, point.y + size.height)
        }
        val groupVisuals = buildGroupVisuals(boundsByNode)
        for (visual in groupVisuals) {
            minX = min(minX, visual.bounds.minX)
            minY = min(minY, visual.bounds.minY)
            maxX = max(maxX, visual.bounds.maxX)
            maxY = max(maxY, visual.bounds.maxY)
        }
        val groupedSourceEdges = outgoingGroups.flatMapTo(hashSetOf()) { group ->
            group.sharedRelations.flatMap(SharedOutgoingRelation::sourceEdges)
        }
        for (edge in snapshot.edges.filterNot(groupedSourceEdges::contains)) {
            if (nodesById[edge.sourceId]?.isTest == true || nodesById[edge.targetId]?.isTest == true) continue
            val source = points[edge.sourceId] ?: continue
            val target = points[edge.targetId] ?: continue
            val sourceNode = nodesById[edge.sourceId] ?: continue
            val targetNode = nodesById[edge.targetId] ?: continue
            val sourceSize = nodeSize(sourceNode, metrics)
            val targetSize = nodeSize(targetNode, metrics)
            val centerX = (source.x + target.x) / 2.0
            val labelWidth = edge.callables.maxOfOrNull { callable ->
                metrics.stringWidth(callable.label) + 18.0
            } ?: continue
            minX = min(minX, centerX - labelWidth / 2.0)
            maxX = max(maxX, centerX + labelWidth / 2.0)
            minY = min(minY, min(source.y + sourceSize.height, target.y + targetSize.height))
            maxY = max(maxY, max(source.y, target.y))
        }
        val groupVisualsById = groupVisuals.associateBy { visual -> visual.group.id }
        for (group in outgoingGroups) {
            val source = groupVisualsById[group.id]?.collector ?: continue
            for (relation in group.sharedRelations) {
                if (
                    group.memberIds.any { memberId -> nodesById[memberId]?.isTest == true } ||
                    nodesById[relation.targetId]?.isTest == true
                ) {
                    continue
                }
                val target = points[relation.targetId] ?: continue
                val labelWidth = relation.callables.maxOfOrNull { callable ->
                    metrics.stringWidth(callable.label) + 18.0
                } ?: continue
                val centerX = (source.x + target.x) / 2.0
                minX = min(minX, centerX - labelWidth / 2.0)
                maxX = max(maxX, centerX + labelWidth / 2.0)
                minY = min(minY, min(source.y, target.y))
                maxY = max(maxY, max(source.y, target.y))
            }
        }
        return Rectangle2D.Double(minX, minY, max(1.0, maxX - minX), max(1.0, maxY - minY))
    }

    private fun callableAt(screenPoint: Point): CallableRelation? {
        val world = screenToWorld(screenPoint)
        return labelBounds.asReversed().firstOrNull { it.first.contains(world) }?.second
    }

    private fun edgeAt(screenPoint: Point): Pair<String, String>? {
        val world = screenToWorld(screenPoint)
        val tolerance = JBUI.scale(6).toDouble() / scale
        for ((curve, key) in edgeCurves.asReversed()) {
            val bounds = curve.bounds2D
            if (!Rectangle2D.Double(
                    bounds.x - tolerance,
                    bounds.y - tolerance,
                    bounds.width + tolerance * 2,
                    bounds.height + tolerance * 2,
                ).contains(world)
            ) {
                continue
            }
            val iterator = curve.getPathIterator(null, 1.0)
            val coordinates = DoubleArray(6)
            var previousX = 0.0
            var previousY = 0.0
            while (!iterator.isDone) {
                when (iterator.currentSegment(coordinates)) {
                    PathIterator.SEG_MOVETO -> {
                        previousX = coordinates[0]
                        previousY = coordinates[1]
                    }

                    PathIterator.SEG_LINETO -> {
                        if (
                            Line2D.ptSegDist(
                                previousX,
                                previousY,
                                coordinates[0],
                                coordinates[1],
                                world.x,
                                world.y,
                            ) <= tolerance
                        ) {
                            return key
                        }
                        previousX = coordinates[0]
                        previousY = coordinates[1]
                    }
                }
                iterator.next()
            }
        }
        return null
    }

    private fun nodeAt(screenPoint: Point): FileNode? {
        val world = screenToWorld(screenPoint)
        val id = nodeBounds.entries.firstOrNull { it.value.contains(world) }?.key ?: return null
        return nodesById[id]
    }

    private fun groupAt(screenPoint: Point): OutgoingRelationGroup? {
        val world = screenToWorld(screenPoint)
        val groupId = groupBoundsById.entries.firstOrNull { (_, bounds) -> bounds.contains(world) }?.key ?: return null
        return outgoingGroups.firstOrNull { group -> group.id == groupId }
    }

    private fun screenToWorld(point: Point): Point2D.Double = Point2D.Double(
        (point.x - offsetX) / scale,
        (point.y - offsetY) / scale,
    )

    private fun worldToScreen(point: Point2D.Double): Point2D.Double = Point2D.Double(
        point.x * scale + offsetX,
        point.y * scale + offsetY,
    )

    private fun zoomAt(anchor: Point, requestedScale: Double) {
        val worldAnchor = screenToWorld(anchor)
        targetScale = requestedScale.coerceIn(0.2, 4.0)
        targetOffsetX = anchor.x - worldAnchor.x * targetScale
        targetOffsetY = anchor.y - worldAnchor.y * targetScale
        startAnimation()
    }

    private fun startAnimation() {
        if (!animationTimer.isRunning) animationTimer.start()
    }

    private val isLargeGraph: Boolean
        get() = snapshot.nodes.size > 36 || snapshot.edges.size > 72

    private data class PlannedCallableLabel(
        val bounds: Rectangle2D.Double,
        val callable: CallableRelation,
        val font: Font,
    )

    private data class PlannedLabelGroup(
        val labels: List<PlannedCallableLabel>,
        val anchor: Point2D.Double,
    )

    private data class GroupVisual(
        val group: OutgoingRelationGroup,
        val bounds: Rectangle2D.Double,
        val collector: Point2D.Double,
    )

    private data class VisualEdge(
        val key: Pair<String, String>,
        val source: Rectangle2D.Double,
        val target: Rectangle2D.Double,
        val curve: CubicCurve2D.Double,
        val downward: Boolean,
        val bidirectional: Boolean,
        val interfaceDispatch: Boolean,
        val callbackArgument: Boolean,
        val testRelation: Boolean,
        val activeRelation: Boolean,
        val order: Int,
        val callables: List<CallableRelation>,
    )

    companion object {
        private val CANVAS_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.canvas", JBColor(0xF7F8FA, 0x1E1F22))
        private val NODE_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.node", JBColor(0xFFFFFF, 0x2B2D30))
        private val ACTIVE_NODE_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.nodeActive", JBColor(0xEAF2FF, 0x2D3F63))
        private val TEST_NODE_BACKGROUND = JBColor.namedColor("FileColor.Green", JBColor(0xE7F4E8, 0x29402F))
        private val GROUP_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.group", JBColor(0xF1F3F7, 0x24262B))
        private val GROUP_BORDER = JBColor.namedColor("GoFileRelationGraph.groupBorder", JBColor(0xA8ACB5, 0x4D5058))
        private val GROUP_TEXT = JBColor.namedColor("GoFileRelationGraph.groupText", JBColor(0x6C707E, 0x9DA0A8))
        private val GROUP_CONNECTION = JBColor.namedColor("GoFileRelationGraph.groupConnection", JBColor(0x8B8D94, 0x656870))
        private val GROUP_COLLECTOR = JBColor.namedColor("GoFileRelationGraph.groupCollector", JBColor(0x7A5AF8, 0xA78BFA))
        private val NODE_BORDER = JBColor.namedColor("GoFileRelationGraph.nodeBorder", JBColor(0xB8BCC4, 0x5A5D63))
        private val ACTIVE_NODE_BORDER = JBColor.namedColor("GoFileRelationGraph.nodeActiveBorder", JBColor(0x3574F0, 0x548AF7))
        private val HOVER_NODE_BORDER = JBColor.namedColor("GoFileRelationGraph.nodeHoverBorder", JBColor(0x6C707E, 0xFFFFFF))
        private val NODE_TEXT = JBColor.namedColor("GoFileRelationGraph.nodeText", JBColor(0x1F2329, 0xDFE1E5))
        private val PLACEHOLDER_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.placeholder", JBColor(0xFFF8E4, 0x332E20))
        private val PLACEHOLDER_BORDER = JBColor.namedColor("GoFileRelationGraph.placeholderBorder", JBColor(0xB48A38, 0xC99C48))
        private val PLACEHOLDER_TEXT = JBColor.namedColor("GoFileRelationGraph.placeholderText", JBColor(0x75570F, 0xD6B35E))
        private val DIRECT_EDGE = JBColor.namedColor("GoFileRelationGraph.edge", JBColor(0x6C707E, 0x8B8D94))
        private val INTERFACE_EDGE = JBColor.namedColor("GoFileRelationGraph.interfaceEdge", JBColor(0x7A5AF8, 0xA78BFA))
        private val CALLBACK_EDGE = JBColor.namedColor("GoFileRelationGraph.callbackEdge", JBColor(0x277D91, 0x62B8CA))
        private val TEST_EDGE = JBColor.namedColor("GoFileRelationGraph.testEdge", JBColor(0x4E9657, 0x6AAB73))
        private val LABEL_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.label", JBColor(0xF0F4FA, 0x25282E))
        private val INTERFACE_LABEL_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.interfaceLabel", JBColor(0xF1EDFF, 0x302A42))
        private val CALLBACK_LABEL_BACKGROUND = JBColor.namedColor("GoFileRelationGraph.callbackLabel", JBColor(0xE7F5F8, 0x20343A))
        private val DIRECT_LABEL_TEXT = JBColor.namedColor("GoFileRelationGraph.labelText", JBColor(0x315F9B, 0x9CC2FF))
    }
}
