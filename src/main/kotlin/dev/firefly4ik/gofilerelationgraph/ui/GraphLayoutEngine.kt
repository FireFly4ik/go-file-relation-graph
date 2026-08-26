package dev.firefly4ik.gofilerelationgraph.ui

import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.FileNode
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import java.awt.Dimension
import java.awt.geom.Point2D

object GraphLayoutEngine {
    private const val MIN_LEVEL_GAP = 42.0
    private const val CROSS_GAP = 24.0
    private const val COMPONENT_GAP = 43.0

    fun layout(
        snapshot: GraphSnapshot,
        labelHeight: Double = 25.0,
        labelNodeGap: Double = 14.0,
        labelGap: Double = 6.0,
        edgeLabelWidth: (dev.firefly4ik.gofilerelationgraph.model.FileEdge) -> Double = { 0.0 },
        nodeSize: (FileNode) -> Dimension = { Dimension(132, 36) },
    ): Map<String, Point2D.Double> {
        if (snapshot.nodes.isEmpty()) return emptyMap()

        val allNodeIds = snapshot.nodes.mapTo(linkedSetOf()) { it.id }
        val adjacency = allNodeIds.associateWith { mutableSetOf<String>() }
        for (edge in snapshot.edges) {
            if (edge.sourceId !in allNodeIds || edge.targetId !in allNodeIds) continue
            adjacency.getValue(edge.sourceId) += edge.targetId
            adjacency.getValue(edge.targetId) += edge.sourceId
        }
        val unseen = allNodeIds.toMutableSet()
        val components = mutableListOf<Set<String>>()
        while (unseen.isNotEmpty()) {
            val component = linkedSetOf<String>()
            val queue = ArrayDeque<String>()
            queue += unseen.first()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!component.add(id)) continue
                unseen -= id
                adjacency.getValue(id).filterTo(queue) { neighbor -> neighbor !in component }
            }
            components += component
        }
        if (components.size > 1) {
            val layouts = components.map { component ->
                val componentNodes = snapshot.nodes.filter { node -> node.id in component }
                val componentEdges = snapshot.edges.filter { edge ->
                    edge.sourceId in component && edge.targetId in component
                }
                val componentSnapshot = GraphSnapshot(componentNodes, componentEdges)
                val points = layout(
                    snapshot = componentSnapshot,
                    labelHeight = labelHeight,
                    labelNodeGap = labelNodeGap,
                    labelGap = labelGap,
                    edgeLabelWidth = edgeLabelWidth,
                    nodeSize = nodeSize,
                )
                var minX = componentNodes.minOf { node ->
                    points.getValue(node.id).x - nodeSize(node).width / 2.0
                }
                var maxX = componentNodes.maxOf { node ->
                    points.getValue(node.id).x + nodeSize(node).width / 2.0
                }
                val testById = componentNodes.associate { node -> node.id to node.isTest }
                for (edge in componentEdges) {
                    if (testById[edge.sourceId] == true || testById[edge.targetId] == true) continue
                    val source = points.getValue(edge.sourceId)
                    val target = points.getValue(edge.targetId)
                    val width = edgeLabelWidth(edge)
                    minX = minOf(minX, (source.x + target.x) / 2.0 - width / 2.0)
                    maxX = maxOf(maxX, (source.x + target.x) / 2.0 + width / 2.0)
                }
                ComponentLayout(component, points, minX, maxX)
            }.sortedWith(
                compareByDescending<ComponentLayout> { component -> component.nodeIds.size }
                    .thenBy { component -> component.nodeIds.min() },
            )

            val result = mutableMapOf<String, Point2D.Double>()
            val main = layouts.first()
            val mainShift = -(main.minX + main.maxX) / 2.0
            for ((id, point) in main.points) result[id] = Point2D.Double(point.x + mainShift, point.y)
            var leftEdge = main.minX + mainShift
            var rightEdge = main.maxX + mainShift
            for (component in layouts.drop(1)) {
                val placeOnLeft = -leftEdge < rightEdge
                val shift = if (placeOnLeft) {
                    leftEdge - COMPONENT_GAP - component.maxX
                } else {
                    rightEdge + COMPONENT_GAP - component.minX
                }
                for ((id, point) in component.points) result[id] = Point2D.Double(point.x + shift, point.y)
                leftEdge = minOf(leftEdge, component.minX + shift)
                rightEdge = maxOf(rightEdge, component.maxX + shift)
            }
            return result
        }

        val nodeIds = snapshot.nodes.mapTo(linkedSetOf()) { it.id }
        val titleById = snapshot.nodes.associate { it.id to it.title }
        val testById = snapshot.nodes.associate { it.id to it.isTest }
        val siblingOrder = nodeIds.associateWith { id ->
            val allIncoming = snapshot.edges.filter { edge -> edge.targetId == id }
            val preferredIncoming = if (testById[id] == false) {
                allIncoming.filter { edge -> testById[edge.sourceId] == false }.ifEmpty { allIncoming }
            } else {
                allIncoming
            }
            val ranks = preferredIncoming.map { incomingEdge ->
                snapshot.edges.filter { candidate ->
                    candidate.sourceId == incomingEdge.sourceId &&
                        (testById[id] == true || testById[candidate.targetId] == false)
                }
                    .sortedWith(compareBy(FileEdge::order, FileEdge::targetId))
                    .indexOf(incomingEdge)
            }
            ranks.average().takeUnless(Double::isNaN) ?: Double.MAX_VALUE
        }
        val outgoing = nodeIds.associateWith { mutableListOf<String>() }
        val incoming = nodeIds.associateWith { mutableListOf<String>() }
        val weights = mutableMapOf<Pair<String, String>, Int>()
        for (edge in snapshot.edges) {
            if (edge.sourceId !in nodeIds || edge.targetId !in nodeIds) continue
            if (edge.targetId !in outgoing.getValue(edge.sourceId)) {
                outgoing.getValue(edge.sourceId) += edge.targetId
                incoming.getValue(edge.targetId) += edge.sourceId
            }
            weights[edge.sourceId to edge.targetId] = edge.callables.sumOf { callable ->
                if (callable.isInterfaceDispatch || callable.isCallbackArgument) 1 else 4
            }.coerceAtLeast(1)
        }

        val remaining = nodeIds.toMutableSet()
        val orderedStart = mutableListOf<String>()
        val orderedEnd = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val source = remaining
                .filter { id -> incoming.getValue(id).none { it in remaining } }
                .minByOrNull(titleById::getValue)
            if (source != null) {
                orderedStart += source
                remaining -= source
                continue
            }

            val sink = remaining
                .filter { id -> outgoing.getValue(id).none { it in remaining } }
                .minByOrNull(titleById::getValue)
            if (sink != null) {
                orderedEnd.add(0, sink)
                remaining -= sink
                continue
            }

            val selected = remaining.maxWith(
                compareBy<String> { id ->
                    outgoing.getValue(id).filter { it in remaining }
                        .sumOf { target -> weights[id to target] ?: 1 } -
                        incoming.getValue(id).filter { it in remaining }
                            .sumOf { parent -> weights[parent to id] ?: 1 }
                }.thenByDescending(titleById::getValue),
            )
            orderedStart += selected
            remaining -= selected
        }
        val ordered = orderedStart + orderedEnd
        val orderIndex = ordered.withIndex().associate { (index, id) -> id to index }
        val forwardOutgoing = nodeIds.associateWith { id ->
            outgoing.getValue(id).filter { target -> orderIndex.getValue(id) < orderIndex.getValue(target) }
        }
        val forwardIncoming = nodeIds.associateWith { id ->
            incoming.getValue(id).filter { parent -> orderIndex.getValue(parent) < orderIndex.getValue(id) }
        }

        val explicitLevels = snapshot.nodes
            .mapNotNull { node -> node.layoutLevel?.let { level -> node.id to level } }
            .toMap()
            .takeIf { levelsById -> levelsById.size == nodeIds.size }
        val levels = if (explicitLevels != null) {
            explicitLevels.toMutableMap()
        } else {
            val inferred = nodeIds.associateWith { 0 }.toMutableMap()
            for (source in ordered) {
                for (target in forwardOutgoing.getValue(source)) {
                    inferred[target] = maxOf(inferred.getValue(target), inferred.getValue(source) + 1)
                }
            }
            for (source in ordered.asReversed()) {
                val latestLevel = forwardOutgoing.getValue(source)
                    .minOfOrNull { target -> inferred.getValue(target) - 1 }
                    ?: continue
                val earliestLevel = forwardIncoming.getValue(source)
                    .maxOfOrNull { parent -> inferred.getValue(parent) + 1 }
                    ?: 0
                if (latestLevel >= earliestLevel) {
                    inferred[source] = maxOf(inferred.getValue(source), latestLevel)
                }
            }
            inferred
        }

        val byLevel = nodeIds.groupBy(levels::getValue).toSortedMap()
            .mapValuesTo(sortedMapOf()) { (_, ids) ->
                ids.sortedBy(titleById::getValue).toMutableList()
            }
        val rank = mutableMapOf<String, Double>()
        for (ids in byLevel.values) ids.forEachIndexed { index, id -> rank[id] = index.toDouble() }

        for ((_, ids) in byLevel) {
            ids.sortWith(compareBy<String> { id ->
                val allParents = forwardIncoming.getValue(id)
                val preferredParents = if (testById[id] == false) {
                    allParents.filter { parent -> testById[parent] == false }.ifEmpty { allParents }
                } else {
                    allParents
                }
                preferredParents.mapNotNull(rank::get).average().takeUnless(Double::isNaN)
                    ?: rank.getValue(id)
            }.thenBy(siblingOrder::getValue).thenBy(titleById::getValue))
            ids.forEachIndexed { index, id -> rank[id] = index.toDouble() }
        }

        val forwardEdges = snapshot.edges.filter { edge ->
            edge.sourceId in nodeIds && edge.targetId in nodeIds &&
                orderIndex.getValue(edge.sourceId) < orderIndex.getValue(edge.targetId)
        }
        val productionEdges = forwardEdges.filter { edge ->
            testById[edge.sourceId] == false && testById[edge.targetId] == false
        }
        val layoutCost = {
            val normalizedRank = nodeIds.associateWith { id ->
                val ids = byLevel.getValue(levels.getValue(id))
                (rank.getValue(id) + 0.5) / ids.size
            }
            var callOrderViolations = 0
            for (edges in forwardEdges.groupBy(FileEdge::sourceId).values) {
                for (firstIndex in 0 until edges.lastIndex) {
                    for (secondIndex in firstIndex + 1 until edges.size) {
                        val first = edges[firstIndex]
                        val second = edges[secondIndex]
                        if (first.targetId == second.targetId || first.order == second.order) continue
                        val expected = first.order.compareTo(second.order)
                        val actual = normalizedRank.getValue(first.targetId)
                            .compareTo(normalizedRank.getValue(second.targetId))
                        if (expected != actual) callOrderViolations++
                    }
                }
            }

            val crossingCount = { edges: List<FileEdge> ->
                var crossings = 0
                for (firstIndex in 0 until edges.lastIndex) {
                    val first = edges[firstIndex]
                    val firstSourceLevel = levels.getValue(first.sourceId)
                    val firstTargetLevel = levels.getValue(first.targetId)
                    for (secondIndex in firstIndex + 1 until edges.size) {
                        val second = edges[secondIndex]
                        if (first.sourceId == second.sourceId || first.targetId == second.targetId) continue
                        val secondSourceLevel = levels.getValue(second.sourceId)
                        val secondTargetLevel = levels.getValue(second.targetId)
                        val firstBoundary = maxOf(firstSourceLevel, secondSourceLevel)
                        val lastBoundary = minOf(firstTargetLevel, secondTargetLevel) - 1
                        for (boundary in firstBoundary..lastBoundary) {
                            val firstTopRatio = (boundary - firstSourceLevel).toDouble() /
                                (firstTargetLevel - firstSourceLevel)
                            val firstBottomRatio = (boundary + 1 - firstSourceLevel).toDouble() /
                                (firstTargetLevel - firstSourceLevel)
                            val secondTopRatio = (boundary - secondSourceLevel).toDouble() /
                                (secondTargetLevel - secondSourceLevel)
                            val secondBottomRatio = (boundary + 1 - secondSourceLevel).toDouble() /
                                (secondTargetLevel - secondSourceLevel)
                            val firstTop = normalizedRank.getValue(first.sourceId) +
                                (normalizedRank.getValue(first.targetId) - normalizedRank.getValue(first.sourceId)) *
                                firstTopRatio
                            val firstBottom = normalizedRank.getValue(first.sourceId) +
                                (normalizedRank.getValue(first.targetId) - normalizedRank.getValue(first.sourceId)) *
                                firstBottomRatio
                            val secondTop = normalizedRank.getValue(second.sourceId) +
                                (normalizedRank.getValue(second.targetId) - normalizedRank.getValue(second.sourceId)) *
                                secondTopRatio
                            val secondBottom = normalizedRank.getValue(second.sourceId) +
                                (normalizedRank.getValue(second.targetId) - normalizedRank.getValue(second.sourceId)) *
                                secondBottomRatio
                            if ((firstTop - secondTop) * (firstBottom - secondBottom) < -0.000001) crossings++
                        }
                    }
                }
                crossings
            }
            val productionCrossings = crossingCount(productionEdges)
            val allCrossings = crossingCount(forwardEdges)
            val productionSpan = productionEdges.sumOf { edge ->
                kotlin.math.abs(normalizedRank.getValue(edge.sourceId) - normalizedRank.getValue(edge.targetId))
            }
            val allSpan = forwardEdges.sumOf { edge ->
                kotlin.math.abs(normalizedRank.getValue(edge.sourceId) - normalizedRank.getValue(edge.targetId))
            }
            callOrderViolations * 1_000_000_000_000_000.0 +
                productionCrossings * 1_000_000_000_000.0 +
                productionSpan * 1_000_000_000.0 +
                allCrossings * 1_000_000.0 +
                allSpan
        }

        val optimizationPasses = if (
            nodeIds.size <= MAX_OPTIMIZED_NODES && forwardEdges.size <= MAX_OPTIMIZED_EDGES
        ) {
            4
        } else {
            0
        }
        repeat(optimizationPasses) { pass ->
            val levelsToOptimize = if (pass % 2 == 0) byLevel.values else byLevel.values.reversed()
            for (ids in levelsToOptimize) {
                var index = 0
                while (index < ids.lastIndex) {
                    val before = layoutCost()
                    val left = ids[index]
                    val right = ids[index + 1]
                    ids[index] = right
                    ids[index + 1] = left
                    rank[right] = index.toDouble()
                    rank[left] = (index + 1).toDouble()
                    if (layoutCost() + 0.0001 < before) {
                        if (index > 0) index-- else index++
                    } else {
                        ids[index] = left
                        ids[index + 1] = right
                        rank[left] = index.toDouble()
                        rank[right] = (index + 1).toDouble()
                        index++
                    }
                }
            }
        }

        val sizes = snapshot.nodes.associate { it.id to nodeSize(it) }
        val incomingAtLevelCount = snapshot.edges.groupingBy { edge ->
            edge.targetId to levels[edge.sourceId]
        }.eachCount()
        val outgoingAtLevelCount = snapshot.edges.groupingBy { edge ->
            edge.sourceId to levels[edge.targetId]
        }.eachCount()
        val edgesByNode = nodeIds.associateWith { id ->
            snapshot.edges.filter { edge -> edge.sourceId == id || edge.targetId == id }
        }
        val labelFootprints = nodeIds.associateWith { id ->
            edgesByNode.getValue(id)
                .asSequence()
                .maxOfOrNull { edge ->
                    if (testById[edge.sourceId] == true || testById[edge.targetId] == true) {
                        return@maxOfOrNull 0.0
                    }
                    val oppositeId = if (edge.sourceId == id) edge.targetId else edge.sourceId
                    val sharesOppositeEndpoint = if (edge.sourceId == id) {
                        incomingAtLevelCount.getOrDefault(oppositeId to levels[id], 0) > 1
                    } else {
                        outgoingAtLevelCount.getOrDefault(oppositeId to levels[id], 0) > 1
                    }
                    edgeLabelWidth(edge) * if (sharesOppositeEndpoint) 2.0 else 1.0
                }
                ?: 0.0
        }
        val result = mutableMapOf<String, Point2D.Double>()
        val visualEdgeGroups = snapshot.edges.groupBy { edge ->
            if (edge.sourceId <= edge.targetId) {
                edge.sourceId to edge.targetId
            } else {
                edge.targetId to edge.sourceId
            }
        }
        var depth = 0.0
        var previousHeight = 0.0
        var previousLevel: Int? = null
        for ((level, ids) in byLevel) {
            val precedingLevel = previousLevel
            if (precedingLevel != null) {
                val requiredGap = visualEdgeGroups.values
                    .mapNotNull { edges ->
                        val edge = edges.first()
                        val sourceLevel = levels[edge.sourceId] ?: return@mapNotNull null
                        val targetLevel = levels[edge.targetId] ?: return@mapNotNull null
                        val minimumLevel = minOf(sourceLevel, targetLevel)
                        val maximumLevel = maxOf(sourceLevel, targetLevel)
                        if (precedingLevel !in minimumLevel until maximumLevel || level > maximumLevel) {
                            return@mapNotNull null
                        }
                        val labels = edges
                            .filterNot { groupedEdge ->
                                testById[groupedEdge.sourceId] == true || testById[groupedEdge.targetId] == true
                            }
                            .sumOf { groupedEdge -> groupedEdge.callables.size }
                        if (labels == 0) return@mapNotNull null
                        val requiredHeight = labelNodeGap * 2 + labelHeight * labels + labelGap * (labels - 1)
                        requiredHeight / (maximumLevel - minimumLevel).coerceAtLeast(1)
                    }
                    .maxOrNull()
                    ?: 0.0
                depth += previousHeight + maxOf(MIN_LEVEL_GAP, requiredGap) + 4.0
            }
            previousHeight = ids.maxOf { id -> sizes.getValue(id).height }.toDouble()
            val slotWidths = ids.associateWith { id ->
                maxOf(sizes.getValue(id).width.toDouble(), labelFootprints.getValue(id))
            }
            val totalWidth = ids.sumOf(slotWidths::getValue) +
                CROSS_GAP * (ids.size - 1).coerceAtLeast(0)
            var cursor = -totalWidth / 2.0
            for (id in ids) {
                val width = slotWidths.getValue(id)
                result[id] = Point2D.Double(cursor + width / 2.0, depth)
                cursor += width + CROSS_GAP
            }
            previousLevel = level
        }
        return result
    }

    private data class ComponentLayout(
        val nodeIds: Set<String>,
        val points: Map<String, Point2D.Double>,
        val minX: Double,
        val maxX: Double,
    )

    private const val MAX_OPTIMIZED_NODES = 36
    private const val MAX_OPTIMIZED_EDGES = 72
}
