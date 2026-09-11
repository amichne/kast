package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.ObserverFileChange
import io.github.amichne.kast.appserver.core.ObserverFileChangeKind
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalSubject
import io.github.amichne.kast.appserver.runtime.HostedApprovalOwnerId
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.appserver.runtime.document
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object KastHostedPlanChallengeDecoder {
    fun decode(
        request: HostedPlanApprovalRequest,
        raw: String,
    ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> {
        val document =
            try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
        if (
            document.keys != setOf("version", "operation", "root", "host", "planId", "challenge", "preview") ||
                document["version"] != JsonPrimitive(1) ||
                document.string("operation") != request.operation.canonical.name
        )
            return Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
        val subject =
            when (val admitted = subject(document, request)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        val change =
            when (val admitted = preview(document)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        return HostedPlanApprovalChallenge.fromStoredPlan(request, subject, change)
    }

    private fun subject(
        document: JsonObject,
        request: HostedPlanApprovalRequest,
    ): Refinement<ExactPlanApprovalSubject, HostedPlanApprovalFailure> {
        val root =
            try {
                document.string("root")?.let { CanonicalBrokerDirectory.admit(Path.of(it)) }
            } catch (_: InvalidPathException) {
                null
            } ?: return Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
        val host =
            when (val admitted = HostedApprovalOwnerId.admit(document.string("host").orEmpty())) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        val subject =
            when (
                val admitted =
                    ExactPlanApprovalSubject.admit(
                        planIdentity = document.string("planId").orEmpty(),
                        hostedChallenge = document.string("challenge").orEmpty(),
                        operation = request.operation,
                        root = root,
                        host = host,
                    )
            ) {
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
                is Refinement.Refined -> admitted.value
            }
        return Refinement.Refined(subject)
    }

    private fun preview(document: JsonObject): Refinement<ObserverFileChange, HostedPlanApprovalFailure> {
        val preview =
            document["preview"] as? JsonObject ?: return Refinement.Rejected(HostedPlanApprovalFailure.PREVIEW_REJECTED)
        if (preview.keys != setOf("path", "diff"))
            return Refinement.Rejected(HostedPlanApprovalFailure.PREVIEW_REJECTED)
        val change =
            when (
                val admitted =
                    ObserverFileChange.admit(
                        preview.string("path").orEmpty(),
                        ObserverFileChangeKind.UPDATE,
                        preview.string("diff").orEmpty(),
                    )
            ) {
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.PREVIEW_REJECTED)
                is Refinement.Refined -> admitted.value
            }
        return Refinement.Refined(change)
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
