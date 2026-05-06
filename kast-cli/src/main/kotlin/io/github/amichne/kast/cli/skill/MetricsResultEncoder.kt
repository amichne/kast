package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.indexstore.api.metrics.impact.ChangeImpactNode
import io.github.amichne.kast.indexstore.api.metrics.impact.DeadCodeCandidate
import io.github.amichne.kast.indexstore.api.metrics.module.ApiSurfaceMetric
import io.github.amichne.kast.indexstore.api.metrics.general.DeclarationInfo
import io.github.amichne.kast.indexstore.api.metrics.impact.FanInMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.FanOutMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.LowUsageSymbol
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleBoundaryMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCycleMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthMetric
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun encodeFanInMetrics(json: Json, items: List<FanInMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(FanInMetric.serializer()),
        items,
    )

internal fun encodeApiSurfaceMetrics(json: Json, items: List<ApiSurfaceMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ApiSurfaceMetric.serializer()),
        items,
    )

internal fun encodeModuleBoundaryMetrics(json: Json, items: List<ModuleBoundaryMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ModuleBoundaryMetric.serializer()),
        items,
    )

internal fun encodeDeclarations(json: Json, items: List<DeclarationInfo>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(DeclarationInfo.serializer()),
        items,
    )

internal fun encodeFanOutMetrics(json: Json, items: List<FanOutMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(FanOutMetric.serializer()),
        items,
    )

internal fun encodeModuleCouplingMetrics(json: Json, items: List<ModuleCouplingMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ModuleCouplingMetric.serializer()),
        items,
    )

internal fun encodeLowUsageSymbols(json: Json, items: List<LowUsageSymbol>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(LowUsageSymbol.serializer()),
        items,
    )

internal fun encodeModuleCycleMetrics(json: Json, items: List<ModuleCycleMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ModuleCycleMetric.serializer()),
        items,
    )

internal fun encodeModuleDepthMetrics(json: Json, items: List<ModuleDepthMetric>): JsonElement =
    json.encodeToJsonElement(
        ListSerializer(ModuleDepthMetric.serializer()),
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
