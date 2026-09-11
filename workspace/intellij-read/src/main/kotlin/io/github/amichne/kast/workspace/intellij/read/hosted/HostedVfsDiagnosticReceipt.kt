package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.Gson
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime

/** Only finite labels, bounded counts and existing host correlation cross the log boundary. */
internal fun publishHostedVfsEvidence(
    host: IdeReadHostLifetime,
    evidence: HostedVfsBatchEvidence,
    publish: (String) -> Unit,
) {
    val receipt =
        when (evidence) {
            HostedVfsBatchEvidence.OutsideRoot -> return
            is HostedVfsBatchEvidence.Observed ->
                mapOf(
                    "event" to "kast_hosted_vfs",
                    "host" to host.value.toString(),
                    "outcome" to "observed",
                    "pathCategoriesAreSyntactic" to true,
                    "counts" to
                        evidence.counts.map { count ->
                            mapOf(
                                "kind" to count.coordinate.kind.name,
                                "origin" to count.coordinate.origin.name,
                                "category" to count.coordinate.category.name,
                                "paths" to count.paths.value,
                            )
                        },
                )
            is HostedVfsBatchEvidence.Rejected ->
                mapOf(
                    "event" to "kast_hosted_vfs",
                    "host" to host.value.toString(),
                    "outcome" to "rejected",
                    "failure" to evidence.failure.name,
                )
        }
    publish(Gson().toJson(receipt))
}
