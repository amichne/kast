package io.github.amichne.kast.idea.backend

internal fun loadIndexerVersion(): String =
    KastIndexerBackend::class.java
        .getResource("/kast-indexer-version.txt")
        ?.readText()
        ?.trim()
        ?: "unknown"
