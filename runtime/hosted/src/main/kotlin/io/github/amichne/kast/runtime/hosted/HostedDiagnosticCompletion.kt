package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadCompletionPolicy

/** Only diagnostics opt into caller-time accounting through final freshness and encoded publication. */
internal fun HostedRequest.Read.completionPolicy(): HostedReadCompletionPolicy =
    when (this) {
        is HostedRequest.Diagnostic -> HostedReadCompletionPolicy.CALLER_ELAPSED
        is HostedRequest.Query,
        is HostedRequest.Discover,
        is HostedRequest.Inspect,
        is HostedRequest.Source,
        is HostedRequest.Traversal -> HostedReadCompletionPolicy.HOST_CONTAINMENT
    }
