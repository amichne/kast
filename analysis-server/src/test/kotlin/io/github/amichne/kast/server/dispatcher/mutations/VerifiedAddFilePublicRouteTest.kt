package io.github.amichne.kast.server

import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.result.AdditionDeclarationCollisionSignature
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclarationKind
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.server.change.NativeVerifiedAddFileOperations
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyMode
import io.github.amichne.kast.server.change.VerifiedAddFileBinding
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanPreview
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddFileReceipt
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class VerifiedAddFilePublicRouteTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `native binding routes the plan and every finite apply outcome on the public wire`() {
        val target = tempDir.resolve("src/main/kotlin/demo/Added.kt")
            .toAbsolutePath()
            .normalize()
        val planId = refined(VerifiedAddFilePlanId.refine("af-" + "4".repeat(64)))
        val recoveryId = VerifiedAddFileRecoveryId.fromPlan(planId)
        val initialVersion = refined(VerifiedAddFilePlanVersion.refine(0L))
        val terminalVersion = refined(VerifiedAddFilePlanVersion.refine(5L))
        val planResult = VerifiedAddFilePlanResult.Planned(
            planId = planId,
            planVersion = initialVersion,
            preview = VerifiedAddFilePlanPreview(
                targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
                proposedContent = refined(
                    VerifiedAddFileContent.refine("package demo\n\nclass Added\n"),
                ),
                generation = MutationSemanticGeneration(7L),
            ),
        )
        val receipt = VerifiedAddFileReceipt(
            targetPath = AdditionTargetPath.parse(target.toString()),
            postimageSha256 = AdditionPostimageSha256.of("a".repeat(64)),
            generation = MutationSemanticGeneration(8L),
            packageIdentity = AdditionKotlinPackage.Named.of("demo"),
            declarations = listOf(
                AdditionTopLevelDeclaration.of(
                    packageIdentity = AdditionKotlinPackage.Named.of("demo"),
                    name = "Added",
                    kind = AdditionTopLevelDeclarationKind.CLASS,
                    relativeStartOffset = 14,
                    relativeEndOffset = 25,
                    collisionSignature = AdditionDeclarationCollisionSignature.of("c".repeat(64)),
                ),
            ),
        )
        val outcomes = listOf(
            VerifiedAddFileApplyResult.Verified(planId, terminalVersion, receipt),
            VerifiedAddFileApplyResult.Rejected(
                planId,
                initialVersion,
                VerifiedAddFilePlanStage.AWAITING_APPROVAL,
                VerifiedAddFileProgress.PLANNING,
                VerifiedAddFileFailure.TARGET_GENERATED,
            ),
            VerifiedAddFileApplyResult.Rejected(
                planId,
                initialVersion,
                VerifiedAddFilePlanStage.RECOVERY_PREPARED,
                VerifiedAddFileProgress.RECOVERY_PREPARATION,
                VerifiedAddFileFailure.PLAN_NOT_FOUND,
            ),
            VerifiedAddFileApplyResult.RolledBack(
                planId,
                terminalVersion,
                VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.PSI_NOT_ADMITTED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            ),
            VerifiedAddFileApplyResult.RecoveryRequired(
                planId,
                recoveryId,
                initialVersion,
                VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                VerifiedAddFileFailure.PUBLICATION_FAILED,
                VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            ),
            VerifiedAddFileApplyResult.ReconciliationRequired(
                planId,
                recoveryId,
                initialVersion,
                VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                VerifiedAddFileProgress.PSI_ADMISSION,
                VerifiedAddFileFailure.PSI_NOT_ADMITTED,
                VerifiedAddFileReconciliationAction.INSPECT_TARGET,
            ),
        )
        var nextOutcome: VerifiedAddFileApplyResult = outcomes.first()
        var observedPlan: VerifiedAddFilePlanRequest? = null
        var observedApply: VerifiedAddFileApplyRequest? = null
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            verifiedAddFiles = VerifiedAddFileBinding.Native(
                object : NativeVerifiedAddFileOperations {
                    override suspend fun plan(
                        request: VerifiedAddFilePlanRequest,
                    ): VerifiedAddFilePlanResult {
                        observedPlan = request
                        return planResult
                    }

                    override suspend fun apply(
                        request: VerifiedAddFileApplyRequest,
                    ): VerifiedAddFileApplyResult {
                        observedApply = request
                        return nextOutcome
                    }
                },
            ),
        )

        val planWire = dispatchSuccess(dispatcher, "change/plan-add-file", planParams())
        assertEquals("AWAITING_APPROVAL", planWire["stage"]?.jsonPrimitive?.content)
        assertEquals(target.toString(), observedPlan?.targetPath?.value)
        assertEquals("package demo\n\nclass Added\n", observedPlan?.proposedContent?.value)
        outcomes.forEach { outcome ->
            nextOutcome = outcome
            val wire = dispatchSuccess(dispatcher, "change/apply-add-file", applyParams())
            val expectedOutcome = when (outcome) {
                is VerifiedAddFileApplyResult.Verified -> "VERIFIED"
                is VerifiedAddFileApplyResult.Rejected -> "REJECTED"
                is VerifiedAddFileApplyResult.RolledBack -> "ROLLED_BACK"
                is VerifiedAddFileApplyResult.RecoveryRequired -> "RECOVERY_REQUIRED"
                is VerifiedAddFileApplyResult.ReconciliationRequired -> "RECONCILIATION_REQUIRED"
            }
            assertEquals(expectedOutcome, wire["outcome"]?.jsonPrimitive?.content)
        }
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), observedApply?.workspaceRoot?.value)
        assertEquals(planId, observedApply?.planId)
        assertEquals(initialVersion, observedApply?.expectedVersion)
        assertEquals(VerifiedAddFileApplyMode.APPLY, observedApply?.mode)
        assertEquals("kast-public-cli", observedApply?.approvalEvidence?.approvedBy?.value)
        assertEquals("b".repeat(64), observedApply?.approvalEvidence?.evidenceSha256?.value)
    }

    @Test
    fun `recovery-capable apply results retain their distinct recovery identity on the public wire`() {
        val planId = refined(VerifiedAddFilePlanId.refine("af-" + "4".repeat(64)))
        val recoveryId = VerifiedAddFileRecoveryId.fromPlan(planId)
        val version = refined(VerifiedAddFilePlanVersion.refine(0L))
        val results = listOf(
            VerifiedAddFileApplyResult.RecoveryRequired(
                planId = planId,
                recoveryId = recoveryId,
                planVersion = version,
                stage = VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                progress = VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                failure = VerifiedAddFileFailure.PUBLICATION_FAILED,
                action = VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            ),
            VerifiedAddFileApplyResult.ReconciliationRequired(
                planId = planId,
                recoveryId = recoveryId,
                planVersion = version,
                stage = VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                progress = VerifiedAddFileProgress.PSI_ADMISSION,
                failure = VerifiedAddFileFailure.PSI_NOT_ADMITTED,
                action = VerifiedAddFileReconciliationAction.INSPECT_TARGET,
            ),
        )

        results.forEach { result ->
            val dispatcher = dispatcherReturning(result)
            val wire = dispatchSuccess(dispatcher, "change/apply-add-file", applyParams())

            assertEquals(recoveryId.value, wire["recoveryId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `public route rejects every incompatible lifecycle dimension`() {
        val planId = refined(VerifiedAddFilePlanId.refine("af-" + "4".repeat(64)))
        val initialVersion = refined(VerifiedAddFilePlanVersion.refine(0L))
        val terminalVersion = refined(VerifiedAddFilePlanVersion.refine(5L))
        val validRejected = VerifiedAddFileApplyResult.Rejected(
            planId = planId,
            planVersion = initialVersion,
            stage = VerifiedAddFilePlanStage.AWAITING_APPROVAL,
            progress = VerifiedAddFileProgress.PLANNING,
            failure = VerifiedAddFileFailure.TARGET_GENERATED,
        )
        val invalidResults = listOf(
            "stage" to validRejected.copy(stage = VerifiedAddFilePlanStage.APPROVED),
            "progress" to validRejected.copy(progress = VerifiedAddFileProgress.REVALIDATION),
            "failure" to validRejected.copy(failure = VerifiedAddFileFailure.PUBLICATION_FAILED),
            "version" to validRejected.copy(planVersion = terminalVersion),
            "recovery-id" to VerifiedAddFileApplyResult.RecoveryRequired(
                planId = planId,
                recoveryId = VerifiedAddFileRecoveryId.fromPlan(
                    refined(VerifiedAddFilePlanId.refine("af-" + "5".repeat(64))),
                ),
                planVersion = initialVersion,
                stage = VerifiedAddFilePlanStage.APPLIED_UNVERIFIED,
                progress = VerifiedAddFileProgress.WORKSPACE_PUBLICATION,
                failure = VerifiedAddFileFailure.PUBLICATION_FAILED,
                action = VerifiedAddFileRecoveryDispositionAction.DELETE_CREATED_TARGET,
            ),
        )

        invalidResults.forEach { (dimension, invalid) ->
            val raw = runBlocking {
                dispatcherReturning(invalid).dispatch(
                    JsonRpcRequest(
                        id = JsonPrimitive(1),
                        method = "change/apply-add-file",
                        params = applyParams(),
                    ),
                )
            }
            val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

            assertEquals("VERIFIED_ADD_FILE_RESULT_INVALID", error.error.data?.code, dimension)
            assertEquals(
                "INCOMPATIBLE_LIFECYCLE",
                error.error.data?.details?.get("failure"),
                dimension,
            )
        }
    }

    @Test
    fun `missing and additional request fields fail before binding lookup`() {
        val approval = applyParams()["approvalEvidence"]!!.jsonObject
        val cases = listOf(
            "change/plan-add-file" to JsonObject(
                planParams().filterKeys { it != "proposedContent" },
            ),
            "change/plan-add-file" to JsonObject(
                planParams() + ("unexpected" to JsonPrimitive(true)),
            ),
            "change/apply-add-file" to JsonObject(
                applyParams().filterKeys { it != "expectedVersion" },
            ),
            "change/apply-add-file" to JsonObject(
                applyParams() + (
                    "approvalEvidence" to JsonObject(
                        approval + ("unexpected" to JsonPrimitive(true)),
                    )
                ),
            ),
        )

        cases.forEach { (method, params) ->
            val error = json.decodeFromJsonElement(
                JsonRpcErrorResponse.serializer(),
                dispatchRaw(method, params),
            )

            assertEquals("VALIDATION_ERROR", error.error.data?.code, method)
            assertEquals("MALFORMED_WIRE_REQUEST", error.error.data?.details?.get("failure"), method)
        }
    }

    @Test
    fun `verified add file lifecycle fails closed without an operation-specific binding`() {
        listOf(
            "change/plan-add-file" to buildJsonObject {
                put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
                put("targetPath", tempDir.resolve("src/main/kotlin/demo/Added.kt").toString())
                put("proposedContent", "package demo\n\nclass Added\n")
            },
            "change/apply-add-file" to buildJsonObject {
                put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
                put("planId", "af-" + "4".repeat(64))
                put("expectedVersion", 0)
                put("mode", "APPLY")
                put("approvalEvidence", buildJsonObject {
                    put("approvedBy", "kast-public-cli")
                    put("evidenceSha256", "b".repeat(64))
                })
            },
        ).forEach { (method, params) ->
            val response = dispatchRaw(method, params)
            val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

            assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code, method)
            assertEquals("VERIFIED_ADD_FILE", error.error.data?.details?.get("capability"), method)
        }
    }

    @Test
    fun `legacy one-shot and raw plan add file entrypoints are retired`() {
        listOf("symbol/add-file", "raw/plan-add-file").forEach { method ->
            val response = dispatchRaw(method, buildJsonObject {})
            val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

            assertEquals(-32601, error.error.code, method)
        }
    }

    private fun dispatcherReturning(result: VerifiedAddFileApplyResult): RpcAnalysisDispatcher =
        RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            verifiedAddFiles = VerifiedAddFileBinding.Native(
                object : NativeVerifiedAddFileOperations {
                    override suspend fun plan(
                        request: VerifiedAddFilePlanRequest,
                    ): VerifiedAddFilePlanResult = error("plan must not be invoked")

                    override suspend fun apply(
                        request: VerifiedAddFileApplyRequest,
                    ): VerifiedAddFileApplyResult = result
                },
            ),
        )

    private fun applyParams(): JsonObject = buildJsonObject {
        put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
        put("planId", "af-" + "4".repeat(64))
        put("expectedVersion", 0)
        put("mode", "APPLY")
        put("approvalEvidence", buildJsonObject {
            put("approvedBy", "kast-public-cli")
            put("evidenceSha256", "b".repeat(64))
        })
    }

    private fun planParams(): JsonObject = buildJsonObject {
        put("workspaceRoot", tempDir.toAbsolutePath().normalize().toString())
        put(
            "targetPath",
            tempDir.resolve("src/main/kotlin/demo/Added.kt").toAbsolutePath().normalize().toString(),
        )
        put("proposedContent", "package demo\n\nclass Added\n")
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
        return json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw).result.jsonObject
    }

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        when (refinement) {
            is VerifiedAddFileRefinement.Refined -> refinement.value
            is VerifiedAddFileRefinement.Rejected -> error("Unexpected fixture rejection: ${refinement.failure}")
        }
}
