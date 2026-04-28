package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.indexstore.ChangeImpactNode
import io.github.amichne.kast.indexstore.DeadCodeCandidate
import io.github.amichne.kast.indexstore.ModuleCouplingMetric
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun encodeModuleCouplingMetrics(json: Json, items: List<ModuleCouplingMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ModuleCouplingMetric.serializer()),
        items,
    )

internal fun encodeDeadCodeCandidates(json: Json, items: List<DeadCodeCandidate>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(DeadCodeCandidate.serializer()),
        items,
    )

internal fun encodeChangeImpactNodes(json: Json, items: List<ChangeImpactNode>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ChangeImpactNode.serializer()),
        items,
    )
