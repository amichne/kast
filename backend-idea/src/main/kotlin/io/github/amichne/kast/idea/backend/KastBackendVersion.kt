package io.github.amichne.kast.idea.backend

internal fun loadBackendVersion(): String =
    KastPluginBackend::class.java
        .getResource("/kast-backend-version.txt")
        ?.readText()
        ?.trim()
        ?: "unknown"
