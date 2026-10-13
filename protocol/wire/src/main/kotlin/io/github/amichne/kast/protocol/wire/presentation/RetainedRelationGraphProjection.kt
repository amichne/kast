package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.wire.RelationFactWireDocument
import io.github.amichne.kast.protocol.wire.RelationReferenceOccurrenceWireDocument
import io.github.amichne.kast.protocol.wire.RelationReferenceOwnershipWireDocument
import io.github.amichne.kast.protocol.wire.SymbolWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json

/** Projects one already-admitted page without a provider, clock, or store. Full output remains the fallback. */
internal fun projectRetainedRelationGraph(
    basis: QueryResultReference,
    items: List<QueryResultItemCliDocument>,
    observations: List<RelationReferenceOccurrenceWireDocument>,
    originalItems: List<QueryResultItemDocument> = emptyList(),
    output: QueryOutputDocument? = null,
): QueryRelationGraphProjection {
    if (items.isEmpty()) return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_KIND)
    val projection = RetainedRelationGraphProjection()
    return when (items.first()) {
        is QueryResultItemCliDocument.ExactSymbol ->
            projectSymbolGraph(basis, items, originalItems, observations, output, projection)
        is QueryResultItemCliDocument.ReferenceOccurrence ->
            projectReferenceGraph(basis, items, observations, projection)
        is QueryResultItemCliDocument.ExactSymbolNode,
        is QueryResultItemCliDocument.ReferenceEdge,
        is QueryResultItemCliDocument.Occurrence,
        is QueryResultItemCliDocument.TraversalRecord,
        is QueryResultItemCliDocument.BindingRow,
        is QueryResultItemCliDocument.ValuePath,
        is QueryResultItemCliDocument.ImpactWitness ->
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_KIND)
    }
}

private fun projectReferenceGraph(
    basis: QueryResultReference,
    items: List<QueryResultItemCliDocument>,
    observations: List<RelationReferenceOccurrenceWireDocument>,
    projection: RetainedRelationGraphProjection,
): QueryRelationGraphProjection {
    val edges = mutableListOf<QueryResultItemCliDocument>()
    for (item in items) {
        if (item !is QueryResultItemCliDocument.ReferenceOccurrence)
            return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_KIND)
        val row =
            item.rowId ?: return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_ID_ABSENT)
        if (item.ref != item.occurrence.target.selector)
            return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.TARGET_REFERENCE_MISMATCH)
        val witness =
            when (val admitted = projection.witness(item.occurrence)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return QueryRelationGraphProjection.Ineligible(admitted.failure)
            }
        edges += QueryResultItemCliDocument.ReferenceEdge(row, witness)
    }
    val observed = mutableListOf<String>()
    for (observation in observations) {
        val witness =
            when (val admitted = projection.witness(observation)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return QueryRelationGraphProjection.Ineligible(admitted.failure)
            }
        observed += witness
    }
    return QueryRelationGraphProjection.Projected(
        edges,
        QueryRelationGraphCliDocument(
            basis = basis,
            nodes = projection.nodes.values.toList(),
            witnesses = projection.witnesses.values.toList(),
            referenceObservations = observed,
            detail = graphDetail(basis, QueryRelationGraphOutputCliDocument.Occurrences),
        ),
    )
}

private fun graphDetail(
    basis: QueryResultReference,
    output: QueryRelationGraphOutputCliDocument,
): QueryRelationGraphDetailCliDocument =
    QueryRelationGraphDetailCliDocument(
        request =
            QueryRelationGraphReadCliDocument(
                type = QueryRelationGraphReadKindCliDocument.READ_RESULT,
                result = basis,
                cursor = 0,
                evidenceCursor = 0,
                output = output,
            ),
        verbose = true,
    )

private class RetainedRelationGraphProjection {
    val nodes = linkedMapOf<String, QueryRelationGraphNodeCliDocument>()
    val witnesses = linkedMapOf<String, QueryRelationGraphWitnessCliDocument>()
    private val descriptions = mutableMapOf<String, SymbolWireDocument>()
    private val nodeEvidence = mutableMapOf<String, SymbolWireDocument>()
    private val evidence = mutableMapOf<String, RelationReferenceOccurrenceWireDocument>()
    val connections = linkedMapOf<String, QueryRelationGraphConnectionCliDocument>()
    private val connectionEvidence = mutableMapOf<String, RelationFactWireDocument>()
    private val referenceConnections = mutableMapOf<RelationFactWireDocument, String>()

    fun symbol(
        item: QueryResultItemCliDocument.ExactSymbol,
        original: QueryResultItemDocument.ExactSymbol,
    ): Refinement<QueryResultItemCliDocument.ExactSymbolNode, QueryRelationGraphIneligibility> {
        val row = item.rowId ?: return Refinement.Rejected(QueryRelationGraphIneligibility.ROW_ID_ABSENT)
        if (original.connections.values.isEmpty())
            return Refinement.Rejected(QueryRelationGraphIneligibility.CONNECTION_PROOF_ABSENT)
        if (original.toCliDocument() != item)
            return Refinement.Rejected(QueryRelationGraphIneligibility.ROW_IDENTITY_MISMATCH)
        val links = mutableListOf<String>()
        for (connection in original.connections.values) {
            when (val admitted = connection(connection.toWireDocument())) {
                is Refinement.Refined -> links += admitted.value
                is Refinement.Rejected -> return admitted
            }
        }
        val symbol =
            descriptions[item.ref] ?: return Refinement.Rejected(QueryRelationGraphIneligibility.ROW_NODE_UNAVAILABLE)
        if (symbol.kind != item.kind) return Refinement.Rejected(QueryRelationGraphIneligibility.ROW_IDENTITY_MISMATCH)
        return Refinement.Refined(
            QueryResultItemCliDocument.ExactSymbolNode(
                node = nodeId(item.ref),
                kind = item.kind,
                name = item.name,
                location = item.location,
                signature = item.signature,
                connections = links,
                source = item.source,
                rowId = row,
                matches = item.matches,
            )
        )
    }

    private fun connection(value: RelationFactWireDocument): Refinement<String, QueryRelationGraphIneligibility> {
        val id =
            "connection:v1:" + digest(graphIdentityJson.encodeToString(RelationFactWireDocument.serializer(), value))
        val prior = connectionEvidence[id]
        if (prior != null) return if (prior == value) Refinement.Refined(id) else identityConflict()
        val source =
            when (val admitted = node(value.source)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val target =
            when (val admitted = node(value.target)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        connections[id] =
            referenceConnections[value]?.let { QueryRelationGraphConnectionCliDocument.ReferenceBacked(id, it) }
                ?: QueryRelationGraphConnectionCliDocument.Direct(
                    id = id,
                    source = source,
                    target = target,
                    meaning = value.meaning,
                    direction = QueryRelationGraphConnectionDirectionCliDocument.SOURCE_TO_TARGET,
                    occurrence = value.occurrence,
                    provenance = value.provenance,
                    coverage = value.coverage,
                )
        connectionEvidence[id] = value
        return Refinement.Refined(id)
    }

    private fun node(symbol: SymbolWireDocument): Refinement<String, QueryRelationGraphIneligibility> {
        val prior = descriptions[symbol.selector]
        if (prior != null && prior != symbol) return identityConflict()
        val id = nodeId(symbol.selector)
        if (nodeEvidence[id]?.let { it != symbol } == true) return identityConflict()
        descriptions[symbol.selector] = symbol
        nodeEvidence[id] = symbol
        nodes[id] = QueryRelationGraphNodeCliDocument(id, QueryRelationGraphSymbolCliDocument.from(symbol))
        return Refinement.Refined(id)
    }

    fun witness(value: RelationReferenceOccurrenceWireDocument): Refinement<String, QueryRelationGraphIneligibility> {
        val id =
            "witness:v1:" +
                digest(graphIdentityJson.encodeToString(RelationReferenceOccurrenceWireDocument.serializer(), value))
        val prior = evidence[id]
        if (prior != null) return if (prior == value) Refinement.Refined(id) else identityConflict()
        val target =
            when (val admitted = node(value.target)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val ownership =
            when (val owner = value.ownership) {
                is RelationReferenceOwnershipWireDocument.DeclarationOwned -> {
                    val source =
                        when (val admitted = node(owner.declaration)) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected -> return admitted
                        }
                    QueryRelationGraphOwnershipCliDocument.DeclarationOwned(source)
                }
                is RelationReferenceOwnershipWireDocument.FileScoped ->
                    QueryRelationGraphOwnershipCliDocument.FileScoped(owner.context)
                is RelationReferenceOwnershipWireDocument.Unavailable ->
                    QueryRelationGraphOwnershipCliDocument.Unavailable(owner.cause)
            }
        evidence[id] = value
        shareReferenceFact(id, value)
        witnesses[id] =
            QueryRelationGraphWitnessCliDocument(
                id = id,
                target = target,
                ownership = ownership,
                meaning = value.meaning,
                direction = referenceDirection(value.ownership),
                occurrence = value.occurrence,
                context = value.context,
                provenance = value.provenance,
                coverage = value.coverage,
            )
        return Refinement.Refined(id)
    }

    private fun shareReferenceFact(id: String, value: RelationReferenceOccurrenceWireDocument) {
        val owner = value.ownership
        if (owner is RelationReferenceOwnershipWireDocument.DeclarationOwned) {
            referenceConnections.putIfAbsent(
                RelationFactWireDocument(
                    value.meaning,
                    owner.declaration,
                    value.target,
                    value.occurrence,
                    value.provenance,
                    value.coverage,
                ),
                id,
            )
        }
    }

    private fun identityConflict() = Refinement.Rejected(QueryRelationGraphIneligibility.IDENTITY_CONFLICT)
}

private fun referenceDirection(owner: RelationReferenceOwnershipWireDocument): QueryRelationGraphDirectionCliDocument =
    when (owner) {
        is RelationReferenceOwnershipWireDocument.DeclarationOwned ->
            QueryRelationGraphDirectionCliDocument.DECLARATION_TO_TARGET
        is RelationReferenceOwnershipWireDocument.FileScoped ->
            QueryRelationGraphDirectionCliDocument.FILE_CONTEXT_TO_TARGET
        is RelationReferenceOwnershipWireDocument.Unavailable -> QueryRelationGraphDirectionCliDocument.OWNER_UNPROVEN
    }

private val graphIdentityJson = Json { encodeDefaults = true }

private fun nodeId(selector: String) = "node:v1:" + digest(selector)

private fun digest(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

private fun projectSymbolGraph(
    basis: QueryResultReference,
    items: List<QueryResultItemCliDocument>,
    originals: List<QueryResultItemDocument>,
    observations: List<RelationReferenceOccurrenceWireDocument>,
    output: QueryOutputDocument?,
    projection: RetainedRelationGraphProjection,
): QueryRelationGraphProjection {
    val selected =
        output as? QueryOutputDocument.Symbols
            ?: return QueryRelationGraphProjection.Ineligible(
                QueryRelationGraphIneligibility.PRESENTATION_OUTPUT_ABSENT
            )
    if (items.size != originals.size)
        return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_IDENTITY_MISMATCH)
    val observed = mutableListOf<String>()
    for (observation in observations) when (val admitted = projection.witness(observation)) {
        is Refinement.Refined -> observed += admitted.value
        is Refinement.Rejected -> return QueryRelationGraphProjection.Ineligible(admitted.failure)
    }
    val rows = mutableListOf<QueryResultItemCliDocument>()
    for ((item, original) in items.zip(originals)) {
        if (item !is QueryResultItemCliDocument.ExactSymbol || original !is QueryResultItemDocument.ExactSymbol)
            return QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_KIND)
        when (val admitted = projection.symbol(item, original)) {
            is Refinement.Refined -> rows += admitted.value
            is Refinement.Rejected -> return QueryRelationGraphProjection.Ineligible(admitted.failure)
        }
    }
    return QueryRelationGraphProjection.Projected(
        rows,
        QueryRelationGraphCliDocument(
            basis = basis,
            nodes = projection.nodes.values.toList(),
            witnesses = projection.witnesses.values.toList(),
            referenceObservations = observed,
            detail =
                graphDetail(
                    basis,
                    QueryRelationGraphOutputCliDocument.Symbols(selected.fields.values.map(::symbolField)),
                ),
            connectionWitnesses = projection.connections.values.toList(),
        ),
    )
}

private fun symbolField(value: QuerySymbolFieldDocument): QueryRelationGraphSymbolFieldCliDocument =
    when (value) {
        QuerySymbolFieldDocument.NAME -> QueryRelationGraphSymbolFieldCliDocument.NAME
        QuerySymbolFieldDocument.LOCATION -> QueryRelationGraphSymbolFieldCliDocument.LOCATION
        QuerySymbolFieldDocument.SIGNATURE -> QueryRelationGraphSymbolFieldCliDocument.SIGNATURE
        QuerySymbolFieldDocument.SOURCE -> QueryRelationGraphSymbolFieldCliDocument.SOURCE
    }
