package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.DurableAddDeclarationPlanningEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable internal data class LiveReceiptDocument(val identity: String, val content: LiveReceiptContent)

@Serializable internal data class LiveReceiptContent(val version: Int, val kind: String, val body: LiveReceiptBody)

@Serializable
internal data class LiveReceiptBody(
    override val plan: JsonObject,
    override val after: LiveReceiptAfter,
    override val source: String,
    override val postimage: String,
    override val anchor: LiveReceiptAnchor,
    override val delta: LiveReceiptDelta,
    override val evidence: DurableAddDeclarationPlanningEvidence,
    val approval: LiveReceiptApproval,
    override val recovery: LiveReceiptRecovery,
    override val semanticObligations: List<String>,
    override val liveObligations: List<String>,
) : LiveReceiptFacts

@Serializable
internal data class LiveReceiptAfter(
    val root: String,
    val owner: String,
    val epoch: Long,
    val contentView: String,
    val version: Int,
    val model: JsonElement,
)

@Serializable
internal data class LiveReceiptAnchor(
    val start: Int,
    val end: Int,
    val name: String,
    val qualifiedIdentity: String?,
    val kind: String,
    val signature: String,
    val compilerIdentity: String,
)

@Serializable internal data class LiveReceiptDelta(val packageName: String, val name: String, val kind: String)

@Serializable
internal data class LiveReceiptApproval(val thread: String, val turn: String, val call: String, val challenge: String)

@Serializable internal data class LiveReceiptRecovery(val binding: String, val prepared: String, val applied: String)

internal interface LiveReceiptFacts {
    val plan: JsonObject
    val after: LiveReceiptAfter
    val source: String
    val postimage: String
    val anchor: LiveReceiptAnchor
    val delta: LiveReceiptDelta
    val evidence: DurableAddDeclarationPlanningEvidence
    val recovery: LiveReceiptRecovery
    val semanticObligations: List<String>
    val liveObligations: List<String>
}

@Serializable internal data class LiveReceiptDocumentV2(val identity: String, val content: LiveReceiptContentV2)

@Serializable internal data class LiveReceiptContentV2(val version: Int, val kind: String, val body: LiveReceiptBodyV2)

@Serializable
internal data class LiveReceiptBodyV2(
    override val plan: JsonObject,
    override val after: LiveReceiptAfter,
    override val source: String,
    override val postimage: String,
    override val anchor: LiveReceiptAnchor,
    override val delta: LiveReceiptDelta,
    override val evidence: DurableAddDeclarationPlanningEvidence,
    val execution: LocalEndpointExecutionDocument,
    override val recovery: LiveReceiptRecovery,
    override val semanticObligations: List<String>,
    override val liveObligations: List<String>,
) : LiveReceiptFacts

@Serializable
internal data class LocalEndpointExecutionDocument(
    val type: LocalEndpointExecutionType,
    val operation: io.github.amichne.kast.change.apply.LiveChangeEffect,
    val root: String,
    val host: String,
    val planId: String,
)

@Serializable
internal enum class LocalEndpointExecutionType {
    LOCAL_ENDPOINT_OPERATION
}

@Serializable internal data class LiveReceiptVersionDocument(val content: LiveReceiptVersion)

@Serializable internal data class LiveReceiptVersion(val version: Int)
