package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement

/**
 * Shared row rules for full and streamed admission. The caller supplies actual admitted inventory and endpoint rows.
 */
object HistoricalFactPolicy {
    fun admitFile(
        file: HistoricalFile,
        modules: Map<HistoricalModuleId, HistoricalModule>,
    ): Refinement<Unit, HistoricalAdmissionFailure> {
        val module = modules[file.module] ?: return Refinement.Rejected(HistoricalAdmissionFailure.UNKNOWN_MODULE)
        if (module.id != file.module) return Refinement.Rejected(HistoricalAdmissionFailure.UNKNOWN_MODULE)
        if (module.sourceSet == HistoricalSourceSet.Rootless)
            return Refinement.Rejected(HistoricalAdmissionFailure.ROOTLESS_FILE)
        return Refinement.Refined(Unit)
    }

    fun admitOccurrence(
        occurrence: HistoricalOccurrence,
        declarations: Set<HistoricalDeclarationId>,
    ): Refinement<Unit, HistoricalAdmissionFailure> {
        if (occurrence.target !in declarations) return Refinement.Rejected(HistoricalAdmissionFailure.INVALID_ENDPOINT)
        when (val owner = occurrence.owner) {
            is HistoricalOccurrenceOwner.Declaration ->
                if (owner.id !in declarations || owner.id.file != occurrence.sourceFile)
                    return Refinement.Rejected(HistoricalAdmissionFailure.OWNER_MISMATCH)
            is HistoricalOccurrenceOwner.FileScoped ->
                if (owner.context != occurrence.context || !fileScopedContext(owner.context))
                    return Refinement.Rejected(HistoricalAdmissionFailure.OWNER_MISMATCH)
            is HistoricalOccurrenceOwner.Unavailable -> Unit
        }
        return Refinement.Refined(Unit)
    }

    private fun fileScopedContext(context: HistoricalReferenceContext): Boolean =
        when (context) {
            HistoricalReferenceContext.IMPORT,
            HistoricalReferenceContext.ALIASED_IMPORT,
            HistoricalReferenceContext.FILE_ANNOTATION -> true
            HistoricalReferenceContext.TYPE,
            HistoricalReferenceContext.CODE -> false
        }
}
