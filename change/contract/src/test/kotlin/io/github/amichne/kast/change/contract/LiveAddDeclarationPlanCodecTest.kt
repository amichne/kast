package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveAddDeclarationPlanCodecTest {
    @Test
    fun `v2 retains exact bounded extent scope identity and canonical bytes`() {
        val encoded = checkNotNull(javaClass.getResource("/live-add-declaration-plan-v2.json")).readText()
        val decoded = LiveAddDeclarationPlanCodec.decode(encoded).refined()
        assertEquals(
            io.github.amichne.kast.traversal.contract.TraversalExtent.ThroughDepth(
                io.github.amichne.kast.traversal.contract.TraversalDepthLimit.parse(5).refined()
            ),
            decoded.verificationScope.traversals.single().budget.extent,
        )
        assertEquals(RelationSearchBoundary.RETAINED_SUBJECT, decoded.verificationScope.traversals.single().expansion)
        assertEquals("eeeeb74620ba720465d0683ceb63194de94b11997ee939b40e94dc783412c75b", decoded.planId.value)
        assertEquals(encoded, LiveAddDeclarationPlanCodec.encode(decoded))
    }

    @Test
    fun `exhaustive replay emits v3 typed extent and preserves it across decode`() {
        val original = detachedLivePlan()
        val scope =
            LiveAddDeclarationVerificationScope.restore(
                    original.verificationScope.relations,
                    original.verificationScope.traversals.map {
                        it.copy(
                            budget =
                                it.budget.copy(
                                    extent = io.github.amichne.kast.traversal.contract.TraversalExtent.Exhaustive
                                )
                        )
                    },
                    original.verificationScope.diagnostics,
                    original.evidence,
                )
                .refined()
        val input =
            AdmittedLiveAddDeclarationPlanInput.restore(
                    LivePlanningTarget(original.basis.observation, original.target, original.content),
                    LivePlannedDeclaration(original.declaration, original.expectedSemanticDelta),
                    LivePlanningEvidence(original.evidence, scope),
                )
                .refined()
        val plan = LiveAddDeclarationChangePlan.issue(input)
        val encoded = LiveAddDeclarationPlanCodec.encode(plan)
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(3), document.getValue("schemaVersion"))
        assertEquals(
            Json.parseToJsonElement(checkNotNull(javaClass.getResource("/live-traversal-exhaustive.json")).readText()),
            document
                .getValue("verificationScope")
                .jsonObject
                .getValue("traversals")
                .jsonArray
                .single()
                .jsonObject
                .getValue("extent"),
        )
        val decoded = LiveAddDeclarationPlanCodec.decode(encoded).refined()
        assertEquals(
            io.github.amichne.kast.traversal.contract.TraversalExtent.Exhaustive,
            decoded.verificationScope.traversals.single().budget.extent,
        )
        assertEquals(encoded, LiveAddDeclarationPlanCodec.encode(decoded))
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.MALFORMED,
            rejected(encoded.replace("\"schemaVersion\":3", "\"schemaVersion\":2")),
        )
    }

    @Test
    fun `durable replay retains explicit expansion scope directory and source sets`() {
        val plan = detachedLivePlan()
        val explicit =
            RelationSearchBoundary.Explicit(
                plan.target.scope,
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("src").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined())).refined(),
            )
        val scope =
            LiveAddDeclarationVerificationScope.restore(
                    plan.verificationScope.relations.map { it.copy(boundary = explicit) },
                    plan.verificationScope.traversals.map { it.copy(expansion = explicit) },
                    plan.verificationScope.diagnostics,
                    plan.evidence,
                )
                .refined()
        val document = LiveVerificationScopeCodec.document(scope)
        val encoded = Json.encodeToString(LiveVerificationScopeDocument.serializer(), document)
        assertEquals(
            "EXPLICIT",
            Json.parseToJsonElement(encoded)
                .jsonObject
                .getValue("relations")
                .jsonArray
                .first()
                .jsonObject
                .getValue("boundary")
                .jsonObject
                .getValue("type")
                .let {
                    (it as JsonPrimitive).content
                },
        )
        val restored = LiveVerificationScopeCodec.restore(document, plan.basis.observation, plan.evidence).refined()
        assertEquals(explicit, restored.relations.first().boundary)
        assertEquals(explicit, restored.traversals.first().expansion)
    }

    @Test
    fun `historical string boundary codec version fails closed`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED,
            rejected(encoded.replace("\"schemaVersion\":2", "\"schemaVersion\":1")),
        )
    }

    @Test
    fun `historical live plan round trip preserves complete model compiler identity constraints and obligations`() {
        val plan = detachedLivePlan()
        val encoded = LiveAddDeclarationPlanCodec.encode(plan)

        val decoded = LiveAddDeclarationPlanCodec.decode(encoded).refined()

        assertEquals(encoded, LiveAddDeclarationPlanCodec.encode(decoded))
        assertEquals(plan.planId, decoded.planId)
        assertEquals(plan.basis.observation.reference, decoded.basis.observation.reference)
        assertEquals(plan.basis.observation.model.sourceRoots, decoded.basis.observation.model.sourceRoots)
        assertEquals(plan.target.evidence, decoded.target.evidence)
        assertEquals(plan.target.constraints, decoded.target.constraints)
        assertEquals(plan.evidence, decoded.evidence)
        assertEquals(plan.verificationScope.relations, decoded.verificationScope.relations)
        assertEquals(plan.verificationScope.traversals, decoded.verificationScope.traversals)
        assertEquals(
            plan.verificationScope.diagnostics.map { it.files },
            decoded.verificationScope.diagnostics.map { it.files },
        )
        assertEquals(plan.requiredVerification.semanticObligations, decoded.requiredVerification.semanticObligations)
        assertEquals(plan.requiredVerification.liveObligations, decoded.requiredVerification.liveObligations)
        assertFalse(encoded.contains("generation"))
    }

    @Test
    fun `replay requests retain their exact budgets and diagnostic scope on restart`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        val scope = Json.parseToJsonElement(encoded).jsonObject.getValue("verificationScope").jsonObject
        assertEquals(JsonPrimitive(5), scope.getValue("traversals").jsonArray.single().jsonObject.getValue("depth"))
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.IDENTITY_MISMATCH,
            rejected(encoded.replace("\"depth\":5", "\"depth\":4")),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(encoded.replace("\"depth\":5", "\"depth\":0")),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(
                change(
                    encoded,
                    "verificationScope",
                    JsonObject(scope.toMutableMap().apply { put("traversals", JsonArray(emptyList())) }),
                )
            ),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(
                change(
                    encoded,
                    "verificationScope",
                    JsonObject(
                        scope.toMutableMap().apply { put("diagnostics", JsonArray(listOf(JsonArray(emptyList())))) }
                    ),
                )
            ),
        )
    }

    @Test
    fun `unknown codec reference versions and fields fail closed`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED,
            rejected(change(encoded, "schemaVersion", JsonPrimitive(4))),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED,
            rejected(change(encoded, "referenceVersion", JsonPrimitive(2))),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.MALFORMED,
            rejected(change(encoded, "unknown", JsonPrimitive("unsupported"))),
        )
    }

    @Test
    fun `owner epoch compiler target and search constraints cannot drift on restart`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(change(encoded, "owner", JsonPrimitive(UUID(0, 2).toString()))),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(change(encoded, "epoch", JsonPrimitive(8))),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(encoded.replace("\"name\":\"service\"", "\"name\":\"different\"")),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(encoded.replace("\"sourceSets\":[\"main\"]", "\"sourceSets\":[\"test\"]")),
        )
    }

    @Test
    fun `plan identity binds unrelated model roots source image intent delta and relation meaning`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        val changed =
            listOf(
                encoded.replace("\"module\":\"support\"", "\"module\":\"other\""),
                change(encoded, "sourceContent", JsonPrimitive("2".repeat(64))),
                change(encoded, "declaration", JsonPrimitive("fun added() = 2")),
                change(encoded, "expectedName", JsonPrimitive("other")),
                encoded.replace("\"meaning\":\"REFERENCES\"", "\"meaning\":\"CALLERS\""),
            )
        changed.forEach { assertEquals(LiveAddDeclarationPlanDecodeFailure.IDENTITY_MISMATCH, rejected(it)) }
    }

    @Test
    fun `generated and ambiguous target owners are not restored as authored targets`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(encoded.replaceFirst("\"provenance\":\"AUTHORED\"", "\"provenance\":\"GENERATED\"")),
        )
        val document = Json.parseToJsonElement(encoded).jsonObject
        val model = document.getValue("model").jsonArray
        val nestedOwner =
            JsonObject(
                model[0].jsonObject.toMutableMap().apply {
                    put("sourceRoot", JsonPrimitive("/workspace/app/src/main/kotlin/sample"))
                }
            )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
            rejected(change(encoded, "model", JsonArray(model + nestedOwner))),
        )
    }

    @Test
    fun `required coverage and every verification obligation survive decoding`() {
        val encoded = LiveAddDeclarationPlanCodec.encode(detachedLivePlan())
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(change(encoded, "semanticObligations", JsonArray(emptyList()))),
        )
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(change(encoded, "liveObligations", JsonArray(emptyList()))),
        )
        val evidence = Json.parseToJsonElement(encoded).jsonObject.getValue("evidence").jsonObject
        assertEquals(
            LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE,
            rejected(
                change(
                    encoded,
                    "evidence",
                    JsonObject(
                        evidence.toMutableMap().apply {
                            put("diagnostics", JsonArray(emptyList()))
                        }
                    ),
                )
            ),
        )
    }

    private fun change(encoded: String, key: String, value: JsonElement): String =
        JsonObject(Json.parseToJsonElement(encoded).jsonObject.toMutableMap().apply { put(key, value) }).toString()

    private fun rejected(encoded: String) =
        assertInstanceOf<Refinement.Rejected<LiveAddDeclarationPlanDecodeFailure>>(
                LiveAddDeclarationPlanCodec.decode(encoded)
            )
            .failure
}

private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
