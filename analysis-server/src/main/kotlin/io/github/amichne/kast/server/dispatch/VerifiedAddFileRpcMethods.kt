package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileApplyMode
import io.github.amichne.kast.server.change.VerifiedAddFileBinding
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path

/** Routes the public add-file method only through its operation-specific binding. */
internal suspend fun RpcMethodRouter.dispatchVerifiedAddFileMethod(
    method: String,
    params: JsonElement?,
): JsonElement? = when (method) {
    PLAN_ADD_FILE_METHOD -> {
        val request = decodeParams(JsonObject.serializer(), params).admitPlanRequest()
        requireVerifiedAddFileOperations().plan(request).toWire()
    }
    APPLY_ADD_FILE_METHOD -> {
        val request = decodeParams(JsonObject.serializer(), params).admitApplyRequest()
        requireVerifiedAddFileOperations().apply(request).toWire()
    }
    else -> null
}

private fun RpcMethodRouter.requireVerifiedAddFileOperations() =
    when (val binding = verifiedAddFiles) {
        VerifiedAddFileBinding.Unavailable -> throw CapabilityNotSupportedException(
            capability = VERIFIED_ADD_FILE_CAPABILITY,
            message = "The runtime has no operation-specific verified add-file binding",
        )
        is VerifiedAddFileBinding.Native -> binding.operations
    }

/**
 * Proof transition: `JsonObject -> VerifiedAddFilePlanRequest`.
 *
 * Establishes exact fields, one normalized workspace-contained Kotlin target, and LF-normalized
 * source. [VerifiedAddFileValueFailure] is projected through [ValidationException]; primitives may
 * be extracted only here at the JSON-RPC boundary.
 */
private fun JsonObject.admitPlanRequest(): VerifiedAddFilePlanRequest {
    val fields = when (val admission = ExactVerifiedAddFileFields.admit(this, PLAN_REQUEST_FIELDS)) {
        is VerifiedAddFileExactFieldsAdmission.Admitted -> admission.value
        is VerifiedAddFileExactFieldsAdmission.Rejected ->
            throw admission.failure.toValidationException()
    }
    val workspaceRoot = normalizedWorkspace(fields.stringField("workspaceRoot"))
    val target = fields.stringField("targetPath").refinedBy(VerifiedAddFileTargetPath::refine)
    if (!Path.of(target.value).startsWith(workspaceRoot.toJavaPath())) {
        throw ValidationException("TARGET_OUTSIDE_WORKSPACE")
    }
    return VerifiedAddFilePlanRequest(
        workspaceRoot = workspaceRoot,
        targetPath = target,
        proposedContent = fields.stringField("proposedContent").refinedBy(VerifiedAddFileContent::refine),
    )
}

/**
 * Proof transition: `JsonObject -> VerifiedAddFileApplyRequest`.
 *
 * Establishes exact fields, the distinct add-file PlanId, non-negative expected version, and
 * canonical approval evidence. [VerifiedAddFileValueFailure] is projected through
 * [ValidationException]; primitives may be extracted only here at the JSON-RPC boundary.
 */
private fun JsonObject.admitApplyRequest(): VerifiedAddFileApplyRequest {
    val fields = when (val admission = ExactVerifiedAddFileFields.admit(this, APPLY_REQUEST_FIELDS)) {
        is VerifiedAddFileExactFieldsAdmission.Admitted -> admission.value
        is VerifiedAddFileExactFieldsAdmission.Rejected ->
            throw admission.failure.toValidationException()
    }
    val approval = fields["approvalEvidence"] as? JsonObject
        ?: throw ValidationException("MALFORMED_WIRE_REQUEST")
    val approvalFields = when (val admission = ExactVerifiedAddFileFields.admit(approval, APPROVAL_FIELDS)) {
        is VerifiedAddFileExactFieldsAdmission.Admitted -> admission.value
        is VerifiedAddFileExactFieldsAdmission.Rejected ->
            throw admission.failure.toValidationException()
    }
    return VerifiedAddFileApplyRequest(
        workspaceRoot = normalizedWorkspace(fields.stringField("workspaceRoot")),
        planId = fields.stringField("planId").refinedBy(VerifiedAddFilePlanId::refine),
        expectedVersion = fields.longField("expectedVersion").refinedBy(VerifiedAddFilePlanVersion::refine),
        mode = fields.stringField("mode").refinedBy(VerifiedAddFileApplyMode::refine),
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = approvalFields.stringField("approvedBy").refinedBy(VerifiedAddFileApprovedBy::refine),
            evidenceSha256 = approvalFields.stringField("evidenceSha256")
                .refinedBy(VerifiedAddFileApprovalEvidenceSha256::refine),
        ),
    )
}

private inline fun <T> String.refinedBy(
    transition: (String) -> VerifiedAddFileRefinement<T>,
): T = when (val refinement = transition(this)) {
    is VerifiedAddFileRefinement.Refined -> refinement.value
    is VerifiedAddFileRefinement.Rejected -> throw ValidationException(refinement.failure.name)
}

private inline fun <T> Long.refinedBy(
    transition: (Long) -> VerifiedAddFileRefinement<T>,
): T = when (val refinement = transition(this)) {
    is VerifiedAddFileRefinement.Refined -> refinement.value
    is VerifiedAddFileRefinement.Rejected -> throw ValidationException(refinement.failure.name)
}

private class ExactVerifiedAddFileFields private constructor(
    private val fields: JsonObject,
) {
    operator fun get(name: String): JsonElement? = fields[name]

    companion object {
        /**
         * Proof transition:
         * `(JsonObject, Set<String>) -> VerifiedAddFileExactFieldsAdmission`.
         *
         * [VerifiedAddFileExactFieldsAdmission.Admitted] proves the object contains exactly the
         * operation-owned field set. Missing or additional fields produce the closed
         * [VerifiedAddFileExactFieldsFailure]. Raw values may be extracted only by the field
         * readers at this JSON-RPC request boundary.
         */
        fun admit(
            candidate: JsonObject,
            expected: Set<String>,
        ): VerifiedAddFileExactFieldsAdmission =
            if (candidate.keys == expected) {
                VerifiedAddFileExactFieldsAdmission.Admitted(ExactVerifiedAddFileFields(candidate))
            } else {
                VerifiedAddFileExactFieldsAdmission.Rejected(
                    VerifiedAddFileExactFieldsFailure.MALFORMED_WIRE_REQUEST,
                )
            }
    }
}

private sealed interface VerifiedAddFileExactFieldsAdmission {
    data class Admitted(val value: ExactVerifiedAddFileFields) : VerifiedAddFileExactFieldsAdmission

    data class Rejected(
        val failure: VerifiedAddFileExactFieldsFailure,
    ) : VerifiedAddFileExactFieldsAdmission
}

private enum class VerifiedAddFileExactFieldsFailure {
    MALFORMED_WIRE_REQUEST,
}

private fun VerifiedAddFileExactFieldsFailure.toValidationException(): ValidationException =
    ValidationException(
        message = "Invalid verified add-file request: $name",
        details = mapOf("failure" to name),
    )

private fun ExactVerifiedAddFileFields.stringField(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw ValidationException("MALFORMED_WIRE_REQUEST")
    if (!primitive.isString) throw ValidationException("MALFORMED_WIRE_REQUEST")
    return primitive.content
}

private fun ExactVerifiedAddFileFields.longField(name: String): Long {
    val primitive = this[name] as? JsonPrimitive
        ?: throw ValidationException("MALFORMED_WIRE_REQUEST")
    return primitive.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: throw ValidationException("MALFORMED_WIRE_REQUEST")
}

private fun normalizedWorkspace(raw: String): io.github.amichne.kast.api.contract.NormalizedPath {
    val path = runCatching { Path.of(raw) }.getOrNull()
        ?: throw ValidationException("WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE")
    if (!path.isAbsolute || path.normalize().toString() != raw) {
        throw ValidationException("WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE")
    }
    return io.github.amichne.kast.api.contract.NormalizedPath.ofAbsolute(path)
}

private fun VerifiedAddFilePlanResult.toWire(): JsonObject = when (this) {
    is VerifiedAddFilePlanResult.Planned -> buildJsonObject {
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("operation", "add-file")
        put("preview", buildJsonObject {
            put("targetPath", preview.targetPath.value)
            put("proposedContent", preview.proposedContent.value)
            put("generation", preview.generation.value)
        })
        put("schemaVersion", SCHEMA_VERSION)
    }
    is VerifiedAddFilePlanResult.Rejected -> buildJsonObject {
        put("failure", failure.name)
        put("operation", "add-file")
        put("schemaVersion", SCHEMA_VERSION)
    }
}

private fun VerifiedAddFileApplyResult.toWire(): JsonObject =
    when (val admission = AdmittedVerifiedAddFileApplyResult.admit(this)) {
        is VerifiedAddFileApplyResultAdmission.Admitted -> admission.value.toWire()
        is VerifiedAddFileApplyResultAdmission.Rejected -> throw AnalysisException(
            statusCode = 500,
            errorCode = "VERIFIED_ADD_FILE_RESULT_INVALID",
            message = "The native add-file result violated its closed lifecycle contract",
            details = mapOf("failure" to admission.failure.name),
        )
    }

private fun AdmittedVerifiedAddFileApplyResult.toWire(): JsonObject =
    result.toAdmittedWire()

private fun VerifiedAddFileApplyResult.toAdmittedWire(): JsonObject = when (this) {
    is VerifiedAddFileApplyResult.Verified -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("operation", "add-file")
        put("publication", buildJsonObject {
            put("generation", receipt.generation.value)
        })
        put("identity", buildJsonObject {
            put("targetPath", receipt.targetPath.value)
            put("packageName", receipt.packageIdentity.wireName())
            put("declarations", buildJsonArray {
                receipt.declarations.forEach { declaration ->
                    add(buildJsonObject {
                        put("name", declaration.name)
                        put("kind", declaration.kind.name)
                    })
                }
            })
        })
        put("postimageSha256", receipt.postimageSha256.value)
        put("schemaVersion", SCHEMA_VERSION)
    }
    is VerifiedAddFileApplyResult.Rejected -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("progress", progress.name)
        put("failure", failure.name)
        put("operation", "add-file")
        put("schemaVersion", SCHEMA_VERSION)
    }
    is VerifiedAddFileApplyResult.RolledBack -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("progress", progress.name)
        put("failure", failure.name)
        put("recoveryAction", action.name)
        put("operation", "add-file")
        put("schemaVersion", SCHEMA_VERSION)
    }
    is VerifiedAddFileApplyResult.RecoveryRequired -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("recoveryId", recoveryId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("progress", progress.name)
        put("failure", failure.name)
        put("recoveryAction", action.name)
        put("operation", "add-file")
        put("schemaVersion", SCHEMA_VERSION)
    }
    is VerifiedAddFileApplyResult.ReconciliationRequired -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("recoveryId", recoveryId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("progress", progress.name)
        put("failure", failure.name)
        put("reconciliationAction", action.name)
        put("operation", "add-file")
        put("schemaVersion", SCHEMA_VERSION)
    }
}

private fun AdditionKotlinPackage.wireName(): String = when (this) {
    AdditionKotlinPackage.Root -> ""
    is AdditionKotlinPackage.Named -> segments.joinToString(".") { it.value }
}

private const val PLAN_ADD_FILE_METHOD = "change/plan-add-file"
private const val APPLY_ADD_FILE_METHOD = "change/apply-add-file"
private const val VERIFIED_ADD_FILE_CAPABILITY = "VERIFIED_ADD_FILE"
private val PLAN_REQUEST_FIELDS = setOf("workspaceRoot", "targetPath", "proposedContent")
private val APPLY_REQUEST_FIELDS =
    setOf("workspaceRoot", "planId", "expectedVersion", "mode", "approvalEvidence")
private val APPROVAL_FIELDS = setOf("approvedBy", "evidenceSha256")
