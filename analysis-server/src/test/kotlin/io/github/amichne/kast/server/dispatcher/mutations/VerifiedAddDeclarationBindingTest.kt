package io.github.amichne.kast.server

import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.server.change.NativeVerifiedAddDeclarationOperations
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationBinding
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationIdentity
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationKind
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationName
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPackageName
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanId
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanPreview
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanVersion
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPostimageSha256
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProposedDeclaration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPublication
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPublicationGeneration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationSourceRange
import io.github.amichne.kast.server.change.VerifiedAddDeclarationTargetPath
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWireRefinement
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWorkspaceStateIdentity
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class VerifiedAddDeclarationBindingTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `verified add declaration routes fail closed without a native binding`() {
        listOf(
            "change/plan-add-declaration" to planParams(),
            "change/apply-add-declaration" to applyParams(),
        ).forEach { (method, params) ->
            val response = dispatchRaw(method, params)
            val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

            assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
            assertEquals("VERIFIED_ADD_DECLARATION", error.error.data?.details?.get("capability"))
        }
    }

    @Test
    fun `native binding receives refined plan and apply requests and returns its exact durable wire`() {
        val planId = refined(VerifiedAddDeclarationPlanId.refine("4".repeat(64)))
        val planVersion = refined(VerifiedAddDeclarationPlanVersion.refine(0L))
        val targetPath = refined(VerifiedAddDeclarationTargetPath.refine(sampleFile().toString()))
        val declaration = refined(VerifiedAddDeclarationProposedDeclaration.refine("class Added"))
        val generation = refined(VerifiedAddDeclarationPublicationGeneration.refine(7L))
        val verifiedVersion = refined(VerifiedAddDeclarationPlanVersion.refine(5L))
        val publicationGeneration = refined(VerifiedAddDeclarationPublicationGeneration.refine(8L))
        val stateIdentity = refined(
            VerifiedAddDeclarationWorkspaceStateIdentity.refine("verified-add-declaration-g1"),
        )
        val sourceRange = refined(VerifiedAddDeclarationSourceRange.refine(16, 27))
        val packageName = refined(VerifiedAddDeclarationPackageName.refine(""))
        val declarationName = refined(VerifiedAddDeclarationDeclarationName.refine("Added"))
        val postimageSha256 = refined(VerifiedAddDeclarationPostimageSha256.refine("a".repeat(64)))
        val planResult = VerifiedAddDeclarationPlanResult.Planned(
            planId = planId,
            planVersion = planVersion,
            preview = VerifiedAddDeclarationPlanPreview(
                targetPath = targetPath,
                proposedDeclaration = declaration,
                generation = generation,
            ),
        )
        val verifiedResult = VerifiedAddDeclarationApplyResult.Verified(
            planId = planId,
            planVersion = verifiedVersion,
            publication = VerifiedAddDeclarationPublication(
                generation = publicationGeneration,
                workspaceStateIdentity = stateIdentity,
            ),
            identity = VerifiedAddDeclarationDeclarationIdentity(
                targetPath = targetPath,
                sourceRange = sourceRange,
                packageName = packageName,
                declarationName = declarationName,
                declarationKind = VerifiedAddDeclarationDeclarationKind.CLASS,
            ),
            postimageSha256 = postimageSha256,
        )
        var observedPlan: io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest? = null
        var observedApply: io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest? = null
        val binding = VerifiedAddDeclarationBinding.Native(
            object : NativeVerifiedAddDeclarationOperations {
                override suspend fun plan(
                    request: io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest,
                ): VerifiedAddDeclarationPlanResult {
                    observedPlan = request
                    return planResult
                }

                override suspend fun apply(
                    request: io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest,
                ): VerifiedAddDeclarationApplyResult {
                    observedApply = request
                    return verifiedResult
                }
            },
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            verifiedAddDeclarations = binding,
        )

        val planWire = dispatchSuccess(dispatcher, "change/plan-add-declaration", planParams())
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), observedPlan?.workspaceRoot?.value)
        assertEquals(sampleFile().toAbsolutePath().normalize().toString(), observedPlan?.targetPath?.value)
        assertEquals("class Added", observedPlan?.proposedDeclaration?.value)
        assertEquals(
            buildJsonObject {
                put("planId", "4".repeat(64))
                put("planVersion", 0)
                put("stage", "AWAITING_APPROVAL")
                put("operation", "add-declaration")
                put("preview", buildJsonObject {
                    put("targetPath", sampleFile().toAbsolutePath().normalize().toString())
                    put("proposedDeclaration", "class Added")
                    put("generation", 7)
                })
                put("schemaVersion", 7)
            },
            planWire,
        )

        val applyWire = dispatchSuccess(dispatcher, "change/apply-add-declaration", applyParams())
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), observedApply?.workspaceRoot?.value)
        assertEquals("4".repeat(64), observedApply?.planId?.value)
        assertEquals(0L, observedApply?.expectedVersion?.value)
        assertEquals("reviewer", observedApply?.approvalEvidence?.approvedBy?.value)
        assertEquals("b".repeat(64), observedApply?.approvalEvidence?.evidenceSha256?.value)
        assertEquals(
            buildJsonObject {
                put("outcome", "VERIFIED")
                put("planId", "4".repeat(64))
                put("planVersion", 5)
                put("operation", "add-declaration")
                put("publication", buildJsonObject {
                    put("generation", 8)
                    put("workspaceStateIdentity", "verified-add-declaration-g1")
                })
                put("identity", buildJsonObject {
                    put("targetPath", sampleFile().toAbsolutePath().normalize().toString())
                    put("sourceRange", buildJsonObject {
                        put("startOffset", 16)
                        put("endOffset", 27)
                    })
                    put("packageName", "")
                    put("declarationName", "Added")
                    put("declarationKind", "CLASS")
                })
                put("postimageSha256", "a".repeat(64))
                put("schemaVersion", 7)
            },
            applyWire,
        )
    }

    @Test
    fun `apply boundary rejects every untrusted lifecycle primitive before invoking native code`() {
        var applyInvoked = false
        val binding = VerifiedAddDeclarationBinding.Native(
            object : NativeVerifiedAddDeclarationOperations {
                override suspend fun plan(
                    request: io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest,
                ): VerifiedAddDeclarationPlanResult = fail("plan must not be invoked")

                override suspend fun apply(
                    request: io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest,
                ): VerifiedAddDeclarationApplyResult {
                    applyInvoked = true
                    return fail("apply must not be invoked")
                }
            },
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            verifiedAddDeclarations = binding,
        )
        listOf(
            JsonObject(applyParams() + ("unexpected" to JsonPrimitive(true))) to
                "MALFORMED_WIRE_REQUEST",
            applyParams(planId = "F".repeat(64)) to "PLAN_ID_NOT_CANONICAL",
            applyParams(expectedVersion = -1L) to "EXPECTED_VERSION_NEGATIVE",
            applyParams(approvedBy = "") to "APPROVED_BY_BLANK",
            applyParams(approvedBy = " reviewer ") to "APPROVED_BY_NOT_TRIMMED",
            applyParams(evidenceSha256 = "B".repeat(64)) to
                "APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL",
        ).forEach { (invalid, expectedFailure) ->
            val raw = runBlocking {
                dispatcher.dispatch(
                    JsonRpcRequest(
                        id = JsonPrimitive(1),
                        method = "change/apply-add-declaration",
                        params = invalid,
                    ),
                )
            }
            val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

            assertEquals("VALIDATION_ERROR", error.error.data?.code)
            assertEquals(expectedFailure, error.error.data?.details?.get("failure"))
        }
        assertEquals(false, applyInvoked)
    }

    @Test
    fun `legacy add declaration planning and write entrypoints are retired`() {
        listOf("raw/plan-add-declaration", "symbol/add-declaration").forEach { method ->
            val response = dispatchRaw(method, buildJsonObject {})
            val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

            assertEquals(-32601, error.error.code, method)
        }
    }

    private fun planParams(): JsonObject = buildJsonObject {
        put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
        put("targetPath", sampleFile().toAbsolutePath().normalize().toString())
        put("proposedDeclaration", "class Added")
    }

    private fun applyParams(
        planId: String = "4".repeat(64),
        expectedVersion: Long = 0L,
        approvedBy: String = "reviewer",
        evidenceSha256: String = "b".repeat(64),
    ): JsonObject = buildJsonObject {
        put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
        put("planId", planId)
        put("expectedVersion", expectedVersion)
        put("approvalEvidence", buildJsonObject {
            put("approvedBy", approvedBy)
            put("evidenceSha256", evidenceSha256)
        })
    }

    private fun dispatchSuccess(
        dispatcher: RpcAnalysisDispatcher,
        method: String,
        params: JsonObject,
    ): JsonObject {
        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(id = JsonPrimitive(1), method = method, params = params),
            )
        }
        val envelope = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        return envelope.result.jsonObject
    }

    private fun <T> refined(refinement: VerifiedAddDeclarationWireRefinement<T>): T =
        when (refinement) {
            is VerifiedAddDeclarationWireRefinement.Refined -> refinement.value
            is VerifiedAddDeclarationWireRefinement.Rejected -> {
                assertNotNull(refinement.failure)
                fail("Unexpected test fixture rejection: ${refinement.failure}")
            }
        }
}
