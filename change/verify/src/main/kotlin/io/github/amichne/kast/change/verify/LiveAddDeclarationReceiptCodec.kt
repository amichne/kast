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
    const val VERSION = 1
    private const val KIND = "LIVE_ADD_DECLARATION_RECEIPT"
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(receipt: HistoricalLiveAddDeclarationReceipt): String =
        json.encodeToString(
            LiveReceiptDocument.serializer(),
            LiveReceiptDocument(receipt.identity.value, content(receipt)),
        )

    fun decode(encoded: String): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
        val document: LiveReceiptDocument =
            try {
                json.decodeFromString<LiveReceiptDocument>(LiveReceiptDocument.serializer(), encoded)
            } catch (_: SerializationException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            } catch (_: IllegalArgumentException) {
                return rejected(LiveReceiptFailure.MALFORMED)
            }
        if (document.content.version != VERSION || document.content.kind != KIND)
            return rejected(LiveReceiptFailure.VERSION_UNSUPPORTED)
        val identity = ChangeReceiptIdentity.parse(document.identity) ?: return rejected(LiveReceiptFailure.MALFORMED)
        val restored =
            when (val decoded = decodeReceiptBody(document.content.body)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return decoded
            }
        if (restored.identity != identity) return rejected(LiveReceiptFailure.IDENTITY_MISMATCH)
        return if (encode(restored) == encoded) Refinement.Refined(restored) else rejected(LiveReceiptFailure.MALFORMED)
    }

    internal fun identity(receipt: HistoricalLiveAddDeclarationReceipt): ChangeReceiptIdentity {
        val canonical = json.encodeToString(LiveReceiptContent.serializer(), content(receipt))
        val digest =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString(
                ""
            ) { byte ->
                "%02x".format(Locale.ROOT, byte)
            }
        return checkNotNull(ChangeReceiptIdentity.parse("receipt:$digest"))
    }

    private fun content(receipt: HistoricalLiveAddDeclarationReceipt): LiveReceiptContent {
        val plan = json.parseToJsonElement(LiveAddDeclarationPlanCodec.encode(receipt.plan)).jsonObject
        return LiveReceiptContent(
            VERSION,
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
                        thread = receipt.approval.thread,
                        turn = receipt.approval.turn,
                        call = receipt.approval.call,
                        challenge = receipt.approval.challenge.value,
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
