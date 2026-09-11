package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangePreviewDiff
import io.github.amichne.kast.protocol.contract.ChangePreviewPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAXIMUM_CHALLENGE_BYTES = 300_000
private const val MAXIMUM_PREVIEW_PATH_BYTES = 4096
private const val MAXIMUM_PREVIEW_DIFF_BYTES = 262144
private val challengeRejected = Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)

/** Correlates a bounded stored-plan challenge with the admitted endpoint and exact request. */
internal fun admitHostedApprovalChallenge(
    raw: ByteArray,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
    operation: ExistingIdeOperation.ApprovalPreparation,
): Refinement<String, ExistingIdeFailure> {
    if (raw.size !in 1..MAXIMUM_CHALLENGE_BYTES) return challengeRejected
    return try {
        val node = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject ?: return challengeRejected
        if (node.keys != setOf("version", "operation", "root", "host", "planId", "challenge", "preview"))
            return challengeRejected
        if (node["version"] != JsonPrimitive(1) || node.string("operation") != operation.kind.name)
            return challengeRejected
        if (node.string("root") != root.path.toString() || node.string("host") != descriptor.host.toString())
            return challengeRejected
        if (node.string("planId") != operation.identity.value.removePrefix("plan:")) return challengeRejected
        if (node.string("challenge")?.matches(Regex("[0-9a-f]{64}")) != true) return challengeRejected
        when (val preview = admitChallengePreview(node)) {
            is Refinement.Refined -> Refinement.Refined(node.toString())
            is Refinement.Rejected -> preview
        }
    } catch (_: IllegalArgumentException) {
        challengeRejected
    }
}

private fun admitChallengePreview(node: JsonObject): Refinement<Unit, ExistingIdeFailure> {
    val preview = node["preview"] as? JsonObject ?: return challengeRejected
    if (preview.keys != setOf("path", "diff")) return challengeRejected
    val path = preview.string("path") ?: return challengeRejected
    val diff = preview.string("diff") ?: return challengeRejected
    if (
        path.toByteArray(Charsets.UTF_8).size !in 1..MAXIMUM_PREVIEW_PATH_BYTES ||
            ChangePreviewPath.parse(path) is Refinement.Rejected
    )
        return challengeRejected
    if (
        diff.toByteArray(Charsets.UTF_8).size !in 1..MAXIMUM_PREVIEW_DIFF_BYTES ||
            ChangePreviewDiff.parse(diff) is Refinement.Rejected
    )
        return challengeRejected
    return Refinement.Refined(Unit)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
