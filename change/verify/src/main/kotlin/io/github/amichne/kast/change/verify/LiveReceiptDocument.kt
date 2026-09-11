package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.DurableAddDeclarationPlanningEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable internal data class LiveReceiptDocument(val identity: String, val content: LiveReceiptContent)

@Serializable internal data class LiveReceiptContent(val version: Int, val kind: String, val body: LiveReceiptBody)

@Serializable
internal data class LiveReceiptBody(
    val plan: JsonObject,
    val after: LiveReceiptAfter,
    val source: String,
    val postimage: String,
    val anchor: LiveReceiptAnchor,
    val delta: LiveReceiptDelta,
    val evidence: DurableAddDeclarationPlanningEvidence,
    val approval: LiveReceiptApproval,
    val recovery: LiveReceiptRecovery,
    val semanticObligations: List<String>,
    val liveObligations: List<String>,
)

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
