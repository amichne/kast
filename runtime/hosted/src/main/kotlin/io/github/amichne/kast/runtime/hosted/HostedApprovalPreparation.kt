package io.github.amichne.kast.runtime.hosted

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.protocol.protocolPreview
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.io.StringReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun decodeHostedApprovalPreparation(
    root: CanonicalWorkspaceRoot,
    document: String,
): Refinement<HostedRequest, HostedEndpointFailure> {
    val fields = mutableMapOf<String, String>()
    val rejected = Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
    try {
        val reader = JsonReader(StringReader(document)).apply { isLenient = false }
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name !in setOf("operation", "planIdentity") || name in fields || reader.peek() != JsonToken.STRING)
                return rejected
            fields[name] = reader.nextString()
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT || fields.keys != setOf("operation", "planIdentity"))
            return rejected
        val effect =
            LiveChangeEffect.entries.singleOrNull { it.name == fields.getValue("operation") } ?: return rejected
        val identity = ChangePlanIdentity.parse(fields.getValue("planIdentity")) ?: return rejected
        return Refinement.Refined(HostedRequest.PrepareApproval(root, effect, identity))
    } catch (_: java.io.IOException) {
        return rejected
    } catch (_: RuntimeException) {
        return rejected
    }
}

internal fun prepareHostedApprovalResponse(
    plan: LiveAddDeclarationChangePlan,
    effect: LiveChangeEffect,
    owner: IdeReadHostLifetime,
    approvals: HostedChangeApprovals,
): HostedResponse {
    val challenge =
        when (val prepared = approvals.prepare(plan, effect)) {
            is Refinement.Refined -> prepared.value
            is Refinement.Rejected -> return HostedResponse.Rejected(HostedEndpointFailure.APPROVAL_UNAVAILABLE)
        }
    val preview = plan.protocolPreview().entries.single()
    return HostedResponse.Completed(
        Json.encodeToString(
            HostedApprovalDocument(
                version = 1,
                operation = effect.name,
                root = plan.basis.observation.reference.workspaceRoot.value,
                host = owner.value.toString(),
                planId = plan.planId.value,
                challenge = challenge.value,
                preview =
                    HostedApprovalPreviewDocument(
                        path = preview.path.value,
                        diff =
                            when (effect) {
                                LiveChangeEffect.CHANGE_APPLY -> preview.diff.value
                                LiveChangeEffect.CHANGE_RECOVER ->
                                    "@@ rollback planned declaration @@\n" +
                                        plan.declaration.value.lineSequence().joinToString("\n") { "-$it" }
                            },
                    ),
            )
        )
    )
}

@Serializable
private data class HostedApprovalDocument(
    val version: Int,
    val operation: String,
    val root: String,
    val host: String,
    val planId: String,
    val challenge: String,
    val preview: HostedApprovalPreviewDocument,
)

@Serializable private data class HostedApprovalPreviewDocument(val path: String, val diff: String)
