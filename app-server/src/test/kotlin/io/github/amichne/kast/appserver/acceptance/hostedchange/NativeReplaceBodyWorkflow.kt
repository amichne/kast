package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.query.PublicToolFields
import io.github.amichne.kast.appserver.query.PublicToolQuerySymbols
import io.github.amichne.kast.appserver.query.PublicToolReferenceSource
import io.github.amichne.kast.appserver.query.PublicToolReplaceBody
import io.github.amichne.kast.appserver.query.PublicToolRunAction
import io.github.amichne.kast.appserver.query.PublicToolSymbolsOutput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One disposable native query -> body replacement -> fresh exact-reference reuse. */
internal class NativeReplaceBodyWorkflow(
    private val source: Path,
    private val evidence: NativeChangeEvidence,
    private val controls: NativeFixtureControls,
    private val reopenAfterRestore: Boolean = true,
) {
    suspend fun run(peer: NativeChangePeer) {
        val before = Files.readAllBytes(source)
        val progress = NativeBodyProgress()
        evidence.record("replace-body-reference-reuse", NativeCaseOutcome.UNQUALIFIED)
        try {
            val reference = queryTarget(peer, progress)
            assertRejections(peer, reference, before, progress)
            val applied = applyReplacement(peer, reference, progress)
            verifyReuse(peer, before, applied, progress)
        } catch (rejected: NativeRejected) {
            evidence.record(
                "replace-body-reference-reuse",
                NativeCaseOutcome.UNQUALIFIED,
                Json.encodeToJsonElement(
                        NativeBodyFailureEvidence.serializer(),
                        NativeBodyFailureEvidence(
                            progress.stage,
                            rejected.failure.name,
                            progress.fields
                                .filter {
                                    it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,70}"))
                                }
                                .take(30),
                        ),
                    )
                    .jsonObject,
            )
            throw rejected
        } finally {
            Files.write(source, before)
            if (reopenAfterRestore) controls.restart()
            demand(Files.readAllBytes(source).contentEquals(before), NativeFailure.SOURCE_CHANGED)
        }
    }

    private suspend fun queryTarget(peer: NativeChangePeer, progress: NativeBodyProgress): String {
        val found = NativeChangeRead(peer).searchFunction("bodyTarget", 1)
        progress.fields = found.keys.sorted()
        val item = (found["items"] as JsonArray).single() as JsonObject
        progress.fields = item.keys.sorted()
        return item.textAt("ref")
    }

    private suspend fun assertRejections(
        peer: NativeChangePeer,
        reference: String,
        before: ByteArray,
        progress: NativeBodyProgress,
    ) {
        progress.stage = NativeBodyStage.NEGATIVE
        for (body in
            listOf(
                "{ return \"bad\" } trailing",
                "{ return \"bad\" ",
                "{ kotlin.contracts.contract { returns() }; return \"bad\" }",
            )) {
            assertPlanningRejection(peer, reference, body, before)
        }
        for (unsupported in listOf("bodyExpression", "bodyInline", "bodyContract")) {
            val item =
                (NativeChangeRead(peer).searchFunction(unsupported, 1)["items"] as JsonArray).single() as JsonObject
            assertPlanningRejection(peer, item.textAt("ref"), REPLACEMENT_BODY, before)
        }
        evidence.record("replace-body-rejections", NativeCaseOutcome.PASSED)
    }

    private suspend fun applyReplacement(
        peer: NativeChangePeer,
        reference: String,
        progress: NativeBodyProgress,
    ): NativeBodyApplied {
        progress.stage = NativeBodyStage.REPLACE
        val applied = peer.call("replace_body", replaceArguments(reference))
        progress.fields = applied.rejectionFields()
        if (applied is NativeToolResult.Document && applied.rejected()) {
            val error = applied.payload["error"] as? JsonObject
            val plan = error?.get("plan") as? JsonObject
            progress.fields =
                listOfNotNull(
                    (error?.get("code") as? JsonPrimitive)?.content?.let { "CODE_$it" },
                    (plan?.get("status") as? JsonPrimitive)?.content?.let { "PLAN_$it" },
                    (plan?.get("reason") as? JsonPrimitive)?.content?.let { "REASON_$it" },
                )
        }
        demand(applied is NativeToolResult.Document && !applied.rejected(), NativeFailure.PROVIDER_REJECTED)
        val run = applied.document()
        progress.fields = run.keys.sorted()
        demand(run["status"] == JsonPrimitive("complete"), NativeFailure.VERIFIED_RECEIPT_MISSING)
        val result = run.objectAt("application")
        progress.fields = result.keys.sorted()
        demand(
            result["status"] == JsonPrimitive("complete") && result["state"] == JsonPrimitive("verified"),
            NativeFailure.VERIFIED_RECEIPT_MISSING,
        )
        return NativeBodyApplied(
            run.textAt("planIdentity"),
            result.textAt("receiptIdentity"),
            result.textAt("freshReference"),
        )
    }

    private suspend fun verifyReuse(
        peer: NativeChangePeer,
        before: ByteArray,
        applied: NativeBodyApplied,
        progress: NativeBodyProgress,
    ) {
        progress.stage = NativeBodyStage.POSTIMAGE
        val after = Files.readAllBytes(source)
        val expected = before.toString(Charsets.UTF_8).replaceFirst(ORIGINAL_BODY, REPLACEMENT_BODY).toByteArray()
        demand(!before.contentEquals(expected) && after.contentEquals(expected), NativeFailure.SOURCE_CHANGED)
        progress.stage = NativeBodyStage.REUSE
        val reused = peer.call("query_symbols", referenceQuery(applied.freshReference))
        progress.fields = reused.document().keys.sorted()
        demand(!reused.rejected(), NativeFailure.PROVIDER_REJECTED)
        val item = (reused.document()["items"] as? JsonArray)?.singleOrNull() as? JsonObject
        demand(item?.get("name") == JsonPrimitive("bodyTarget"), NativeFailure.RESULT_SHAPE_REJECTED)
        evidence.record(
            "replace-body-reference-reuse",
            NativeCaseOutcome.PASSED,
            Json.encodeToJsonElement(
                    NativeBodyReuseEvidence.serializer(),
                    NativeBodyReuseEvidence(
                        sha256(applied.freshReference.toByteArray()),
                        sha256(applied.planIdentity.toByteArray()),
                        sha256(applied.receiptIdentity.toByteArray()),
                        sha256(before),
                        sha256(after),
                    ),
                )
                .jsonObject,
        )
    }

    private suspend fun assertPlanningRejection(
        peer: NativeChangePeer,
        reference: String,
        body: String,
        before: ByteArray,
    ) {
        val rejected = peer.call("replace_body", replaceArguments(reference, body))
        val error = (rejected as? NativeToolResult.Document)?.payload?.get("error") as? JsonObject
        demand(
            rejected.rejected() && error?.get("code") == JsonPrimitive("PLANNING_REJECTED"),
            NativeFailure.EXPECTED_REJECTION_MISSING,
        )
        demand(Files.readAllBytes(source).contentEquals(before), NativeFailure.SOURCE_CHANGED)
    }

    private fun replaceArguments(reference: String, body: String = REPLACEMENT_BODY): JsonObject =
        Json.encodeToJsonElement(
                PublicToolReplaceBody.serializer(),
                PublicToolReplaceBody(
                    ProtocolText.parse(reference).nativeValue(),
                    ProtocolText.parse(body).nativeValue(),
                ),
            )
            .jsonObject

    private fun referenceQuery(reference: String): JsonObject {
        val refs =
            (BoundedProtocolList.create(listOf(ProtocolText.parse(reference).nativeValue())) as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(listOf(PublicToolFields.NAME)) as Refinement.Refined).value
        return Json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(
                    PublicToolRunAction(PublicToolReferenceSource(refs), null, PublicToolSymbolsOutput(fields))
                ),
            )
            .jsonObject
    }

    private companion object {
        const val ORIGINAL_BODY = "{ return \"before\" }"
        const val REPLACEMENT_BODY = "{ return \"after\" }"
    }
}

private data class NativeBodyProgress(
    var stage: NativeBodyStage = NativeBodyStage.QUERY,
    var fields: List<String> = emptyList(),
)

private data class NativeBodyApplied(
    val planIdentity: String,
    val receiptIdentity: String,
    val freshReference: String,
)

private fun NativeToolResult.rejectionFields(): List<String> =
    when (this) {
        is NativeToolResult.BrokerRejected -> listOf("BROKER_${failure.name}")
        is NativeToolResult.WorkspaceRejected -> listOf("WORKSPACE_${failure.name}")
        NativeToolResult.ResponseLost -> listOf("RESPONSE_LOST")
        is NativeToolResult.Document -> payload.keys.sorted()
    }

@Serializable
private enum class NativeBodyStage {
    QUERY,
    NEGATIVE,
    REPLACE,
    POSTIMAGE,
    REUSE,
}

@Serializable
private data class NativeBodyFailureEvidence(
    val stage: NativeBodyStage,
    val failure: String,
    val fields: List<String>,
)

@Serializable
private data class NativeBodyReuseEvidence(
    val referenceSha256: String,
    val planIdentitySha256: String,
    val receiptIdentitySha256: String,
    val sourcePreimageSha256: String,
    val sourcePostimageSha256: String,
)
