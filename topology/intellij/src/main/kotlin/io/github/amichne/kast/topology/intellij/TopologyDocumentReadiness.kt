package io.github.amichne.kast.topology.intellij

/** Closed cached-document states relevant to exact source-content extraction. */
internal enum class TopologyDocumentReadiness {
    READY,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    ;

    companion object {
        fun observe(
            fileModified: Boolean,
            psiCommitted: Boolean,
        ): TopologyDocumentReadiness = when {
            fileModified -> DOCUMENT_DIRTY
            !psiCommitted -> PSI_DOCUMENT_UNCOMMITTED
            else -> READY
        }
    }
}
