package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddDeclarationBinding
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanId
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanVersion
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProposedDeclaration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRequestAdmission
import io.github.amichne.kast.server.change.VerifiedAddDeclarationRequestFailure
import io.github.amichne.kast.server.change.VerifiedAddDeclarationTargetPath
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWireRefinement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path

internal suspend fun RpcMethodRouter.dispatchVerifiedAddDeclarationMethod(
    method: String,
    params: JsonElement?,
): JsonElement? = when (method) {
    PLAN_ADD_DECLARATION_METHOD -> {
        val operations = requireVerifiedAddDeclarationOperations()
        val request = decodeParams(JsonObject.serializer(), params)
            .admitPlanRequest()
            .requireAdmitted()
        operations.plan(request).toWire()
    }
    APPLY_ADD_DECLARATION_METHOD -> {
        val operations = requireVerifiedAddDeclarationOperations()
        val request = decodeParams(JsonObject.serializer(), params)
            .admitApplyRequest()
            .requireAdmitted()
        operations.apply(request).toWire()
    }
    else -> null
}

private fun RpcMethodRouter.requireVerifiedAddDeclarationOperations() =
    when (val binding = verifiedAddDeclarations) {
        VerifiedAddDeclarationBinding.Unavailable -> throw CapabilityNotSupportedException(
            capability = VERIFIED_ADD_DECLARATION_CAPABILITY,
            message = "The runtime does not provide verified add-declaration operations",
        )
        is VerifiedAddDeclarationBinding.Native -> binding.operations
    }

/**
 * Proof transition:
 * `JsonObject -> VerifiedAddDeclarationRequestAdmission<VerifiedAddDeclarationPlanRequest>`.
 *
 * Establishes one normalized workspace-contained Kotlin target and one normalized declaration. The
 * closed expected failure is [VerifiedAddDeclarationRequestFailure]. Raw strings may be extracted
 * only here at the JSON-RPC boundary.
 */
private fun JsonObject.admitPlanRequest():
    VerifiedAddDeclarationRequestAdmission<VerifiedAddDeclarationPlanRequest> {
    if (keys != PLAN_REQUEST_FIELDS) {
        return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val workspaceRoot = when (val field = stringField("workspaceRoot")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val targetPath = when (val field = stringField("targetPath")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val proposedDeclaration = when (val field = stringField("proposedDeclaration")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val workspace = when (val admission = refineWorkspaceRoot(workspaceRoot)) {
        is VerifiedAddDeclarationRequestAdmission.Admitted -> admission.request
        is VerifiedAddDeclarationRequestAdmission.Rejected -> return admission
    }
    val target = when (val refined = VerifiedAddDeclarationTargetPath.refine(targetPath)) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.TARGET_PATH_NOT_NORMALIZED_ABSOLUTE_KOTLIN,
        )
    }
    if (!Path.of(target.value).startsWith(workspace.toJavaPath())) {
        return rejected(VerifiedAddDeclarationRequestFailure.TARGET_OUTSIDE_WORKSPACE)
    }
    val declaration = when (
        val refined = VerifiedAddDeclarationProposedDeclaration.refine(proposedDeclaration)
    ) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.PROPOSED_DECLARATION_NOT_NORMALIZED,
        )
    }
    return VerifiedAddDeclarationRequestAdmission.Admitted(
        VerifiedAddDeclarationPlanRequest(workspace, target, declaration),
    )
}

/**
 * Proof transition:
 * `JsonObject -> VerifiedAddDeclarationRequestAdmission<VerifiedAddDeclarationApplyRequest>`.
 *
 * Establishes one normalized workspace root, canonical plan identity, non-negative expected version,
 * trimmed approval actor, and canonical approval digest. The closed expected failure is
 * [VerifiedAddDeclarationRequestFailure]. Raw strings and numbers may be extracted only here at the
 * JSON-RPC boundary.
 */
private fun JsonObject.admitApplyRequest():
    VerifiedAddDeclarationRequestAdmission<VerifiedAddDeclarationApplyRequest> {
    if (keys != APPLY_REQUEST_FIELDS) {
        return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val workspaceRoot = when (val field = stringField("workspaceRoot")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val planId = when (val field = stringField("planId")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val expectedVersion = when (val field = longField("expectedVersion")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val rawApprovalEvidence = this["approvalEvidence"] as? JsonObject
        ?: return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    if (rawApprovalEvidence.keys != APPROVAL_EVIDENCE_FIELDS) {
        return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val rawApprovedBy = when (val field = rawApprovalEvidence.stringField("approvedBy")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val rawEvidenceSha256 = when (val field = rawApprovalEvidence.stringField("evidenceSha256")) {
        is WireFieldRefinement.Refined -> field.value
        WireFieldRefinement.Malformed ->
            return rejected(VerifiedAddDeclarationRequestFailure.MALFORMED_WIRE_REQUEST)
    }
    val workspace = when (val admission = refineWorkspaceRoot(workspaceRoot)) {
        is VerifiedAddDeclarationRequestAdmission.Admitted -> admission.request
        is VerifiedAddDeclarationRequestAdmission.Rejected -> return admission
    }
    val admittedPlanId = when (val refined = VerifiedAddDeclarationPlanId.refine(planId)) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.PLAN_ID_NOT_CANONICAL,
        )
    }
    val version = when (val refined = VerifiedAddDeclarationPlanVersion.refine(expectedVersion)) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.EXPECTED_VERSION_NEGATIVE,
        )
    }
    if (rawApprovedBy.isBlank()) {
        return rejected(VerifiedAddDeclarationRequestFailure.APPROVED_BY_BLANK)
    }
    if (rawApprovedBy != rawApprovedBy.trim()) {
        return rejected(VerifiedAddDeclarationRequestFailure.APPROVED_BY_NOT_TRIMMED)
    }
    val approvedBy = when (
        val refined = VerifiedAddDeclarationApprovedBy.refine(rawApprovedBy)
    ) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.APPROVED_BY_NOT_TRIMMED,
        )
    }
    val evidenceSha256 = when (
        val refined = VerifiedAddDeclarationApprovalEvidenceSha256.refine(
            rawEvidenceSha256,
        )
    ) {
        is VerifiedAddDeclarationWireRefinement.Refined -> refined.value
        is VerifiedAddDeclarationWireRefinement.Rejected -> return rejected(
            VerifiedAddDeclarationRequestFailure.APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
        )
    }
    return VerifiedAddDeclarationRequestAdmission.Admitted(
        VerifiedAddDeclarationApplyRequest(
            workspaceRoot = workspace,
            planId = admittedPlanId,
            expectedVersion = version,
            approvalEvidence = VerifiedAddDeclarationApprovalEvidence(approvedBy, evidenceSha256),
        ),
    )
}

private sealed interface WireFieldRefinement<out T> {
    data class Refined<T>(val value: T) : WireFieldRefinement<T>

    data object Malformed : WireFieldRefinement<Nothing>
}

/**
 * Proof transition: `(JsonObject, field name) -> WireFieldRefinement<String>`.
 *
 * Establishes that the exact field exists as a JSON string. [WireFieldRefinement.Malformed] is the
 * closed expected failure; raw content may be extracted only here at the JSON-RPC boundary.
 */
private fun JsonObject.stringField(name: String): WireFieldRefinement<String> {
    val primitive = this[name] as? JsonPrimitive ?: return WireFieldRefinement.Malformed
    return if (primitive.isString) {
        WireFieldRefinement.Refined(primitive.content)
    } else {
        WireFieldRefinement.Malformed
    }
}

/**
 * Proof transition: `(JsonObject, field name) -> WireFieldRefinement<Long>`.
 *
 * Establishes that the exact field exists as an integral JSON number. [WireFieldRefinement.Malformed]
 * is the closed expected failure; raw numeric content may be extracted only here at the JSON-RPC
 * boundary.
 */
private fun JsonObject.longField(name: String): WireFieldRefinement<Long> {
    val primitive = this[name] as? JsonPrimitive ?: return WireFieldRefinement.Malformed
    val value = primitive.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: return WireFieldRefinement.Malformed
    return WireFieldRefinement.Refined(value)
}

private fun VerifiedAddDeclarationPlanResult.toWire(): JsonObject = when (this) {
    is VerifiedAddDeclarationPlanResult.Planned -> buildJsonObject {
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("stage", stage.name)
        put("operation", "add-declaration")
        put("preview", buildJsonObject {
            put("targetPath", preview.targetPath.value)
            put("proposedDeclaration", preview.proposedDeclaration.value)
            put("generation", preview.generation.value)
        })
        put("schemaVersion", schemaVersion)
    }
    is VerifiedAddDeclarationPlanResult.Rejected -> buildJsonObject {
        put("failure", failure.name)
        put("operation", "add-declaration")
        put("schemaVersion", schemaVersion)
    }
}

private fun VerifiedAddDeclarationApplyResult.toWire(): JsonObject = when (this) {
    is VerifiedAddDeclarationApplyResult.Verified -> buildJsonObject {
        put("outcome", outcome.name)
        put("planId", planId.value)
        put("planVersion", planVersion.value)
        put("operation", "add-declaration")
        put("publication", buildJsonObject {
            put("generation", publication.generation.value)
            put("workspaceStateIdentity", publication.workspaceStateIdentity.value)
        })
        put("identity", buildJsonObject {
            put("targetPath", identity.targetPath.value)
            put("sourceRange", buildJsonObject {
                put("startOffset", identity.sourceRange.startOffset)
                put("endOffset", identity.sourceRange.endOffset)
            })
            put("packageName", identity.packageName.value)
            put("declarationName", identity.declarationName.value)
            put("declarationKind", identity.declarationKind.name)
        })
        put("postimageSha256", postimageSha256.value)
        put("schemaVersion", schemaVersion)
    }
    is VerifiedAddDeclarationApplyResult.Rejected -> buildJsonObject {
        putCommonApplyOutcome(this@toWire)
        put("failure", failure.name)
    }
    is VerifiedAddDeclarationApplyResult.RecoveryRequired -> buildJsonObject {
        putCommonApplyOutcome(this@toWire)
        put("action", action.name)
    }
    is VerifiedAddDeclarationApplyResult.ReconciliationRequired -> buildJsonObject {
        putCommonApplyOutcome(this@toWire)
        put("action", action.name)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putCommonApplyOutcome(
    result: VerifiedAddDeclarationApplyResult.Incomplete,
) {
    put("outcome", result.outcome.name)
    put("planId", result.planId.value)
    put("planVersion", result.planVersion.value)
    put("stage", result.stage.name)
    put("progress", result.progress.name)
    put("operation", "add-declaration")
    put("schemaVersion", result.schemaVersion)
}

/**
 * Proof transition: `String -> VerifiedAddDeclarationRequestAdmission<NormalizedPath>`.
 *
 * Establishes a normalized absolute workspace root. The closed expected failure is
 * [VerifiedAddDeclarationRequestFailure.WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE]. Raw path extraction
 * is permitted only here at the JSON-RPC boundary.
 */
private fun refineWorkspaceRoot(
    raw: String,
): VerifiedAddDeclarationRequestAdmission<NormalizedPath> {
    val path = runCatching { Path.of(raw) }.getOrNull()
        ?: return rejected(
            VerifiedAddDeclarationRequestFailure.WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE,
        )
    if (!path.isAbsolute || path.normalize().toString() != raw) {
        return rejected(VerifiedAddDeclarationRequestFailure.WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE)
    }
    return VerifiedAddDeclarationRequestAdmission.Admitted(NormalizedPath.ofAbsolute(path))
}

private fun <T> VerifiedAddDeclarationRequestAdmission<T>.requireAdmitted(): T = when (this) {
    is VerifiedAddDeclarationRequestAdmission.Admitted -> request
    is VerifiedAddDeclarationRequestAdmission.Rejected -> throw ValidationException(
        message = "Invalid verified add-declaration request: ${failure.name}",
        details = mapOf("failure" to failure.name),
    )
}

private fun rejected(
    failure: VerifiedAddDeclarationRequestFailure,
): VerifiedAddDeclarationRequestAdmission.Rejected =
    VerifiedAddDeclarationRequestAdmission.Rejected(failure)

private const val PLAN_ADD_DECLARATION_METHOD = "change/plan-add-declaration"
private const val APPLY_ADD_DECLARATION_METHOD = "change/apply-add-declaration"
private const val VERIFIED_ADD_DECLARATION_CAPABILITY = "VERIFIED_ADD_DECLARATION"
private val PLAN_REQUEST_FIELDS = setOf("workspaceRoot", "targetPath", "proposedDeclaration")
private val APPLY_REQUEST_FIELDS = setOf(
    "workspaceRoot",
    "planId",
    "expectedVersion",
    "approvalEvidence",
)
private val APPROVAL_EVIDENCE_FIELDS = setOf("approvedBy", "evidenceSha256")
