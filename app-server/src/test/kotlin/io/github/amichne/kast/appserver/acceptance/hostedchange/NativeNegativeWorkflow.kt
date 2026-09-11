package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class NativeNegativeWorkflow(
    private val source: Path,
    private val evidence: NativeChangeEvidence,
    private val foreignRoot: NativeForeignRootBoundary,
    private val trace: NativeProcessTrace,
) {
    suspend fun run(peer: NativeChangePeer, reference: String) {
        val original = Files.readAllBytes(source)
        foreignRoot.reject(nativePlanArguments(reference, "fun foreignRefusal() = value"))
        demand(Files.readAllBytes(source).contentEquals(original), NativeFailure.SOURCE_CHANGED)
        evidence.record("foreign-root-refusal", NativeCaseOutcome.PASSED)
        unsupportedIntents(peer, reference)
        ambiguousName(peer)
    }

    private suspend fun unsupportedIntents(peer: NativeChangePeer, reference: String) {
        val before = Files.readAllBytes(source)
        val target = ProtocolText.parse(reference).nativeValue()
        val intents =
            listOf(
                ChangeIntentDocument.AddFile(text("Unsupported.kt"), text("class Unsupported")),
                ChangeIntentDocument.ReplaceDeclaration(target, text("class Replacement")),
                ChangeIntentDocument.RenameSymbol(target, text("Renamed")),
            )
        for (intent in intents) {
            val arguments =
                Json.encodeToJsonElement(ChangePlanRequest.serializer(), ChangePlanRequest(intent)).jsonObject
            val rejected = peer.call("change_plan", arguments)
            demand(
                rejected.rejected() &&
                    trace.boundaryRejections.last() == NativeBoundaryRejection.IDE_OPERATION_UNSUPPORTED,
                NativeFailure.EXPECTED_REJECTION_MISSING,
            )
            demand(
                Files.readAllBytes(source).contentEquals(before) &&
                    !Files.exists(source.parent.resolve("Unsupported.kt")),
                NativeFailure.SOURCE_CHANGED,
            )
        }
        evidence.record(
            "unsupported-intents",
            NativeCaseOutcome.PASSED,
            buildJsonObject { put("rejectedIntentCount", intents.size) },
        )
    }

    private suspend fun ambiguousName(peer: NativeChangePeer) {
        val before = Files.readAllBytes(source)
        NativeChangeRead(peer).searchFunction("sharedOperation", 5)
        val rejected = peer.call("change_plan", nativePlanArguments("sharedOperation", "fun shouldNotExist() = Unit"))
        demand(
            rejected.rejected() && rejected.document()["reason"] == JsonPrimitive("exact-symbol-required"),
            NativeFailure.EXPECTED_REJECTION_MISSING,
        )
        demand(Files.readAllBytes(source).contentEquals(before), NativeFailure.SOURCE_CHANGED)
        evidence.record(
            "ambiguous-name-is-not-authority",
            NativeCaseOutcome.PASSED,
            buildJsonObject { put("candidateCount", 5) },
        )
    }

    private fun text(value: String) = ProtocolText.parse(value).nativeValue()
}
