package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Versioned immutable historical receipt. Decoding cannot issue successful application or approval proof. */
object LiveAddDeclarationReceiptCodec {
    const val VERSION = 2
    private const val KIND = "LIVE_ADD_DECLARATION_RECEIPT"
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun version(receipt: HistoricalLiveAddDeclarationReceipt): Int =
        when (receipt.execution) {
            is HistoricalLiveApproval -> 1
            is HistoricalLiveExecution.LocalEndpointOperation -> VERSION
        }

    fun encode(receipt: HistoricalLiveAddDeclarationReceipt): String =
        when (val execution = receipt.execution) {
            is HistoricalLiveApproval ->
                json.encodeToString(
                    LiveReceiptDocument.serializer(),
                    LiveReceiptDocument(receipt.identity.value, legacyContent(receipt, execution)),
                )
            is HistoricalLiveExecution.LocalEndpointOperation ->
                json.encodeToString(
                    LiveReceiptDocumentV2.serializer(),
                    LiveReceiptDocumentV2(receipt.identity.value, content(receipt, execution)),
                )
        }

    fun decode(encoded: String): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
        val version =
            try {
                Json { ignoreUnknownKeys = true }.decodeFromString<LiveReceiptVersionDocument>(encoded).content.version
            } catch (_: SerializationException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            }
        return when (version) {
            1 -> decodeLegacy(encoded)
            VERSION -> decodeCurrent(encoded)
            else -> rejected(LiveReceiptFailure.VERSION_UNSUPPORTED)
        }
    }

    private fun decodeLegacy(encoded: String): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
        val document: LiveReceiptDocument =
            try {
                json.decodeFromString<LiveReceiptDocument>(LiveReceiptDocument.serializer(), encoded)
            } catch (_: SerializationException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            }
        if (document.content.version != 1 || document.content.kind != KIND)
            return rejected(LiveReceiptFailure.VERSION_UNSUPPORTED)
        val identity = ChangeReceiptIdentity.parse(document.identity) ?: return rejected(LiveReceiptFailure.MALFORMED)
        val execution =
            when (val decoded = decodeReceiptApproval(document.content.body.approval)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return decoded
            }
        val restored =
            when (val decoded = decodeReceiptBody(document.content.body, execution)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return decoded
            }
        if (restored.identity != identity) return rejected(LiveReceiptFailure.IDENTITY_MISMATCH)
        return if (encode(restored) == encoded) Refinement.Refined(restored) else rejected(LiveReceiptFailure.MALFORMED)
    }

    internal fun identity(receipt: HistoricalLiveAddDeclarationReceipt): ChangeReceiptIdentity {
        val canonical =
            when (val execution = receipt.execution) {
                is HistoricalLiveApproval ->
                    json.encodeToString(LiveReceiptContent.serializer(), legacyContent(receipt, execution))
                is HistoricalLiveExecution.LocalEndpointOperation ->
                    json.encodeToString(LiveReceiptContentV2.serializer(), content(receipt, execution))
            }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString(
                ""
            ) { byte ->
                "%02x".format(Locale.ROOT, byte)
            }
        return checkNotNull(ChangeReceiptIdentity.parse("receipt:$digest"))
    }

    private fun legacyContent(
        receipt: HistoricalLiveAddDeclarationReceipt,
        approval: HistoricalLiveApproval,
    ): LiveReceiptContent {
        val plan = json.parseToJsonElement(LiveAddDeclarationPlanCodec.encode(receipt.plan)).jsonObject
        return LiveReceiptContent(
            1,
            KIND,
            LiveReceiptBody(
                plan = plan,
                after = afterDocument(receipt, plan),
                source = receipt.source.path.value,
                postimage = receipt.postimage.value,
                anchor = anchorDocument(receipt.anchor),
                delta =
                    LiveReceiptDelta(
                        receipt.observedDelta.packageName,
                        receipt.observedDelta.declarationName,
                        receipt.observedDelta.declarationKind.name,
                    ),
                evidence = receipt.evidence,
                approval =
                    LiveReceiptApproval(
                        thread = approval.thread,
                        turn = approval.turn,
                        call = approval.call,
                        challenge = approval.challenge.value,
                    ),
                recovery =
                    LiveReceiptRecovery(
                        receipt.recovery.binding.value,
                        receipt.recovery.preparedDigest.value,
                        receipt.recovery.appliedDigest.value,
                    ),
                semanticObligations = receipt.semanticObligations.map { it.name },
                liveObligations = receipt.liveObligations.map { it.name },
            ),
        )
    }

    private fun decodeCurrent(encoded: String): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
        val document =
            try {
                json.decodeFromString<LiveReceiptDocumentV2>(encoded)
            } catch (_: SerializationException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            }
        if (document.content.version != VERSION || document.content.kind != KIND)
            return rejected(LiveReceiptFailure.VERSION_UNSUPPORTED)
        val body = document.content.body
        val plan =
            when (val decoded = LiveAddDeclarationPlanCodec.decode(body.plan.toString())) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return rejected(LiveReceiptFailure.PLAN_MISMATCH)
            }
        val execution =
            when (val decoded = decodeReceiptExecution(body.execution, plan)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return decoded
            }
        val restored =
            when (val decoded = decodeReceiptBody(body, execution)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return decoded
            }
        if (restored.identity.value != document.identity) return rejected(LiveReceiptFailure.IDENTITY_MISMATCH)
        return if (encode(restored) == encoded) Refinement.Refined(restored) else rejected(LiveReceiptFailure.MALFORMED)
    }

    private fun content(
        receipt: HistoricalLiveAddDeclarationReceipt,
        execution: HistoricalLiveExecution.LocalEndpointOperation,
    ): LiveReceiptContentV2 {
        val plan = json.parseToJsonElement(LiveAddDeclarationPlanCodec.encode(receipt.plan)).jsonObject
        return LiveReceiptContentV2(
            VERSION,
            KIND,
            LiveReceiptBodyV2(
                plan = plan,
                after = afterDocument(receipt, plan),
                source = receipt.source.path.value,
                postimage = receipt.postimage.value,
                anchor = anchorDocument(receipt.anchor),
                delta =
                    LiveReceiptDelta(
                        receipt.observedDelta.packageName,
                        receipt.observedDelta.declarationName,
                        receipt.observedDelta.declarationKind.name,
                    ),
                evidence = receipt.evidence,
                execution = execution.document(),
                recovery =
                    LiveReceiptRecovery(
                        receipt.recovery.binding.value,
                        receipt.recovery.preparedDigest.value,
                        receipt.recovery.appliedDigest.value,
                    ),
                semanticObligations = receipt.semanticObligations.map { it.name },
                liveObligations = receipt.liveObligations.map { it.name },
            ),
        )
    }

    private fun afterDocument(receipt: HistoricalLiveAddDeclarationReceipt, plan: JsonObject): LiveReceiptAfter {
        val reference = receipt.after.reference
        return LiveReceiptAfter(
            root = reference.workspaceRoot.value,
            owner = reference.host.value.toString(),
            epoch = reference.epoch.value,
            contentView = reference.contentView.name,
            version = reference.version,
            model = plan.getValue("model"),
        )
    }

    private fun anchorDocument(anchor: CompilerGroundedSymbolEvidence): LiveReceiptAnchor {
        val qualified =
            when (val identity = anchor.qualifiedIdentity) {
                is ExactDeclarationQualifiedIdentity.Available -> identity.value
                ExactDeclarationQualifiedIdentity.Unavailable -> null
            }
        return LiveReceiptAnchor(
            start = anchor.range.startInclusive,
            end = anchor.range.endExclusive,
            name = anchor.name.value,
            qualifiedIdentity = qualified,
            kind = anchor.kind.name,
            signature = anchor.signature.canonicalEncoding().value,
            compilerIdentity = anchor.compilerIdentity.value,
        )
    }

    private fun rejected(failure: LiveReceiptFailure) = Refinement.Rejected(failure)
}
