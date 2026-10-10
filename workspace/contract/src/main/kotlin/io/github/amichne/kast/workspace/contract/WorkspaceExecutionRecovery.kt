package io.github.amichne.kast.workspace.contract

/** Detached operation classification. Read preparation may still save documents or refresh native state. */
enum class WorkspaceRequestEffect {
    OBSERVATION,
    READ,
    MUTATION,
    UNKNOWN,
}

enum class WorkspaceExecutionCertainty {
    KNOWN,
    UNCERTAIN,
}

/** This is provider termination, not a claim that transport termination proves native settlement. */
enum class WorkspaceExecutionSettlement {
    PROVIDER_RUNNING,
    PROVIDER_TERMINATED,
}

enum class WorkspaceExecutionDisposition {
    AWAIT_PROVIDER,
    REOBSERVE_NATIVE_AUTHORITY,
    RECONCILE_MUTATION,
}

/**
 * Result uncertainty is not model invalidity. Native work retains its own capacity until the adapter proves native
 * settlement. After provider termination every new semantic request must pass fresh native admission and final epoch
 * validation. A usable model never reconciles an uncertain edit. No timeout can supply termination evidence.
 */
fun workspaceExecutionDisposition(
    effect: WorkspaceRequestEffect,
    certainty: WorkspaceExecutionCertainty,
    settlement: WorkspaceExecutionSettlement,
): WorkspaceExecutionDisposition {
    if (settlement == WorkspaceExecutionSettlement.PROVIDER_RUNNING) return WorkspaceExecutionDisposition.AWAIT_PROVIDER
    if (certainty == WorkspaceExecutionCertainty.KNOWN) return WorkspaceExecutionDisposition.REOBSERVE_NATIVE_AUTHORITY
    return when (effect) {
        WorkspaceRequestEffect.OBSERVATION,
        WorkspaceRequestEffect.READ -> WorkspaceExecutionDisposition.REOBSERVE_NATIVE_AUTHORITY
        WorkspaceRequestEffect.MUTATION,
        WorkspaceRequestEffect.UNKNOWN -> WorkspaceExecutionDisposition.RECONCILE_MUTATION
    }
}
