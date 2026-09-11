package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Correlates a bounded stored-plan challenge with the admitted endpoint and exact request. */
internal fun admitHostedApprovalChallenge(
    raw: ByteArray,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
    operation: ExistingIdeOperation.ApprovalPreparation,
): Refinement<String, ExistingIdeFailure> {
    val rejected = Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    if (raw.size !in 1..300_000) return rejected
    return try {
        val node = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject ?: return rejected
        if (node.keys != setOf("version", "operation", "root", "host", "planId", "challenge", "preview"))
            return rejected
        fun string(key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (
            node["version"] != JsonPrimitive(1) ||
                string("operation") != operation.kind.name ||
                string("root") != root.path.toString() ||
                string("host") != descriptor.host.toString() ||
                string("planId") != operation.identity.value.removePrefix("plan:") ||
                string("challenge")?.matches(Regex("[0-9a-f]{64}")) != true
        )
            return rejected
        val preview = node["preview"] as? JsonObject ?: return rejected
        if (preview.keys != setOf("path", "diff")) return rejected
        val path = (preview["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return rejected
        val diff = (preview["diff"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return rejected
        val relative = Path.of(path)
        if (
            path.toByteArray(Charsets.UTF_8).size !in 1..4096 ||
                relative.isAbsolute ||
                relative.normalize() != relative ||
                relative.any { it.toString() in setOf("..", ".") } ||
                '\\' in path ||
                diff.toByteArray(Charsets.UTF_8).size !in 1..262144
        )
            return rejected
        Refinement.Refined(node.toString())
    } catch (_: IllegalArgumentException) {
        rejected
    }
}
