package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit

internal sealed interface NativeSourceEntityProjection {
    data class Projected(val page: IntellijSourceEntityPage) : NativeSourceEntityProjection

    data class Rejected(val reason: IntellijSourceReadRejection) : NativeSourceEntityProjection
}

/** One read-action invocation owns detached facts; all invocations share the request's execution accounting. */
internal class IntellijSourceEntityAttempt
private constructor(
    private val execution: IntellijSourceExecution,
    private val page: IntellijSourceEntityPageCollector,
) {
    val admission: SourceEntityCollectionAdmission
        get() = page.admission

    fun admitUnit(): SourceExecutionAdmission = execution.admitUnit()

    fun offer(entity: SourceEntity): SourceEntityCollectionAdmission = page.offer(entity)

    fun projectDeclaration(kind: DeclarationKind, project: () -> Unit) = page.projectDeclaration(kind, project)

    fun finish(): IntellijSourceEntityPage = page.finish()

    companion object {
        /** Cancellation escapes unchanged, so a canceled invocation cannot return its detached page. */
        fun collect(
            execution: IntellijSourceExecution,
            selection: EntitySelection.Matching,
            cursor: IntellijSourceEntityCursor,
            limit: SourceEntityLimit,
            read: (IntellijSourceEntityAttempt) -> NativeSourceEntityProjection,
        ): NativeSourceEntityProjection =
            read(IntellijSourceEntityAttempt(execution, IntellijSourceEntityPageCollector(selection, cursor, limit)))
    }
}
