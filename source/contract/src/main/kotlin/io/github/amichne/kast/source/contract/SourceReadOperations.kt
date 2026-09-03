package io.github.amichne.kast.source.contract

/** Sole host-neutral public operation boundary for one bounded authoritative source read. */
fun interface SourceReadOperations {
    /**
     * Proof transition: `SourceReadRequest -> SourceReadResult`.
     *
     * Implementations must revalidate the anchor and keep IntelliJ/K2 values request-local.
     */
    suspend fun read(request: SourceReadRequest): SourceReadResult
}
