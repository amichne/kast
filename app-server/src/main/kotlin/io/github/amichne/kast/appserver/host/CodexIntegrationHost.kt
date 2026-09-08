package io.github.amichne.kast.appserver.host

import io.github.amichne.kast.appserver.CodexIntegrationRun

/** Process and transport ownership selected after one shared broker has qualified its catalog. */
internal fun interface CodexIntegrationHost {
    suspend fun run(closeIntegration: suspend () -> Unit): CodexIntegrationRun
}
