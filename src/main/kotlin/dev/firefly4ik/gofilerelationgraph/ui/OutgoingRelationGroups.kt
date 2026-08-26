package dev.firefly4ik.gofilerelationgraph.ui

import dev.firefly4ik.gofilerelationgraph.model.CallableRelation
import dev.firefly4ik.gofilerelationgraph.model.FileEdge
import dev.firefly4ik.gofilerelationgraph.model.GraphSnapshot
import dev.firefly4ik.gofilerelationgraph.model.RelationKind

internal data class OutgoingRelationGroup(
    val id: String,
    val memberIds: List<String>,
    val sharedRelations: List<SharedOutgoingRelation>,
)

internal data class SharedOutgoingRelation(
    val targetId: String,
    val sourceEdges: List<FileEdge>,
    val callables: List<CallableRelation>,
    val order: Int,
)

internal object OutgoingRelationGroups {
    const val MEMBER_GAP = 14.0
    const val HORIZONTAL_INSET = 12.0
    const val TOP_INSET = 24.0
    const val BOTTOM_INSET = 34.0

    fun find(snapshot: GraphSnapshot): List<OutgoingRelationGroup> {
        if (snapshot.nodes.size < 2 || snapshot.edges.isEmpty()) return emptyList()

        val nodesById = snapshot.nodes.associateBy { node -> node.id }
        val validEdges = snapshot.edges.filter { edge ->
            edge.sourceId in nodesById && edge.targetId in nodesById
        }
        val directions = validEdges.mapTo(hashSetOf()) { edge -> edge.sourceId to edge.targetId }
        val edgesBySource = validEdges.groupBy(FileEdge::sourceId)
        val candidates = snapshot.nodes.mapNotNull { node ->
            if (node.isPlaceholder) return@mapNotNull null
            val outgoing = edgesBySource[node.id].orEmpty()
                .sortedWith(compareBy(FileEdge::order, FileEdge::targetId))
            if (
                outgoing.isEmpty() || outgoing.any { edge ->
                    edge.callables.isEmpty() || edge.targetId to edge.sourceId in directions
                }
            ) {
                return@mapNotNull null
            }
            val signature = outgoing.map { edge ->
                OutgoingEdgeSignature(
                    targetId = edge.targetId,
                    callables = edge.callables
                        .map { callable -> CallableSignature(callable.label, callable.kind) }
                        .sortedWith(compareBy(CallableSignature::label, CallableSignature::kind)),
                )
            }
            node.id to GroupingKey(node.layoutLevel, node.isTest, signature)
        }

        return candidates.groupBy(Pair<String, GroupingKey>::second)
            .values
            .asSequence()
            .map { entries -> entries.map(Pair<String, GroupingKey>::first).sorted() }
            .filter { memberIds -> memberIds.size > 1 }
            .map { memberIds ->
                val representativeEdges = edgesBySource.getValue(memberIds.first())
                    .sortedWith(compareBy(FileEdge::order, FileEdge::targetId))
                val sharedRelations = representativeEdges.mapIndexed { index, representative ->
                    val sourceEdges = memberIds.map { memberId ->
                        edgesBySource.getValue(memberId)
                            .sortedWith(compareBy(FileEdge::order, FileEdge::targetId))[index]
                    }
                    val callables = representative.callables
                        .groupBy { callable -> CallableSignature(callable.label, callable.kind) }
                        .toSortedMap(compareBy(CallableSignature::label, CallableSignature::kind))
                        .map { (signature, representativeCallables) ->
                            val matching = sourceEdges.flatMap { edge ->
                                edge.callables.filter { callable ->
                                    callable.label == signature.label && callable.kind == signature.kind
                                }
                            }
                            representativeCallables.first().copy(
                                callSites = matching.flatMap(CallableRelation::callSites),
                            )
                        }
                    SharedOutgoingRelation(
                        targetId = representative.targetId,
                        sourceEdges = sourceEdges,
                        callables = callables,
                        order = index,
                    )
                }
                OutgoingRelationGroup(
                    id = "\u0000outgoing-group:${memberIds.joinToString("\u0001")}",
                    memberIds = memberIds,
                    sharedRelations = sharedRelations,
                )
            }
            .sortedBy(OutgoingRelationGroup::id)
            .toList()
    }

    private data class GroupingKey(
        val layoutLevel: Int?,
        val test: Boolean,
        val outgoing: List<OutgoingEdgeSignature>,
    )

    private data class OutgoingEdgeSignature(
        val targetId: String,
        val callables: List<CallableSignature>,
    )

    private data class CallableSignature(
        val label: String,
        val kind: RelationKind,
    )
}
